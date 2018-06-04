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
import faunadb.query.{ Arr, Do, Expr }
import faunadb.values.{ NullV, Value => FValue }
import java.io._
import monix.eval._
import monix.execution.atomic._
import monix.reactive._
import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.duration._

private[process] abstract class Phase(val description: String, connPool: ConnectionPool)(implicit c: Context) {
  this: QueryRunner =>

  private type Records = Seq[Record]
  private type QueryBatch = (Seq[Record], Seq[Expr])
  private type SuccessfulBatch = (Seq[Record], Seq[FValue])
  private type RetryQuery = (Record, Expr)
  private type RetryBatch = Seq[RetryQuery]
  private type RetriedPair = (Record, Result[FValue])
  private type RetriedBatch = Seq[RetriedPair]
  private type BatchResult = Either[RetriedBatch, SuccessfulBatch]

  private[this] val backoff = new Backoff(
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
      .bufferTumbling(c.config.batchSize)
      .consumeWith(
        Consumer.foreachParallelAsync(
          c.config.concurrentStreams)(execute)
      )
      .runAsync
  }

  private def execute(batch: Records): Task[Unit] = {
    runBatch(connPool.pickClient(), buildQueries(batch))
      .onErrorRecoverWith(handleWrongCredentials)
      .foreachL(handleResults)
  }

  private def buildQueries(batch: Records): QueryBatch = {
    val records = Seq.newBuilder[Record]
    val queries = Seq.newBuilder[Expr]

    batch foreach { record =>
      ErrorHandler.handle(buildQuery(record)) foreach { query =>
        records += record
        queries += query
      }
    }

    (records.result(), queries.result())
  }

  private def runBatch(client: FaunaClient, batch: QueryBatch): Task[BatchResult] = {
    val (records, queries) = batch

    backoff(Stats.ImportLatency.measure(runQuery(client, queries)))
      .map(values => Right((records, values)))
      .onErrorRecoverWith(
        handleQueryErrorsWith(_ =>
          splitBatchAndRetry(client, records zip queries) map (Left(_))
        )
      )
  }

  private def splitBatchAndRetry(client: FaunaClient, retryBatch: RetryBatch): Task[RetriedBatch] = {
    Observable
      .fromIterable(retryBatch)
      .mapTask(retryQuery(client, _))
      .toListL
  }

  private def retryQuery(client: FaunaClient, pair: RetryQuery): Task[RetriedPair] = {
    val (record, expr) = pair

    backoff(Stats.ImportLatency.measure(client.query(expr)))
      .map(record -> Result(_))
      .onErrorRecover(
        handleQueryErrorsWith(e =>
          record -> Err(s"${e.getMessage} at ${record.localized}")
        )
      )
  }

  private def handleResults(response: BatchResult): Unit = {
    response match {
      case Right(successfulBatch) => handleSuccessfulBatch(successfulBatch)
      case Left(retriedBatch)     => handleRetriedBatch(retriedBatch)
    }
  }

  private def handleSuccessfulBatch(successfulBatch: SuccessfulBatch): Unit = {
    @tailrec
    def loop0(records: Records, values: Seq[FValue]): Unit = {
      if (records.nonEmpty) {
        ErrorHandler.handle(handleResponse(records.head, values.head))
        loop0(records.tail, values.tail)
      }
    }

    val (records, values) = successfulBatch
    loop0(records, values)
  }

  private def handleRetriedBatch(retriedBatch: RetriedBatch): Unit = {
    retriedBatch foreach { case (record, retriedValue) =>
      ErrorHandler.handle(retriedValue) foreach { value =>
        ErrorHandler.handle(handleResponse(record, value))
      }
    }
  }

  private def handleQueryErrorsWith[B](handle: Throwable => B): PartialFunction[Throwable, B] = {
    case e @ (_: BadRequestException |
              _: NotFoundException) =>
      handle(e)

    case e: UnknownException
      if e.getMessage.startsWith("request too large") ||
        e.getMessage.startsWith("Unparseable service 413 response") =>
      Log.warn("Request too large. Consider reducing the configured batch-size.")
      handle(e)
  }

  private val handleWrongCredentials: PartialFunction[Throwable, Task[Nothing]] = {
    case e @ (_: UnauthorizedException |
              _: PermissionDeniedException) =>
      Log.info("Invalid key. You must provide a valid SERVER key.")
      Task.raiseError(e)
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
    def isExpired: Boolean = lastErrorTs < Scheduler.currentTimeMillis() - resetTime.toMillis

    def inc(): State = {
      State(
        totalErrors + 1,
        Scheduler.currentTimeMillis(),
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

  private[this] final val ZeroState = State(0, 0, Duration.Zero)
  private[this] final val state = Atomic(ZeroState)

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
  private[this] val nulls =
    Stream.continually(NullV)

  protected final def runQuery(client: FaunaClient, exprs: Seq[Expr]): Future[Seq[FValue]] = {
    client
      .query(Do(Arr(exprs: _*), NullV))
      .map(_ => nulls.take(exprs.length))
  }

  // When discarding results you probably don't want to check the responses as well
  protected def handleResponse(record: Record, value: FValue): Result[Unit] = Result.unit
}
