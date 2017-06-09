package faunadb.importer.report

import ch.qos.logback.classic.LoggerContext
import org.slf4j._

object Log {
  private val info = LoggerFactory.getLogger("info")
  private val error = LoggerFactory.getLogger("error")
  private val fatal = LoggerFactory.getLogger("fatal")

  @volatile private var statusLine: String = ""
  @volatile private var lastSize: Int = 0

  def info(msg: String): Unit = syncInfo(_.info(msg))
  def warn(msg: String): Unit = syncInfo(_.warn(s"[WARN] $msg"))

  private def syncInfo(f: Logger => Unit): Unit = {
    if (lastSize > 0) {
      synchronized {
        print(fillLine())
        f(info)
        print(statusLine)
      }
    } else {
      f(info)
    }
  }

  def status(line: String) {
    if (info.isInfoEnabled()) {
      synchronized {
        print(fillLine(line))
        lastSize = line.length
        statusLine = line
      }
    }
  }

  def clearStatus(): Unit = status("")

  private def fillLine(line: String = ""): String =
    s"\r\r$line${" " * Math.max(0, lastSize - line.length)}\r"

  def error(msg: String): Unit = error.error(msg)
  def fatal(err: Throwable): Unit = fatal.error(err.getMessage, err)

  def stop() {
    LoggerFactory.getILoggerFactory match {
      case c: LoggerContext => c.stop()
      case _                =>
    }
  }
}
