# Chapter 3. Building a Web Server

This chapter covers the following topics:

  - Recap of `org.http4s.HttpRoutes[F]`
  - Introduction to `HttpApp[F]`
  - Building and Testing an HTTP Server Example
  - Why Throwable instead of EitherT?
  - Recommendations with Middleware Approach

## Recap of `org.http4s.HttpRoutes[F]`

Recall that Chapter 1 introduced `org.http4s.HttpRoutes[F]`. It's a type alias that consists of:

```scala
type HttpRoutes[F[_]] = Http[OptionT[F, *], F]
type Http[F[_], G[_]] = Kleisli[F, Request[G], Response[G]]
```

`Kleisli[F, Request[G], Response[G]]` effectively is `Request[G] => F[Response[G]]`. Recall that, in our case, the `F[_]`
 is the same for both the `F[_]` and `G[_]` parameters.

It's trivial to compose multiple routes together via <+>.

Note that, so far, we haven't yet built a web server, but rather only routes. Let's look at `org.http4s.HttpApp`.

```scala
type HttpApp[F[_]] = Http[F, F]
type Http[F[_], G[_]] = Kleisli[F, Request[G], Response[G]]
```

Again, since our effect type is `F[_]` for each parameter, we end up with:

`Kleisli[F, Request[F], Response[F]]`, which effecitvely reduces to a function: `Request[F] => F[Response[F]]`.

## Building and Testing an HTTP Server Example

Let's look into an example of building a server with two routes:

- GET /{userId}/messages  - return all messages for a given User.
    - No authorization requirements
    - Responses:
        - HTTP-200 Retrieved the messages
        - HTTP-500 Something went wrong on server
- POST /{userId}/messages - create a message for a given User
    - Must include Authorization header whose value identifies the User
    - Responses:
        - HTTP-200 Created the message
        - HTTP-401 Unauthorized (missing or invalid x-secret Header)
        - HTTP-403 Forbidden - x-secret header's value was wrong
        - HTTP-500 Something went wrong on server

Consider the following commented code for building the server.

