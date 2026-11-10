package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.cloudshiftdev.gradle.codeartifact.ProxyResolver
import io.cloudshiftdev.gradle.codeartifact.cacheKey
import io.cloudshiftdev.gradle.codeartifact.internal.gradle.HttpErrorFactory
import io.cloudshiftdev.gradle.codeartifact.toHttpsProtocolUrl
import io.cloudshiftdev.gradle.codeartifact.token.TokenResolver
import java.io.InputStream
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.gradle.api.logging.Logging
import org.gradle.api.resources.ResourceException
import org.gradle.authentication.Authentication
import org.gradle.internal.SystemProperties
import org.gradle.internal.hash.HashCode
import org.gradle.internal.resource.ExternalResource
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.ReadableContent
import org.gradle.internal.resource.connector.ResourceConnectorFactory
import org.gradle.internal.resource.connector.ResourceConnectorSpecification
import org.gradle.internal.resource.metadata.ExternalResourceMetaData
import org.gradle.internal.resource.transfer.AbstractExternalResourceAccessor
import org.gradle.internal.resource.transfer.DefaultExternalResourceConnector
import org.gradle.internal.resource.transfer.ExternalResourceConnector
import org.gradle.internal.resource.transfer.ExternalResourceLister
import org.gradle.internal.resource.transfer.ExternalResourceReadResponse
import org.gradle.internal.resource.transfer.ExternalResourceUploader
import org.gradle.internal.resource.transport.http.ApacheDirectoryListingParser
import org.gradle.util.GradleVersion

internal class CodeArtifactResourceConnectorFactory(
    private val okHttpClient: OkHttpClient,
    private val proxyResolver: ProxyResolver,
    private val tokenResolver: TokenResolver,
    private val httpErrors: HttpErrorFactory,
) : ResourceConnectorFactory {
    private val supportedProtocols = setOf("codeartifact")

    override fun getSupportedProtocols(): Set<String> = supportedProtocols

    override fun getSupportedAuthentication(): Set<Class<out Authentication>> = emptySet()

    override fun createResourceConnector(
        connectionDetails: ResourceConnectorSpecification
    ): ExternalResourceConnector {
        val accessor =
            CodeArtifactResourceAccessor(okHttpClient, proxyResolver, tokenResolver, httpErrors)
        return DefaultExternalResourceConnector(
            accessor,
            CodeArtifactResourceLister(accessor),
            CodeArtifactResourceUploader,
        )
    }
}

private fun request(block: Request.Builder.() -> Unit): Request {
    return Request.Builder().apply(block).build()
}

