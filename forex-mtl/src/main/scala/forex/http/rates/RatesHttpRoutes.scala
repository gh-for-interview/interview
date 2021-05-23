package forex.http
package rates

import cats.data.Validated.Valid
import cats.effect.Sync
import cats.implicits.toTraverseOps
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.errors.Error
import forex.programs.rates.{Protocol => RatesProgramProtocol}
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      List(from, to).sequence match {
        case Valid(List(from, to)) =>
          (from, to) match {
            case (f, t) if f == t => BadRequest(GetApiError("Invalid request, both currencies are the same"))
            case _ => rates
              .get(RatesProgramProtocol.GetRatesRequest(from, to))
              .flatMap {
                case Left(err) => err match {
                  case Error.RateLookupFailed(msg) => InternalServerError(GetApiError(msg))
                  case e => InternalServerError(GetApiError(e.getLocalizedMessage))
                }
                case Right(value) => Ok(value.asGetApiResponse)
              }
          }

        case _ => BadRequest(GetApiError(s"Query params parse failed: passed currency is unsupported"))
      }
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
