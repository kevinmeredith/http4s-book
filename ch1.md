# Chapter 1. What is `http4s`?

This chapter covers the following topics:
 * Introduction to http4s and `org.http4s.HttpRoutes[F]`
 * HttpRoutes[F] Example

## Introduction to http4s and `org.http4s.HttpRoutes[F]`

It's a Scala library for building web servers and clients with pure functional programming.

The library's [docs](https://http4s.org/v0.21/service/) begins its introduction of a Web Service with:

> An HttpRoutes[F] is a simple alias for Kleisli[OptionT[IO, *], Request, Response]. If that’s meaningful to you,
> great. If not, don’t panic: Kleisli is just a convenient wrapper around a Request => F[Response], and F is an
> effectful operation.

A Scala/FP Developer critically commented on [Twitter](https://twitter.com/hmemcpy/status/1215198123502571521):

> This does not belong anywhere near getting started.

I can see the author's point, yet the documentation is no doubt correct. Let's walk through this explanation in-depth.

[cats](https://github.com/typelevel/cats) documents [Kleisli](https://typelevel.org/cats/datatypes/kleisli.html), [OptionT](https://typelevel.org/cats/datatypes/optiont.html),
and many other concepts. Please read those descriptions if you're not comfortable with them.

The power and simplicity of that type is composition. In http4s, a web server consists of one or more combined `HttpRoutes[F]`.

Let's break down `HttpRoutes[IO]`. Recall it's a type alias for `Kleisli[OptionT[IO, *], Request[IO], Response[IO]]`. It's effectively
a function: `Request[IO] => OptionT[IO, Response[IO]]`. The `Request[IO]` represents an HTTP Request. The `OptionT[IO, Response[IO]]`
speaks to the optional HTTP Response.

Note the optionality, namely `OptionT[IO, Response[IO]]`, since the given `Request[IO]` may not apply to or match the
`HttpRoutes[F]`. In other words the route may not actually handle the given request, hence the optionality.

## HttpRoutes[F] Example

The following examples show the evaluation of supplying a `Request[IO]` to a `OptionT[IO, Response[IO]]`. In this
example, observe that non-empty response will be returned since the `HttpRoutes[IO]` handles the `Request[IO]`, namely
returning an `Response[IO](status = Status.Ok)`.

```scala
sbt:http4s-book> console
[info] Starting scala interpreter...
Welcome to Scala 2.12.12 (Java HotSpot(TM) 64-Bit Server VM, Java 1.8.0_112).
Type in expressions for evaluation. Or try :help.

scala> import org.http4s._, cats.effect._, cats._, cats.data._, org.http4s.dsl.io._, org.http4s.implicits._
import org.http4s._
import cats.effect._
import cats._
import cats.data._
import org.http4s.dsl.io._
import org.http4s.implicits._

scala> val fooRequest: Request[IO] = Request[IO](uri = uri"www.leanpub.com").withPathInfo("/foo")
fooRequest: org.http4s.Request[cats.effect.IO] = Request(method=GET, uri=/foo, headers=Headers())

scala> val fooService: HttpRoutes[IO] = HttpRoutes.of[IO] {
     |     case GET -> Root / "foo" => IO.pure(Response[IO](status = Status.Ok))
     |   }
fooService: org.http4s.HttpRoutes[cats.effect.IO] = Kleisli(org.http4s.HttpRoutes$$$Lambda$4760/1573956710@6093fe7c)

scala> fooService.run(fooRequest).value
res0: cats.effect.IO[Option[org.http4s.Response[cats.effect.IO]]] = IO$1434040922

scala> fooService.run(fooRequest).value.unsafeRunSync
res1: Option[org.http4s.Response[cats.effect.IO]] = Some(Response(status=200, headers=Headers()))
```

In the following, note that `None` will be returned. That's because, upon execution of the `IO`,
the given `Request[IO]` does not apply or match the `HttpRoutes[IO]`.

```scala
scala> val barRequest: Request[IO] = Request[IO](uri = uri"www.leanpub.com").withPathInfo("/bar")
barRequest: org.http4s.Request[cats.effect.IO] = Request(method=GET, uri=/bar, headers=Headers())

scala> fooService.run(barRequest).value.unsafeRunSync
res2: Option[org.http4s.Response[cats.effect.IO]] = None
```

I previously mentioned the power of composition. Let's now look at how we can compose two `HttpRoutes[IO]` into a single
one.

```scala
scala> import cats.implicits._
import cats.implicits._

scala> val barService: HttpRoutes[IO] = HttpRoutes.of[IO] {
     |   case GET -> Root / "bar" => IO.pure(Response[IO](status = Status.NoContent))
     | }
barService: org.http4s.HttpRoutes[cats.effect.IO] = Kleisli(org.http4s.HttpRoutes$$$Lambda$4760/1573956710@60088dfe)

// As the http4s' docs note, it's necessary to use the '-Ypartial-unification' scalac option when using `<+>`
scala> val combined: HttpRoutes[IO] = fooService <+> barService
combined: org.http4s.HttpRoutes[cats.effect.IO] = Kleisli(cats.data.KleisliSemigroupK$$Lambda$4828/455661998@2dc11b92)

scala> combined.run(fooRequest).value.unsafeRunSync
res4: Option[org.http4s.Response[cats.effect.IO]] = Some(Response(status=200, headers=Headers()))

scala> combined.run(barRequest).value.unsafeRunSync
res5: Option[org.http4s.Response[cats.effect.IO]] = Some(Response(status=204, headers=Headers()))
```

With the aim of making the optionality piece crystal clear, let's look at the signature of the `HttpRoutes#of[IO]` method:


```scala
  def of[F[_]: Defer: Applicative](pf: PartialFunction[Request[F], F[Response[F]]]): HttpRoutes[F] =
```

Before we proceed, let's look at the type classes, `Defer` and `Applicative`.

The [cats](https://github.com/typelevel/cats/blob/v2.4.2/core/src/main/scala/cats/Defer.scala#L6-L22) code includes
the following comment:

> Defer is a type class that shows the ability to defer creation
> ...
> The law is that defer(fa) is equivalent to fa, but not evaluated immediately,

```scala
trait Defer[F[_]] {
    def defer[A](fa: => F[A]): F[A]
}
```

[cats](https://github.com/typelevel/cats/blob/v2.4.2/core/src/main/scala/cats/Applicative.scala#L18-L31) defines `Applicative`.

```scala
trait Applicative[F[_]] extends Apply[F] ... {
    def pure[A](x: A): F[A]
    def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]
}
```

The ability to compose `HttpRoutes[IO]` is significant. In my professional experience, I've found building separate, small `HttpRoutes[IO]`
to be better than building large `HttpRoutes[IO]`. By "large" and "small," I'm talking about the number of pattern match cases.

Building separate, small `HttpRoutes[IO]`, offers the following benefits:

    * Readability  - understanding an `HttpRoutes[IO]` with 1 path is easier to understand than 10 paths.
    * Testability  - writing a test against an `HttpRoutes[IO]` with 1 path will produce fewer lines of code than one
                     with 10 path test.

Overall, building separate, small `HttpRoutes[IO]` produces code that's easier to maintain. Otherwise, the risk exists,
which I've encountered first-hand and been guilty of, of teams building bad habits of just always adding to
an existing `HttpRoutes[IO]`. This approach is risky since it likely will reduce understandability and testability.