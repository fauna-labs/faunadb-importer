package faunadb.importer.parser

import faunadb.importer.config.Context
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._

class TSVSpec extends ParserSpec {
  
  val parser = TSV

  implicit val tsvContext: Context = context.copy(
    fieldsInOrder = Vector("name", "age")
  )

  "The tsv parser" should "parse tab separate strings" in {
    parse("bob\t42\njoe\t25") should contain only (
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
    val useHeaderLine = tsvContext.copy(
      skipRootElement = true
    )

    parse("aa\tbb\nbob\t42")(useHeaderLine) should contain only Ok(
      Object(Pos(2, 1),
        "name" -> Scalar(Pos(2, 1), StringT, "bob"),
        "age" -> Scalar(Pos(2, 5), StringT, "42")
      )
    )
  }

  it should "parse empty file" in {
    parse("") shouldBe empty
  }

  it should "parser empty values as null" in {
    parse("\t") should contain only Ok(
      Object(Pos(1, 1),
        "name" -> Null(Pos(1, 1)),
        "age" -> Null(Pos(1, 2))
      )
    )
  }

  it should "parser null values as null" in {
    parse("null \tNULL") should contain only Ok(
      Object(Pos(1, 1),
        "name" -> Null(Pos(1, 1)),
        "age" -> Null(Pos(1, 7))
      )
    )
  }

  it should "fail when entry has more columns than specified" in {
    parse("bob\t42\tmale") should contain only
      Err("Line has more columns than specified at line: 1, column: 8")
  }
}
