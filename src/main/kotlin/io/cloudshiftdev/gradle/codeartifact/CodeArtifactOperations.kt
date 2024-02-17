package io.cloudshiftdev.gradle.codeartifact

import aws.sdk.kotlin.services.codeartifact.model.PackageFormat
import aws.sdk.kotlin.services.codeartifact.publishPackageVersion
import aws.sdk.kotlin.services.codeartifact.withConfig
import aws.smithy.kotlin.runtime.content.asByteStream
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logging

public object CodeArtifactOperations {
    private val logger = Logging.getLogger(CodeArtifactOperations::class.java)

    public fun publishPackageVersion(
        genericPackage: GenericPackage,
        codeArtifactRepositoryUrl: String
    ) {
        val endpoint =
            codeArtifactRepositoryUrl.toCodeArtifactEndpoint()
                ?: error("Invalid endpoint : $codeArtifactRepositoryUrl")

        codeArtifactClient(endpoint).use { codeArtifact ->
            genericPackage.assets.forEachIndexed() { idx: Int, asset ->
                val isLastElement = idx == genericPackage.assets.size - 1
                runBlocking {
                    val (sha256, sha256Time) = measureTimedValue { asset.sha256() }
                    logger.lifecycle(
                        "Calculated SHA256 for asset '${asset.name}' in $sha256Time; $sha256"
                    )

                    val timeTaken = measureTime {
                        logger.lifecycle(
                            "Uploading CodeArtifact generic artifact asset '${asset.name}' (${genericPackage.namespace}/${genericPackage.name}/${genericPackage.version}) (size: ${asset.content.length()} to ${endpoint.url}"
                        )
                        // workaround for https://github.com/awslabs/aws-sdk-kotlin/issues/1217
                        codeArtifact
                            .withConfig { interceptors += PrecomputedHashInterceptor(sha256) }
                            .use {
                                it.publishPackageVersion {
                                    domain = endpoint.domain
                                    domainOwner = endpoint.domainOwner
                                    repository = endpoint.repository
                                    namespace = genericPackage.namespace
                                    format = PackageFormat.Generic
                                    `package` = genericPackage.name
                                    packageVersion = genericPackage.version
                                    assetSha256 = sha256
                                    assetName = asset.name
                                    assetContent = asset.content.asByteStream()
                                    unfinished = !isLastElement
                                }
                            }
                    }
                    logger.lifecycle("Uploaded ${asset.name} in $timeTaken")
                }
            }
        }
    }
}
