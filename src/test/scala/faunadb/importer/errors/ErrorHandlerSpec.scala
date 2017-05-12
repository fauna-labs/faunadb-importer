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
    ErrorHandler.handle(Ok(1)) shouldBe Some(1)
    ErrorHandler.handle(Err("a error")) shouldBe None
  }

  it should "throw an error by default when an error is found" in {
    the[ErrorHandler.Stop] thrownBy ErrorHandler.handle(Err("an error")) should have message "an error"
  }
}
