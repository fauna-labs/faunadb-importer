package faunadb.importer.process

import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.parser._
import faunadb.importer.persistence._
import faunadb.importer.process.phases._
import faunadb.importer.report._
import java.io._
import scala.concurrent._
import scala.concurrent.duration._

object Import {
  val CacheFile = new File("cache/ids")

  def run(config: Config, filesToImport: Seq[(File, Context)]) {
    val pool = ConnectionPool(config.endpoints, config.secret)

    try {
      for (step <- steps(pool, filesToImport)) {
        step.run()
        step.close()
      }
      Log.info("Import completed successfully")
    } catch {
      case e: ErrorHandler.Stop =>
        Log.info(s"The import has found an error: ${e.getMessage}")
        Log.info("Check logs/errors.log for more information.")

      case e: Throwable =>
        Log.fatal(e)
        Log.info("An unexpected error happened. The import can not proceed.")
        Log.info("Check logs/exceptions.log for more information.")

    } finally {
      pool.close()
    }
  }

  private def steps(connPool: ConnectionPool, filesToImport: Seq[(File, Context)]): Seq[Step] = {
    val (files, _) = filesToImport.unzip

    val steps = Seq.newBuilder[Step]
    steps += new IncBytesToRead(files)

    if (CacheFile.exists()) Log.info("Using pre-generated ids...") else {
      steps += new IncBytesToRead(files)
      steps += new GenerateIds(connPool, filesToImport)(
        IdCache.openForWrite(CacheFile)
      )
    }

    steps += new InsertRecords(connPool, filesToImport)(
      IdCache.openForRead(CacheFile)
    )
    steps.result()
  }
}

private trait Step {
  def run(): Unit
  def close(): Unit
}

private trait NonCloseableStep extends Step {
  def close(): Unit = ()
}

private abstract class RunPhase[A <: CacheFile](filesToLoad: Seq[(File, Context)], openCache: => A)
  extends Step {

  private lazy val cache = openCache

  def phase(idCache: A)(implicit c: Context): Phase
  def close(): Unit = cache.close()

  def run(): Unit = for ((file, context) <- filesToLoad) {
    implicit val _ = context
    val currentPhase = phase(cache)
    Log.info(s"${currentPhase.description} for $file")

    InputParser(file) fold (
      error =>
        throw new IllegalStateException(error),

      parser =>
        try
          Await.result(
            currentPhase.run(
              parser
                .records()
                .flatMap(ErrorHandler.handle(_))
            ),
            Duration.Inf
          )
        finally
          parser.close()
    )
  }
}

private final class IncBytesToRead(filesToLoad: Seq[File]) extends NonCloseableStep {
  def run(): Unit =
    for (file <- filesToLoad)
      Stats.BytesToRead.inc(file.length())
}

private final class GenerateIds(connPool: ConnectionPool, filesToLoad: Seq[(File, Context)])
  (openCache: => IdCache.Write) extends RunPhase[IdCache.Write](filesToLoad, openCache) {

  def phase(cacheWrite: IdCache.Write)(implicit c: Context): Phase =
    GenerateIds(cacheWrite, connPool)
}

private final class InsertRecords(connPool: ConnectionPool, filesToLoad: Seq[(File, Context)])
  (openCache: => IdCache.Read) extends RunPhase[IdCache.Read](filesToLoad, openCache) {

  def phase(cacheRead: IdCache.Read)(implicit c: Context): Phase =
    InsertRecords(cacheRead, connPool)
}
