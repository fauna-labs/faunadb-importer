package faunadb.importer.errors

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.report._

sealed trait ErrorStrategy
object ErrorStrategy {
  final case object StopOnError extends ErrorStrategy
  final case object DoNotStop extends ErrorStrategy
}

sealed trait ErrorHandler {
  def handle[A](value: Result[A]): Option[A]
}

object ErrorHandler {
  final class Stop(msg: String) extends Exception(msg)

  def handle[A](value: Result[A])(implicit h: ErrorHandler): Option[A] =
    h.handle(value)

  implicit def ResultHandler(implicit c: Context) = new ErrorHandler {
    def handle[A](value: Result[A]): Option[A] = value fold (
      msg => {
        Log.error(msg)
        Stats.ErrorsFound.inc()

        c.config.errorStrategy match {
          case ErrorStrategy.StopOnError => throw new Stop(msg)
          case ErrorStrategy.DoNotStop   => None
        }
      },
      res => Some(res)
    )
  }
}
