package faunadb.specs

import faunadb._
import faunadb.importer.persistence._
import faunadb.query._
import faunadb.values.{ Value => FValue }
import scala.concurrent._

trait Mocks {
  this: SimpleSpec =>

  trait MockedFauna {
    val faunaClient: FaunaClient = stub[FaunaClient]

    val connPool: ConnectionPool = {
      val pool = stub[ConnectionPool]
      (pool.borrowClient _).when().returns(faunaClient)
      (pool.maxConcurrentReferences _).when().returns(1)
      pool
    }

    def queryReturns(value: FValue): Unit = {
      (faunaClient.query(_: Expr)(_: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(value))
        .once()
    }

    def queryFailsWith(error: Throwable): Unit = {
      (faunaClient.query(_: Expr)(_: ExecutionContext))
        .when(*, *)
        .returns(Future.failed(error))
        .once()
    }

    def verifyQueryWasCalled(times: Int = 1): Unit = {
      (faunaClient.query(_: Expr)(_: ExecutionContext))
        .verify(*, *)
        .repeat(times)
    }

    def verifyQueryWasNOTCalled(): Unit = {
      (faunaClient.query(_: Expr)(_: ExecutionContext))
        .verify(*, *)
        .never()
    }

    def batchedQueryReturns(values: FValue*): Unit = {
      (faunaClient.query(_: Iterable[Expr])(_: ExecutionContext))
        .when(*, *)
        .returns(Future.successful(values.toIndexedSeq))
        .once()
    }

    def verifyBatchWasCalled(times: Int): Unit = {
      (faunaClient.query(_: Iterable[Expr])(_: ExecutionContext))
        .verify(*, *)
        .repeat(times)
    }
  }

}
