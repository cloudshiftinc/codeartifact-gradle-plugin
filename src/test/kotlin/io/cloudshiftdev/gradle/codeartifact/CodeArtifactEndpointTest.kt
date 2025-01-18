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

class CodeArtifactEndpointTest : FunSpec() {
    init {
        val testData =
            listOf(
                "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data",
                "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data/",
                "https://mydomain-123456789012.d.codeartifact.us-east-1.amazonaws.com/maven/release",
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
            val endpoint = "https://my_domain-111122223333.d.codeartifact.us-west-2.amazonaws.com/npm/my_repo/".toCodeArtifactEndpoint()
            endpoint.domain.shouldBe("my_domain")
            endpoint.domainOwner.shouldBe("111122223333")
            endpoint.region.shouldBe("us-west-2")
            endpoint.repository.shouldBe("my_repo")
            endpoint.type.shouldBe("npm")
        }
    }
}
