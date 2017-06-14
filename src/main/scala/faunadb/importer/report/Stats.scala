package faunadb.importer.report

import com.codahale.metrics._
import faunadb.importer.concurrent._
import java.util.concurrent._
import org.slf4j._
import scala.concurrent.duration._

object Stats {
  private[report] val reg = new MetricRegistry()

  val BytesToRead: Counter = reg.counter("bytes-to-read")
  val BytesRead: Counter = reg.counter("bytes-read")
  val ErrorsFound: Counter = reg.counter("errors-found")
  val ImportLatency: Timer = reg.timer("import-latency")
  val ServerLatency: Timer = reg.timer("server-latency")
}

sealed trait ReportType
object ReportType {
  final case object Inline extends ReportType
  final case object Detailed extends ReportType
  final case object Silent extends ReportType
}

object StatsReporter {
  import ReportType._

  private var reporter: Option[StatsReporter] = None

  def start(reportType: ReportType) {
    stop()
    reporter = {
      val r = reportType match {
        case Detailed => new DetailedReporter(Stats.reg)
        case Inline   => new InlineReporter()
        case Silent   => new SilentReporter()
      }
      r.start()
      Some(r)
    }
  }

  def stop() {
    reporter = reporter flatMap { r =>
      r.stop()
      None
    }
  }
}

private trait StatsReporter {
  def start()
  def stop()
}

private final class SilentReporter extends StatsReporter {
  def start() {}
  def stop() {}
}

private class InlineReporter extends StatsReporter {

  private var task: Option[Scheduler.Task] = _
  private val nanosToMills = 1.0 / MILLISECONDS.toNanos(1)

  def start() {
    task = Some {
      Scheduler.schedule(1.seconds) {
        Log.status(report())
      }
    }
  }

  def stop() {
    task = task flatMap { t =>
      t.cancel()
      Log.clearStatus()
      Log.info(report())
      None
    }
  }

  private def report(): String =
    Seq(
      f"Progress: ${Stats.BytesRead.getCount / Math.max(1.0, Stats.BytesToRead.getCount) * 100}%.2f%%",
      s"Errors: ${Stats.ErrorsFound.getCount}",
      f"RPS: ${Stats.ImportLatency.getOneMinuteRate}%.2f",
      "Latency(client/server): " +
        f"${Stats.ImportLatency.getSnapshot.get99thPercentile * nanosToMills}%.2f/" +
        f"${Stats.ServerLatency.getSnapshot.get99thPercentile * nanosToMills}%.2f"
    ) mkString " "
}

private final class DetailedReporter(reg: MetricRegistry) extends StatsReporter {

  private val logger = LoggerFactory.getLogger("status")
  private var reporter: Option[ScheduledReporter] = _

  def start(): Unit = {
    reporter = {
      val r = Slf4jReporter.forRegistry(reg)
        .convertRatesTo(TimeUnit.MILLISECONDS)
        .outputTo(logger)
        .build()

      r.start(20, TimeUnit.SECONDS)
      Some(r)
    }
  }

  def stop(): Unit = {
    reporter = reporter flatMap { r =>
      r.stop()
      None
    }
  }
}