/** See [org.gradle.internal.resource.transport.http.HttpResourceAccessor] */
internal class CodeArtifactResourceAccessor(
    private val okHttpClient: OkHttpClient,
    private val proxyResolver: ProxyResolver,
    private val tokenResolver: TokenResolver,
    private val httpErrors: HttpErrorFactory,
) : AbstractExternalResourceAccessor() {
    private val logger = Logging.getLogger(CodeArtifactResourceAccessor::class.java)

    /**
     * Proxy base URL per endpoint (domain/owner/region); avoids env/property lookups per request.
     */
    private val proxyBaseUrls = ConcurrentHashMap<String, Optional<URI>>()

    private companion object {
        private val UserAgent: String

        init {
            val osName = System.getProperty("os.name")
            val osVersion = System.getProperty("os.version")
            val osArch = System.getProperty("os.arch")
            val javaVendor = System.getProperty("java.vendor")
            val javaVersion = SystemProperties.getInstance().javaVersion
            val javaVendorVersion = System.getProperty("java.vm.version")
            UserAgent =
                String.format(
                    "Gradle/%s (%s;%s;%s) (%s;%s;%s) (via io.cloudshiftdev.codeartifact)",
                    GradleVersion.current().version,
                    osName,
                    osVersion,
                    osArch,
                    javaVendor,
                    javaVersion,
                    javaVendorVersion,
                )
        }
    }

    override fun openResource(
        location: ExternalResourceName,
        revalidate: Boolean,
    ): ExternalResourceReadResponse? {
        return try {
            val (url, request) = prepareGetRequest(location, revalidate)
            val response = executeRequest(request)
            // Gradle's contract (see HttpResourceAccessor): null means the resource is missing;
            // a closed 404 response would otherwise be read and fail with "closed".
            if (response.code == 404) return null
            OkHttpResponse(url, response)
        } catch (e: HttpStatusFailure) {
            // Gradle reports these (and retries transient ones); don't log them as errors here.
            logger.debug("Error retrieving resource: ${location.uri}", e.gradleException)
            throw e.gradleException
        } catch (t: Throwable) {
            logger.error("Error retrieving resource: ${location.uri}", t)
            throw t
        }
    }

    override fun getMetaData(
        location: ExternalResourceName,
        revalidate: Boolean,
    ): ExternalResourceMetaData? {
        return try {
            val (url, request) = prepareGetRequest(location, revalidate)

            executeRequest(request.newBuilder().head().build()).use { response ->
                if (response.code == 404) return null

                CodeArtifactMetadata(url, response)
            }
        } catch (e: HttpStatusFailure) {
            // Gradle reports these (and retries transient ones); don't log them as errors here.
            logger.debug(
                "Error retrieving metadata for resource: ${location.uri}",
                e.gradleException,
            )
            throw e.gradleException
        } catch (t: Throwable) {
            logger.error("Error retrieving metadata for resource: ${location.uri}", t)
            throw t
        }
    }

    private fun prepareGetRequest(
        location: ExternalResourceName,
        revalidate: Boolean,
    ): Pair<HttpUrl, Request> {
        val endpoint =
            location.uri.toCodeArtifactEndpointOrNull()
                ?: error("Invalid CodeArtifact endpoint: ${location.uri}")

        val proxyBaseUrl =
            proxyBaseUrls
                .computeIfAbsent(endpoint.cacheKey) {
                    Optional.ofNullable(proxyResolver.resolve(endpoint))
                }
                .orElse(null)
        val rawUrl =
            proxyBaseUrl?.let { proxyUrl(it, location.uri) } ?: location.uri.toHttpsProtocolUrl()

        val url = rawUrl.toHttpUrlOrNull() ?: error("Invalid URL: $rawUrl")

        val token = tokenResolver.resolve(endpoint).value
        val request = request {
            url(url)
            header("Authorization", "Bearer $token")
            header("User-Agent", UserAgent)
            // Matches Gradle's HttpResourceAccessor: ask caches (e.g. a CDN proxy) to revalidate.
            if (revalidate) header("Cache-Control", "max-age=0")
        }
        return Pair(url, request)
    }

    /**
     * Appends the resource's (still-encoded) path to [proxyBaseUrl], keeping any path prefix of the
     * base URL (and any query); `URI.resolve` with an absolute path would replace that prefix.
     */
    private fun proxyUrl(proxyBaseUrl: URI, resource: URI): URI =
        URI(
            "${proxyBaseUrl.scheme}://${proxyBaseUrl.rawAuthority}" +
                proxyBaseUrl.rawPath.orEmpty().trimEnd('/') +
                "/" +
                resource.rawPath.trimStart('/') +
                (proxyBaseUrl.rawQuery?.let { "?$it" } ?: "")
        )

    /**
     * Executes [request]. A 404 is returned (closed) for the caller to report as missing; any other
     * non-success status is thrown as Gradle's HTTP error exception so Gradle's retry (5xx/408/429)
     * and repository-disabling logic applies.
     */
    private fun executeRequest(request: Request): Response {
        val response = okHttpClient.newCall(request).execute()
        if (response.isSuccessful) return response
        response.close()
        if (response.code == 404) return response
        throw HttpStatusFailure(
            httpErrors.create(request.method, request.url.toUri(), response.code, response.message)
        )
    }

    /** Carries the Gradle HTTP error out of [executeRequest]; unwrapped before reaching Gradle. */
    private class HttpStatusFailure(val gradleException: RuntimeException) :
        RuntimeException(null, null, false, false)

    private class OkHttpResponse(source: HttpUrl, private val response: Response) :
        ExternalResourceReadResponse {
        private val metadata = CodeArtifactMetadata(source, response)

        override fun openStream(): InputStream = response.body.byteStream()

        override fun getMetaData(): ExternalResourceMetaData = metadata

        override fun close() {
            response.close()
        }
    }

    private class CodeArtifactMetadata(source: HttpUrl, private val response: Response) :
        ExternalResourceMetaData {
        private companion object {
            val ZeroDate = Date(0)
        }

        private val location = source.toUri()

        // OkHttp's date parsing is thread-safe (resolution runs in parallel).
        private val lastModified: Date = response.headers.getDate("Last-Modified") ?: ZeroDate

        private val contentType = response.header("Content-Type")

        private val filename = source.pathSegments.lastOrNull()

        private val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L

        private val etag = response.header("ETag")

        private val sha1 =
            response.header("x-checksum-sha1")?.let {
                try {
                    HashCode.fromString(it)
                } catch (_: Exception) {
                    null
                }
            }

        override fun getLocation(): URI = location

        override fun getLastModified(): Date = lastModified

        override fun getContentType(): String? = contentType

        override fun getFilename(): String? = filename

        override fun getContentLength(): Long = contentLength

        override fun getEtag(): String? = etag

        override fun getSha1(): HashCode? = sha1

        override fun wasMissing(): Boolean = response.code == 404
    }
}

/** See [org.gradle.internal.resource.transport.http.HttpResourceLister] */
private class CodeArtifactResourceLister(private val accessor: CodeArtifactResourceAccessor) :
    ExternalResourceLister {
    override fun list(directory: ExternalResourceName): List<String>? {
        return accessor.withContent(
            directory,
            true,
            object : ExternalResource.ContentAndMetadataAction<List<String>> {
                override fun execute(
                    inputStream: InputStream,
                    metaData: ExternalResourceMetaData,
                ): List<String>? {
                    if (metaData.wasMissing()) {
                        return null
                    }

                    val contentType = metaData.contentType
                    val parser = ApacheDirectoryListingParser()
                    try {
                        return parser.parse(directory.uri, inputStream, contentType)
                    } catch (e: Exception) {
                        throw ResourceException(
                            directory.uri,
                            "Unable to parse HTTP directory listing for '${directory.uri}'.",
                            e,
                        )
                    }
                }
            },
        )
    }
}

/** Publishing uses the repository's https URL with token credentials, never `codeartifact://`. */
private object CodeArtifactResourceUploader : ExternalResourceUploader {
    override fun upload(resource: ReadableContent, destination: ExternalResourceName) {
        throw UnsupportedOperationException(
            "Publishing to a codeartifact:// URL is not supported (${destination.uri}); " +
                "use the repository's https:// URL in publishing { repositories { ... } }"
        )
    }
}
