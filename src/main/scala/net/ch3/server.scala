package net.ch3

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
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
  * GET /{userId}/messages  - return all messages for a given User.
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved all of the user's message - { "content" : String, "timestamp" : String ISO861 }
        * HTTP-500 Something went wrong on server
  * POST /{userId}/messages - create a message for a given User
    * Must include x-secret header whose value equals the server's secret
    * Responses:
        * HTTP-200 Created the message
        * HTTP-401 Unauthorized (missing or invalid Authorization Header)
        * HTTP-403 Forbidden - Authorization user != path's user
        * HTTP-500 Something went wrong on server
   */

  object Messages {
    final case class Message(content: String, timestamp: Instant)
    object Message {
      implicit val encoder: Encoder[Message] = new Encoder[Message] {
        override def apply(a: Message): Json =
          Json.obj(
            "content"   := a.content,
            "timestamp" := a.timestamp.toEpochMilli
          )
      }
    }
    def apply[F[_]](implicit ev: Messages[F]): Messages[F] = ev
  }
  trait Messages[F[_]] {
    def get(userId: UserId): F[List[Messages.Message]]
    def create(userId: UserId, message: Messages.Message): F[Unit]
  }
  object UserId {
    def unapply(str: String): Option[UserId] =
      Try(new UserId(UUID.fromString(str))).toOption
  }

  sealed abstract class ApiError() extends RuntimeException
  object ApiError {
    case object MissingAuthorizationHeader extends ApiError
    case object InvalidCreateMessageRequestPayload extends ApiError
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
            case ApiError.InvalidCreateMessageRequestPayload => Response[F](status = Status.BadRequest)
            case ApiError.MissingAuthorizationHeader => Response[F](status = Status.Unauthorized)
            case ApiError.IncorrectSecretHeaderValue => Response[F](status = Status.Unauthorized)
          }
        }
    }

  def routes[F[_] : Http4sDsl : Messages : Sync](trustedAuthToken: Secret): HttpRoutes[F] =
    middleware[F](routesHelper[F](trustedAuthToken))

  private def routesHelper[F[_] : Http4sDsl : Messages : Sync](secret: Secret): HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = implicitly[Http4sDsl[F]]

    val secretHeader: CaseInsensitiveString = CaseInsensitiveString("x-secret")

    import dsl._

    def getSecret(headers: Headers): F[Secret] =
      for {
        secretHeader <- Sync[F].fromOption(
          headers.get(secretHeader),
          ApiError.MissingAuthorizationHeader
        )
      } yield Secret(secretHeader.value)

    HttpRoutes.of[F] {
      case GET -> Root / UserId(userId) / "messages"  =>
        val messages: F[List[Messages.Message]] =
          Messages[F].get(userId)
        messages.map { _messages: List[Messages.Message] =>
          Response[F](status = Status.Ok)
            .withEntity[List[Messages.Message]](
              _messages
            )
        }
      case req @ POST -> Root / UserId(userId) / "messages"  =>
        for {
          headerSecret <- getSecret(req.headers)
          _ <- {
            if (secret === headerSecret) Sync[F].unit
            else Sync[F].raiseError(ApiError.IncorrectSecretHeaderValue)
          }
          createMessageRequest <- req.as[CreateMessageRequest]
            .adaptError { case _ => ApiError.InvalidCreateMessageRequestPayload }
          now <- Sync[F].delay(Instant.now(Clock.systemUTC()))
          message = Messages.Message(createMessageRequest.content, now)
          _ <- Messages[F].create(userId, message)
        } yield Response[F](status = Status.Ok)
    }
  }

}