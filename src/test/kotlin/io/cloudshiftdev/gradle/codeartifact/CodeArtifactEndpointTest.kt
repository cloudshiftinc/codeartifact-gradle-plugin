package io.cloudshiftdev.gradle.codeartifact

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotBeEmpty
import java.net.URI

class CodeArtifactEndpointTest : FunSpec() {
    init {
        val testData =
            listOf(
                "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data",
                "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data/",
                "https://mydomain-123456789012.d.codeartifact.us-east-1.amazonaws.com/maven/release",
                "https://mydomain-123456789012.d.codeartifact.us-east-1.amazonaws.com:443/maven/release",
            )

        withData(testData) { url ->
            val endpoint = url.toCodeArtifactEndpointOrNull().shouldNotBeNull()
            val domainOwner = endpoint.domainOwner
            domainOwner.shouldNotBeEmpty().shouldHaveLength(12)
            domainOwner.shouldMatch("[0-9]+")
        }

        test("serialization/deserialization works") {
            val endpoint =
                "https://mydomain-123456789012.d.codeartifact.us-east-1.amazonaws.com/maven/release"
                    .toCodeArtifactEndpoint()
            val mapper = jacksonObjectMapper()

            val json = mapper.writeValueAsString(endpoint)

            val unmarshalledEndpoint = mapper.readValue<CodeArtifactEndpoint>(json)

            unmarshalledEndpoint.shouldBe(endpoint)
        }

        test("url parsing works") {
            val endpoint =
                "https://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                    .toCodeArtifactEndpoint()
            endpoint.domain.shouldBe("my_domain")
            endpoint.domainOwner.shouldBe("111122223333")
            endpoint.region.shouldBe("us-west-2")
            endpoint.repository.shouldBe("my_repo")
            endpoint.type.shouldBe("npm")
        }

        test("codeartifact protocol works") {
            val endpoint =
                "codeartifact://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                    .toCodeArtifactEndpoint()
            endpoint.url
                .toString()
                .shouldBe(
                    "https://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                )
        }

        test("toCodeArtifactProtocolUrl() works") {
            val endpoint = codeArtifactEndpoint()

            endpoint
                .toCodeArtifactProtocolUrl()
                .toString()
                .shouldBe(
                    "codeartifact://test-domain-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data"
                )
        }

        test("URI.toCodeArtifactEndpoint() works") {
            val uri =
                URI(
                    "https://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                )

            val endpoint = uri.toCodeArtifactEndpoint()

            endpoint.url shouldBe uri
            endpoint.domain shouldBe "my_domain"
            endpoint.domainOwner shouldBe "111122223333"
            endpoint.type shouldBe "npm"
            endpoint.region shouldBe "us-west-2"
            endpoint.name shouldBe "MyDomainMyRepo"
            endpoint.repository shouldBe "my_repo"
        }

        test("URI.toHttpsProtocolUrl() works") {
            val uri =
                URI(
                    "codeartifact://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                )

            uri.toHttpsProtocolUrl()
                .toString()
                .shouldBe(
                    "https://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/"
                )
        }
    }
}
