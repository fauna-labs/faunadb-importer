package faunadb.importer

import monix.execution.{ Cancelable, Scheduler => MScheduler }
import scala.concurrent.duration._

package object concurrent {

  implicit val scheduler: MScheduler = MScheduler.global

  object Scheduler {
    final class Task private[Scheduler](task: Cancelable) {
      def cancel(): Unit = task.cancel()
    }

    def schedule(interval: FiniteDuration, initialDelay: FiniteDuration = Duration.Zero)(f: => Unit): Task = {
      new Task(scheduler.scheduleAtFixedRate(initialDelay, interval)(f))
    }
  }
}
