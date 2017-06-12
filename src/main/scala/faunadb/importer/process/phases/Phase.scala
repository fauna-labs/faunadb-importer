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
import java.io._
import monix.eval._
import monix.execution.atomic._
import monix.reactive._
import scala.concurrent._
import scala.concurrent.duration._

private[process] abstract class Phase(val description: String, connPool: ConnectionPool)(implicit c: Context) {
  this: QueryRunner =>

  private type QPair = (Record, Expr)
  private type RPair = (Record, Result[FValue])
  private type QBatch = Seq[QPair]
  private type RBatch = Seq[RPair]

  private val backoff = new Backoff(
    maxErrors = c.config.maxNetworkErrors,
    resetTime = c.config.networkErrorsResetTime,
    backoffTime = c.config.networkErrorsBackoffTime,
    backoffFactor = c.config.networkErrorsBackoffFactor,
    maxBackoffTime = c.config.maxNetworkErrorsBackoffTime
  )

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
      .mapAsync(connPool.maxConcurrentReferences)(pickClientAndRun)
      .onErrorRecoverWith(handleWrongCredentials)
      .mergeMap(Observable.fromIterable(_))
  }

  private def pickClientAndRun(batch: QBatch): Task[RBatch] = {
    val client = connPool.borrowClient()
    runBatch(client, batch) doOnFinish (_ => Task.now(connPool.returnClient(client)))
  }

  private def runBatch(client: FaunaClient, batch: QBatch): Task[RBatch] = {
    val (records, exprs) = batch.unzip

    backoff(Stats.ImportLatency.measure(runQuery(client, exprs)))
      .map(res => records zip res.map(Result(_)))
      .onErrorRecoverWith {
        handleQueryErrorsWith { _ =>
          splitBatchAndRetry(client, batch)
        }
      }
  }

  private def splitBatchAndRetry(client: FaunaClient, batch: QBatch): Task[RBatch] = {
    Observable
      .fromIterable(batch)
      .mapAsync(1)(pair => retryQPair(client, pair))
      .toListL
  }

  private def retryQPair(client: FaunaClient, pair: QPair): Task[RPair] = {
    val (record, expr) = pair

    backoff(Stats.ImportLatency.measure(client.query(expr)))
      .map(record -> Result(_))
      .onErrorRecover {
        handleQueryErrorsWith { e =>
          record -> Err(s"${e.getMessage} at ${record.localized}")
        }
      }
  }

  private def handleQueryErrorsWith[B](handle: (Throwable) => B): PartialFunction[Throwable, B] = {
    case e @ (_: BadRequestException |
              _: NotFoundException) =>
      handle(e)

    case e: UnknownException
      if e.getMessage.startsWith("request too large") ||
        e.getMessage.startsWith("Unparsable service 413response") =>
      Log.warn("Request too large. Consider reducing the configured batch-size.")
      handle(e)
  }

  private val handleWrongCredentials: PartialFunction[Throwable, Observable[Nothing]] = {
    case e @ (_: UnauthorizedException |
              _: PermissionDeniedException) =>
      Log.info("Invalid key. You must provide a valid SERVER key.")
      Observable.raiseError(e)
  }
}

private final class Backoff(
  maxErrors: Int,
  resetTime: FiniteDuration,
  backoffTime: FiniteDuration,
  backoffFactor: Int,
  maxBackoffTime: FiniteDuration
) {

  private case class State(
    totalErrors: Int,
    lastErrorTs: Long,
    backoffDelay: FiniteDuration) {

    def hasErrors: Boolean = totalErrors > 0
    def isOverMax: Boolean = totalErrors > maxErrors
    def isUnstable: Boolean = totalErrors > maxErrors / 2
    def isExpired: Boolean = lastErrorTs < scheduler.currentTimeMillis() - resetTime.toMillis

    def inc(): State = {
      State(
        totalErrors + 1,
        scheduler.currentTimeMillis(),
        incBackoffDelay()
      )
    }

    private def incBackoffDelay(): FiniteDuration = {
      if (hasErrors) {
        maxBackoffTime.min(backoffDelay * backoffFactor)
      } else {
        backoffTime
      }
    }
  }

  private final val ZeroState = State(0, 0, Duration.Zero)
  private final val state = Atomic(ZeroState)

  def apply[A](thunk: => Future[A]): Task[A] = {
    val s = state.get

    val task = if (s.isUnstable) {
      Task
        .deferFuture(thunk)
        .delayExecution(s.backoffDelay)
    } else {
      // Forces async boundary to gain more parallelism
      Task.deferFuture(thunk)
    }

    task
      .onErrorRecoverWith(handleNetworkErrors(thunk))
      .doOnFinish(resetErrorCount)
  }

  private def handleNetworkErrors[A](f: => Future[A]): PartialFunction[Throwable, Task[A]] = {
    case e @ (_: TimeoutException |
              _: UnavailableException) =>
      delayRetry(f, e)

    case e: IOException
      if e.getMessage == "Remotely closed" =>
      delayRetry(f, e)
  }

  private def delayRetry[A](f: => Future[A], error: Throwable): Task[A] = {
    val s = state.transformAndGet(_.inc())

    if (s.isOverMax) {
      Task.raiseError(
        new IllegalStateException(
          s"$maxErrors network issues found in $resetTime.",
          error
        )
      )
    } else {
      // Forces async boundary to avoid stack overflow
      Task.suspend(
        Task
          .deferFuture(f)
          .delayExecution(s.backoffDelay)
          .onErrorHandleWith(handleNetworkErrors(f))
          .doOnFinish(resetErrorCount)
      )
    }
  }

  private def resetErrorCount(res: Option[Throwable]): Task[Unit] = {
    val s = state.get

    if (res.isEmpty && s.hasErrors && s.isExpired) {
      state.compareAndSet(s, ZeroState)
    }

    Task.unit
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
