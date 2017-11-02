package faunadb.importer

import monix.execution.{ Cancelable, ExecutionModel => ExecModel, Scheduler => MScheduler }
import scala.concurrent.duration._

package object concurrent {

  implicit val _scheduler: MScheduler =
    MScheduler.io(executionModel = ExecModel.AlwaysAsyncExecution)

  final object Scheduler {
    final class Task private[Scheduler](task: Cancelable) {
      def cancel(): Unit = task.cancel()
    }

    def schedule(interval: FiniteDuration, initialDelay: FiniteDuration = Duration.Zero)(f: => Unit): Task =
      new Task(_scheduler.scheduleAtFixedRate(initialDelay, interval)(f))

    def currentTimeMillis(): Long =
      _scheduler.currentTimeMillis()
  }
}
