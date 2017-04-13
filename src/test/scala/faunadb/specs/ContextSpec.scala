package faunadb.specs

import faunadb.importer.config._

trait ContextSpec
  extends SimpleSpec
    with DataFixtures {

  implicit val context = Context(
    config = Config(
      secret = "secret",
      endpoints = Seq("http://localhost:8443")
    ),
    clazz = aClass
  )
}
