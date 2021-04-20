# 1. What is `http4s`?

The library's [docs](https://http4s.org/v0.21/service/) begin its discussions of a Web Service with:

> An HttpRoutes[F] is a simple alias for Kleisli[OptionT[F, *], Request, Response]. If that’s meaningful to you,
> great. If not, don’t panic: Kleisli is just a convenient wrapper around a Request => F[Response], and F is an
> effectful operation.

A well-known Scala/FP Developer critically commented on [Twitter](https://twitter.com/hmemcpy/status/1215198123502571521):

> This does not belong anywhere near getting started.

I can see the author's point, however let's walk through this explanation in-depth.

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

I previously mentioned the power of composition. Let's look at how we can compose two `HttpRoutes[IO]` into a single one.

```scala
scala> import cats.implicits._
import cats.implicits._

scala> val barService: HttpRoutes[IO] = HttpRoutes.of[IO] {
     |   case GET -> Root / "bar" => IO.pure(Response[IO](status = Status.NoContent))
     | }
barService: org.http4s.HttpRoutes[cats.effect.IO] = Kleisli(org.http4s.HttpRoutes$$$Lambda$4760/1573956710@60088dfe)

// As the http4s' docs note, it's necessary to use the '-Ypartial-unification scalac option when using `<+>`
scala> val combined: HttpRoutes[IO] = fooService <+> barService
combined: org.http4s.HttpRoutes[cats.effect.IO] = Kleisli(cats.data.KleisliSemigroupK$$Lambda$4828/455661998@2dc11b92)

scala> combined.run(fooRequest).value.unsafeRunSync
res4: Option[org.http4s.Response[cats.effect.IO]] = Some(Response(status=200, headers=Headers()))

scala> combined.run(barRequest).value.unsafeRunSync
res5: Option[org.http4s.Response[cats.effect.IO]] = Some(Response(status=204, headers=Headers()))
```