package net.ch3

import cats.effect.IO
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import munit.CatsEffectSuite
import net.ch3.models.Secret
import net.ch3.server.Messages
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe.{jsonDecoder, jsonEncoder}
import org.http4s.implicits._
import org.http4s.dsl.Http4sDsl

// The purpose of this spec is to write a unit test for the
// net.ch3.server.routes HTTP Service.
// In short, each spec will consist of making an HttpRoutes by
// calling net.ch3.server.routes, passing an HTTP request,
// and then verifying the expected HTTP Response.
final class serverspec extends CatsEffectSuite {

  // Define a helper method for producing a Messages[IO]
  // implementation. Note that the parameters are used for
  // the return types of the interface.
  private def stubMessagesImpl(
    stubGetResult: IO[List[Messages.Message]],
    stubCreateResult: IO[Unit]
  ): Messages[IO] = new Messages[IO] {
    override def get: IO[List[Messages.Message]] = stubGetResult
    override def create(message: Messages.Message): IO[Unit] = stubCreateResult
  }

  // Since IO[A] is covariant, any function that requires an IO[A] will accept
  // an IO[Nothing] since Nothing is at the bottom of Scala's class hierarchy.
  // See https://github.com/typelevel/cats-effect/blob/v2.5.1/core/shared/src/main/scala/cats/effect/IO.scala#L85
  // In short, it's convenient to use
  // val notUsed: IO[Nothing]
  //    rather than
  // def notUsed[A] : IO[A]
  // as the former is more concise.
  private val notUsed: IO[Nothing] = IO.raiseError(new RuntimeException("not used - should not be called!"))

  // Although it won't be used, it's necessary to create a Uri for constructing an HTTP Request
  private val TestUri: Uri = uri"http://www.only-used-for-testing.com"

  // Define a 'Http4sDsl[IO]' as net.ch3.server.routes requires it
  private implicit val http4sDsl: Http4sDsl[IO] = Http4sDsl[IO]

  // net.ch3.server.routes takes a 'log: String => F[Unit]' parameter.
  private def noOpLog(str: String): IO[Unit] = IO.unit

  // The first test verifies that GET /messages succeeds
  test("GET /messages returns a list of messages") {
    val timestamp: Instant = Instant.EPOCH

    val content: String = "how memorable"

    val messages: List[Messages.Message] =
      List(Messages.Message(content, timestamp))

    // Creates a Messages[IO] that returns 'IO.pure(messages)'
    // on the 'get', while raising an error for the 'create' method.
    // Note that GET /messages only accesses Messages[IO]#get, hence the
    // reason for passing 'notUsed'.
    val messagesImpl: Messages[IO] =
      stubMessagesImpl(IO.pure(messages), notUsed)

    val trustedAuthToken: Secret = Secret("not used since GET does not require the x-secret header")

    // Build the HttpRoutes[IO] by calling server.routes.
    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, noOpLog, trustedAuthToken)

    // Construct the HTTP Request for calling GET /messages
    val request: Request[IO] = Request[IO](
      method = Method.GET,
      uri = TestUri / "messages"
    )

    val result: IO[(Status, Json)] = for {
      // Apply the HttpRequest to routes#run, which will return an OptionT[IO, Response[F]].
      // Again, recall HttpRoutes[IO] is simply a type alias for
      // Kleisli[OptionT[IO, *], Request[IO], Response[IO]]
      // By applying the Request[IO], our result is OptionT[IO, Response[IO]].
      // To get at the IO[Response[IO]], I call OptionT#getOrElseF, which will raise the given
      // error if the value of the OptionT type is empty.
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
      // Decode the HTTP Response to JSON. In this case, the payload will attempted to be decoded as JSON
      json <- resp.as[Json]
    } yield (resp.status, json)

    // Verify that the HTTP Response's status = 200
    // and its payload matches [{"content" : ..., "timestamp" : ...}]
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

  test("POST /messages returns a 401 for a request having no x-secret Header") {
    // Observe that both IO arguments are notUsed since Messages#create should
    // never be called since the request lacks the x-secret header.
    val messagesImpl: Messages[IO] =
      stubMessagesImpl(notUsed, notUsed)

    val trustedAuthToken: Secret = Secret("not used since the request does not include an x-secret header")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, noOpLog, trustedAuthToken)

    // Build a POST with a JSON payload
    val request: Request[IO] = Request[IO](
      method = Method.POST,
      uri = TestUri / "messages"
    ).withEntity[Json](
      Json.obj("content" := "a witty remark")
    )

    val result: IO[Status] = for {
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    // Verify the HTTP Response's status = 401
    result.map { s: Status =>
        assertEquals(s, Status.Unauthorized)
    }
  }

  test("POST /messages returns a 401 for a request including a x-secret header with the wrong value") {
    // Observe that both IO arguments are notUsed since Messages#create should
    // never be called since the request's x-secret header does not equal the server's secret.
    val messagesImpl: Messages[IO] =
      stubMessagesImpl(notUsed, notUsed)

    val secret: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, noOpLog, secret)

    val request: Request[IO] = Request[IO](
      method = Method.POST,
      uri = TestUri / "messages"
    ).withHeaders(Headers.of(Header("x-secret", "not-the-secret")))
      .withEntity[Json](
        Json.obj("content" := "a witty remark")
      )

    val result: IO[Status] = for {
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    result.map { s: Status =>
      assertEquals(s, Status.Unauthorized)
    }
  }

  test("POST /messages returns a 200") {
    // Observe that the stubCreateResult argument is IO.unit, i.e. indicating
    // the successful creation of a message.
    val messagesImpl: Messages[IO] =
      stubMessagesImpl(notUsed, IO.unit)

    val secret: Secret = Secret("secret")

    val routes: HttpRoutes[IO] = server.routes[IO](messagesImpl, noOpLog, secret)

    val request: Request[IO] = Request[IO](
      method = Method.POST,
      uri = TestUri / "messages"
    ).withHeaders(Headers.of(Header("x-secret", secret.value)))
      .withEntity[Json](
        Json.obj("content" := "a witty remark")
      )

    val result: IO[Status] = for {
      resp <- routes.run(request).getOrElseF(IO.raiseError(new RuntimeException("test failed!")))
    } yield resp.status

    result.map { s: Status =>
      assertEquals(s, Status.Ok)
    }
  }

}
