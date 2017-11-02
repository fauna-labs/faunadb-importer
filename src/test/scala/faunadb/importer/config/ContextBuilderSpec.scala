package faunadb.importer.config

import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._
import java.io.File

class ContextBuilderSpec extends SimpleSpec {
  import ContextBuilder.Dsl._

  val file = new File("file1")

  val config = Config(
    secret = "abc",
    endpoints = Seq("url1", "url2"),
    batchSize = 2,
    errorStrategy = ErrorStrategy.DoNotStop
  )

  "The context builder" should "build the pair of file and context" in {
    val builder = ContextBuilder()
    builder.file(file)
    builder += Clazz("clazz1")
    builder += Field("id", SelfRefT)
    builder += Field("name", StringT)
    builder += Rename("oldName", "newName")
    builder += Ignore("ignoredField")
    builder += TSField("tsField")
    builder += SkipRoot(true)

    builder.result(config) shouldBe Ok(
      Seq(
        file ->
          Context(
            config = config,
            clazz = "clazz1",
            idField = Some("id"),
            tsField = Some("tsField"),
            typesByField = Map("id" -> SelfRefT, "name" -> StringT),
            fieldsInOrder = IndexedSeq("id", "name"),
            fieldNameByLegacyName = Map("oldName" -> "newName"),
            ignoredFields = Set("ignoredField"),
            skipRootElement = true
          )
      )
    )
  }

  it should "ignore renames to the same name" in {
    val builder = ContextBuilder()
    builder.file(file)
    builder += Clazz("clazz1")
    builder += Rename("name", "name")

    builder.result(config) shouldBe Ok(
      Seq(file -> Context(config = config, clazz = "clazz1"))
    )
  }

  it should "fail when no file to import" in {
    ContextBuilder().result(config) shouldBe Err("No file to import could be found")
  }

  it should "fail when no class" in {
    val builder = ContextBuilder()
    builder.file(file)
    builder.result(config) shouldBe Err("\"class\" was NOT specified for import file file1")
  }

  it should "fail when no multiple ref fields" in {
    val builder = ContextBuilder()
    builder.file(file)
    builder += Field("id", SelfRefT)
    builder += Field("other", SelfRefT)
    builder.result(config) shouldBe Err("There can be only one self-ref field per import file")
  }

}
