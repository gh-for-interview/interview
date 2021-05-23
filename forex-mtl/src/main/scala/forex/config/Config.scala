package forex.config

import cats.effect.Sync
import fs2.Stream

import pureconfig.ConfigSource
import pureconfig.generic.auto._

trait ConfigProvider {
  def provide[F[_]: Sync](path: String): Stream[F, ApplicationConfig]
}

trait ConfigImpl extends ConfigProvider {

  /**
   * @param path the property path inside the default configuration
   */
  def provide[F[_]: Sync](path: String): Stream[F, ApplicationConfig] = {
    Stream.eval(Sync[F].delay(
      ConfigSource.default.at(path).loadOrThrow[ApplicationConfig]))
  }

}
