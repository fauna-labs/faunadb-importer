package faunadb.importer

import faunadb.importer.concurrent._
import faunadb.importer.lang._
import faunadb.importer.process._
import faunadb.importer.report._

object Main {
  def main(args: Array[String]): Unit = {
    CmdArgs.parse(args) foreach { case (config, filesToImport) =>
      val startTime = System.currentTimeMillis()

      Runtime.getRuntime.addShutdownHook(new Thread("shutdown-hook") {
        override def run(): Unit = {
          Log.info("SIGKILL found...")
          shutdown(startTime)
        }
      })

      Log.info("Starting import...")
      StatsReporter.start()

      try {
        Import.run(config, filesToImport)
      } finally {
        shutdown(startTime)
      }
    }
  }

  def shutdown(startTime: Long) {
    Log.info("Shutting down the import...")
    Log.info(s"Execution time: ${TimeFormat.prettyDuration(startTime, System.currentTimeMillis())}")
    StatsReporter.stop()
    Concurrent.shutdown()
    Log.stop()
  }
}
