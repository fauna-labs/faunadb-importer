package faunadb.importer.persistence

import com.ning.http.client.AsyncHandler._
import com.ning.http.client._
import faunadb._
import faunadb.importer.report._
import java.util.concurrent.TimeUnit._
import java.util.concurrent.atomic._
import scala.collection.JavaConversions._

trait ConnectionPool {
  def pickClient: FaunaClient
  def release(client: FaunaClient): Unit
  def close(): Unit
  def size: Int
}

object ConnectionPool {
  def apply(endpoints: Seq[String], secret: String): ConnectionPool =
    new FaunaClientPool(endpoints, secret)
}

private final class FaunaClientPool(endpoints: Seq[String], secret: String)
  extends ConnectionPool {

  private val refCountByClient: Map[FaunaClient, AtomicInteger] = {
    val clients =
      if (endpoints.isEmpty) Seq(newClient(None))
      else endpoints map (url => newClient(Some(url)))

    clients.map(_ -> new AtomicInteger(0)).toMap
  }

  val size: Int = refCountByClient.size

  private val clientsByIndex = refCountByClient.toIndexedSeq
  @volatile private var searchIndex = -1

  def pickClient: FaunaClient = {
    var pickedClient: FaunaClient = null
    var pickedRefCount: AtomicInteger = null
    var minRefCountFound = Int.MaxValue

    // Start from a different position everytime so we don't
    // prefer the first few clients in the pool
    searchIndex = cycleInc(searchIndex)
    var index = searchIndex
    var walked = 0

    while (walked < size) {
      val (client, refCount) = clientsByIndex(index)
      val currentCount = refCount.get()

      // Prefere the client with less references to it
      if (currentCount < minRefCountFound) {
        minRefCountFound = currentCount
        pickedRefCount = refCount
        pickedClient = client
      }

      index = cycleInc(index)
      walked += 1
    }

    pickedRefCount.incrementAndGet()
    pickedClient
  }

  def release(client: FaunaClient): Unit = {
    refCountByClient.get(client) foreach (_.decrementAndGet())
  }

  def close(): Unit = {
    refCountByClient.keys foreach (_.close())
  }

  private def cycleInc(n: Int): Int = {
    if (n + 1 >= size) 0 else n + 1
  }

  private def newClient(endpoint: Option[String]): FaunaClient = {
    FaunaClient(
      secret = secret,
      endpoint = endpoint.orNull,
      httpClient = new HttpWrapper()
    )
  }
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
    headers.getHeaders.get("X-Query-Time") foreach { time =>
      Stats.ServerLatency.update(time.toLong, MILLISECONDS)
    }

    delegate.onHeadersReceived(headers)
  }

  def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = delegate.onBodyPartReceived(bodyPart)
  def onStatusReceived(status: HttpResponseStatus): STATE = delegate.onStatusReceived(status)
  def onThrowable(t: Throwable): Unit = delegate.onThrowable(t)
  def onCompleted(): T = delegate.onCompleted()
}
