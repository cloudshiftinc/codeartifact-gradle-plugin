package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * Keeps the plugin's dependency on Gradle internals contained: internal types may only be imported
 * by the `internal.gradle` seam, plus the resource connector SPI types listed below (the transport
 * extension point itself is internal, so those cannot be avoided). Anything else should go through
 * [GradleInternals].
 */
class InternalApiBoundaryTest :
    FunSpec({
        val sourceRoot = File("src/main/kotlin")
        val seamPackage = "io/cloudshiftdev/gradle/codeartifact/internal/gradle/"

        val allowedOutsideSeam =
            mapOf(
                "io/cloudshiftdev/gradle/codeartifact/resource/CodeArtifactResourceConnectorFactory.kt" to
                    setOf(
                        "org.gradle.internal.hash.HashCode",
                        "org.gradle.internal.resource.ExternalResource",
                        "org.gradle.internal.resource.ExternalResourceName",
                        "org.gradle.internal.resource.ReadableContent",
                        "org.gradle.internal.resource.connector.ResourceConnectorFactory",
                        "org.gradle.internal.resource.connector.ResourceConnectorSpecification",
                        "org.gradle.internal.resource.metadata.ExternalResourceMetaData",
                        "org.gradle.internal.resource.transfer.ExternalResourceAccessor",
                        "org.gradle.internal.resource.transfer.ExternalResourceConnector",
                        "org.gradle.internal.resource.transfer.ExternalResourceLister",
                        "org.gradle.internal.resource.transfer.ExternalResourceReadResponse",
                        "org.gradle.internal.resource.transfer.ExternalResourceUploader",
                        "org.gradle.internal.resource.transport.http.ApacheDirectoryListingParser",
                    )
            )

        val internalImport = Regex("""^import\s+(org\.gradle\.[\w.]*\binternal\b[\w.*]*)""")

        test("Gradle internal imports are confined to the seam and allowlisted SPI types") {
            val sources =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            sources.shouldNotBeEmpty()

            val violations = sources.flatMap { file ->
                val path = file.relativeTo(sourceRoot).invariantSeparatorsPath
                if (path.startsWith(seamPackage)) return@flatMap emptyList()
                val allowed = allowedOutsideSeam[path].orEmpty()
                file
                    .readLines()
                    .mapNotNull { internalImport.find(it.trim())?.groupValues?.get(1) }
                    .filterNot { it in allowed }
                    .map { "$path: $it" }
            }

            violations.shouldBeEmpty()
        }
    })
