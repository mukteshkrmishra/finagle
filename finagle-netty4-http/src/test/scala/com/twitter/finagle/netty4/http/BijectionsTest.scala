package com.twitter.finagle.netty4.http

import com.twitter.conversions.time._
import com.twitter.finagle.http._
import com.twitter.io.{Buf, BufReader}
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.{Cookie => _, _}
import io.netty.handler.codec.http.cookie.{
  Cookie => NettyCookie,
  DefaultCookie => NettyDefaultCookie
}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.runner.RunWith
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scala.collection.JavaConverters._
import scala.util.Random

object BijectionsTest {
  import Arbitrary.arbitrary

  val arbMethod = Gen.oneOf(
    Method.Get,
    Method.Post,
    Method.Trace,
    Method.Delete,
    Method.Put,
    Method.Connect,
    Method.Options
  )

  val arbKeys = Gen.oneOf("Foo", "Bar", "Foo-Bar", "Bar-Baz")

  val arbUri = for {
    scheme <- Gen.oneOf("http", "https")
    hostLen <- Gen.choose(1, 20)
    pathLen <- Gen.choose(1, 20)
    tld <- Gen.oneOf(".net", ".com", "org", ".edu")
    host = Random.alphanumeric.take(hostLen).mkString
    path = Random.alphanumeric.take(pathLen).mkString
  } yield (new URI(scheme, host + tld, "/" + path, null)).toASCIIString

  val arbHeader = for {
    key <- arbKeys
    len <- Gen.choose(0, 10)
  } yield (key, Random.alphanumeric.take(len).mkString)

  val arbResponse = for {
    code <- Gen.chooseNum(100, 510)
    version <- Gen.oneOf(Version.Http10, Version.Http11)
    headers <- Gen.containerOf[Seq, (String, String)](arbHeader)
    body <- arbitrary[String]
  } yield {
    val res = Response(version, Status(code))
    headers.foreach { case (k, v) => res.headerMap.add(k, v) }
    res.contentString = body
    (res, body)
  }

  val arbRequest = for {
    method <- arbMethod
    uri <- arbUri
    version <- Gen.oneOf(Version.Http10, Version.Http11)
    headers <- Gen.containerOf[Seq, (String, String)](arbHeader)
    body <- arbitrary[String]
  } yield {

    val req = Request.apply(version, method, uri, BufReader(Buf.Utf8(body)))
    headers.foreach { case (k, v) => req.headerMap.add(k, v) }
    req.setChunked(false)
    req.contentString = body
    req.headerMap.set(Fields.ContentLength, body.length.toString)
    (req, body)
  }

  val arbNettyMethod =
    Gen.oneOf(
      HttpMethod.GET,
      HttpMethod.POST
    )

  val arbNettyStatus =
    Gen.oneOf(
      HttpResponseStatus.OK,
      HttpResponseStatus.BAD_REQUEST,
      HttpResponseStatus.SERVICE_UNAVAILABLE,
      HttpResponseStatus.GATEWAY_TIMEOUT
    )

  val arbNettyVersion =
    Gen.oneOf(
      HttpVersion.HTTP_1_0,
      HttpVersion.HTTP_1_1,
      new HttpVersion("SECURE-HTTP/1.4", true)
    )

  val arbNettyRequest = for {
    method <- arbNettyMethod
    uri <- arbUri
    version <- arbNettyVersion
    kvHeaders <- Gen.containerOf[Seq, (String, String)](arbHeader)
    body <- arbitrary[String]
  } yield {
    val headers = new DefaultHttpHeaders()
    kvHeaders.foreach { case (k, v) => headers.add(k, v) }
    val req = new DefaultFullHttpRequest(
      version,
      method,
      uri,
      Unpooled.wrappedBuffer(body.getBytes(UTF_8)),
      headers,
      EmptyHttpHeaders.INSTANCE
    )
    (req, body)
  }

  val arbNettyResponse = for {
    method <- arbNettyMethod
    version <- arbNettyVersion
    status <- arbNettyStatus
    kvHeaders <- Gen.containerOf[Seq, (String, String)](arbHeader)
    body <- arbitrary[String]
  } yield {
    val headers = new DefaultHttpHeaders
    kvHeaders.foreach { case (k, v) => headers.add(k, v) }
    val req = new DefaultFullHttpResponse(
      version,
      status,
      Unpooled.wrappedBuffer(body.getBytes(UTF_8)),
      headers,
      EmptyHttpHeaders.INSTANCE
    )
    (req, body)
  }
}

@RunWith(classOf[JUnitRunner])
class BijectionsTest extends FunSuite with GeneratorDrivenPropertyChecks {
  import BijectionsTest._

  test("netty http request -> finagle") {
    forAll(arbNettyRequest) {
      case (in: FullHttpRequest, body: String) =>
        val out = Bijections.netty.fullRequestToFinagle(in, new InetSocketAddress(0))
        assert(out.uri == in.uri)
        assert(out.isChunked == false)
        assert(out.contentString == body)
        assert(out.version == Bijections.netty.versionToFinagle(in.protocolVersion))
        out.headerMap.foreach {
          case (k, v) =>
            assert(in.headers.getAll(k).asScala.toSet == out.headerMap.getAll(k).toSet)
        }
    }
  }

  test("netty http response -> finagle") {
    forAll(arbNettyResponse) {
      case (in: FullHttpResponse, body: String) =>
        val out = Bijections.netty.fullResponseToFinagle(in)
        assert(out.statusCode == in.status.code)
        assert(out.isChunked == false)
        assert(out.contentString == body)
        assert(out.version == Bijections.netty.versionToFinagle(in.protocolVersion))
        out.headerMap.foreach {
          case (k, v) =>
            assert(in.headers.getAll(k).asScala.toSet == out.headerMap.getAll(k).toSet)
        }
    }
  }

