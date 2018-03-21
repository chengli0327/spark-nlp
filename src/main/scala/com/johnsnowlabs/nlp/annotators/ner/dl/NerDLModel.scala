package com.johnsnowlabs.nlp.annotators.ner.dl

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.UUID

import com.johnsnowlabs.ml.tensorflow.{NerDatasetEncoder, DatasetEncoderParams, TensorflowNer, TensorflowWrapper}
import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, NAMED_ENTITY, TOKEN}
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common.TokenizedWithSentence
import com.johnsnowlabs.nlp.annotators.ner.Verbose
import com.johnsnowlabs.nlp.serialization.StructFeature
import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession


class NerDLModel(override val uid: String)
  extends AnnotatorModel[NerDLModel]
    with HasWordEmbeddings
    with ParamsAndFeaturesWritable {


  def this() = this(Identifiable.randomUID("NerDLModel"))

  override val requiredAnnotatorTypes = Array(DOCUMENT, TOKEN)
  override val annotatorType = NAMED_ENTITY

  val datasetParams = new StructFeature[DatasetEncoderParams](this, "datasetParams")
  def setDatasetParams(params: DatasetEncoderParams) = set(this.datasetParams, params)

  var tensorflow: TensorflowWrapper = null

  def setTensorflow(tf: TensorflowWrapper): NerDLModel = {
    this.tensorflow = tf
    this
  }

  @transient
  private var _model: TensorflowNer = null

  def model: TensorflowNer = {
    if (_model == null) {
      require(tensorflow != null, "Tensorflow must be set before usage. Use method setTensorflow() for it.")
      require(embeddings.isDefined, "Embeddings must be defined before usage")
      require(datasetParams.isSet, "datasetParams must be set before usage")

      val encoder = new NerDatasetEncoder(embeddings.get.getEmbeddings, datasetParams.get.get)
      _model = new TensorflowNer(
        tensorflow,
        encoder,
        10,
        Verbose.Silent)
    }

    _model
  }

  override protected def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    require(model != null, "call setModel before usage")

    // Parse
    val tokenized = TokenizedWithSentence.unpack(annotations).toArray

    // Predict
    val labels = model.predict(tokenized)

    // Combine labels with sentences tokens
    (0 until tokenized.length).flatMap{i =>
      val sentence = tokenized(i)
      (0 until sentence.tokens.length).map{j =>
        val token = sentence.indexedTokens(j)
        val label = labels(i)(j)
        new Annotation(NAMED_ENTITY, token.begin, token.end, label, Map.empty)
      }
    }
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)

    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)

    // 1. Create tmp folder
    val tmpFolder = Files.createTempDirectory(UUID.randomUUID().toString.takeRight(12) + "_nerdl")
      .toAbsolutePath.toString
    val tfFile = Paths.get(tmpFolder, NerDLModel.tfFile).toString

    // 2. Save Tensorflow state
    tensorflow.saveToFile(tfFile)

    // 3. Copy to dest folder
    fs.copyFromLocalFile(new Path(tfFile), new Path(path))

    // 4. Remove tmp folder
    FileUtils.deleteDirectory(new File(tmpFolder))
  }
}

object NerDLModel extends ParamsAndFeaturesReadable[NerDLModel] {

  val tfFile = "tensorflow"

  override def onRead(instance: NerDLModel, path: String, spark: SparkSession): Unit = {

    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)

    // 1. Create tmp directory
    val tmpFolder = Files.createTempDirectory(UUID.randomUUID().toString.takeRight(12) + "_nerdl")
      .toAbsolutePath.toString

    // 2. Copy to local dir
    fs.copyToLocalFile(new Path(path, tfFile), new Path(tmpFolder))

    // 3. Read Tensorflow state
    val tf = TensorflowWrapper.read(new Path(tmpFolder, tfFile).toString)
    instance.setTensorflow(tf)

    // 4. Remove tmp folder
    FileUtils.deleteDirectory(new File(tmpFolder))
  }
}
