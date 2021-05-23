package forex_it.config

import cats.effect.Sync
import forex.config.{ApplicationConfig, ConfigProvider}
import fs2.Stream
import pureconfig.ConfigSource
import pureconfig.generic.auto._

trait ConfigStub extends ConfigProvider {

  private var injection = ConfigSource.empty

  def injectConfig(rawConfig: String): Unit = {
    injection = injection.withFallback(ConfigSource.string(rawConfig))
  }

  /**
   * @param path the property path inside the default configuration
   */
  def provide[F[_]: Sync](path: String): Stream[F, ApplicationConfig] = {
    Stream.eval(Sync[F].delay(
      injection.withFallback(ConfigSource.default).at(path).loadOrThrow[ApplicationConfig]
    ))
  }
}
