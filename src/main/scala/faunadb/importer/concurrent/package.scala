package faunadb.importer

import akka.actor._
import akka.stream._
import scala.concurrent._
import scala.concurrent.duration._

package object concurrent {
  implicit val actorSystem: ActorSystem = ActorSystem("faunadb-import")
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  object Scheduler {
    sealed trait Task {
      def cancel(): Unit
    }

    def schedule(interval: FiniteDuration, initialDelay: FiniteDuration = Duration.Zero)(f: => Unit): Task = {
      val task = actorSystem.scheduler.schedule(initialDelay, interval)(f)
      new Scheduler.Task {
        def cancel(): Unit = task.cancel()
      }
    }
  }

  object Concurrent {
    def shutdown() {
      actorMaterializer.shutdown()
      Await.ready(actorSystem.terminate(), 5.minutes)
    }
  }
}
