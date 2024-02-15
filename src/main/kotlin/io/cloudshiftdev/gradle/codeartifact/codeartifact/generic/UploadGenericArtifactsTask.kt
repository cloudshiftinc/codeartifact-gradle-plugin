package io.cloudshiftdev.gradle.codeartifact.codeartifact.generic

import aws.sdk.kotlin.services.codeartifact.model.PackageFormat
import aws.sdk.kotlin.services.codeartifact.publishPackageVersion
import aws.smithy.kotlin.runtime.content.asByteStream
import io.cloudshiftdev.gradle.codeartifact.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.codeartifact.codeArtifactClient
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
        )
    }
}

internal fun uploadGenericPackage(genericPackage: GenericPackage, endpoint: CodeArtifactEndpoint) {
    codeArtifactClient(endpoint).use { codeArtifact ->
        genericPackage.assets.forEachIndexed() { idx: Int, asset ->
            val isLastElement = idx == genericPackage.assets.size - 1
            runBlocking {
                codeArtifact.publishPackageVersion {
                    domain = endpoint.domain
                    domainOwner = endpoint.domainOwner
                    repository = endpoint.repository
                    namespace = genericPackage.namespace
                    format = PackageFormat.Generic
                    `package` = genericPackage.name
                    packageVersion = genericPackage.version
                    assetSha256 = asset.sha256()
                    assetName = asset.name
                    assetContent = asset.content.asByteStream()
                    unfinished = !isLastElement
                }
            }
        }
    }
}
