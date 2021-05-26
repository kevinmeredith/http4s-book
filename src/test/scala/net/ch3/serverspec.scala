package net.ch3

import cats.effect.IO
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import munit.CatsEffectSuite
import net.ch3.models.{Secret, UserId}
import net.ch3.server.Messages
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe.{jsonDecoder, jsonEncoder}
import org.http4s.implicits._
import org.http4s.dsl.Http4sDsl

final class serverspec extends CatsEffectSuite {

  private def mockMessagesImpl(
    stubGetResult: IO[List[Messages.Message]],
    stubCreateResult: IO[Unit]
  ) = new Messages[IO] {
    override def get(userId: UserId): IO[List[Messages.Message]] = stubGetResult
    override def create(userId: UserId, message: Messages.Message): IO[Unit] = stubCreateResult
  }

  private val notUsed: IO[Nothing] = IO.raiseError(new RuntimeException("not used - should not be called!"))

  private val TestUri: Uri = uri"http://www.only-used-for-testing.com"

  private implicit val http4sDsl: Http4sDsl[IO] = Http4sDsl[IO]

  test("GET /{userId}/messages returns a list of messages") {
    val timestamp: Instant = Instant.EPOCH

    val content: String = "how memorable"

    val messages: List[Messages.Message] =
      List(Messages.Message(content, timestamp))

    val messagesImpl: Messages[IO] =
      mockMessagesImpl(IO.pure(messages), notUsed)

    val trustedAuthToken: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, trustedAuthToken)

    val result: IO[(Status, Json)] = for {
      uuid <- IO.delay(UUID.randomUUID())
      request =  Request[IO](
        method = Method.GET,
        uri = TestUri / uuid.toString / "messages"
      )
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
      json <- resp.as[Json]
    } yield (resp.status, json)

    result.map {
      case (status, json) =>
        assertEquals(status, Status.Ok)
        assertEquals(
          json,
          Json.arr(
            Json.obj("content" := content, "timestamp" := "1970-01-01T00:00:00")
          )
        )
    }
  }

  test("PUT /{userId}/messages returns a 401 for a request having no x-secret Header") {
    val messagesImpl: Messages[IO] =
      mockMessagesImpl(notUsed, notUsed)

    val trustedAuthToken: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, trustedAuthToken)

    val result: IO[Status] = for {
      uuid <- IO.delay(UUID.randomUUID())
      request =  Request[IO](
        method = Method.POST,
        uri = TestUri / uuid.toString / "messages"
      ).withEntity[Json](
        Json.obj("content" := "a witty remark")
      )
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    result.map { s: Status =>
        assertEquals(s, Status.Unauthorized)
    }
  }

  test("PUT /{userId}/messages returns a 401 for a request including a x-secret header with the wrong value") {
    val messagesImpl: Messages[IO] =
      mockMessagesImpl(notUsed, notUsed)

    val secret: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, secret)

    val result: IO[Status] = for {
      uuid <- IO.delay(UUID.randomUUID())
      request =  Request[IO](
        method = Method.POST,
        uri = TestUri / uuid.toString / "messages"
      ).withHeaders(Headers.of(Header("x-secret", "not-the-secret")))
       .withEntity[Json](
        Json.obj("content" := "a witty remark")
      )
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    result.map { s: Status =>
      assertEquals(s, Status.Unauthorized)
    }
  }

  test("PUT /{userId}/messages returns a 200") {
    val messagesImpl: Messages[IO] =
      mockMessagesImpl(notUsed, IO.unit)

    val secret: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, secret)

    val result: IO[Status] = for {
      uuid <- IO.delay(UUID.randomUUID())
      request =  Request[IO](
        method = Method.POST,
        uri = TestUri / uuid.toString / "messages"
      ).withHeaders(Headers.of(Header("x-secret", secret.value)))
        .withEntity[Json](
          Json.obj("content" := "a witty remark")
        )
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    result.map { s: Status =>
      assertEquals(s, Status.Ok)
    }
  }

}
