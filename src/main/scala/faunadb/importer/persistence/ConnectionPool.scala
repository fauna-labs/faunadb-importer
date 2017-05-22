package faunadb.importer.persistence

import faunadb.FaunaClient

object ConnectionPool {
  def apply(endpoints: Seq[String], secret: String): ConnectionPool =
    new ConnectionPool(endpoints, secret)
}

final class ConnectionPool private(endpoints: Seq[String], secret: String) {
  val clients: Seq[FaunaClient] =
    if (endpoints.isEmpty) Seq(FaunaClient(secret))
    else endpoints map (FaunaClient(secret, _))

  val size: Int = clients.size
  def close(): Unit = clients.foreach(_.close())
}
