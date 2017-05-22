package faunadb.importer

import com.codahale.metrics._
import java.util.concurrent._

package object report {
  implicit class TimerOps(timer: Timer) {
    def measure[T](f: => T): T = timer.time(new Callable[T] {
      def call(): T = f
    })
  }
}
