package forex.services.rates.caches

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import forex.domain.Rate
import cats.syntax.all._
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import io.circe.generic.auto._
import io.circe.parser.{decode => jsonDecode}
import io.circe.syntax._

import scala.concurrent.duration.FiniteDuration


class RedisCache[F[_]: ContextShift: ConcurrentEffect: Timer]
(uriRaw: String, ttl: FiniteDuration, timeout: FiniteDuration) extends Cache[F] {

  private val hashKey = "exchange_rates"

  private def rateToHashKey(pair: Rate.Pair) = s"${pair.from.show}${pair.to.show}"

  private val rateSplitEpi: SplitEpi[String, Rate] =
    SplitEpi[String, Rate](
      str => jsonDecode[Rate](str).getOrElse(throw new IllegalArgumentException(s"Cant parse as json string $str")),
      _.asJson.noSpaces
    )

  private val rateCodec: RedisCodec[String, Rate] = Codecs.derive(RedisCodec.Utf8, rateSplitEpi)

  private def withTimeout[A](f: F[A], fallback: F[A]): F[A] = {
    Concurrent[F].racePair(f, Timer[F].sleep(timeout)).flatMap {
      case Left((a, fiberB)) =>
        fiberB.cancel.map(_ => a)
      case Right((fiberA, _)) =>
        Concurrent[F].background(fiberA.cancel)
        fallback
    }
  }

  private val commandsApi: Resource[F, RedisCommands[F, String, Rate]] =
    for {
      client <- RedisClient[F].from(uriRaw)
      redis  <- Redis[F].fromClient(client, rateCodec)
    } yield redis

  def get(pair: Rate.Pair): F[Option[Rate]] = {
    val result = commandsApi.use { cmd =>
      cmd.hGet(hashKey, rateToHashKey(pair))
    }
    withTimeout(result, Option.empty[Rate].pure[F])
  }

  def put(rates: List[Rate]): F[Unit] = {
    val result = commandsApi.use { cmd =>
      for {
        _ <- cmd.hmSet(hashKey, rates.map(rate => rateToHashKey(rate.pair) -> rate).toMap)
        _ <- cmd.expire(hashKey, ttl)
      } yield ()
    }
    withTimeout(result, ().pure[F])
  }

}
