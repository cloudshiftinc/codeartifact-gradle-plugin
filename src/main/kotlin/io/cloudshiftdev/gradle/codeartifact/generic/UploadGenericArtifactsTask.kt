package io.cloudshiftdev.gradle.codeartifact.generic

import aws.sdk.kotlin.services.codeartifact.model.PackageFormat
import aws.sdk.kotlin.services.codeartifact.publishPackageVersion
import aws.sdk.kotlin.services.codeartifact.withConfig
import aws.smithy.kotlin.runtime.content.asByteStream
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.PrecomputedHashInterceptor
import io.cloudshiftdev.gradle.codeartifact.codeArtifactClient
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "This task runs infrequently and deals with potentially large files")
public abstract class UploadGenericArtifactsTask : DefaultTask() {

    @get:Input public abstract val repository: Property<String>

    @get:Input public abstract val packageName: Property<String>

    @get:Input public abstract val packageNamespace: Property<String>

    @get:Input public abstract val packageVersion: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract var artifacts: FileCollection

    @TaskAction
    public fun uploadArtifacts() {
        val genericPackage =
            GenericPackage(
                packageName.get(),
                packageNamespace.get(),
                packageVersion.get(),
                artifacts.map { GenericPackage.Asset(it.name, it) },
            )

        uploadGenericPackage(
            genericPackage,
            repository.get().toCodeArtifactEndpoint()
                ?: error("Invalid endpoint : ${repository.get()}"),
            logger
        )
    }
}

internal fun uploadGenericPackage(
    genericPackage: GenericPackage,
    endpoint: CodeArtifactEndpoint,
    logger: Logger
) {
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
