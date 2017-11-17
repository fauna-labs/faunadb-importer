package faunadb.importer.persistence

import faunadb.specs._

class ConnectionPoolSpec extends SimpleSpec {

  "The connection pool" should "balance across all endpoints" in {
    val pool = ConnectionPool(
      secret = "secret",
      endpoints = Seq(
        "http://localhost:8443",
        "http://localhost:8444"
      ),
    )

    try {
      val cA1 = pool.pickClient()
      val cB1 = pool.pickClient()
      val cA2 = pool.pickClient()

      cA1 shouldNot be(cB1) // Choose different clients when all ref counts are the same
      cA1 shouldBe cA2 // Go around and start again when all ref counts are the same
    } finally {
      pool.close()
    }
  }

  it should "point to cloud if no endpoint" in {
    val pool = ConnectionPool(
      secret = "secret",
      endpoints = Seq.empty,
    )

    try {
      val client = pool.pickClient()
      client shouldNot be(null) // Default to cloud
    } finally {
      pool.close()
    }
  }
}
