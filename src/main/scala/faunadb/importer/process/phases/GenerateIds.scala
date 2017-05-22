package faunadb.importer.process.phases

import akka._
import akka.stream._
import akka.stream.scaladsl._
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.{ Expr, NextId }
import faunadb.values.Value
import scala.concurrent._

private[process] object GenerateIds {
  def apply(fauna: FaunaStream, idCache: IdCache)(implicit c: Context): GenerateIds =
    new GenerateIds(fauna, idCache)
}

private[process] final class GenerateIds(fauna: FaunaStream, idCache: IdCache)
  (implicit context: Context) extends Phase {

  val desc = "Pre-generating ids"

  def run(records: => Stream[Record]): Future[Done] =
    Source
      .fromIterator(records.iterator _)
      .map(_ -> NextId())
      .via(queryForIds)
      .runWith(storeIds)

  private val queryForIds = Flow.fromGraph {
    GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val unzip = builder.add(Unzip[Record, Expr]())
      val zipUp = builder.add(Zip[Seq[Record], Seq[Value]]())

      unzip.out0 ~> batchOf[Record] ~> zipUp.in0
      unzip.out1 ~> batchOf[Expr] ~> fauna.runWith(QueryRunner.PreserveValues) ~> zipUp.in1

      FlowShape(unzip.in, zipUp.out)
    }
  } map {
    case (records, values) =>
      records.zip(values)
  }

  private val storeIds = sinkWithErrorCheck[Seq[(Record, Value)], Seq[Result[Unit]]] {
    _ map { case (record, id) =>
      faunaIdAsString(id) flatMap { newId =>
        idCache.put(context.clazz, record.id, newId) map { _ =>
          Err(
            s"Duplicated id ${record.id} found for record at " +
              s"${record.localized}"
          )
        } getOrElse Ok(())
      }
    }
  }

  private def faunaIdAsString(id: Value): Result[Long] =
    id.to[String]
      .map { s =>
        try Ok(s.toLong) catch {
          case e: Throwable => Err(s"Can NOT convert id returned from fauna to Long. ${e.getMessage}")
        }
      }
      .getOrElse(Err(s"Fauna did NOT returned a string ID. Value returned: $id"))

  private def batchOf[A]: Flow[A, Seq[A], NotUsed] =
    Flow[A].grouped(context.config.batchSize)
}
