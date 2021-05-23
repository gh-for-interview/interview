package forex.services.rates.caches

import forex.domain.Rate

trait Cache[F[_]] {
  def get(pair: Rate.Pair): F[Option[Rate]]
  def put(rates: List[Rate]): F[Unit]
}
