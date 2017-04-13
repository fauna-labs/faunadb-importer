package faunadb.importer.errors

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.report._
import scala.collection.GenTraversableLike
import scala.language.higherKinds

sealed trait ErrorStrategy
object ErrorStrategy {
  final case object StopOnError extends ErrorStrategy
  final case object DoNotStop extends ErrorStrategy
}

sealed trait ErrorHandler[-E] {
  def check(value: E): Boolean
}

object ErrorHandler {
  self =>
  final class Stop(msg: String) extends Exception(msg)

  def check[E](value: E)(implicit h: ErrorHandler[E]): Boolean = h.check(value)
  def filter[A: ErrorHandler, R](input: GenTraversableLike[A, R]): R = input.filter(!check(_)) // Can't use filterNot because it's not lazy

  implicit def ResultHandler[E](implicit c: Context) = new ErrorHandler[Result[E]] {
    def check(value: Result[E]): Boolean = value fold (
      msg => {
        Log.error(msg)
        Stats.ErrorsFound.inc()

        c.config.errorStrategy match {
          case ErrorStrategy.StopOnError => throw new Stop(msg)
          case ErrorStrategy.DoNotStop   => true
        }
      },
      _ => false
    )
  }

  implicit def SeqHandler[E: ErrorHandler] = new ErrorHandler[Seq[E]] {
    def check(value: Seq[E]): Boolean = {
      var hasError = false
      for (elem <- value if self.check(elem)) hasError = true
      hasError
    }
  }
}
