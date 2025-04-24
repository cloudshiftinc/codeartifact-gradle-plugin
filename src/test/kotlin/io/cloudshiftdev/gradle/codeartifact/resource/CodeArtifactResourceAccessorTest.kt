@file:OptIn(ExperimentalOkHttpApi::class)

package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.codeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.codeArtifactToken
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.zip.GZIPOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ExperimentalOkHttpApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.metadata.ExternalResourceMetaData

@OptIn(ExperimentalOkHttpApi::class)
class CodeArtifactResourceAccessorTest :
    FunSpec({
        test("GET works") {
            withMockWebServer { server, httpClient ->
                val token =
                    codeArtifactToken(endpoint = codeArtifactEndpoint(scheme = "codeartifact"))
                val accessor =
                    CodeArtifactResourceAccessor(
                        httpClient,
                        proxyResolver = { null },
                        tokenResolver = { token },
                    )

                val url = token.endpoint.withPath("test/test.xml")
                /*
                   content-type: text/xml
                   server: CloudFront
                   date: Wed, 23 Apr 2025 22:58:38 GMT
                   x-amzn-trace-id: Root=1-6809709e-5105737766cca8646f23eea4;
                   cache-control: public, max-age=365000000, immutable
                   content-disposition: inline; filename="ktor-bom-3.1.2.pom"
                   etag: W/"9eae15989ef6bf8401fe30edaf6b00d28547125a4be6717ed009b491c0303161"
                   last-modified: Wed, 23 Apr 2025 19:04:34 GMT
                   x-checksum-md5: 3fe0a07047c9dd21a6ce2e284b56b262
                   x-checksum-sha1: e316ed756e17fee68b587236dd90a7fe3412fba5
                   x-checksum-sha2: 9eae15989ef6bf8401fe30edaf6b00d28547125a4be6717ed009b491c0303161
                   x-checksum-sha512: b49225b542edfc035915a7c7a3cd12f36cfbd1b15371b8f75454747d92ceeff8c6715a223caa4d8163e4be06abb96cf899020a3e543fabe467bd75244b85d00f
                   x-packageversionrevision: o5M0fjeWKbd3j1m+uaWB3fY9ROnlh3sPmV07hQqSxIc=
                   x-amzn-requestid: 710ffa9c-6796-4e50-bd07-0ced6262c244
                   content-encoding: gzip
                   vary: Accept-Encoding
                   x-cache: Hit from cloudfront
                   via: 1.1 9e18d585dd3be9c0f23cd4e61a8f930e.cloudfront.net (CloudFront)
                   x-amz-cf-pop: YVR52-P2
                   alt-svc: h3=":443"; ma=86400
                   x-amz-cf-id: Ps5CQjoy-J5uSE2WY0Bs5j_wuZyzbsoYpwwEzLIJbSlAQSA_6YXTFg==
                   age: 70386
                */
                server.enqueue(
                    MockResponse()
                        .newBuilder()
                        .addHeader("Content-Type", "text/xml")
                        .addHeader("Server", "CloudFront")
                        .addHeader("Date", "Wed, 23 Apr 2025 22:58:38 GMT")
                        .addHeader("x-amzn-trace-id", "Root=1-6809709e-5105737766cca8646f23eea4;")
                        .addHeader("Cache-Control", "public, max-age=365000000, immutable")
                        .addHeader("content-disposition", "inline; filename=\"test.xml\"")
                        .addHeader(
                            "etag",
                            "W/\"9eae15989ef6bf8401fe30edaf6b00d28547125a4be6717ed009b491c0303161\"",
                        )
                        .addHeader("last-modified", "Wed, 23 Apr 2025 19:04:34 GMT")
                        .addHeader("x-checksum-md5", "3fe0a07047c9dd21a6ce2e284b56b262")
                        .addHeader("x-checksum-sha1", "e316ed756e17fee68b587236dd90a7fe3412fba5")
                        .addHeader(
                            "x-checksum-sha2",
                            "9eae15989ef6bf8401fe30edaf6b00d28547125a4be6717ed009b491c0303161",
                        )
                        .addHeader(
                            "x-checksum-sha512",
                            "b49225b542edfc035915a7c7a3cd12f36cfbd1b15371b8f75454747d92ceeff8c6715a223caa4d8163e4be06abb96cf899020a3e543fabe467bd75244b85d00f",
                        )
                        .addHeader(
                            "x-packageversionrevision",
                            "o5M0fjeWKbd3j1m+uaWB3fY9ROnlh3sPmV07hQqSxIc=",
                        )
                        .addHeader("x-amzn-requestid", "710ffa9c-6796-4e50-bd07-0ced6262c244")
                        .addHeader("content-encoding", "gzip")
                        .addHeader("vary", "Accept-Encoding")
                        .addHeader("x-cache", "Hit from cloudfront")
                        .addHeader(
                            "via",
                            "1.1 9e18d585dd3be9c0f23cd4e61a8f930e.cloudfront.net (CloudFront)",
                        )
                        .addHeader("x-amz-cf-pop", "YVR52-P2")
                        .addHeader("alt-svc", "h3=\":443\"; ma=86400")
                        .addHeader(
                            "x-amz-cf-id",
                            "Ps5CQjoy-J5uSE2WY0Bs5j_wuZyzbsoYpwwEzLIJbSlAQSA_6YXTFg==",
                        )
                        .addHeader("age", "70386")
                        .body(Buffer().write(gzipCompress("Hello, World!")))
                        .build()
                )
                accessor.withContent(url.toExternalResourceName(), false) {
                    it: InputStream,
                    metadata: ExternalResourceMetaData ->
                    it.readAllBytes().toString(Charsets.UTF_8) shouldBe "Hello, World!"

                    assertSoftly {
                        metadata.location shouldBe url
                        metadata.lastModified.toString() shouldBe "Wed Apr 23 12:04:34 PDT 2025"
                        metadata.contentType shouldBe "text/xml"
                        metadata.filename shouldBe "test.xml"
                        metadata.contentLength shouldBe -1L
                        metadata.etag shouldBe
                            "W/\"9eae15989ef6bf8401fe30edaf6b00d28547125a4be6717ed009b491c0303161\""
                        metadata.sha1.toString() shouldBe "e316ed756e17fee68b587236dd90a7fe3412fba5"
                        metadata.wasMissing() shouldBe false
                    }
                }

                val req = server.takeRequest()
                assertSoftly {
                    req.path shouldBe url.path
                    req.method shouldBe "GET"
                    val headers = req.headers
                    headers["Authorization"] shouldBe "Bearer ${token.value}"
                    headers["X-Original-Url"] shouldBe url.toString()
                    headers["User-Agent"] shouldStartWith "Gradle/"
                    headers["Connection"] shouldBe "Keep-Alive"
                    headers["Accept-Encoding"] shouldBe "gzip"

                    headers.names() shouldBe
                        setOf(
                            "Authorization",
                            "X-Original-Url",
                            "User-Agent",
                            "Connection",
                            "Accept-Encoding",
                            "Host",
                        )
                }
            }
        }
    })

private fun URI.toExternalResourceName(): ExternalResourceName {
    return ExternalResourceName(this)
}

private fun CodeArtifactEndpoint.withPath(path: String): URI {
    return url.toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path)?.build()?.toUri()
        ?: error("Invalid URL")
}

private fun withMockWebServer(block: (MockWebServer, OkHttpClient) -> Unit) {

    val server = MockWebServer()
    server.start()
    val httpClient = OkHttpClient.Builder().addInterceptor(MockWebServerInterceptor(server)).build()
    try {
        block(server, httpClient)
    } finally {
        server.shutdown()
    }
}

private class MockWebServerInterceptor(private val server: MockWebServer) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Create a new URL using the mock server but preserving the path and query
        val mockUrl =
            server.url(
                originalUrl.encodedPath +
                    if (originalUrl.encodedQuery != null) "?${originalUrl.encodedQuery}" else ""
            )

        // Build a new request with the mock URL
        val newRequest =
            originalRequest
                .newBuilder()
                .url(mockUrl)
                .header("X-Original-Url", originalUrl.toString())
                .build()

        return chain.proceed(newRequest)
    }
}

private fun gzipCompress(data: String): ByteArray {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gzipOutputStream ->
        gzipOutputStream.write(data.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
}
