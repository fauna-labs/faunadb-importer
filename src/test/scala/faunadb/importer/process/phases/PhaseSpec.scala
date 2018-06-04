package faunadb.importer.process.phases

import faunadb.errors._
import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang.{ Result, _ }
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query._
import faunadb.specs._
import faunadb.values.{ Value => FValue, _ }
import java.io.IOException
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._

class PhaseSpec
  extends ContextSpec
    with ConcurrentUtils
    with Mocks {

  implicit val c: Context = context.copy(
    config = context.config.copy(
      networkErrorsBackoffTime = 1.micro
    )
  )

  class FakePhase(connPool: ConnectionPool)
    extends Phase("fake phase", connPool)
      with DiscardValues {

    protected def buildQuery(record: Record): Result[Expr] =
      Ok(NullV)
  }

  "A Phase" should "process records in order" in new MockedFauna {
    var output: mutable.Builder[Record, Seq[Record]] =
      Seq.newBuilder[Record]

    val phase = new FakePhase(connPool) {
      override protected def handleResponse(record: Record, value: FValue): Result[Unit] = {
        output += record
        super.handleResponse(record, value)
      }
    }

    val input = Seq(record1, record2, record3)

    queryReturns(NullV)

    await(phase.run(
      input.iterator
    ))

    verifyQueryWasCalled()
    output.result() shouldBe input
  }

  it should "fail if it can not build a query" in new MockedFauna {
    val phase = new FakePhase(connPool) {
      protected override def buildQuery(record: Record): Result[Expr] =
        Err("an error")
    }

    the[ErrorHandler.Stop] thrownBy await(
      phase.run(
        Iterator(
          record1
        )
      )
    ) should have message "an error"

    verifyQueryWasNOTCalled()
  }

  it should "fail if it can not handle the respose" in new MockedFauna {
    val phase = new FakePhase(connPool) {
      override protected def handleResponse(record: Record, value: FValue): Result[Unit] =
        Err("an error")
    }

    queryReturns(NullV)

    the[ErrorHandler.Stop] thrownBy await(
      phase.run(
        Iterator(
          record1
        )
      )
    ) should have message "an error"

    verifyQueryWasCalled()
  }

  it should "retry on bad request" in new RetryOn(new BadRequestException("bad request"))
  it should "retry on not round" in new RetryOn(new NotFoundException("not found"))
  it should "retry on request too large" in new RetryOn(new UnknownException("request too large"))
  it should "retry on 413" in new RetryOn(new UnknownException("Unparseable service 413 response"))
  it should "backoff and retry on timeout" in new BackoffOn(new TimeoutException("time out"))
  it should "backoff and retry on unavailable" in new BackoffOn(new UnavailableException("unavailable"))
  it should "backoff and retry on remotely closed" in new BackoffOn(new IOException("Remotely closed"))

  it should "not retry on other errors" in new MockedFauna {
    val phase = new FakePhase(connPool)

    queryFailsWith(new UnknownException("something went wrong"))

    the[UnknownException] thrownBy await(
      phase.run(
        Iterator(
          record1
        )
      )
    ) should have message "something went wrong"
  }

  class RetryOn(error: Throwable) extends MockedFauna {
    val phase = new FakePhase(connPool)

    inSequence {
      queryFailsWith(error)
      queryReturns(NullV)
      queryReturns(NullV)
    }

    await(
      phase.run(Iterator(
        record1,
        record2
      ))
    )

    // One for the failing batch, then one for each item in the batch
    verifyQueryWasCalled(times = 3)
  }

  class BackoffOn(error: Throwable) extends MockedFauna {
    val phase = new FakePhase(connPool)

    inSequence {
      queryFailsWith(error)
      queryReturns(NullV)
    }

    await(
      phase.run(Iterator(
        record1,
        record2
      ))
    )

    // Just retry the whole batch without splitting it
    verifyQueryWasCalled(times = 2)
  }
}
