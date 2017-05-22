package faunadb.importer.values

import faunadb.importer.lang.{ Result, _ }
import faunadb.specs._
import faunadb.values.{ Value => FValue, _ }

class RecordConverterSpec extends ContextSpec with DataFixtures {

  implicit val _ = context.copy(
    typesByField = Map("age" -> DoubleT),
    fieldNameByLegacyName = Map("created_at" -> "ts"),
    ignoredFields = Set("old_field")
  )

  val toFauna: (Record) => Result[FValue] = RecordConverter(allRecordsIds)

  "The record converter" should "convert from AST to fauna values" in {
    toFauna(Record("0", None, Null(pos))) shouldBe Ok(NullV)
    toFauna(Record("0", None, Scalar(pos, StringT, "a string"))) shouldBe Ok(StringV("a string"))
    toFauna(Record("0", None, Scalar(pos, LongT, "10"))) shouldBe Ok(LongV(10))
    toFauna(Record("0", None, Scalar(pos, DoubleT, "10.2"))) shouldBe Ok(DoubleV(10.2))
    toFauna(Record("0", None, Scalar(pos, BoolT, "true"))) shouldBe Ok(BooleanV(true))
    toFauna(Record("0", None, Scalar(pos, SelfRef, "1"))) shouldBe Ok(StringV("0"))
    toFauna(Record("0", None, Scalar(pos, Ref(aClass), "2"))) shouldBe Ok(RefV(s"classes/$aClass/${record2.id}"))
    toFauna(Record("0", None, Scalar(pos, TimeT(None), "2017-02-01T00:00:00Z"))) shouldBe Ok(TimeV("2017-02-01T00:00:00Z"))
    toFauna(Record("0", None, Scalar(pos, TimeT(Some("yyyy")), "2017"))) shouldBe Ok(TimeV("2017"))
    toFauna(Record("0", None, Scalar(pos, DateT(Some("yyyy")), "2017"))) shouldBe Ok(DateV("2017"))
    toFauna(Record("0", None, Object(pos, "name" -> Scalar(pos, StringT, "bob")))) shouldBe Ok(ObjectV("name" -> StringV("bob")))
    toFauna(Record("0", None, Sequence(pos, Scalar(pos, StringT, "bob")))) shouldBe Ok(ArrayV(StringV("bob")))
  }

  it should "apply type conversions if specified" in {
    toFauna(Record("0", None, Object(pos, Map("age" -> Scalar(pos, StringT, "42"))))) shouldBe
      Ok(ObjectV("age" -> DoubleV(42.0)))
  }

  it should "rename fields" in {
    toFauna(Record("0", None, Object(pos, "created_at" -> Null(pos)))) shouldBe Ok(ObjectV("ts" -> NullV))
  }

  it should "ignore fields" in {
    toFauna(Record("0", None, Object(pos, "old_field" -> Null(pos)))) shouldBe Ok(ObjectV())
  }

  it should "not convert nested values" in {
    toFauna(Record("0", None, Object(pos,
      "nested" -> Object(pos,
        "age" -> Scalar(pos, StringT, "42"),
        "created_at" -> Scalar(pos, StringT, "4222"),
        "old_field" -> Scalar(pos, StringT, "old")
      )))) shouldBe
      Ok(ObjectV(
        "nested" -> ObjectV(
          "age" -> StringV("42"),
          "created_at" -> StringV("4222"),
          "old_field" -> StringV("old")
        )
      ))
  }

  it should "allow nulls when types are manually specified" in {
    toFauna(Record("0", None, Object(pos, "age" -> Null(pos)))) shouldBe Ok(ObjectV("age" -> NullV))
  }

  it should "fail on invalid data" in {
    toFauna(Record("0", None, Scalar(pos, BoolT, "bananas"))) shouldBe
      Err("Can not convert value to boolean at line: 0, column: 0: bananas. For input string: \"bananas\"")
  }

  it should "fail on invalid type definition" in {
    toFauna(Record("0", None, Object(pos, "age" -> Sequence(pos)))) shouldBe
      Err("Can not convert value to double at line: 0, column: 0: []")
  }

  it should "on missing foreign key" in {
    toFauna(Record("0", None, Scalar(pos, Ref("non-existing"), "1"))) shouldBe
      Err("Can not find referenced id 1 for class non-existing at line: 0, column: 0: 1")
  }
}
