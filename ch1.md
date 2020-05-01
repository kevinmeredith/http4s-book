# 1. What is `http4s`?

The library's [docs](https://http4s.org/v0.21/service/) begin its discussions of a Web Service with:

> An HttpRoutes[F] is a simple alias for Kleisli[OptionT[F, *], Request, Response]. If that’s meaningful to you,
> great. If not, don’t panic: Kleisli is just a convenient wrapper around a Request => F[Response], and F is an
> effectful operation.

A fairly well-known Scala/FP Developer critically commented on [Twitter](https://twitter.com/hmemcpy/status/1215198123502571521):

> This does not belong anywhere near getting started.

cats documents [Kleisli](https://typelevel.org/cats/datatypes/kleisli.html), [OptionT](https://typelevel.org/cats/datatypes/optiont.html),
and many other concepts.

Roughly speaking, the power and simplicity of that type is composition. An entire web service consists of one or more `HttpRoutes[F]`.

To elaborate on `Kleisli[OptionT[F, *], Request, Response]`, it's effectively a function: `Request[F] => OptionT[F, Response[F]]`.

So, for a given Request[F],