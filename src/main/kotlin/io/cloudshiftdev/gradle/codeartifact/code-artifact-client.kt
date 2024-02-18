package io.cloudshiftdev.gradle.codeartifact

import aws.sdk.kotlin.runtime.auth.credentials.AssumeRoleParameters
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProviderConfigurationException
import aws.sdk.kotlin.runtime.auth.credentials.StsAssumeRoleCredentialsProvider
import aws.sdk.kotlin.services.codeartifact.CodeartifactClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CachedCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningAttributes
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest

internal fun codeArtifactClient(endpoint: CodeArtifactEndpoint): CodeartifactClient {
    return CodeartifactClient {
        region = endpoint.region
        credentialsProvider = buildCredentialsProvider(endpoint.url.queryParameters())
    }
}

internal fun buildCredentialsProvider(queryParameters: Map<String, String>): CredentialsProvider {
    val profileKey = "codeartifact.profile"

    val providers =
        listOfNotNull(
            (queryParameters[profileKey] ?: resolveSystemVar(profileKey))?.let {
                ProfileCredentialsProvider(profileName = it)
            },
            CodeArtifactEnvironmentCredentialsProvider(),

            // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
            DefaultChainCredentialsProvider(),
        )

    val bootstrapProviders = CredentialsProviderChain(providers)
    val stsRoleArnKey = "codeartifact.stsRoleArn"

    val provider =
        resolveSystemVar(stsRoleArnKey)?.let { roleArn ->
            StsAssumeRoleCredentialsProvider(
                bootstrapCredentialsProvider = bootstrapProviders,
                assumeRoleParameters =
                    AssumeRoleParameters(
                        roleArn = roleArn,
                        roleSessionName = "codeartifact-client",

                        // scope down the policy so this client can *only* do CodeArtifact
                        // actions, regardless of
                        // what the underlying policy allows
                        policy =
                            """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Action": "codeartifact:*",
                              "Resource": "*"
                            },
                            {
                              "Effect": "Allow",
                              "Action": "sts:GetServiceBearerToken",
                              "Resource": "*"                           
                            }
                          ]
                        }
                    """
                                .trimIndent(),
                    ),
            )
        } ?: bootstrapProviders

    return CachedCredentialsProvider(provider)
}

private class CodeArtifactEnvironmentCredentialsProvider : CredentialsProvider {
    private fun requireEnv(variable: String): String =
        resolveSystemVar(variable)
            ?: throw ProviderConfigurationException(
                "Missing value for environment variable `$variable`",
            )

    override suspend fun resolve(attributes: Attributes): Credentials {
        return Credentials(
            accessKeyId = requireEnv("codeartifact.accessKeyId"),
            secretAccessKey = requireEnv("codeartifact.secretAccessKey"),
            sessionToken = resolveSystemVar("codeartifact.sessionToken"),
        )
    }
}

// workaround for https://github.com/awslabs/aws-sdk-kotlin/issues/1217; remove once issue addressed
internal class PrecomputedHashInterceptor(private val hash: String) : HttpInterceptor {
    override suspend fun modifyBeforeRetryLoop(
        context: ProtocolRequestInterceptorContext<Any, HttpRequest>
    ): HttpRequest {
        context.executionContext[AwsSigningAttributes.HashSpecification] =
            HashSpecification.Precalculated(hash)
        return context.protocolRequest
    }
}
