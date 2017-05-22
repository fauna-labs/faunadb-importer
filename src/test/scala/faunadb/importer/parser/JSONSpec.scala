package faunadb.importer.parser

import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._
import scala.language.implicitConversions

class JSONSpec extends ContextSpec with IOReaderUtils {

  "The JSON parser" should "parse an empty source" in {
    JSON.parse("") shouldBe empty
  }

  it should "parse fields from an object" in {
    JSON.parse(
      """
        |{
        |   "name": "bob",
        |   "age": 21,
        |   "weight": 78.5,
        |   "active": true,
        |   "notHere": null,
        |   "nested": {
        |     "obj": "here"
        |   },
        |   "emptyObj": {},
        |   "arr": [1, 2.1, "three", { "a": 1 }],
        |   "emptyArr": []
        |}
        |""".stripMargin
    ) should contain only Ok(
      Object(Pos(2, 1),
        "name" -> Scalar(Pos(3, 12), StringT, "bob"),
        "age" -> Scalar(Pos(4, 11), LongT, "21"),
        "weight" -> Scalar(Pos(5, 14), DoubleT, "78.5"),
        "active" -> Scalar(Pos(6, 14), BoolT, "true"),
        "notHere" -> Null(Pos(7, 15)),
        "nested" -> Object(Pos(8, 14),
          "obj" -> Scalar(Pos(9, 13), StringT, "here")
        ),
        "emptyObj" -> Object(Pos(11, 16)),
        "arr" -> Sequence(
          Pos(12, 11),
          Scalar(Pos(12, 12), LongT, "1"),
          Scalar(Pos(12, 15), DoubleT, "2.1"),
          Scalar(Pos(12, 20), StringT, "three"),
          Object(Pos(12, 29),
            "a" -> Scalar(Pos(12, 36), LongT, "1")
          )
        ),
        "emptyArr" -> Sequence(Pos(13, 16))
      )
    )
  }

  it should "parse multiple objects" in {
    JSON.parse(
      """
        |{ "a": "b" }
        |{ "c": "d" }
      """.stripMargin
    ) should contain only (
      Ok(Object(Pos(2, 1), "a" -> Scalar(Pos(2, 8), StringT, "b"))),
      Ok(Object(Pos(3, 1), "c" -> Scalar(Pos(3, 8), StringT, "d")))
    )
  }

  it should "parse multiple objects in the same line" in {
    JSON.parse("""{ "a": "b" }{ "c": "d" }""") should contain only (
      Ok(Object(Pos(1, 1), "a" -> Scalar(Pos(1, 8), StringT, "b"))),
      Ok(Object(Pos(1, 13), "c" -> Scalar(Pos(1, 20), StringT, "d")))
    )
  }

  it should "fail on broken entries" in {
    JSON.parse("""{ "a": b }{ "c": "d" }""") should contain only Err(
      "Invalid JSON entry at line: 1, column: 9. " +
        "Unrecognized token 'b': was expecting ('true', 'false' or 'null')"
    )
  }

  it should "be able to skip array" in {
    val skipArray = context.copy(skipRootElement = true)

    JSON.parse("""[]""")(skipArray) shouldBe empty
    JSON.parse("""[{ "a": "b" }, { "c": "d" }]""")(skipArray) should contain only (
      Ok(Object(Pos(1, 2), "a" -> Scalar(Pos(1, 9), StringT, "b"))),
      Ok(Object(Pos(1, 16), "c" -> Scalar(Pos(1, 23), StringT, "d")))
    )
  }
}
