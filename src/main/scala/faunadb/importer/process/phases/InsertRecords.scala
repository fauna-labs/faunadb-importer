package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang.{ Result, _ }
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query._
import faunadb.values.{ Value, _ }
import scala.concurrent.Future

private[process] object InsertRecords {
  def apply(fauna: FaunaStream, idCache: IdCache)(implicit context: Context): InsertRecords =
    new InsertRecords(fauna, idCache)
}

private[process] final class InsertRecords(fauna: FaunaStream, idCache: IdCache)
  (implicit context: Context) extends Phase {

  val desc = "Inserting records"
  val toFauna: (Record) => Result[Value] = RecordConverter(idCache)

  // TODO: Keep track of inserted rows so we can retry the failures later
  def run(records: => Stream[Record]): Future[Done] =
    recordsToExprs(records)
      .grouped(context.config.batchSize)
      .via(fauna.runWith(QueryRunner.DiscardValues))
      .runWith(Sink.ignore)

  private def recordsToExprs(records: => Stream[Record]): Source[Expr, NotUsed] =
    Source.fromIterator(records.iterator _)
      .map(recordToExpr)
      .filterNot(ErrorHandler.check)
      .map(_.get)

  private def recordToExpr(record: Record): Result[Expr] =
    for {
      ref <- refFor(record)
      ts <- tsFor(record)
      data <- toFauna(record)
    } yield
      Insert(ref, ts, Action.Create, Obj("data" -> data))

  private def refFor(record: Record): Result[RefV] =
    idCache.get(context.clazz, record.id) map { newId =>
      Ok(RefV(
        s"classes/${context.clazz}/$newId"
      ))
    } getOrElse
      Err(s"Could not find pre-generated id ${record.id} " +
        s"for record at ${record.localized}")

  private def tsFor(record: Record): Result[Expr] =
    try
      record.ts match {
        case None                               => Ok(Time("now"))
        case Some(Scalar(_, t @ TimeT(_), raw)) => Ok(TimeV(t.format(raw)))
        case Some(other)                        => Err(s"Invalid value at timestamp field at ${other.localized}")
      }
    catch {
      case e: IllegalArgumentException =>
        Err(s"Can not use timestamp field for record at ${record.localized}. ${e.getMessage}")

      case e: Throwable =>
        Err(s"Unexpected error ${e.getMessage} while trying to use timestamp field for record at ${record.ts}")
    }
}
