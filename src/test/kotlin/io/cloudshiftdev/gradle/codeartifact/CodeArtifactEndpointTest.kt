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
    }
}
