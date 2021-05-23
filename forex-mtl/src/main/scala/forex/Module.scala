package forex

import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.services._
import forex.programs._
import forex.services.rates.caches.Cache
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.middleware.{AutoSlash, Timeout}

class Module[F[_]: ConcurrentEffect: ContextShift: Timer](config: ApplicationConfig) {

  private val ratesCacheService: Cache[F] = config.cache match {
    case "redis" => RatesServices.redisCache[F](config.redisUri, config.cacheTtl, config.internalTimeout)
    case "in-memory" => RatesServices.inMemoryCache[F](config.cacheTtl)
    case c => throw new IllegalArgumentException(s"No cache implementation found for argument $c")
  }

  private val ratesService: RatesService[F] =
    RatesServices.live[F](config.oneFrameUri, ratesCacheService, config.token, config.internalTimeout)

  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

}
