package faunadb.importer.lang

import java.util.concurrent.TimeUnit._

object TimeFormat {
  def prettyDuration(start: Long, end: Long): String = {
    val interval = end - start
    val hr = MILLISECONDS.toHours(interval)
    val min = MILLISECONDS.toMinutes(interval - HOURS.toMillis(hr))
    val sec = MILLISECONDS.toSeconds(interval - HOURS.toMillis(hr) - MINUTES.toMillis(min))
    val ms = MILLISECONDS.toMillis(interval - HOURS.toMillis(hr) - MINUTES.toMillis(min) - SECONDS.toMillis(sec))

    f"$hr%02d:$min%02d:$sec%02d.$ms%d"
  }
}
