package faunadb.importer.errors

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.specs._

class ErrorHandlerSpec extends ContextSpec {
  self =>

  trait NoStop {
    implicit val context: Context = self.context.copy(
      config = self.context.config.copy(
        errorStrategy = ErrorStrategy.DoNotStop
      )
    )
  }

  "The error handler" should "handle result" in new NoStop {
    ErrorHandler.check(Ok(1)) shouldBe false
    ErrorHandler.check(Err("a error")) shouldBe true
  }

  it should "handle sequences" in new NoStop {
    ErrorHandler.check(Seq(Ok(1), Ok(2))) shouldBe false
    ErrorHandler.check(Seq(Ok(1), Err("an error"))) shouldBe true
  }

  it should "filter sequences" in new NoStop {
    ErrorHandler.filter(Seq(Ok(1), Err("an error"), Ok(3))) shouldBe Seq(Ok(1), Ok(3))
  }

  it should "throw an error by default when an error is found" in {
    the[ErrorHandler.Stop] thrownBy ErrorHandler.check(Err("an error")) should have message "an error"
  }
}
