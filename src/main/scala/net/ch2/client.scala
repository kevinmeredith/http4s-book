package net.ch2

import cats._
import cats.effect.Sync
import cats.implicits._
import io.circe._
import org.http4s.client.{ Client, UnexpectedStatus }
import org.http4s._
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import java.time.Instant
import java.time.DateTimeException
import scala.util.control.NoStackTrace

// The `Messages` algebr  a includes a single method to get messages.
trait Messages[F[_]] {
  def getMessages(topicName: String): F[List[Messages.Message]]
}
// Next, let's look at the companion object. Its primary purpose is
// to provide an implementation of Messages[F].
object Messages {
  // Represents the model definition of a `Message`.
  final case class Message(value: String, timestamp: Instant)
  object Message {
    // Expose a 'from' method for attempting to build a Message
    // from a MessageDTO
    def from(m: MessageDTO): Either[DateTimeException, Message] =
      Either
        .catchOnly[DateTimeException] {
          Instant.ofEpochMilli(m.timestamp)
        }
        .map { ts: Instant =>
          new Message(m.value, ts)
        }

  }

  // DTO = Data Transfer Object
  // This 'private" case class represents the JSON payload. It's private
  // since its purpose is to make the conversion from JSON => Message, the true
  // model data type, easier.
  private final case class MessageDTO(value: String, timestamp: Long)
  private object MessageDTO {
    // Define a circe Decoder, a typeclass that provides a method
    // to go from, roughly speaking, JSON => A.
    implicit val decoder: Decoder[MessageDTO] = new Decoder[MessageDTO] {
      final def apply(c: HCursor): Decoder.Result[MessageDTO] =
        for {
          value         <- c.downField("value").as[String]
          timestampLong <- c.downField("timestamp").as[Long]
        } yield MessageDTO(value, timestampLong)
    }
  }

  // Define a custom Throwable that includes an "errorMessage" and underlying Throwable.
  // The motivation for this Throwable is to make it explicitly clear what failed when
  // attempting to get List[Message] via the HTTP API.
  // When an error occurs, it's critical that the Software Engineer reviewing error logs
  // know what failed and why. To the business, clear error reporting is a competitive
  // advantage since Software Engineers can more promptly address errors if their cause
  // is explicitly captured, such as "GetMessagesError."
  // In my own experience in production, a logger will be responsible for
  // logging the "errorMessage," as well as the stack trace of the "t" Throwable.
  // In addition, note that "GetMessagesError" mixes in scala.control.util.NoStackTrace.
  // It does that since the stack trace of "GetMessagesError" is not meaningful.
  // Finally, note that it's "private." If this error occurs, there will be no
  // way to meaningfully recover from it. Consequently, in the interest of minimizing
  // the surface area of this API, let's remove its accessibility to all but
  // to "object Messages."
  final case class GetMessagesError(errorMessage: String, t: Throwable)
    extends RuntimeException(errorMessage, t)
      with NoStackTrace

