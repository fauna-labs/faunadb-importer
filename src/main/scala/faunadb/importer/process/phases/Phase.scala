package faunadb.importer.process.phases

import akka._
import akka.stream._
import akka.stream.scaladsl._
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.query.Expr
import faunadb.values.{ Value => FValue }
import scala.concurrent._

private[process] trait Phase[A] {
  import GraphDSL.Implicits._

  implicit val context: Context

  val description: String

  protected val runFlow: Flow[(A, Expr), (A, Result[FValue]), NotUsed]
  protected def buildExpr(elem: A): Result[Expr]
  protected def handledResult(elem: A, value: FValue): Result[Unit]

  def run(data: Iterator[A]): Future[Done] = {
    val graph = RunnableGraph.fromGraph(GraphDSL.create(Sink.ignore) { implicit b =>
      sink =>
        val source = Source.fromIterator(() => data)
        val bcast = b.add(Broadcast[A](2))
        val zipUp = b.add(Zip[A, Result[Expr]])

        source ~> bcast
        bcast ~> zipUp.in0
        bcast ~> Flow.fromFunction(buildExpr) ~> zipUp.in1

        zipUp.out ~> discardErrors[Expr] ~>
          runFlow ~> discardErrors[FValue] ~>
          consume ~> discardErrors[Unit] ~>
          sink

        ClosedShape
    })

    graph.run()
  }

  private def discardErrors[B]: Flow[(A, Result[B]), (A, B), NotUsed] =
    Flow[(A, Result[B])].mapConcat {
      case (elem, res) =>
        ErrorHandler.handle(res).map(elem -> _).toList
    }

  private val consume = Flow.fromFunction[(A, FValue), (A, Result[Unit])] {
    case (elem, res) =>
      elem -> handledResult(elem, res)
  }
}
