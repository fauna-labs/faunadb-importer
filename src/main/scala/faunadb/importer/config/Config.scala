package faunadb.importer.config

import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.report._
import scala.concurrent.duration._

case class Config(
  // Fauna secret used to authenticate requests
  secret: String,

  // Fauna endpoints to send queries to.
  // If no endpoint, defaults to could.
  endpoints: Seq[String] = Seq.empty,

  // Number of queries to be executed at a single batch
  batchSize: Int = 50,

  // Number of parallel streams used to send request to the server
  concurrentStreams: Int = Runtime.getRuntime.availableProcessors() * 2,

  // Maximum number of network errors tolerated
  maxNetworkErrors: Int = 50,

  // The timeframe to reset the network errors counter
  networkErrorsResetTime: FiniteDuration = 2.minutes,

  // The time delay applied when backing off network requests
  networkErrorsBackoffTime: FiniteDuration = 1.seconds,

  // The maximum time delay applied when exponentially backing off network requests
  maxNetworkErrorsBackoffTime: FiniteDuration = 1.minutes,

  // The factor used when exponentially backing of network requests
  networkErrorsBackoffFactor: Int = 2,

  // Error handling strategy to be used
  errorStrategy: ErrorStrategy = ErrorStrategy.StopOnError,

  // Progress report strategy to be used
  reportType: ReportType = ReportType.Inline
)

final class ConfigBuilder {
  import ConfigBuilder._

  private val steps = IndexedSeq.newBuilder[BuildStep]

  def +=(step: BuildStep): ConfigBuilder = {
    steps += step
    this
  }

  def result(): Result[Config] = {
    steps.result().foldLeft(Config(""))((c, fn) => fn(c)) match {
      case c if c.secret.isEmpty => Err("No key's secret specified")
      case c                     => Ok(c)
    }
  }
}

object ConfigBuilder {
  type BuildStep = (Config => Config)

  def apply(): ConfigBuilder =
    new ConfigBuilder()

  final object Dsl {
    def Secret(value: String): BuildStep = _.copy(secret = value)
    def Endpoints(value: Seq[String]): BuildStep = _.copy(endpoints = value)
    def BatchSize(value: Int): BuildStep = _.copy(batchSize = value)
    def ConcurrentStreams(value: Int): BuildStep = _.copy(concurrentStreams = value)
    def OnError(value: ErrorStrategy): BuildStep = _.copy(errorStrategy = value)
    def Report(value: ReportType): BuildStep = _.copy(reportType = value)
    def MaxNetworkErrors(value: Int): BuildStep = _.copy(maxNetworkErrors = value)
    def NetworkErrorsResetTime(value: FiniteDuration): BuildStep = _.copy(networkErrorsResetTime = value)
    def NetworkErrorsBackoffTime(value: FiniteDuration): BuildStep = _.copy(networkErrorsBackoffTime = value)
    def MaxNetworkErrorsBackoffTime(value: FiniteDuration): BuildStep = _.copy(maxNetworkErrorsBackoffTime = value)
    def NetworkErrorsBackoffFactor(value: Int): BuildStep = _.copy(networkErrorsBackoffFactor = value)
  }
}
