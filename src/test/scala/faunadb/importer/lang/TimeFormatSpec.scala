package faunadb.importer.lang

import faunadb.specs.SimpleSpec

class TimeFormatSpec extends SimpleSpec {

  "The time" should "pretty print an interval" in {
    TimeFormat.prettyDuration(11234124, 52102300) shouldBe "11:21:08.176"
  }

}
