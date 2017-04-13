package faunadb.importer.process

import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.parser._
import faunadb.importer.persistence._
import faunadb.importer.process.phases._
import faunadb.importer.report._
import faunadb.importer.values._
import java.io._
import scala.concurrent._
import scala.concurrent.duration._

object Import {
  type Input = Seq[(File, Context)]

  def run(config: Config, filesToImport: Input) {
    val pool = ConnectionPool(config.endpoints, config.secret)

    try {
      new Import(pool).run(filesToImport)
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
}

private class Import(connPool: ConnectionPool) {
  def run(filesToImport: Import.Input) {
    val idCache = IdCache() // TODO: save and load ids cache
    filesToImport.foreach { case (file, _) => Stats.BytesToRead.inc(file.length() * 2) /* Two phases*/}
    forAll(filesToImport) { implicit c => GenerateIds(AkkaFaunaStream(connPool), idCache) }
    forAll(filesToImport) { implicit c => InsertRecords(AkkaFaunaStream(connPool), idCache) }
  }

  def forAll(files: Import.Input)(fn: Context => Phase) {
    files.foreach { case (file, context) =>
      implicit val _ = context

      val phase = fn(context)
      Log.info(s"${phase.desc} for $file")

      InputParser(file) fold (
        error => throw new IllegalArgumentException(error),
        parser => try runPhase(phase, parser.records()) finally parser.close()
      )
    }
  }

  private def runPhase(phase: Phase, records: Stream[Result[Record]])(implicit c: Context) =
    Await.result(
      phase.run(
        ErrorHandler
          .filter(records)
          .map(_.get)
      ),
      Duration.Inf
    )
}
