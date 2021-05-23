package forex

import scala.concurrent.ExecutionContext

import cats.effect._
import forex.config._
import fs2.Stream
import org.http4s.server.blaze.BlazeServerBuilder

trait Main extends IOApp {

  self: ConfigProvider =>

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO](provide[IO]("app")).stream(executionContext).compile.drain.as(ExitCode.Success)

}

class Application[F[_]: ConcurrentEffect: ContextShift: Timer](configProvider: Stream[F, ApplicationConfig]) {

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- configProvider
      module = new Module[F](config)
      _ <- BlazeServerBuilder[F](ec)
            .bindHttp(config.http.port, config.http.host)
            .withHttpApp(module.httpApp)
            .serve
    } yield ()

}

object MainImpl extends Main with ConfigImpl
