package faunadb.importer

import com.codahale.metrics._
import faunadb.importer.concurrent._
import scala.concurrent._

package object report {
  implicit class TimerOps(timer: Timer) {
    def measure[A](f: => Future[A]): Future[A] = {
      val ctx = timer.time()
      val future = f
      future.onComplete(_ => ctx.stop())
      future
    }
  }
}
