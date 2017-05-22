package faunadb.importer.report

import com.codahale.metrics._
import faunadb.importer.concurrent._
import java.text.DecimalFormat
import scala.concurrent.duration._

object Stats {
  private val reg = new MetricRegistry()

  val BytesToRead: Counter = reg.counter("bytes-to-read")
  val BytesRead: Counter = reg.counter("bytes-read")
  val ErrorsFound: Counter = reg.counter("errors-found")
  val Latency: Timer = reg.timer("latency")
}

object StatsReporter {
  private val byteUnits = Array("B", "KB", "MB", "GB", "TB")
  private val bytesFormat = new DecimalFormat("#,##0.0")
  private val nanosToMills = 1.0 / MILLISECONDS.toNanos(1)

  private var reporter: Option[Scheduler.Task] = None

  def start() {
    stop()
    reporter = Some {
      Scheduler.schedule(3.seconds) {
        Log.status(report())
      }
    }
  }

  private def report(): String = {
    val bytesToRead = Stats.BytesToRead.getCount
    val bytesRead = Stats.BytesRead.getCount
    val errors = Stats.ErrorsFound.getCount
    val requests = Stats.Latency
    val latency = Stats.Latency.getSnapshot

    val status = Seq(
      s"Errors: $errors",

      "Bytes(read/total): " +
        s"${prettyBytes(bytesRead)}/" +
        s"${prettyBytes(bytesToRead)}",

      "RPS(mean/1min/5min/15min): " +
        f"${requests.getMeanRate}%.2f/" +
        f"${requests.getOneMinuteRate}%.2f/" +
        f"${requests.getFiveMinuteRate}%.2f/" +
        f"${requests.getFifteenMinuteRate}%.2f",

      "Latency(median/75%/95%): " +
        f"${latency.getMedian * nanosToMills}%.2f/" +
        f"${latency.get75thPercentile * nanosToMills}%.2f/" +
        f"${latency.get95thPercentile() * nanosToMills}%.2f"
    )

    status.mkString(" ")
  }

  private def prettyBytes(size: Long): String = {
    if (size > 0) {
      val unit = (Math.log10(size) / Math.log10(1024)).toInt
      bytesFormat.format(size / Math.pow(1024, unit)) + byteUnits(unit)
    } else "0"
  }

  def stop() {
    reporter foreach { task =>
      task.cancel()
      Log.clearStatus()
      Log.info(report())
      reporter = None
    }
  }
}