  // Define an implementation for Messages[F]
  // Note that there's a typeclass constraint of "F[_] : Sync." Read this as
  // the F, whose kind is * -> *, must have a cats.effect.Sync[F] instance in scope.
  // The purpose of the "c," Client[F], is to make an HTTP Request to the 3rd party API.
  // Finally, the "uri" type is needed as a URI is required for making an HTTP Request.
  def impl[F[_] : Sync](c: Client[F], uri: Uri): Messages[F] = new Messages[F] {
    def getMessages(topicName: String): F[List[Messages.Message]] = {
      // Create a URI with a path of /messages and an added query parameter
      val u: Uri = (uri / "messages").withQueryParam("topicName", topicName)

      // Build the HTTP Request w/ a Method of GET and URI = the constructed one
      val r: Request[F] = Request[F](method = Method.GET, uri = u)

      val messages: F[List[Message]] =
        c.run(r)                       // Call Client#run, which returns a
                                       // Resource[F, Response[F]]
          .use { resp: Response[F] =>  // Invoke the "use" method of Resource:
                                       //   Response[F] => F[List[Message]].
            resp match {               // Pattern match on the Response[F] to inspect,
                                       // in particular, its status.
              case Status.Ok(body) =>  // This pattern match applies to an HTTP-200
                                        // response.
                                       // The "body" variable
                                       // has a type of "Response[F]."
                // Attempt to decode the "body," whose type is "Response[F]," into
                // a "List[MessageDTO]".
                // Note that "org.http4s.Response" inherits this method
                // from "org.http4s.Media:"
                //  > final def as[A](
                //     implicit F: MonadThrow[F],
                //              decoder: EntityDecoder[F, A]
                //    ): F[A]
                // "MonadThrow[F]" is a type alias for "MonadError[F, Throwable]."
                // cats's docs explain MonadError:
                //  > allows you to raise and or handle an error value.
                // In this case, the "MonadError[F, Throwable]" allows for raising
                // or handling Throwable's.
                // http4s's code docs explain:
                //  > A type that can be used to decode a Message EntityDecoder
                //  > is used to attempt to decode a Message returning the
                //  > entire resulting A.
                // In this case, org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
                // creates the "EntityDecoder[F, MessageDTO]." Its type signature
                //  is:
                //  > implicit def circeEntityDecoder[
                //     F[_]: Sync,
                //     A: Decoder
                //    ]: EntityDecoder[F, A]
                // So, that type signature states, given evidence of Sync[F]
                // and Decoder[A], produce an "EntityDecoder[F, A]." This code
                // requires a "Sync[F]" per Messages#impl's implicits.
                // Also, there's an implicit "Decoder[MessageDTO]"
                // defined in the "MessageDTO" object.
                val dtos: F[List[MessageDTO]] =
                  body.as[List[MessageDTO]]

                // At this point, the code has an "F[List[MessageDTO]]." It's then
                // necessary to convert each "MessageDTO" into a "Message," i.e.
                // the domain model representation of a message. Recall that "MessageDTO"
                // is just a private model that's used strictly for decoding JSON
                // into a type.
                val messages: F[List[Message]] =
                    dtos                                    // For converting
                                                            // F[List[MessageDTO]]
                                                            // to
                                                            //  F[List[Message]],
                                                            // we'll use
                                                            // cats.FlatMap[F]#flatMap.
                      .flatMap { _dtos: List[MessageDTO] => // List[MessageDTO] =>
                                                            //  F[List[Message]]
                        Traverse[List]                      // Summon an instance
                                                            // of cats.Traverse
                          .traverse[F, MessageDTO, Message](_dtos) {
                            dto: MessageDTO =>              // MessageDTO => F[Message]
                                val m: Either[DateTimeException, Message] =
                                  Message.from(dto)
                                Sync[F].fromEither(m)
                              }
                      }
                messages
              case non200Status => Sync[F].raiseError(
                GetMessagesError(
                  "non-200 response",
                  UnexpectedStatus(non200Status.status)
                )
              )
            }
          }

      // There are multiple failures that could occur when making an HTTP
      //  Request with org.http4s.client.Client:
      // (1) received non HTTP-200 response
      // (2) received HTTP-200 but failed to decode the HTTP Response's payload
      //     as JSON
      // (3) received HTTP-200 but failed to decode the HTTP Response's JSON
      //     payload as a 'MessageDTO'
      // (4) received HTTP-200 but at least one of the MessageDTO's includes
      //     an invalid 'Long' timestamp
      // (5) client times out, i.e. clients halts/fails due to an exceeded
      //     timer waiting for a response from the client
      // So far this code only handles error case (1) by wrapping it
      //   in a GetMessagesError.
      // When reviewing error messages in the logs, it's valuable to
      //  know exactly why an error occurred. In this case, it's important
      //  to know that as 'GetMessagesError' occurred, including the
      //  message and underlying stack trace.
      // As a result, let's use MonadError[F, Throwable]#adaptError:
      // > override def adaptError[A](fa: F[A])(pf: PartialFunction[E, E]): F[A]
      // In this code, the error "E" type is Throwable, and
      // the "A" is "List[Message]." Note that "adaptError" is an extension
      // method from cats.syntax.MonadErrorOps.
      messages
        .adaptError {
          case e @ GetMessagesError(_, _) =>
            // If a GetMessagesError's already been raised, keep it as the error type.
            e
          case other                      =>
            // If we did return an existing raised "GetMessagesError," we
            // would've produced a redundant
            // GetMessagesError("unexpected error", GetMessagesError(..., ...))
            GetMessagesError("unexpected error", other)
        }
    }
  }
}