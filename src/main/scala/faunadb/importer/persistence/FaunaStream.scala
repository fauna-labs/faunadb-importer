package faunadb.importer.persistence

import akka._
import akka.stream._
import akka.stream.scaladsl._
import faunadb._
import faunadb.errors._
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.report._
import faunadb.query.{ Arr, Expr, Let }
import faunadb.values.{ NullV, Value => FValue }
import scala.collection.immutable.{ Seq => FSeq }
import scala.concurrent._

trait FaunaStream[A] {
  def runWith(queryRunner: QueryRunner): Flow[(A, Expr), (A, Result[FValue]), NotUsed]
}

object AkkaFaunaStream {
  def apply[A](connPool: ConnectionPool)(implicit context: Context): AkkaFaunaStream[A] =
    new AkkaFaunaStream(connPool)
}

final class AkkaFaunaStream[A] private(connPool: ConnectionPool)(implicit context: Context) extends FaunaStream[A] {
  import GraphDSL.Implicits._

  def runWith(queryRunner: QueryRunner): Flow[(A, Expr), (A, Result[FValue]), NotUsed] = {
    Flow.fromGraph {
      GraphDSL.create() { implicit b =>
        val grouped = b.add(Flow[(A, Expr)].grouped(context.config.batchSize))
        val balance = b.add(Balance[FSeq[(A, Expr)]](connPool.size))
        val merge = b.add(Merge[FSeq[(A, Result[FValue])]](connPool.size))
        val concat = b.add(Flow[FSeq[(A, Result[FValue])]].mapConcat(identity))

        grouped ~> balance
        executorsWith(queryRunner).foreach(balance ~> _ ~> merge)
        merge ~> concat

        FlowShape(grouped.in, concat.out)
      }
    }.withAttributes(ActorAttributes.supervisionStrategy {
      case _: UnauthorizedException |
           _: PermissionDeniedException =>
        Log.info("Invalid key. You must provide a valid SERVER key.")
        Supervision.stop
    })
  }

  // FIXME: measure fauna latency (from headers)
  private def executorsWith(queryRunner: QueryRunner) = connPool.clients.map { client =>
    Flow[FSeq[(A, Expr)]].mapAsyncUnordered(context.config.threadsPerEndpoint) { batch =>
      val (elems, exprs) = batch.unzip
      Stats.ImportLatency.measure {
        queryRunner
          .runQuery(client, exprs)
          .map(res => elems.zip(res.map(Result(_))))
          .recoverWith(
            handleErrorsWith(_ =>
              splitBatchAndRetry(client, exprs)
                .map(elems.zip(_))
            )
          )
      }
    }
  }

  private def handleErrorsWith[B](handle: (Throwable) => B): PartialFunction[Throwable, B] = {
    case e @ (_: BadRequestException |
              _: NotFoundException |
              _: TimeoutException) =>
      handle(e)

    case e: UnknownException if e.getMessage.startsWith("request too large") =>
      Log.warn("Request too large. Consider reducing the configured batch-size.")
      handle(e)
  }

  private def splitBatchAndRetry(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[Result[FValue]]] =
    Future.traverse(exprs) { expr =>
      client.query(expr)
        .map(Result(_))
        .recover(handleErrorsWith(e => Err(e.getMessage)))
    }
}

sealed trait QueryRunner {
  def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]]
}

object QueryRunner {
  final val PreserveValues = new QueryRunner {
    def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]] =
      client.query(exprs)
  }

  final val DiscardValues = new QueryRunner {
    def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]] =
      client
        .query(Let(Seq("_" -> Arr(exprs: _*)), NullV))
        .map(_ => Seq.empty)
  }
}
