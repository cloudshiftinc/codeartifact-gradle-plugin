package io.cloudshiftdev.gradle.codeartifact

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class CodeArtifactTokenTest : FunSpec() {

    init {
        val endpoint =
            "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data"
                .toCodeArtifactEndpoint()

        test("serialization works") {
            val token = CodeArtifactToken(endpoint, "my_token", Instant.now().plusSeconds(3600))

            val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

            val json = mapper.writeValueAsString(token)
            println(json)

            val unmarshalledToken = mapper.readValue<CodeArtifactToken>(json)

            unmarshalledToken shouldBe token
        }
    }
}
