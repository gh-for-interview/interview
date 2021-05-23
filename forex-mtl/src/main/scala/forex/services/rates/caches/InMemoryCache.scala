package forex.services.rates.caches

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

import cats.Applicative
import cats.implicits.catsSyntaxApplicativeId
import forex.domain.Rate

import scala.concurrent.duration.FiniteDuration

class InMemoryCache[F[_]: Applicative](ttl: FiniteDuration) extends Cache[F] {

  private var cache: Option[(OffsetDateTime, Map[Rate.Pair, Rate])] = Option.empty

  def get(pair: Rate.Pair): F[Option[Rate]] = {
    cache.flatMap { case (time, cached) =>
      if (time.until(OffsetDateTime.now(), ChronoUnit.MILLIS) > ttl.toMillis) {
        Option.empty[Rate]
      } else {
        cached.get(pair)
      }
    }.pure[F]
  }

  def put(rates: List[Rate]): F[Unit] = {
    cache = Some((OffsetDateTime.now(), rates.map(rate => rate.pair -> rate).toMap))
    ()
  }.pure[F]
}
