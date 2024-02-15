package io.cloudshiftdev.gradle.codeartifact.codeartifact

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProviderConfigurationException
import aws.sdk.kotlin.services.codeartifact.CodeartifactClient
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.collections.Attributes

internal fun codeArtifactClient(endpoint: CodeArtifactEndpoint): CodeartifactClient {
    return CodeartifactClient {
        region = endpoint.region
        credentialsProvider = buildCredentialsProvider(endpoint.url.queryParameters())
    }
}

private fun buildCredentialsProvider(queryParameters: Map<String, String>): CredentialsProvider {
    val profileKey = "codeartifact.profile"
    val providers =
        listOfNotNull(
            (queryParameters[profileKey] ?: resolveSystemVar(profileKey))?.let {
                ProfileCredentialsProvider(profileName = it)
            },
            CodeArtifactEnvironmentCredentialsProvider(),

            // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
            DefaultChainCredentialsProvider()
        )

    return CredentialsProviderChain(providers)
}

private class CodeArtifactEnvironmentCredentialsProvider : CredentialsProvider {
    private fun requireEnv(variable: String): String =
        resolveSystemVar(variable)
            ?: throw ProviderConfigurationException(
                "Missing value for environment variable `$variable`"
            )

    override suspend fun resolve(attributes: Attributes): Credentials {
        return Credentials(
            accessKeyId = requireEnv("codeartifact.accessKeyId"),
            secretAccessKey = requireEnv("codeartifact.secretAccessKey"),
            sessionToken = resolveSystemVar("codeartifact.sessionToken")
        )
    }
}
