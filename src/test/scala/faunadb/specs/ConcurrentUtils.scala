package faunadb.specs

import scala.concurrent._
import scala.concurrent.duration._

trait ConcurrentUtils {
  def await[A](f: => Awaitable[A], timeout: Duration = 5.minutes): A =
    Await.result(f, timeout)
}