```scala
package net.ch3

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder
import org.http4s.dsl.Http4sDsl

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import net.ch3.models.Secret
import org.http4s.util.CaseInsensitiveString

object server {

  /*
  * GET /messages  - return all messages
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved all of the message -
           { "content" : String, "timestamp" : String ISO861 }
        * HTTP-500 Something went wrong on server
  * POST /messages - create a message
    * Must include x-secret header whose value equals the server's secret
    * Responses:
        * HTTP-200 Created the message
        * HTTP-401 Unauthorized (missing or invalid x-secret Header)
        * HTTP-403 Forbidden - Supplied secret does not match the expected value
        * HTTP-500 Something went wrong on server
   */

  object Messages {

    // Define a formatter for printing the Instant.
    // Note that it's private as there's no need to expose it.
    // Further, it's only used for building the Encoder[Messages.Message].
    // https://stackoverflow.com/a/27483371
    private val format: DateTimeFormatter =
      DateTimeFormatter
        .ISO_LOCAL_DATE_TIME
        .withZone(
          ZoneId.from(ZoneOffset.UTC)
        )

    // Define a model, Message, that has a message, content, as well as the time
    // at which it was created.
    final case class Message(content: String, timestamp: Instant)
    object Message {
      // Define the Encoder[Message] within implicit scope.
      // See https://meta.plasm.us/posts/2019/09/30/implicit-scope-and-cats/ for an
      // excellent explanation of implicit scope in Scala.
      implicit val encoder: Encoder[Message] = new Encoder[Message] {
        override def apply(a: Message): Json =
          Json.obj(
            "content"   := a.content,
            "timestamp" := format.format(a.timestamp)
          )
      }
    }
  }

  // Create the interface for dealing with Messages. Note the F[_]
  // type is used to abstract over the effect type in the spirit of
  // Tagless Final.
  trait Messages[F[_]] {
    // Get all messages
    def get: F[List[Messages.Message]]
    // Create a new message. Note that Unit will returned on successful creation.
    def create(message: Messages.Message): F[Unit]
  }

  // Define a sealed trait that represents the recoverable errors
  // that apply to Messages[F]'s implementation.
  // Observe that it extends RuntimeException.
  // You may be asking why RuntimeException is used. A valid criticism
  // of this choice is that RuntimeException is not sealed, i.e. the compiler
  // cannot warn us if we fail to handle a particular sub-class of RuntimeException.
  // The reason for Throwable is, as
  // https://github.com/typelevel/cats-effect/blob/v2.5.1/docs/datatypes/io.md#error-handling
  // explains, the MonadError[IO, Throwable] instance means that any error handling will be
  // against the Throwable type.
  sealed abstract class ApiError() extends RuntimeException
  object ApiError {
    case object MissingXSecretHeader                                  extends ApiError
    final case class InvalidCreateMessageRequestPayload(t: Throwable) extends ApiError
    case object IncorrectSecretHeaderValue                            extends ApiError
  }

  // Define a case class for capturing a 'create messge' request.
  // Effectively it's a Data Transfer Object (see https://en.wikipedia.org/wiki/Data_transfer_object).
  // I opted against defining a Decoder for Messages.Message, which requires a (String + Timestamp).
  // The request simply includes a String message, and then, on the server I filled in the Timestamp
  // with "now."
  private final case class CreateMessageRequest(content: String)
  private object CreateMessageRequest {
    implicit val decoder: Decoder[CreateMessageRequest] = new Decoder[CreateMessageRequest] {
      override def apply(c: HCursor): Result[CreateMessageRequest] =
        c.downField("content")
          .as[String]
          .map{ str: String => CreateMessageRequest(str) }
    }
  }

  // http4s.org defines a Middleware:
  // > A middleware is a wrapper around a service that provides a means of manipulating the
  // > Request sent to service, and/or the Response returned by the service.
  // In this case, this middleware handles any thrown ApiException's, turning them into
  // HTTP Responses.
  private def middleware[F[_] : Sync](routes: HttpRoutes[F], log: String => F[Unit]): HttpRoutes[F] =
    HttpRoutes.apply { req: Request[F] =>
      routes
        .run(req)
        .handleErrorWith {
          // Why are the return types wrapped in OptionT#liftF? The documentation of
          // that function notes:
          // > Lifts the F[A] Functor into an OptionT[F, A].
          // In this case, the A is a Response[F].
          // Recall, from the beginning of this book, that HttpRoutes[F] is a type alias for
          // Kleisli[OptionT[F, *], Request[F], Response[F]]. Go back to that chapter if
          // if that type is not clear.
          case apiError: ApiError => apiError match {
            case ApiError.InvalidCreateMessageRequestPayload(t) =>
              OptionT.liftF(
                log(s"InvalidCreateMessageRequestPayloadResponse with Throwable: ${t.getMessage}").as(
                  Response[F](status = Status.BadRequest)
                )
              )
            case ApiError.MissingXSecretHeader =>
              OptionT.liftF(
                log("MissingXSecretHeader").as(
                  Response[F](status = Status.Unauthorized)
                )
              )
            case ApiError.IncorrectSecretHeaderValue =>
              OptionT.liftF(
                log("IncorrectSecretHeaderValue").as(
                  Response[F](status = Status.Unauthorized)
                )
              )
          }
        }
    }

  // This public method returns HttpRoutes[F], namely the HTTP Service
  // that will handle GET and POST /messages HTTP Requests.
  def routes[F[_] : Http4sDsl : Sync](
    message: Messages[F],
    log: String => F[Unit],
    trustedAuthToken: Secret
  ): HttpRoutes[F] = {
    // Note that its definition consists of applying the middleware to
    // the private method, routesHelper.
    middleware[F](routesHelper[F](message, trustedAuthToken), log)
  }

  // routesHelper is the workhorse method, i.e. actually defines which HTTP Requests
  // the service will handle.
  private def routesHelper[F[_] : Http4sDsl : Sync](message: Messages[F], secret: Secret): HttpRoutes[F] = {
    val secretHeader: CaseInsensitiveString = CaseInsensitiveString("x-secret")

    // Add a helper method for extracting the 'x-secret' header
    // from the given Headers.
    def getSecretHeader(headers: Headers): F[Header] = {

      // If the header is missing, i.e. Headers#get returns None, then
      // raise an 'ApiError.MissingXSecretHeader' RuntimeException.
      Sync[F].fromOption(
        headers.get(secretHeader),
        ApiError.MissingXSecretHeader
      )
    }

    // The following code summons the Http4sDsl[F], and then
    // imports all of its members in order to make use of the
    // http4s DSL.
    val dsl: Http4sDsl[F] = implicitly[Http4sDsl[F]]
    import dsl._

    // HttpRoutes#of accepts a single argument:
    // > PartialFunction[Request[F], F[Response[F]]]
    // Observe that it's a partial function, i.e. does not handle all inputs.
    // This signature makes sense as an HTTP Service accepts a Request and returns a Response.
    HttpRoutes.of[F] {
      // This service handles GET /messages using the http4s DSL, available via the above 'import dsl._'.
      case GET -> Root / "messages"  =>
        // Get a list of messages
        val messages: F[List[Messages.Message]] =
          message.get
        // Map over messages in order to return a F[Response[F]].
        messages.map { _messages: List[Messages.Message] =>
          // Return an HTTP-200 w/ a JSON payload
          // Note the signature of 'withEntity':
          // > def withEntity[T](b: T)(implicit w: EntityEncoder[F, T])
          // In this case, the T is List[Messages.Message].
          // How is EntityEncoder[F, List[Messages.Message]] is in scope?
          // The above import, org.http4s.circe.CirceEntityEncoder.circeEntityEncoder,
          // has the following signature:
          // > implicit def circeEntityEncoder[F[_], A: Encoder]: EntityEncoder[F, A]
          // We've defined an Encoder[Messages.Message] within the Messages.Message's object.
          // circe provides an implicit for lifting an Encoder[A] into a Encoder[List[A]]:
          // > implicit final def encodeList[A](implicit encodeA: Encoder[A]): AsArray[List[A]] =
          // https://github.com/circe/circe/blob/v0.13.0/modules/core/shared/src/main/scala/io/circe/Encoder.scala#L349
          Response[F](status = Status.Ok)
            .withEntity[List[Messages.Message]](
              _messages
            )
        }
      // This service handles POST /messages
      case req @ POST -> Root / "messages"  =>
        for {
          // This API requires authorization. As a result, let's get the 'x-secret- header
          // and verify that the header's value matches the real secret's value.
          header <- getSecretHeader(req.headers)
          _ <- {
            if (secret.value === header.value) Sync[F].unit
            else Sync[F].raiseError(ApiError.IncorrectSecretHeaderValue)
          }
          // Invoke the org.http4s.Media#as method:
          // > final def as[A](implicit F: MonadThrow[F], decoder: EntityDecoder[F, A]): F[A]
          // Note that org.http4s.Request extends Media
          createMessageRequest <- req.as[CreateMessageRequest]
            .adaptError {
              // adaptError is an extension methon on MonadErrorOps. It will map, in this case,
              // a Throwable => Throwable.
              // > def adaptError(pf: PartialFunction[E, E])(implicit F: MonadError[F, E]): F[A]
              // ApiError.InvalidCreateMessageRequestPayload(e)
              // In this case, any failure to read the Request as a CreateMessageRequest
              // will have an error mapped to ApiError.InvalidCreateMessageRequestPayload(e).
              case e => ApiError.InvalidCreateMessageRequestPayload(e)
            }
          // Since Instant.now(Clock.systemUTC()) is not referentially transparent, it must be
          // wrapped in Sync[F]#delay.
          // Its signature is:
          // > def delay[A](thunk: => A): F[A]
          // The A input is evaluated in a by-name manner, i.e. it's not evaluated
          // until it's accessed.
          now <- Sync[F].delay(Instant.now(Clock.systemUTC()))
          // Construct a Messages.Message
          msg = Messages.Message(createMessageRequest.content, now)
          // Create a message using the inteface
          _ <- message.create(msg)
        } yield {
          // Return an HTTP-200 on success
          Response[F](status = Status.Ok)
        }
    }
  }

}
```

