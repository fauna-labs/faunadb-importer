package faunadb.importer.lang

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.{ IterableLike, mutable }

final case class Ok[A](get: A) extends Result[A] {
  def error: String = throw new NoSuchElementException("Ok.error")
  def isSuccess: Boolean = true
}

final case class Err[A](error: String) extends Result[A] {
  def get: A = throw new NoSuchElementException(s"Err($error).get")
  def isSuccess: Boolean = false
}

sealed trait Result[+A] {
  def get: A
  def error: String

  def isSuccess: Boolean
  def isFailure: Boolean = !isSuccess

  @inline def map[B](f: A => B): Result[B] =
    if (isSuccess) Ok(f(get))
    else asInstanceOf[Result[B]]

  @inline def flatMap[B](f: A => Result[B]): Result[B] =
    if (isSuccess) f(get)
    else asInstanceOf[Result[B]]

  @inline def fold[B](ifErr: String => B, ifSucc: A => B): B =
    if (isSuccess) ifSucc(get)
    else ifErr(error)
}

object Result {
  def apply[A](value: A): Result[A] = Ok(value)
}

trait ShortCircuit {
  implicit class SeqResultSC[Repr, A](self: IterableLike[A, Repr]) {
    def flatMapS[B, That](f: (A) => Result[B])(implicit cbf: CanBuildFrom[Repr, B, That]): Result[That] = {
      @tailrec
      def loop0(res: mutable.Builder[B, That], seq: Iterable[A]): Result[That] = {
        if (seq.isEmpty) Ok(res.result()) else f(seq.head) match {
          case Ok(mapped) => loop0(res += mapped, seq.tail)
          case Err(msg)   => Err(msg)
        }
      }
      loop0(cbf(self.repr), self.toIterable)
    }

    def foldLeftS[Z, That](z: => Z)(f: (Z, A) => Result[Z]): Result[Z] = {
      @tailrec
      def loop0(acc: Z, seq: Iterable[A]): Result[Z] = {
        if (seq.isEmpty) Ok(acc) else f(acc, seq.head) match {
          case Ok(mapped) => loop0(mapped, seq.tail)
          case Err(msg)   => Err(msg)
        }
      }
      loop0(z, self.toIterable)
    }
  }
}
