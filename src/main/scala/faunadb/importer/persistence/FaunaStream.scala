package faunadb.importer.persistence

import akka._
import akka.stream._
import akka.stream.scaladsl._
import faunadb.FaunaClient
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.report._
import faunadb.query._
import faunadb.values._
import scala.concurrent._

trait FaunaStream {
  def runWith(queryRunner: QueryRunner): Flow[Seq[Expr], Seq[Value], NotUsed]
}

object AkkaFaunaStream {
  def apply(connPool: ConnectionPool)(implicit context: Context): AkkaFaunaStream =
    new AkkaFaunaStream(connPool)
}

final class AkkaFaunaStream private(connPool: ConnectionPool)(implicit context: Context) extends FaunaStream {
  def runWith(queryRunner: QueryRunner): Flow[Seq[Expr], Seq[Value], NotUsed] = {
    // FIXME: handle request too large
    // FIXME: handle service unavailable
    // FIXME: handle general errors (return result)
    // FIXME: measure fauna latency (from headers)
    val clientFlow = connPool.clients.map { client =>
      Flow[Seq[Expr]].mapAsyncUnordered(context.config.threadsPerEndpoint) { exprs =>
        Stats.Latency.measure {
          queryRunner.runQuery(client, exprs)
        }
      }
    }

    Flow.fromGraph {
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val balance = builder.add(Balance[Seq[Expr]](connPool.size))
        val merge = builder.add(Merge[Seq[Value]](connPool.size))
        clientFlow.foreach(balance ~> _ ~> merge)

        FlowShape(balance.in, merge.out)
      }
    }
  }
}

sealed trait QueryRunner {
  def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[Value]]
}

object QueryRunner {
  final val PreserveValues = new QueryRunner {
    def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[Value]] =
      client.query(exprs)
  }

  final val DiscardValues = new QueryRunner {
    def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[Value]] =
      client
        .query(Let(Seq("_" -> Arr(exprs: _*)), NullV))
        .map(Seq(_))
  }
}
