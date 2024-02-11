package io.cloudshiftdev.gradle.codeartifact.codeartifact

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProviderConfigurationException
import aws.sdk.kotlin.services.codeartifact.CodeartifactClient
import aws.sdk.kotlin.services.codeartifact.getAuthorizationToken
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.time.toJvmInstant
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import net.pearx.kasechange.toScreamingSnakeCase
import org.gradle.api.logging.Logging as GradleLogging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

internal abstract class CodeArtifactTokenProvider : BuildService<CodeArtifactTokenProvider.Params> {
  private val logger = GradleLogging.getLogger(CodeArtifactTokenProvider::class.java)

  interface Params : BuildServiceParameters

  private val localCache = LocalCache()

  private val tokenCache: ConcurrentMap<String, CodeArtifactToken> = ConcurrentHashMap()

  fun tokenForEndpoint(endpoint: CodeArtifactEndpoint): String {
    return tokenCache
        .computeIfAbsent(endpoint.cacheKey) {
          localCache.get(endpoint) {
            val queryParameters = endpoint.url.queryParameters()
            val codeArtifact = CodeartifactClient {
              this.region = endpoint.region
              this.credentialsProvider = buildCredentialsProvider(queryParameters)
            }

            runBlocking {
              logger.lifecycle("Fetching CodeArtifact token for {}", endpoint.cacheKey)
              codeArtifact
                  .getAuthorizationToken {
                    domain = endpoint.domain
                    domainOwner = endpoint.domainOwner
                    durationSeconds = 12.hours.inWholeSeconds
                  }
                  .let {
                    CodeArtifactToken(
                        endpoint = endpoint,
                        value = it.authorizationToken!!,
                        expiration = it.expiration?.toJvmInstant()!!)
                  }
            }
          }
        }
        .value
  }

  private fun buildCredentialsProvider(queryParameters: Map<String, String>): CredentialsProvider {

    val profileKey = "codeartifact.profile"

    val providers =
        listOfNotNull<CredentialsProvider>(
            (queryParameters[profileKey] ?: resolveEnvVar(profileKey))?.let {
              ProfileCredentialsProvider(profileName = it)
            },
            CodeArtifactEnvironmentCredentialsProvider(),

            // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
            DefaultChainCredentialsProvider())

    return CredentialsProviderChain(providers)
  }

  private fun URI.queryParameters() =
      query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
      } ?: emptyMap()
}

private fun resolveEnvVar(key: String): String? {
  return System.getProperty(key)?.takeIf(String::isNotBlank)
      ?: System.getenv(key.toScreamingSnakeCase())?.takeIf(String::isNotBlank)
}

private class CodeArtifactEnvironmentCredentialsProvider : CredentialsProvider {
  private fun requireEnv(variable: String): String =
      resolveEnvVar(variable)
          ?: throw ProviderConfigurationException(
              "Missing value for environment variable `$variable`")

  override suspend fun resolve(attributes: Attributes): Credentials {
    return Credentials(
        accessKeyId = requireEnv("codeartifact.aws.accessKeyId"),
        secretAccessKey = requireEnv("codeartifact.aws.secretAccessKey"),
        sessionToken = resolveEnvVar("codeartifact.aws.sessionToken"))
  }
}
