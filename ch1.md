# 1. What is `http4s`?

The library's [docs](https://http4s.org/v0.21/service/) begin its discussions of a Web Service with:

> An HttpRoutes[F] is a simple alias for Kleisli[OptionT[F, *], Request, Response]. If that’s meaningful to you,
> great. If not, don’t panic: Kleisli is just a convenient wrapper around a Request => F[Response], and F is an
> effectful operation.

A well-known Scala/FP Developer critically commented on [Twitter](https://twitter.com/hmemcpy/status/1215198123502571521):

> This does not belong anywhere near getting started.

[cats](https://github.com/typelevel/cats) documents [Kleisli](https://typelevel.org/cats/datatypes/kleisli.html), [OptionT](https://typelevel.org/cats/datatypes/optiont.html),
and many other concepts.

Roughly speaking, the power and simplicity of that type is composition. An entire web service consists of one or more `HttpRoutes[F]`.

Let's break down `HttpRoutes[IO]`. Recall it's a type alias for `Kleisli[OptionT[F, *], Request, Response]`. It's effectively
a function: `Request[F] => OptionT[F, Response[F]]`.

So, for a given `Request[F]`, it's applied to an `Kleisli[OptionT[F, *], Request, Response]` to return a `OptionT[F, Response[F]]`.

Note the optionality, namely `OptionT[F, *]` since the given `Request[F]` may not apply to the `HttpRoutes[F]`, i.e. the
route may not actually handle the given request.

The following examples show the evaluation of supplying a `Request[IO]` to ``

```scala
@ import $ivy.`org.http4s::http4s-core:0.21.21`
import $ivy.$

@ import $ivy.`org.http4s::http4s-dsl:0.21.21`
import $ivy.$

@ import org.http4s._, cats.effect._, cats._, cats.data._, org.http4s.dsl.io._, org.http4s.implicits._
import org.http4s._, cats.effect._, cats._, cats.data._, org.http4s.dsl.io._, org.http4s.implicits._

@ val fooRequest: Request[IO] = Request[IO](uri = uri"www.leanpub.com").withPathInfo("/foo")
fooRequest: Request[IO] = (
  Method("GET"),
  Uri(None, None, "/foo", , None),
  HttpVersion(1, 1),
  Headers(),
  Stream(..),
  io.chrisdavenport.vault.Vault@17a3bd57
)

@ val fooService: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "foo" => IO.pure(Response[IO](status = Status.Ok))
  }
fooService: HttpRoutes[IO] = Kleisli(org.http4s.HttpRoutes$$$Lambda$3042/1103551404@26e185e2)

@ fooService.run(fooRequest).value
res23: IO[Option[Response[IO]]] = Suspend(org.http4s.HttpRoutes$$$Lambda$3127/530611995@72bfd4ae)

@ fooService.run(fooRequest).value.unsafeRunSync
res24: Option[Response[IO]] = Some(
  Response(Status(200), HttpVersion(1, 1), Headers(), Stream(..), io.chrisdavenport.vault.Vault@14aadfc3)
)
```

In the following, note that `None` will be returned. That's because, upon execution of the `IO`,
the given `Request[IO]` does not apply or match the `HttpRoutes[IO]`.

```scala
@ val barRequest: Request[IO] = Request[IO](uri = uri"www.leanpub.com").withPathInfo("/bar")
barRequest: Request[IO] = (
  Method("GET"),
  Uri(None, None, "/bar", , None),
  HttpVersion(1, 1),
  Headers(),
  Stream(..),
  io.chrisdavenport.vault.Vault@9df44c4
)

// Recall that fooService only handles "GET /foo"
@ fooService.run(barRequest).value.unsafeRunSync
res26: Option[Response[IO]] = None
```