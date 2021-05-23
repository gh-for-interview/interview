package forex.services.rates.interpreters

import java.time.OffsetDateTime

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Timer}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxEitherId}
import cats.syntax.all._
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.services.rates.Algebra
import forex.services.rates.caches.Cache
import forex.services.rates.errors.Error
import org.http4s.{EntityDecoder, Header, Headers, Request, Response, Status, Uri}
import org.http4s.circe.jsonOf
import forex.services.rates.errors.Error.OneFrameLookupFailed
import org.http4s.client.Client


class OneFrameLive[F[_]: ConcurrentEffect : Timer: Concurrent: ContextShift]
(oneFrameUri: String, cache: Cache[F], token: String, client: Client[F]) extends Algebra[F] {

  import io.circe.generic.auto._

  implicit val entityDecoder: EntityDecoder[F, List[OneFrameApiResponse]] = jsonOf[F, List[OneFrameApiResponse]]

  private val allPairsWithoutSameElementPair = Currency.all.flatMap { first =>
    Currency.all.flatMap { second =>
      if (first == second) List.empty else List(first -> second)
    }
  }

  private val rawUri = allPairsWithoutSameElementPair.foldLeft(oneFrameUri + "/rates?") { case (uri, (from, to)) =>
    if (uri.endsWith("?")) {
      s"${uri}pair=${from.show}${to.show}"
    } else {
      s"$uri&pair=${from.show}${to.show}"
    }
  }

  // Just fail here because without this URI app is meaningless
  private val uri = Uri.fromString(rawUri) match {
    case Left(e) => throw e
    case Right(value) => value
  }

  // Look up value in cache, if it is not found, request rates from 3rd party service, return needed pair and update cache
  def get(pair: Rate.Pair): F[Error Either Rate] = {
    cache
      .get(pair)
      .flatMap(_.toRight[Error](OneFrameLookupFailed("Cant find rate in cache")).pure[F])
      .flatMap { eitherErrorOrPair =>
        if (eitherErrorOrPair.isLeft) {
            val result = client.run(Request(uri = uri, headers = Headers.of(Header("token", token))))
              .use {
                case r@Response(Status.Ok, _, _, _, _) => r.as[List[OneFrameApiResponse]].map(_.asRight[Error])
                case r@_ => (r.as[String].map(_ => OneFrameLookupFailed("3rd party service responded with error").asLeft): F[Either[Error, List[OneFrameApiResponse]]])
              }
              .attempt
              .flatMap {
                case Left(_) => (OneFrameLookupFailed("3rd party service is not responding").asLeft: Either[Error, List[OneFrameApiResponse]]).pure[F]
                case Right(value) => value.pure[F]
              }
            for {
              rates <- result.flatMap { either =>
                either.flatMap { responseList =>
                  responseList.map { response =>
                    Rate(
                      Rate.Pair(Currency.fromString(response.from), Currency.fromString(response.to)),
                      Price(response.price),
                      Timestamp(response.time_stamp)
                    )
                  }.asRight[Error]
                }.pure[F]
              }
              _ <- rates.map(cache.put) match {
                case Right(value) => value
                case _ => ().pure[F]
              }
              rate <- rates.flatMap { rates =>
                rates.find(_.pair == pair) match {
                  case Some(value) => value.asRight[Error]
                  case None => OneFrameLookupFailed(s"Pair not found in response of 3rd party service").asLeft
                }
              }.pure[F]
            } yield rate
      } else {
        eitherErrorOrPair.pure[F]
      }
    }
  }

}

case class OneFrameApiResponse(
                                from: String,
                                to: String,
                                price: Double,
                                time_stamp: OffsetDateTime,
                                bid: Double,
                                ask: Double
                              )
