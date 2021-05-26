package net.ch3

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder
import org.http4s.dsl.Http4sDsl

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.UUID
import net.ch3.models.{Secret, UserId}
import net.ch3.server.Messages
import org.http4s.util.CaseInsensitiveString

import scala.util.Try

object server {

  /*
  * GET /messages  - return all messages
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved all of the message - { "content" : String, "timestamp" : String ISO861 }
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
    // Create a new message
    def create(message: Messages.Message): F[Unit]
  }

  // Define a sealed trait that represents the recoverable errors
  // that apply to Messages[F]'s implementation.
  // Observe that it extends RuntimeException.
  // You may be asking why RuntimeException is used. A valid criticism
  // of this choice is that RuntimeException is not sealed, i.e. the compiler
  // cannot warn us if we fail to handle a particular sub-class of RuntimeException.
  // The reason for Throwable is,
  // //https://github.com/typelevel/cats-effect/blob/v2.5.1/docs/datatypes/io.md#error-handling
  sealed abstract class ApiError() extends RuntimeException
  object ApiError {
    case object MissingXSecretHeader extends ApiError
    final case class InvalidCreateMessageRequestPayload(t: Throwable) extends ApiError
    case object IncorrectSecretHeaderValue extends ApiError
  }

  final case class CreateMessageRequest(content: String)
  object CreateMessageRequest {
    implicit val decoder: Decoder[CreateMessageRequest] = new Decoder[CreateMessageRequest] {
      override def apply(c: HCursor): Result[CreateMessageRequest] =
        c.downField("content")
          .as[String]
          .map{ str: String => CreateMessageRequest(str) }
    }
  }

  private def middleware[F[_] : Sync](routes: HttpRoutes[F]): HttpRoutes[F] =
    HttpRoutes.apply { req: Request[F] =>
      routes
        .run(req)
        .recover {
          case apiError: ApiError => apiError match {
            case ApiError.InvalidCreateMessageRequestPayload(_) => Response[F](status = Status.BadRequest)
            case ApiError.MissingXSecretHeader => Response[F](status = Status.Unauthorized)
            case ApiError.IncorrectSecretHeaderValue => Response[F](status = Status.Unauthorized)
          }
        }
    }

  def routes[F[_] : Http4sDsl : Sync](message: Messages[F], trustedAuthToken: Secret): HttpRoutes[F] =
    middleware[F](routesHelper[F](message, trustedAuthToken))

  private def routesHelper[F[_] : Http4sDsl : Sync](message: Messages[F], secret: Secret): HttpRoutes[F] = {
    val secretHeader: CaseInsensitiveString = CaseInsensitiveString("x-secret")

    def getSecretHeader(headers: Headers): F[Header] =
      for {
        secretHeader <- Sync[F].fromOption(
          headers.get(secretHeader),
          ApiError.MissingXSecretHeader
        )
      } yield secretHeader

    val dsl: Http4sDsl[F] = implicitly[Http4sDsl[F]]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "messages"  =>
        val messages: F[List[Messages.Message]] =
          message.get
        messages.map { _messages: List[Messages.Message] =>
          Response[F](status = Status.Ok)
            .withEntity[List[Messages.Message]](
              _messages
            )
        }
      case req @ POST -> Root / "messages"  =>
        for {
          header <- getSecretHeader(req.headers)
          _ <- {
            if (secret.value === header.value) Sync[F].unit
            else Sync[F].raiseError(ApiError.IncorrectSecretHeaderValue)
          }
          createMessageRequest <- req.as[CreateMessageRequest]
            .adaptError { case e => ApiError.InvalidCreateMessageRequestPayload(e) }
          now <- Sync[F].delay(Instant.now(Clock.systemUTC()))
          msg = Messages.Message(createMessageRequest.content, now)
          _ <- message.create(msg)
        } yield Response[F](status = Status.Ok)
    }
  }

}