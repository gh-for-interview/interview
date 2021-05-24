package forex.services.rates

import cats.Applicative
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import forex.services.rates.caches.{Cache, InMemoryCache, RedisCache}
import interpreters._
import org.http4s.client.Client

import scala.concurrent.duration.FiniteDuration

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def redisCache[F[_]: ConcurrentEffect: ContextShift: Timer](uri: String, ttl: FiniteDuration, timeout: FiniteDuration) =
    new RedisCache[F](uri, ttl, timeout)

  def inMemoryCache[F[_]: Applicative](ttl: FiniteDuration) = new InMemoryCache[F](ttl)

  def live[F[_]: ConcurrentEffect : ContextShift: Timer]
  (uri: String, cache: Cache[F], token: String, client: Client[F]): Algebra[F] =
    new OneFrameLive[F](uri, cache, token, client)
}
