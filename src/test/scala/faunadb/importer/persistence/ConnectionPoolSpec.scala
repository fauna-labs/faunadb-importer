package faunadb.importer.persistence

import faunadb.specs._

class ConnectionPoolSpec extends SimpleSpec {

  "The connection pool" should "contain faunadb clients" in {
    val pool = ConnectionPool(
      endpoints = Seq(
        "http://localhost:8443",
        "http://localhost:8444",
        "http://localhost:8445"
      ),
      secret = "secret"
    )

    try pool.size shouldBe 3 finally pool.close()
  }

  it should "point to cloud if no endpoint" in {
    val pool = ConnectionPool(Seq.empty, "secret")
    try pool.size shouldBe 1 finally pool.close()
  }

  it should "balance across all endpoints" in {
    val pool = ConnectionPool(
      endpoints = Seq(
        "http://localhost:8443",
        "http://localhost:8444"
      ),
      secret = "secret"
    )

    try {
      val cA = pool.pickClient
      val cB = pool.pickClient
      val cA1 = pool.pickClient

      cA shouldNot be(cB) // Choose different clients when all ref counts are the same
      cA1 shouldBe cA // Go around and start again when all ref counts are the same

      pool.release(cB)
      pool.pickClient shouldBe cB // Pick the one with less references
      pool.pickClient shouldBe cB // Pick the one with less references
      pool.pickClient shouldBe cB // Pick anyone since all ref counts are the same
      pool.pickClient shouldBe cA // Go around and start again when all ref counts are the same
    } finally {
      pool.close()
    }
  }

}
