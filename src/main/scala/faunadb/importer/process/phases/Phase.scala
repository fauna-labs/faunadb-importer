package faunadb.importer.process.phases

import faunadb._
import faunadb.errors._
import faunadb.importer.concurrent._
import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.report._
import faunadb.importer.values._
import faunadb.query.{ Arr, Expr, Let }
import faunadb.values.{ NullV, Value => FValue }
import monix.eval._
import monix.reactive._
import scala.concurrent._

private[process] abstract class Phase(val description: String, connPool: ConnectionPool)(implicit c: Context) {
  this: QueryRunner =>

  private type QPair = (Record, Expr)
  private type RPair = (Record, Result[FValue])
  private type QBatch = Seq[QPair]
  private type RBatch = Seq[RPair]

  protected def buildQuery(record: Record): Result[Expr]

  def run(records: Iterator[Record]): Future[Unit] = {
    Observable
      .fromIterator(records)
      .flatMap(buildQPair)
      .transform(runQueries)
      .consumeWith(Consumer.foreach(handleResult))
      .runAsync
  }

  private def buildQPair(record: Record): Observable[QPair] = {
    Observable.fromIterable(
      ErrorHandler
        .handle(buildQuery(record))
        .map(record -> _)
    )
  }

  private def handleResult(response: RPair): Unit = {
    val (record, res) = response
    ErrorHandler.handle(res) foreach { value =>
      ErrorHandler.handle(handleResponse(record, value))
    }
  }

  private def runQueries(source: Observable[QPair]): Observable[RPair] = {
    source
      .bufferTumbling(c.config.batchSize)
      .mapAsync(c.config.threadsPerEndpoint * connPool.size)(pickClientAndRun)
      .onErrorRecoverWith(handleWrongCredentials)
      .mergeMap(Observable.fromIterable(_))
  }

  private def pickClientAndRun(batch: Seq[QPair]): Task[RBatch] = {
    val client = connPool.pickClient
    runBatch(client, batch) doOnFinish (_ => Task.now(connPool.release(client)))
  }

  private def runBatch(client: FaunaClient, batch: QBatch): Task[RBatch] = {
    val (records, exprs) = batch.unzip

    Task.fromFuture {
      Stats.ImportLatency.measure {
        runQuery(client, exprs)
          .map(res => records zip res.map(Result(_)))
          .recoverWith(handleQueryErrorsWith(_ => splitBatchAndRetry(client, batch)))
      }
    }
  }

  private def splitBatchAndRetry(client: FaunaClient, batch: QBatch): Future[RBatch] = {
    Observable
      .fromIterable(batch)
      .mapAsync(c.config.threadsPerEndpoint)(pair => Task.fromFuture(retryQPair(client, pair)))
      .toListL.runAsync
  }

  private def retryQPair(client: FaunaClient, pair: QPair): Future[RPair] = {
    val (record, expr) = pair

    client
      .query(expr)
      .map(Result(_))
      .recover(handleQueryErrorsWith(e =>
        Err(s"${e.getMessage} at ${record.localized}")
      ))
      .map(record -> _)
  }

  private def handleQueryErrorsWith[B](handle: (Throwable) => B): PartialFunction[Throwable, B] = {
    case e @ (_: BadRequestException |
              _: NotFoundException |
              _: TimeoutException) =>
      handle(e)

    case e: UnknownException if e.getMessage.startsWith("request too large") =>
      Log.warn("Request too large. Consider reducing the configured batch-size.")
      handle(e)
  }

  private def handleWrongCredentials: PartialFunction[Throwable, Observable[Nothing]] = {
    case e @ (_: UnauthorizedException |
              _: PermissionDeniedException) =>
      Log.info("Invalid key. You must provide a valid SERVER key.")
      Observable.raiseError(e)
  }
}

private[phases] sealed trait QueryRunner {
  protected def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]]
  protected def handleResponse(record: Record, value: FValue): Result[Unit]
}

private[phases] trait PreserveValues extends QueryRunner {
  protected final def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]] =
    client.query(exprs)
}

private[phases] trait DiscardValues extends QueryRunner {
  protected final def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]] = {
    client
      .query(Let(Seq("_" -> Arr(exprs: _*)), NullV))
      .map(_ => Seq.fill(exprs.length)(NullV))
  }

  // When discarding results you probably don't want to check the responses as well
  protected def handleResponse(record: Record, value: FValue): Result[Unit] =
    Ok(())
}