Next, let's review the annotated code for testing our server.

```scala
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
```

To wrap up, let's run the tests:

```scala
TODO test
```

## Why Throwable instead of EitherT?

The server and client use an error type of java.lang.Throwable. In my four years of experience
building production web services to real customers, I've found `F[A]`, where IO is supplied for F
in the main program, to be a simple choice.

Critics of Throwable will correctly point out that Throwable is not sealed. In other words, it can't
provide any compile-time guarantees that our code will handle all sub-classes of Throwable. Such critics
will note that using a 'sealed trait AppError' hierarchy will provide such a guarantee. Likely those folks
will point to EitherT[F, AppError, A] as a better alternative to F[A].

Although EitherT[IO, AppError, A] may seem appealing, it has the following disadvantages:

  - two error channels, Throwable and AppError, can make for ambiguous error handling
  - unifying multiple error types, e.g. AppErrorA and AppErrorB into ParentAppError, requires "lifting"
    boilerplate (see https://gist.github.com/tpolecat/a0b65e8ffdf5dc34a48f)
  - F[A] is simpler for lesser experienced FP Software Engineers to understand than EitherT

However, the bottom line is that it's up to a Software Development Team/Organization to decide what's in their
best interest for maintenance and shipping to production.

Based on my experience, I recommend:

 - make the custom Throwable sub-class error be sealed, e.g. see ApiError from server.scala, to get some exhaustivity
    checking when using the "Middleware Approach" (see below)
    - Note that ApiError is sealed, so pattern matching on it enables us to use the compiler for exhaustive checks
 - use a middleware to match on the ApiError, and then pattern match on the exhaustive cases
    - See the 'middleware' private method in server.scala for an example

## Recommendations with Middleware Approach

 - Group together related services, e.g. GET and POST /messages, when defining a
    `def routes[F[_]](repository: Repository[F]): HttpRoutes[F]`
    - An object/class that contains multiple, unrelated routes is a code maintenance liability. Supporting multiple
      routes typically requires more interfaces as parameters to `def routes`. That results in in more complicated and
      bloated tests. The bloat comes into play since, when
      testing a route in the manner from `serverspec.scala`, every test requires an implementation of each interface.
      If multiple, unrelated routes are grouped together into a single 'def routes', there most certainly will
      involve stubbed
        - Example: `GET and POST /messages` is, at face-value, reasonable for services to keep within the same `def routes`.
        I say "reasonable" since they both share an underlying "Messages" interface. However, adding `POST /login` to a
        `def routes` that includes `GET and POST /messages` would be a poor choice. Given that login and messages are
        separate domains, they likely require separate interfaces. So, if we did group these services together, every
        time we wrote a test for this `def routes`, we'd have to include an un-used interface for `POST /login` API while
        testing `GET and POST /messages`. This bloat will continue to grow as more unrelated routes are added.
 - Use a middleware for each routes, i.e. don't re-use them across different `def routes` in objects/classes.
    - Re-using middlewares across routes results in tight coupling across services. When using a shared middleware, any
      changes could ripple out and break the other route. Tight coupling makes it harder to make precise changes without
      impacting a good deal of the code-base. That slows down development, which makes for a competitive disadvantage.
    - Consequently, I recommend separate AppError sealed trait hierarchies per `def routes`.
        - Although it may result in duplicating code, using a separate error per routes captures all error cases, no more
          or less.

Note that each test creates a local HttpRoutes[IO]. As a result, they are easy to reason about since there's no
shared state. Also, there's no risk of changes in one test breaking another. The fact that they're separate entirely
means that we can run individual tests in parallel. Lastly, the `munit-cats-effect` test library handles actual
`unsafeRunSync`, namely `IO[A] => A`.