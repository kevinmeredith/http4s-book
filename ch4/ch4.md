# Chapter 4. Conclusion

## Overview

This book started in Chapter 1 with an in-depth walkthrough of HttpRoutes[F]. No doubt understanding the underlying type
requires study. However, once you understand it, its simplicity and applicability to handling HTTP Requests becomes
evident.

Chapter 2 focused on building and testing an HTTP Client, showing the fully commented code. The power of http4s's client
is the simplicity of testing it. This chapter introduced building services and interfaces with the Tagless Final approach.
Finally, it closed with a discussion on Effect Types for testing. In short, I suggest cats.effect.IO and
https://github.com/typelevel/munit-cats-effect.

Chapter 3 showed how to build and test a web service, namely a `HttpRoutes[F]`, again including the fully annotated code.
It argued for using an underlying error type of a sealed trait that extends Throwable. It also explained and recommended
the "Middleware Approach" for handling recoverable error Throwable's into HTTP Responses. Also, I spoke in defense of Throwable,
as well as threw water on EitherT.

## Closing

In my eight years of professional Scala experience, I've worked with multiple web service frameworks and libraries
in Scala and Java: Jersey, Play, spray, Lift, http4s and akka-http.

I strongly favor http4s over the others due to its embrace of Pure Functional Programming (FP) and the fact that it's a
library rathe rthan a heavy framework.

Pure FP offers a significant competitive advantage to companies as it enables Software Engineers to more easily reason
about their code. Knowing what your code does and building to your intent is clearly a critical tool for quickly
deploying accurate, defect-free code to production.

\newpage
