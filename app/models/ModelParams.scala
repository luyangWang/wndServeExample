package models

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.intel.analytics.bigdl.numeric.NumericFloat
import com.intel.analytics.zoo.models.common.ZooModel
import com.intel.analytics.zoo.models.recommendation.Recommender
import ml.combust.bundle.BundleFile
import ml.combust.mleap.runtime.MleapSupport._
import ml.combust.mleap.runtime.frame.Transformer
import resource.managed

import scala.io.Source


case class ModelParams(
                   wndModelPath: String,
                   userIndexerModelPath: String,
                   itemIndexerModelPath: String,
                   atcArrayPath: String,
                   env: String,
                   bucketName: String,
                   var wndModel: Option[Recommender[Float]],
                   var wndModelVersion: Option[Long],
                   var userIndexerModel: Option[Transformer],
                   var userIndexerModelVersion: Option[Long],
                   var itemIndexerModel: Option[Transformer],
                   var itemIndexerModelVersion: Option[Long],
                   var atcArray: Option[Array[String]],
                   var atcArrayVersion: Option[Long]
                 )

object ModelParams {

  private val s3client = AmazonS3ClientBuilder.standard().withCredentials(new DefaultAWSCredentialsProviderChain()).build()
  private val lock: ReadWriteLock = new ReentrantReadWriteLock()
  private val currentDir = Paths.get(".").toAbsolutePath.toString

  System.setProperty("bigdl.localMode", "true")
  println("BigDL localMode is: " + System.getProperty("bigdl.localMode"))

  def apply(
             wndModelPath: String,
             userIndexerModelPath: String,
             itemIndexerModelPath: String,
             atcArrayPath: String,
             env: String
           ): ModelParams = ModelParams(
    wndModelPath,
    userIndexerModelPath,
    itemIndexerModelPath,
    atcArrayPath,
    env,
    if (env == "prod" || env == "canary") "ecomdatascience-p" else "ecomdatascience-np",
    loadBigDL(wndModelPath),
    loadVersion(wndModelPath),
    loadMleap(currentDir, userIndexerModelPath),
    loadVersion(userIndexerModelPath),
    loadMleap(currentDir, itemIndexerModelPath),
    loadVersion(itemIndexerModelPath),
    loadArrayFile(atcArrayPath),
    loadVersion(atcArrayPath)
  )

//  def downloadModel(params: ModelParams): Any = {
//    lock.writeLock().lock()
//    try {
//      val file = s3client.getObject(new GetObjectRequest(params.bucketName, "lu/subLatest.zip")).getObjectContent
//      println("Downloading the file")
//      val outputStream = new FileOutputStream(s"${params.currentDir}${params.subModelPath}")
//      IOUtils.copy(file, outputStream)
//      println("Download has completed")
//    }
//    catch {
//      case e: Exception => println(s"Cannot download model at ${params.env}-${params.bucketName}-${params.subModelPath}"); None
//    }
//    finally { lock.writeLock().unlock() }
//  }

  def loadBigDL(path: String) = {
    lock.readLock().lock()
    if (new File(path).exists()) {
      try {
        Some(ZooModel.loadModel[Float](path).asInstanceOf[Recommender[Float]])
      }
          catch {
            case e: Exception => println(s"Cannot load model at $path"); None
          }
      finally { lock.readLock().unlock() }
    }
    else {
      println(s"Cannot load model at $path")
      None
    }
  }

  def loadMleap(currentDir: String, modelPath: String): Option[Transformer] = {
    if (Files.exists(Paths.get(s"$currentDir/$modelPath"))) {
      lock.readLock().lock()
      try Some(
        (for (bundleFile <- managed(BundleFile(s"jar:file:$currentDir/$modelPath"))) yield {
          bundleFile.loadMleapBundle().get
        }).opt.get.root
      )
      catch {
        case _: Exception => println(s"Cannot load Mleap model at $currentDir$modelPath"); None;
      }
      finally { lock.readLock().unlock() }
    }
    else None
  }

  def loadVersion(path: String): Option[Long] = {
    lock.readLock().lock()
    try Some(new File(path).lastModified())
    catch {
      case e: Exception => println(s"Cannot load model version at $path"); None
    }
    finally { lock.readLock().unlock() }
  }

  def loadArrayFile(path: String): Option[Array[String]] = {
    lock.readLock().lock()
    try Some(Source.fromFile(path).getLines().drop(1)
      .flatMap(_.split(",")).toArray)
    catch {
      case e: Exception => println(s"Cannot load array at $path"); None
    }
    finally { lock.readLock().unlock() }
  }

  def refresh(params: ModelParams): ModelParams = {
    params.wndModel = loadBigDL(params.wndModelPath)
    params.wndModelVersion = loadVersion(params.wndModelPath)
    params.userIndexerModel = loadMleap(currentDir, params.userIndexerModelPath)
    params.userIndexerModelVersion = loadVersion(params.userIndexerModelPath)
    params.itemIndexerModel = loadMleap(currentDir, params.itemIndexerModelPath)
    params.itemIndexerModelVersion = loadVersion(params.itemIndexerModelPath)
    params.atcArray = loadArrayFile(params.atcArrayPath)
    params.atcArrayVersion = loadVersion(params.atcArrayPath)
    params
  }

//  def loadModelFile(path: String, currentDir: String): Option[Transformer] = {
//    lock.readLock().lock()
//    try {
//      Some(
//        (
//          for (bundleFile <- managed(BundleFile(s"jar:file:$currentDir$path"))) yield {
//            bundleFile.loadMleapBundle().get
//          }
//          ).opt.get.root
//      )
//    }
//    catch {
//      case e: Exception => println(s"Cannot load model at $currentDir$path"); None
//    }
//    finally { lock.readLock().unlock() }
//  } b b

}