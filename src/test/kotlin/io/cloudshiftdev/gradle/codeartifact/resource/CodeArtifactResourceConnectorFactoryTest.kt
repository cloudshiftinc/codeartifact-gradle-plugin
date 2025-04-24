package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.DefaultProxyResolver
import io.cloudshiftdev.gradle.codeartifact.DefaultSystemVarResolver
import io.cloudshiftdev.gradle.codeartifact.codeArtifactToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient

class CodeArtifactResourceConnectorFactoryTest :
    FunSpec({
        val httpClient = OkHttpClient.Builder().build()
        val token = codeArtifactToken()
        val factory =
            CodeArtifactResourceConnectorFactory(
                httpClient,
                DefaultProxyResolver(DefaultSystemVarResolver()),
            ) {
                token
            }

        test("supported protocols works") {
            factory.supportedProtocols shouldBe setOf("codeartifact")
        }

        test("supported authentication works") {
            factory.supportedAuthentication shouldBe emptySet()
        }
    })
