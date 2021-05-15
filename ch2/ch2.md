# Chapter 2. Building an HTTP Client to Consume from an API

This chapter covers the following topics:
 * Definition of `org.http4s.client.Client[F]`
 * Builing an HTTP Client Example
 * Testing Example

## Definition

The `http4s`'s [docs](https://github.com/http4s/http4s/blob/v0.21.22/client/src/main/scala/org/http4s/client/Client.scala#L31) describe a
`Client`:

> A Client submits Requests to a server and processes the Response.

Let's look at the `http4s` source code of a `Client`:

```scala
trait Client[F[_]] {
  def run(req: Request[F]): Resource[F, Response[F]]
  ...
}
```

In short, given a `Request[F]`, `Client#run` will return a `Resource[F, Response[F]]`.

As the `cats-effect`'s [docs](https://typelevel.org/cats-effect/docs/2.x/datatypes/resource) note, `Resource`
"effectfully allocates and releases a resource." This means that, `cats.effect.Resource`, will properly dispose of any
resources for the `Response[F]`.

Let's look at an example to show how the `http4s` `Client[F]` API works.

## Builing an HTTP Client Example

```scala
```

## Testing Example

When I first used `http4s` professionally, I had come from building web applications using the [Play Framework](https://www.playframework.com/). The
simplicity of testing `http4s`'s `Client[F]`'s attracted me further to this HTTP library.

Let's look at an example. There's a single API: `GET /messages?topic={name}`. It returns an array of messages for a given topic.
A message has a "value," which is text, and "timestamp," which is the time at which the message occurred. Its data type is
Long since it's in milliseconds since the Epoch in Linux. Note that the `topic` query parameter is required.

Example:

```json
{
    "value"     : "Hello world",
    "timestamp" : 1619401781088
}
```

Before we dive into the `http4s` `Client[F]` example, let's discuss "Tagless Final" in Scala.

### Tagless Final

A well-known principle of Software Engineering is "program to an interface, not implementation." This point is important
since programming to an interface results in more maintainable and testable code. The "Tagless Final" approach has the same aim.

In short, this approach's `interface` consists of using a Scala `trait` with a type parameter having a [kind](https://eed3si9n.com/herding-cats/Kinds.html)
 of `* -> *`.

Let's look at an example:

```scala
import org.http4s.client.Client
import org.http4s.Uri
import java.time.Instant
import java.util.UUID

trait Notifications[F[_]] {
    def notify(userId: UUID): F[Unit]
    def getNotifications(userId: UUID): F[List[Notifications.Notification]]
}

object Notifications {
    final case class Notification(message: String, timestamp: Instant)

    // We need an HTTP Client, as well as a URI, for getting notifications.
    // In short, for this implementation of `Notifications[F[_]]`, we need
    // an HTTP Client for accessing an API.
    def impl(c: Client[IO], notificationsApiUri: Uri): Notifications[IO] = new Notifications[IO] {
        override def notify(userId: UUID): IO[Unit] = ???
        def getNotifications(userId: UUID): IO[List[Notification]] = ???
    }
}
```

So, the `trait` represents the interface, whereas `Notifications#impl` defines an implementation of it.

Alternatively, in my professional experience using this approach in production, I've heard the `trait` referred to as the
"algebra," and the implementation is the "interpreter." In my opinion, the language of "interface" and "implementation"
since more Software Engineers will grasp those terms over the "algebra" and interpreter word choice. Also, using the former
terminology, in no way, lessens their purpose.

Let's return to the `GET /messages?topic={name}` API for which we're going to build an `http4s`'s Client.

```scala

```

Finally, let's write a test for this interface's implementation.

```scala

```

### Effect Types for Testing?

One argument of the Tagless Final approach, which provides a polymorphic `F[_]` for the effect type, is that it enables
using a type other than `cats.effect.IO` for testing. Although this is true, in my 4 years of professional
experience building web services in production, I've 99% of the time used `cats.effect.IO`. It's a natural choice since
that's what will be used in the real-world instance of the application. An additional argument for using `cats.effect.IO`
as the effect type is https://github.com/typelevel/munit-cats-effect. That library enables building tests that compare
values of type `IO[A]`. `