package faunadb.importer.parser

import faunadb.importer.config.Context
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._
import java.io.File

class InputParserSpec extends ContextSpec {

  val jsonFile = new File("src/test/resources/testdata.json")
  val csvFile = new File("src/test/resources/testdata.csv")
  val invalidFile = new File("src/test/resources/logback-test.xml")
  val invalidFileName = new File("src/test/resources/blankfile")

  "The input parser" should "parse a JSON file" in {
    InputParser(jsonFile).get.records() should contain only (
      Ok(
        Record("1", None, Object(Pos(1, 1),
          "name" -> Scalar(Pos(1, 11), StringT, "Bob D"),
          "age" -> Scalar(Pos(1, 27), LongT, "21")
        ))
      ),
      Ok(
        Record("2", None, Object(Pos(2, 1),
          "name" -> Scalar(Pos(2, 11), StringT, "Marry"),
          "age" -> Scalar(Pos(2, 27), LongT, "22")
        ))
      )
    )
  }

  it should "parse a CSV file" in {
    val csvContext: Context = context.copy(
      fieldsInOrder = Vector("name", "age")
    )

    InputParser(csvFile)(csvContext).get.records() should contain only (
      Ok(
        Record("1", None, Object(Pos(1, 1),
          "name" -> Scalar(Pos(1, 1), StringT, "Bob D"),
          "age" -> Scalar(Pos(1, 7), StringT, "21")
        ))
      ),
      Ok(
        Record("2", None, Object(Pos(2, 1),
          "name" -> Scalar(Pos(2, 1), StringT, "Marry"),
          "age" -> Scalar(Pos(2, 7), StringT, "22")
        ))
      )
    )
  }

  it should "fail with invalid file extension" in {
    InputParser(invalidFile) shouldBe
      Err("Unsupported file type xml for logback-test.xml")
  }

  it should "fail with invalid file name" in {
    InputParser(invalidFileName) shouldBe
      Err("Unsupported file blankfile")
  }

}
