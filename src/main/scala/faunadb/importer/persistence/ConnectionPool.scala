package faunadb.importer.persistence

import com.ning.http.client.AsyncHandler._
import com.ning.http.client._
import faunadb._
import faunadb.importer.report._
import java.util.concurrent.TimeUnit._
import scala.collection.JavaConverters._

trait ConnectionPool {
  def pickClient(): FaunaClient
  def close(): Unit
}

object ConnectionPool {
  def apply(endpoints: Seq[String], secret: String): ConnectionPool = {
    val httpWrapper = new HttpWrapper // Reuse http client

    endpoints match {
      case endpoint :: Nil => new SingleConnPool(FaunaClient(secret, endpoint, httpClient = httpWrapper))
      case Nil             => new SingleConnPool(FaunaClient(secret, httpClient = httpWrapper))
      case _               => new MultipleConnPool(endpoints map (FaunaClient(secret, _, httpClient = httpWrapper)))
    }
  }
}

private final class SingleConnPool(client: FaunaClient) extends ConnectionPool {
  def pickClient(): FaunaClient = client
  def close(): Unit = client.close()
}

private final class MultipleConnPool(clients: Seq[FaunaClient]) extends ConnectionPool {
  @volatile
  private[this] var searchIndex = -1
  private[this] val clientsByIndex = clients.toIndexedSeq
  private[this] val poolSize = clients.size

  def pickClient(): FaunaClient = {
    searchIndex = Math.max((searchIndex + 1) % poolSize, 0)
    clientsByIndex(searchIndex)
  }

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
  override def prepareRequest(request: Request): AsyncHttpClient#BoundRequestBuilder = {
    super
      .prepareRequest(request)
      .addHeader("User-Agent", "faunadb-importer")
  }

  override def executeRequest[T](request: Request, handler: AsyncHandler[T]): ListenableFuture[T] = {
    super.executeRequest(request, new LatencyMeasureHandler(handler))
  }
}

private final class LatencyMeasureHandler[T](delegate: AsyncHandler[T])
  extends AsyncHandler[T] {

  def onHeadersReceived(headers: HttpResponseHeaders): STATE = {
    headers.getHeaders.get("X-Query-Time").asScala foreach (time => Stats.ServerLatency.update(time.toLong, MILLISECONDS))
    delegate.onHeadersReceived(headers)
  }

  def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = delegate.onBodyPartReceived(bodyPart)
  def onStatusReceived(status: HttpResponseStatus): STATE = delegate.onStatusReceived(status)
  def onThrowable(t: Throwable): Unit = delegate.onThrowable(t)
  def onCompleted(): T = delegate.onCompleted()
}
