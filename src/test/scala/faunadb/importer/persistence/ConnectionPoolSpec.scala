package faunadb.importer.persistence

import faunadb.specs._
import org.scalatest._

class ConnectionPoolSpec extends SimpleSpec with BeforeAndAfterAll {

  "The connection pool" should "contain faunadb clients" in {
    val pool = ConnectionPool(
      Seq(
        "http://localhost:8443",
        "http://localhost:8444",
        "http://localhost:8445"
      ),
      "secret"
    )

    try pool.size shouldBe 3 finally pool.close()
  }

  it should "point to cloud if no endpoint" in {
    val pool = ConnectionPool(Seq.empty, "secret")
    try pool.size shouldBe 1 finally pool.close()
  }

}
