package faunadb.importer.process

import akka._
import akka.stream.scaladsl._
import faunadb.importer.errors._
import faunadb.importer.values._
import scala.concurrent._

package object phases {
  private[process] trait Phase {
    val desc: String
    def run(records: => Stream[Record]): Future[Done]

    protected def sinkWithErrorCheck[IN, OUT: ErrorHandler](fn: IN => OUT): Sink[IN, Future[Done]] =
      Sink.foreach(in => ErrorHandler.check(fn(in)))
  }
}
