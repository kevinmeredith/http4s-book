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