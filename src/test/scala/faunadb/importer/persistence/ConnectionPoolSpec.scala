package faunadb.importer.persistence

import faunadb.specs._

class ConnectionPoolSpec extends SimpleSpec {

  "The connection pool" should "return the maximun number of concurrent requests allowed" in {
    val pool = ConnectionPool(
      endpoints = Seq(
        "http://localhost:8443",
        "http://localhost:8444",
        "http://localhost:8445"
      ),
      secret = "secret",
      maxRefCountPerEndpoint = 5
    )

    try pool.maxConcurrentReferences shouldBe 15 finally pool.close()
  }

  it should "point to cloud if no endpoint" in {
    val pool = ConnectionPool(Seq.empty, "secret", 1)
    try pool.maxConcurrentReferences shouldBe 1 finally pool.close() // Has a single client pointing to cloud
  }

  it should "balance across all endpoints" in {
    val pool = ConnectionPool(
      endpoints = Seq(
        "http://localhost:8443",
        "http://localhost:8444"
      ),
      secret = "secret",
      maxRefCountPerEndpoint = 3
    )

    try {
      val cA = pool.borrowClient()
      val cB = pool.borrowClient()
      val cA1 = pool.borrowClient()

      cA shouldNot be(cB) // Choose different clients when all ref counts are the same
      cA1 shouldBe cA // Go around and start again when all ref counts are the same

      pool.returnClient(cB)
      pool.borrowClient() shouldBe cB // Pick the one with less references
      pool.borrowClient() shouldBe cB // Pick the one with less references
      pool.borrowClient() shouldBe cB // Pick anyone since all ref counts are the same
      pool.borrowClient() shouldBe cA // Go around and start again when all ref counts are the same
    } finally {
      pool.close()
    }
  }

}
