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
  val IdCacheFile = new File("cache/ids")

  def run(config: Config, filesToImport: Seq[(File, Context)]) {
    val pool = ConnectionPool(config.endpoints, config.secret)

    try {
      for (step <- steps(pool, filesToImport)) step.run()
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
    val steps = Seq.newBuilder[Step]
    steps += new IncBytesToRead(filesToImport)

    val idCache = State.load[IdCache](IdCacheFile) match {
      case Some(cachedIds) =>
        Log.info("Using pre-generated ids...")
        cachedIds

      case None =>
        val newCache = IdCache()
        steps += new IncBytesToRead(filesToImport)
        steps += new GenerateIds(connPool, newCache, filesToImport)
        steps += new SavePregeneratedIds(newCache)
        newCache
    }

    steps += new InsertRecords(connPool, idCache, filesToImport)
    steps.result()
  }
}

private sealed trait Step {
  def run(): Unit
}

private sealed abstract class RunPhase(filesToLoad: Seq[(File, Context)]) extends Step {

  def phase()(implicit c: Context): Phase

  def run(): Unit = for ((file, context) <- filesToLoad) {
    implicit val _ = context

    val currentPhase = phase()
    Log.info(s"${currentPhase.description} for $file")

    InputParser(file) fold (
      error => throw new IllegalStateException(error),
      parser => Await.result(
        currentPhase.run(
          parser
            .records()
            .flatMap(ErrorHandler.handle(_))
        ),
        Duration.Inf
      )
    )
  }
}

private final class IncBytesToRead(filesToLoad: Seq[(File, Context)]) extends Step {
  def run(): Unit =
    for ((file, _) <- filesToLoad)
      Stats.BytesToRead.inc(file.length())
}

private final class SavePregeneratedIds(idCache: IdCache) extends Step {
  def run(): Unit =
    State.store(Import.IdCacheFile, idCache)
}

private final class GenerateIds(connPool: ConnectionPool, idCache: IdCache, filesToLoad: Seq[(File, Context)])
  extends RunPhase(filesToLoad) {
  def phase()(implicit c: Context): Phase =
    GenerateIds(idCache, connPool)
}

private final class InsertRecords(connPool: ConnectionPool, idCache: IdCache, filesToLoad: Seq[(File, Context)])
  extends RunPhase(filesToLoad) {
  def phase()(implicit c: Context): Phase =
    InsertRecords(idCache, connPool)
}
