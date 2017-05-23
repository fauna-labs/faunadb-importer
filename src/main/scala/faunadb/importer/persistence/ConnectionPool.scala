package faunadb.importer.persistence

import com.ning.http.client.AsyncHandler._
import com.ning.http.client._
import faunadb._
import faunadb.importer.report._
import java.util.concurrent.TimeUnit._
import scala.collection.JavaConversions._

object ConnectionPool {
  def apply(endpoints: Seq[String], secret: String): ConnectionPool =
    new ConnectionPool(endpoints, secret)
}

final class ConnectionPool private(endpoints: Seq[String], secret: String) {
  val clients: Seq[FaunaClient] =
    if (endpoints.isEmpty) Seq(FaunaClient(secret, httpClient = new HttpWrapper()))
    else endpoints map (FaunaClient(secret, _, httpClient = new HttpWrapper()))

  val size: Int = clients.size
  def close(): Unit = clients foreach (_.close())
}

private final class HttpWrapper extends AsyncHttpClient(
  new AsyncHttpClientConfig.Builder()
    .setConnectTimeout(10000)
    .setRequestTimeout(60000)
    .setPooledConnectionIdleTimeout(4750)
    .setMaxRequestRetry(0)
    .build()
) {
  override def executeRequest[T](request: Request, handler: AsyncHandler[T]): ListenableFuture[T] =
    super.executeRequest(request, new LatencyMeasureHandler(handler))
}

private final class LatencyMeasureHandler[T](delegate: AsyncHandler[T]) extends AsyncHandler[T] {
  private val Time = "X-Query-Time"

  def onHeadersReceived(headers: HttpResponseHeaders): STATE = {
    headers.getHeaders.get(Time) foreach (time => Stats.ServerLatency.update(time.toLong, MILLISECONDS))
    delegate.onHeadersReceived(headers)
  }

  def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = delegate.onBodyPartReceived(bodyPart)
  def onStatusReceived(status: HttpResponseStatus): STATE = delegate.onStatusReceived(status)
  def onThrowable(t: Throwable): Unit = delegate.onThrowable(t)
  def onCompleted(): T = delegate.onCompleted()
}
