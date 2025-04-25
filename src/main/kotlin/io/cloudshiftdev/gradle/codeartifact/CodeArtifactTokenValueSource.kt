package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.service.DefaultCodeArtifactService
import io.cloudshiftdev.gradle.codeartifact.token.DefaultTokenResolver
import io.cloudshiftdev.gradle.codeartifact.token.TokenResolver
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.of

internal abstract class CodeArtifactTokenValueSource :
    ValueSource<String, CodeArtifactTokenValueSource.Parameters> {
    private val tokenResolver: TokenResolver = DefaultTokenResolver(DefaultCodeArtifactService())

    interface Parameters : ValueSourceParameters {
        val endpoint: Property<CodeArtifactEndpoint>
    }

    override fun obtain(): String = tokenResolver.resolve(parameters.endpoint.get()).value
}

public fun ProviderFactory.codeArtifactToken(endpoint: CodeArtifactEndpoint): Provider<String> {
    return of(CodeArtifactTokenValueSource::class) { parameters { this.endpoint = endpoint } }
}
