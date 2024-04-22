package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldNotBeNull
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
    }
}