  test("finagle http response -> netty") {
    forAll(arbResponse) {
      case (in: Response, body: String) =>
        if (!in.isChunked) {
          val out = Bijections.finagle.fullResponseToNetty(in)
          assert(HttpUtil.isTransferEncodingChunked(out) == false)
          assert(out.protocolVersion == Bijections.finagle.versionToNetty(in.version))
          assert(out.asInstanceOf[FullHttpResponse].content.toString(UTF_8) == body)
          in.headerMap.foreach {
            case (k, v) =>
              assert(out.headers.getAll(k).asScala.toSet == in.headerMap.getAll(k).toSet)
          }
        } else {
          val out = Bijections.finagle.responseHeadersToNetty(in)
          assert(HttpUtil.isTransferEncodingChunked(out) == false)
          assert(out.protocolVersion == Bijections.finagle.versionToNetty(in.version))
          assert(out.asInstanceOf[FullHttpResponse].content.toString(UTF_8) == body)
          in.headerMap.foreach {
            case (k, v) =>
              assert(out.headers.getAll(k).asScala.toSet == in.headerMap.getAll(k).toSet)
          }
        }
    }
  }

  test("finagle http request -> netty") {
    forAll(arbRequest) {
      case (in: Request, body: String) =>
        val out = Bijections.finagle.requestToNetty(in)
        assert(HttpUtil.isTransferEncodingChunked(out) == false)
        assert(out.protocolVersion == Bijections.finagle.versionToNetty(in.version))
        assert(out.method == Bijections.finagle.methodToNetty(in.method))
        assert(out.uri == in.uri)
        if (!in.isChunked) {
          assert(out.isInstanceOf[FullHttpRequest])
          val full = out.asInstanceOf[FullHttpRequest]
          assert(full.content.toString(UTF_8) == body)
        }

        in.headerMap.foreach {
          case (k, v) =>
            assert(out.headers.getAll(k).asScala.toSet == in.headerMap.getAll(k).toSet)
        }
    }
  }

  test("finagle http request with chunked and content-length set -> netty") {
    val in = Request()
    in.setChunked(true)
    in.contentLength = 10

    val out = Bijections.finagle.requestToNetty(in)
    assert(!HttpUtil.isTransferEncodingChunked(out))
    assert(out.headers.get(Fields.ContentLength) == "10")
  }

  test("finagle http request with chunked and no content-length set -> netty") {
    val in = Request()
    in.setChunked(true)

    val out = Bijections.finagle.requestToNetty(in)
    assert(HttpUtil.isTransferEncodingChunked(out))
    assert(!out.headers.contains(Fields.ContentLength))
  }

  test("finagle cookie with name, value -> netty") {
    val in = new Cookie(
      name = "name",
      value = "value"
    )
    val out = Bijections.finagle.cookieToNetty(in)
    assert(out.name == "name")
    assert(out.value == "value")
    assert(out.isHttpOnly == false)
    assert(out.isSecure == false)
    assert(out.path == null)
    assert(out.domain == null)
    assert(out.maxAge == NettyCookie.UNDEFINED_MAX_AGE)
  }

  test("finagle cookie with domain, path -> netty") {
    val in = new Cookie(
      name = "name",
      value = "value",
      domain = Some("domain"),
      path = Some("path")
    )
    val out = Bijections.finagle.cookieToNetty(in)
    assert(out.domain == "domain")
    assert(out.path == "path")
  }

  test("finagle cookie with isHttpOnly, isSecure -> netty") {
    val in = new Cookie(
      name = "name",
      value = "value",
      secure = true,
      httpOnly = true
    )
    val out = Bijections.finagle.cookieToNetty(in)
    assert(out.isHttpOnly == true)
    assert(out.isSecure == true)
  }

  test("finagle cookie with maxAge -> netty") {
    val in = new Cookie(
      name = "name",
      value = "value",
      maxAge = Some(5.minutes)
    )
    val out = Bijections.finagle.cookieToNetty(in)
    assert(out.maxAge == 5.minutes.inSeconds)
  }

  test("netty cookie with name, value -> finagle") {
    val in = new NettyDefaultCookie("name", "value")
    val out = Bijections.netty.cookieToFinagle(in)
    assert(out.name == "name")
    assert(out.value == "value")
    assert(out.httpOnly == false)
    assert(out.secure == false)
    assert(out.path == null)
    assert(out.domain == null)
    assert(out.maxAge == Cookie.DefaultMaxAge)
  }

  test("netty cookie with domain, path -> finagle") {
    val in = new NettyDefaultCookie("name", "value")
    in.setDomain("domain")
    in.setPath("path")
    val out = Bijections.netty.cookieToFinagle(in)
    assert(out.domain == "domain")
    assert(out.path == "path")
  }

  test("netty cookie with isHttpOnly, isSecure -> finagle") {
    val in = new NettyDefaultCookie("name", "value")
    in.setSecure(true)
    in.setHttpOnly(true)
    val out = Bijections.netty.cookieToFinagle(in)
    assert(out.httpOnly == true)
    assert(out.secure == true)
  }

  test("netty cookie with maxAge -> finagle") {
    val in = new NettyDefaultCookie("name", "value")
    in.setMaxAge(5.minutes.inSeconds)
    val out = Bijections.netty.cookieToFinagle(in)
    assert(out.maxAge == 5.minutes)
  }
}
