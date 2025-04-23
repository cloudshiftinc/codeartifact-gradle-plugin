package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.cloudshiftdev.gradle.codeartifact.acquireToken
import io.cloudshiftdev.gradle.codeartifact.httpsProtocolUrl
import io.cloudshiftdev.gradle.codeartifact.proxyUrl
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

public class CodeArtifactResourceConnectorFactory(private val okHttpClient: OkHttpClient) :
    ResourceConnectorFactory {
    private val supportedProtocols = setOf("codeartifact")

    override fun getSupportedProtocols(): Set<String> = supportedProtocols

    override fun getSupportedAuthentication(): Set<Class<out Authentication>> = emptySet()

    override fun createResourceConnector(
        connectionDetails: ResourceConnectorSpecification
    ): ExternalResourceConnector {
        val accessor = CodeArtifactResourceAccessor(okHttpClient)
        return DefaultExternalResourceConnector(
            accessor,
            CodeArtifactResourceLister(accessor),
            CodeArtifactResourceUploader(okHttpClient),
        )
    }
}

private fun request(block: Request.Builder.() -> Unit): Request {
    return Request.Builder().apply(block).build()
}

/** See [org.gradle.internal.resource.transport.http.HttpResourceAccessor] */
private class CodeArtifactResourceAccessor(private val okHttpClient: OkHttpClient) :
    AbstractExternalResourceAccessor() {

    private companion object {
        private val UserAgent: String

        init {
            val osName = System.getProperty("os.name")
            val osVersion = System.getProperty("os.version")
            val osArch = System.getProperty("os.arch")
            val javaVendor = System.getProperty("java.vendor")
            val javaVersion = SystemProperties.getInstance().getJavaVersion()
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
    ): ExternalResourceReadResponse {
        val (url, request) = prepareGetRequest(location)
        val response = executeRequest(request)
        return OkHttpResponse(url, response)
    }

    override fun getMetaData(
        location: ExternalResourceName,
        revalidate: Boolean,
    ): ExternalResourceMetaData? {
        val (url, request) = prepareGetRequest(location)

        return executeRequest(request.newBuilder().head().build()).use { response ->
            if (response.code == 404) return null

            CodeArtifactMetadata(url, response)
        }
    }

    private fun prepareGetRequest(location: ExternalResourceName): Pair<HttpUrl, Request> {
        val endpoint =
            location.uri.toCodeArtifactEndpointOrNull()
                ?: error("Invalid CodeArtifact endpoint: ${location.uri}")

        val rawUrl =
            (endpoint.proxyUrl()?.resolve(location.uri.path) ?: location.uri).httpsProtocolUrl()

        val url = rawUrl.toHttpUrlOrNull() ?: error("Invalid URL: $rawUrl")

        val token = endpoint.acquireToken()
        val request = request {
            url(url)
            header("Authorization", "Bearer $token")
            header("User-Agent", UserAgent)
        }
        return Pair(url, request)
    }

    private fun executeRequest(request: Request): Response {
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) response.close()
        return response
    }

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
            val DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        }

        private val location = source.toUri()

        private val lastModified: Date =
            response.headers["Last-Modified"]?.let {
                try {
                    DateFormat.parse(it)
                } catch (_: Exception) {
                    ZeroDate
                }
            } ?: ZeroDate

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

        override fun getLocation(): URI? = location

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
                            "Unable to parse HTTP directory listing for '${directory.getUri()}'.",
                            e,
                        )
                    }
                }
            },
        )
    }
}

private class CodeArtifactResourceUploader(private val okHttpClient: OkHttpClient) :
    ExternalResourceUploader {
    override fun upload(resource: ReadableContent, destination: ExternalResourceName) {
        TODO("Not yet implemented")
    }
}
