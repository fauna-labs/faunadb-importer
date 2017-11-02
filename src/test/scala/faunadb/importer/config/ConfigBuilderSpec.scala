package faunadb.importer.config

import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.specs._

class ConfigBuilderSpec extends SimpleSpec {
  import ConfigBuilder.Dsl._

  "The config builder" should "build a configuration" in {
    val builder = ConfigBuilder()
    builder += Secret("abc")
    builder += Endpoints(Seq("url1", "url2"))
    builder += BatchSize(2)
    builder += ConcurrentStreams(2)
    builder += OnError(ErrorStrategy.DoNotStop)

    builder.result() shouldBe Ok(
      Config(
        secret = "abc",
        endpoints = Seq("url1", "url2"),
        batchSize = 2,
        concurrentStreams = 2,
        errorStrategy = ErrorStrategy.DoNotStop
      )
    )
  }

  it should "fail when no secret is provided" in {
    ConfigBuilder().result() shouldBe Err("No key's secret specified")
  }

}
