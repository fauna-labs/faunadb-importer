package faunadb.importer.values

import faunadb.importer.lang._
import faunadb.specs._

class TypeSpec extends SimpleSpec {

  "Types" should "be able to be found by their definition" in {
    Type.byDefinition("ref") shouldBe Ok(SelfRefT)
    Type.byDefinition("ref(users)") shouldBe Ok(RefT("users"))
    Type.byDefinition("string") shouldBe Ok(StringT)
    Type.byDefinition("long") shouldBe Ok(LongT)
    Type.byDefinition("double") shouldBe Ok(DoubleT)
    Type.byDefinition("bool") shouldBe Ok(BoolT)
    Type.byDefinition("ts") shouldBe Ok(TimeT(None))
    Type.byDefinition("ts(yyyy)") shouldBe Ok(TimeT(Some("yyyy")))
    Type.byDefinition("date") shouldBe Ok(DateT(None))
    Type.byDefinition("date(yyyy)") shouldBe Ok(DateT(Some("yyyy")))
    Type.byDefinition("invalidType") shouldBe Err("Unknown type \"invalidType\"")
    Type.byDefinition("something else") shouldBe Err("Could NOT parse field definition \"something else\"")
  }

}
