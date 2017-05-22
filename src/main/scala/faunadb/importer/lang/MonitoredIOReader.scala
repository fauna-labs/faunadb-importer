package faunadb.importer.lang

import java.io.Reader

object MonitoredIOReader {
  type Callback = (Long) => Unit

  def apply(delegate: Reader)(fn: Callback): MonitoredIOReader =
    new MonitoredIOReader(fn, delegate)
}

final class MonitoredIOReader private(callback: MonitoredIOReader.Callback, delegate: Reader) extends Reader {
  def read(cbuf: Array[Char], off: Int, len: Int): Int = {
    val bytesRead = delegate.read(cbuf, off, len)
    if (bytesRead != -1) callback(bytesRead)
    bytesRead
  }

  def close(): Unit = delegate.close()
}
