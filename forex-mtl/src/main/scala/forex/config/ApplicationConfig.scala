package forex.config

import scala.concurrent.duration.FiniteDuration

case class ApplicationConfig(
    http: HttpConfig,
    redisUri: String,
    oneFrameUri: String,
    token: String,
    cacheTtl: FiniteDuration,
    internalTimeout: FiniteDuration,
    cache: String
)

case class HttpConfig(
    host: String,
    port: Int,
    timeout: FiniteDuration
)
