package faunadb.importer.persistence

import com.ning.http.client.AsyncHandler._
import com.ning.http.client._
import faunadb._
import faunadb.importer.report._
import java.util.concurrent.TimeUnit._
import java.util.concurrent.atomic._
import scala.annotation.tailrec
import scala.collection.JavaConversions._

trait ConnectionPool {
  def borrowClient(): FaunaClient
  def returnClient(client: FaunaClient): Unit
  def maxConcurrentReferences: Int
  def close(): Unit
}

object ConnectionPool {
  def apply(endpoints: Seq[String], secret: String, maxRefCountPerEndpoint: Int): ConnectionPool =
    new FaunaClientPool(endpoints, secret, maxRefCountPerEndpoint)
}

private final class FaunaClientPool(endpoints: Seq[String], secret: String, maxRefCountPerEndpoint: Int)
  extends ConnectionPool {

  private val refCountByClient: Map[FaunaClient, AtomicInteger] = {
    val clients =
      if (endpoints.isEmpty) Seq(newClient(None))
      else endpoints map (url => newClient(Some(url)))

    clients.map(_ -> new AtomicInteger(0)).toMap
  }

  private def newClient(endpoint: Option[String]): FaunaClient = {
    FaunaClient(
      secret = secret,
      endpoint = endpoint.orNull,
      httpClient = new HttpWrapper()
    )
  }

  private val clientsByIndex = refCountByClient.toIndexedSeq
  private val poolSize = refCountByClient.size

  val maxConcurrentReferences: Int = maxRefCountPerEndpoint * poolSize

  @volatile
  private var searchIndex = -1

  @tailrec
  def borrowClient(): FaunaClient = {
    var pickedClient: FaunaClient = null
    var pickedRefCount: AtomicInteger = null
    var minRefCountFound = Int.MaxValue

    // Start from a different position everytime so we don't
    // prefer the first few clients in the pool
    searchIndex = Math.max((searchIndex + 1) % poolSize, 0)
    val startIndex = searchIndex
    var walked = 0

    while (walked < poolSize) {
      val (client, refCount) = clientsByIndex(Math.max((startIndex + walked) % poolSize, 0))
      val currentCount = refCount.get()

      // Prefer the client with less references to it
      if (currentCount < minRefCountFound && currentCount < maxRefCountPerEndpoint) {
        minRefCountFound = currentCount
        pickedRefCount = refCount
        pickedClient = client
      }

      walked += 1
    }

    if (pickedClient == null) {
      throw new IllegalStateException(
        "Maximum number of concurrent references was reached." +
          "Callers to bottowClient must respect the value of maxConcurrentReferences"
      )
    }

    if (!pickedRefCount.compareAndSet(minRefCountFound, minRefCountFound + 1)) {
      // Another thread got the client first. Retry!
      borrowClient()
    } else {
      pickedClient
    }
  }

  def returnClient(client: FaunaClient): Unit = {
    refCountByClient.get(client) foreach (_.decrementAndGet())
  }

  def close(): Unit = {
    refCountByClient.keys foreach (_.close())
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
