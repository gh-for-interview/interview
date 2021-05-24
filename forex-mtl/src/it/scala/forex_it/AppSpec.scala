package forex_it

import java.time.OffsetDateTime
import java.util.concurrent.Executors

import cats.effect.{Blocker, ContextShift, IO}
import com.dimafeng.testcontainers.{Container, ForAllTestContainer, GenericContainer, MultipleContainers}
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

import scala.concurrent.Future
import forex.Main
import forex_it.config.ConfigStub
import org.http4s.circe.jsonOf
import org.http4s.{EntityDecoder, Request, Response, Status}
import org.http4s.client.{Client, JavaNetClientBuilder}
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.global

object MainUnderTest extends Main with ConfigStub

class AppSpec extends Suite
  with BeforeAndAfterAll
  with AnyFlatSpecLike
  with Matchers
  with ForAllTestContainer {

  private val redisPort = 6379
  private val oneFramePort = 8080

  private val redisContainer: GenericContainer =
    GenericContainer(
      dockerImage = s"redis:5.0.4-alpine",
      exposedPorts = Seq(redisPort),
      waitStrategy = Wait.forListeningPort(),
      classpathResourceMapping = Seq(
        ("/redis/custom-launcher.sh", "/custom-launcher.sh", BindMode.READ_ONLY),
        ("/redis/redis.conf", "/test-redis.conf", BindMode.READ_ONLY)
      ),
      command = Seq("ash", "/custom-launcher.sh"),
      labels = Map("code" -> "redis")
    )

  private val oneFrameContainer: GenericContainer =
    GenericContainer(
      dockerImage = s"paidyinc/one-frame",
      exposedPorts = Seq(oneFramePort),
      waitStrategy = Wait.forListeningPort()
    )

  override val container: Container = MultipleContainers(
    oneFrameContainer, redisContainer
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    Future {
      MainUnderTest.injectConfig(
        s"""
          |app {
          |  one-frame-uri = "http://${oneFrameContainer.containerIpAddress}:${oneFrameContainer.mappedPort(oneFramePort)}"
          |  redis-uri = "redis://${redisContainer.containerIpAddress}:${redisContainer.mappedPort(redisPort)}"
          |  cache-ttl = 1 second
          |}
          |""".stripMargin)
      MainUnderTest.main(Array.empty)
    }(global)
    // Wait so app can start properly before running tests
    Thread.sleep(3000)
  }

  private val blockingPool = Executors.newFixedThreadPool(5)
  private val blocker = Blocker.liftExecutorService(blockingPool)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  private val httpClient: Client[IO] = JavaNetClientBuilder[IO](blocker).create

  import io.circe.generic.auto._

  implicit val okDecoder: EntityDecoder[IO, ApiOkResponse] = jsonOf[IO, ApiOkResponse]
  implicit val errorDecoder: EntityDecoder[IO, ApiErrorResponse] = jsonOf[IO, ApiErrorResponse]

  private def assertServiceIsWorking() = {
    val result = httpClient
      .run(Request(uri = uri"http://localhost:8080/rates?from=USD&to=JPY"))
      .use {
        case r@Response(Status.Ok, _, _, _, _) => r.as[ApiOkResponse]
        case r@_ => fail(s"Unexpected response from service, was ${r.as[String].unsafeRunSync()}")
      }

    val response = result.unsafeRunSync()
    response.from shouldBe "USD"
    response.to shouldBe "JPY"
  }

  "Forex app" should "work" in {
    assertServiceIsWorking()
  }

  it should "work if cache is not responding" in {
    val id = redisContainer.containerId
    val dockerClient = redisContainer.dockerClient
    dockerClient.pauseContainerCmd(id).exec()
    Thread.sleep(3000)

    assertServiceIsWorking()

    dockerClient.unpauseContainerCmd(id).exec()
    Thread.sleep(3000)
  }

  it should "return error for incorrect currency" in {
    val result = httpClient
      .run(Request(uri = uri"http://localhost:8080/rates?from=USD&to=XYZ"))
      .use {
        case r@Response(Status.BadRequest, _, _, _, _) => r.as[ApiErrorResponse]
        case r@_ => fail(s"Unexpected response from service, was ${r.as[String].unsafeRunSync()}")
      }

    result.unsafeRunSync() shouldBe ApiErrorResponse("Query params parse failed: passed currency is unsupported")
  }

  it should "return error for pair of same currencies" in {
    val result = httpClient
      .run(Request(uri = uri"http://localhost:8080/rates?from=USD&to=USD"))
      .use {
        case r@Response(Status.BadRequest, _, _, _, _) => r.as[ApiErrorResponse]
        case r@_ => fail(s"Unexpected response from service, was ${r.as[String].unsafeRunSync()}")
      }

    result.unsafeRunSync() shouldBe ApiErrorResponse("Invalid request, both currencies are the same")
  }

  it should "return error if 3rd party service is not responding" in {
    val id = oneFrameContainer.containerId
    val dockerClient = oneFrameContainer.dockerClient
    dockerClient.pauseContainerCmd(id).exec()
    Thread.sleep(3000)
    val result = httpClient
      .run(Request(uri = uri"http://localhost:8080/rates?from=USD&to=JPY"))
      .use {
        case r@Response(Status.InternalServerError, _, _, _, _) => r.as[ApiErrorResponse]
        case r@_ => fail(s"Unexpected response from service, was ${r.as[String].unsafeRunSync()}")
      }

    result.unsafeRunSync() shouldBe ApiErrorResponse("3rd party service is not responding")
    dockerClient.unpauseContainerCmd(id)
  }

}

case class ApiOkResponse(from: String, to: String, price: Double, timestamp: OffsetDateTime)
case class ApiErrorResponse(error: String)
