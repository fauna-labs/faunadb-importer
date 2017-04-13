package faunadb.importer.parser

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._

class RecordParserSpec extends ContextSpec {

  def parse(values: Value*)(implicit context: Context): Stream[PR[Record]] =
    RecordParser.parse(Stream(values.map(Ok(_)): _*))

  "The record parser" should "parse a stream of values" in {
    val data = Object(pos,
      "name" -> Scalar(pos, StringT, "Bob")
    )

    parse(data) should contain only Ok(Record("1", None, data))
  }

  it should "use id field when specified" in {
    val withId = context.copy(idField = Some("id"))

    val data = Object(
      pos,
      "id" -> Scalar(pos, StringT, "42"),
      "name" -> Scalar(pos, StringT, "Bob")
    )

    parse(data)(withId) should contain only Ok(Record("42", None, data))
  }

  it should "use ts field when specified" in {
    val withId = context.copy(tsField = Some("ts"))
    val data = Object(pos, "ts" -> Scalar(pos, TimeT(None), "123"))

    parse(data)(withId) should contain only Ok(
      Record("1", Some(Scalar(pos, TimeT(None), "123")), data)
    )
  }

  it should "it should parse non objects" in {
    val data = Sequence(pos, Scalar(pos, LongT, "42"))
    parse(data) should contain only Ok(Record("1", None, data))
  }

  it should "fail on non objects when an id is expected" in {
    val withId = context.copy(idField = Some("id"))
    val data = Sequence(pos, Scalar(pos, LongT, "42"))

    parse(data)(withId) should contain only
      Err("Can not get field id from non object value at line: 0, column: 0: [42]")
  }

  it should "fail if id can not be found" in {
    val withId = context.copy(idField = Some("id"))
    val data = Object(pos, "name" -> Scalar(pos, StringT, "Bob"))

    parse(data)(withId) should contain only
      Err("Can not find field id for entry at line: 0, column: 0: { \"name\": \"Bob\" }")
  }

  it should "fail if id has a invalid type" in {
    val withId = context.copy(idField = Some("id"))
    val data = Object(pos, "id" -> Sequence(pos, Scalar(pos, StringT, "42")))

    parse(data)(withId) should contain only
      Err("Can not use value of field id as the id for entry at line: 0, column: 0: [\"42\"]")
  }
}
