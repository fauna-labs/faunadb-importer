package faunadb.importer.parser

import faunadb.importer.config.Context
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._

class CSVSpec extends ContextSpec with IOReaderUtils {

  implicit val csvContext: Context = context.copy(
    fieldsInOrder = Vector("name", "age")
  )

  "The csv parser" should "parse comma separate strings" in {
    CSV.parse(
      """bob,42
        |joe,25""".stripMargin
    ) should contain only (
      Ok(Object(Pos(1, 1),
        "name" -> Scalar(Pos(1, 1), StringT, "bob"),
        "age" -> Scalar(Pos(1, 5), StringT, "42")
      )),
      Ok(Object(Pos(2, 1),
        "name" -> Scalar(Pos(2, 1), StringT, "joe"),
        "age" -> Scalar(Pos(2, 5), StringT, "25")
      ))
    )
  }

  it should "use header line" in {
    val useHeaderLine = csvContext.copy(
      skipRootElement = true
    )

    CSV.parse(
      """aa,bb
        |bob,42""".stripMargin
    )(useHeaderLine) should contain only Ok(
      Object(Pos(2, 1),
        "name" -> Scalar(Pos(2, 1), StringT, "bob"),
        "age" -> Scalar(Pos(2, 5), StringT, "42")
      )
    )
  }

  it should "parse empty file" in {
    CSV.parse("") shouldBe empty
  }

  it should "parser empty values as null" in {
    CSV.parse(",") should contain only Ok(
      Object(Pos(1, 1),
        "name" -> Null(Pos(1, 1)),
        "age" -> Null(Pos(1, 2))
      )
    )
  }

  it should "parser null values as null" in {
    CSV.parse("null ,NULL") should contain only Ok(
      Object(Pos(1, 1),
        "name" -> Null(Pos(1, 1)),
        "age" -> Null(Pos(1, 7))
      )
    )
  }

  it should "fail when entry has more columns than specified" in {
    CSV.parse("bob,42,male") should contain only
      Err("Line has more columns than specified at line: 1, column: 8")
  }
}
