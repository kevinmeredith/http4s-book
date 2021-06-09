# Chapter 3. Building a Web Server

This chapter covers the following topics:
  * Recap of `org.http4s.HttpRoutes[F]`
  * Introduction to `HttpApp[F]`
  * Builing an HTTP Server Example
  * Testing Example

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

Let's look into an example of building a server with two routes:

* GET /{userId}/messages  - return all messages for a given User.
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved the message
        * HTTP-500 Something went wrong on server
* POST /{userId}/messages - create a message for a given User
    * Must include Authorization header whose value identifies the User
    * Responses:
        * HTTP-200 Created the message
        * HTTP-401 Unauthorized (missing or invalid Authorization Header)
        * HTTP-403 Forbidden - Authorization user != path's user
        * HTTP-500 Something went wrong on server

Now let's read the src/main code.

```scala
```

## Why Throwable instead of EitherT?

The server and client use an error type of java.lang.Throwable. In my four years of experience
building production web services to real customers, I've found `F[A]`, where IO is supplied for F
in the main program, to be a simple choice.

Critics of Throwable will correctly point out that Throwable is not sealed. In other words, it can't
provide any compile-time guarantees that our code will handle all sub-classes of Throwable. Such critics
will note that using a 'sealed trait ErrorADT' hierarchy will provide such a guarantee. Likely those folks
will point to EitherT[F, ErrorADT, A] as a better alternative to F[A].

...

To minimize the risk of failing an error, e.g. ApiError, I recommend:

 * having the custom Throwable sub-classes be sealed to gain exhaustivity checking
    * Note that ApiError is sealed, so pattern matching on it enables us to use the compiler for exhaustive checks
 * using a middleware to match on the ApiError, and then the exhaustive cases
    * See the 'middleware' private method in server.scala

## Comments on the Middleware Approach

keep only related routes together + use middleware per routes file to avoid tight coupling/hard to maintain +  don't DRY

Let's review the src/test code.

```scala
```