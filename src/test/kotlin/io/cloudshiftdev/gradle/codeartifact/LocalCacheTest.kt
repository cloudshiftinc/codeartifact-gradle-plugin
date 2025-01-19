package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.time.Instant

class LocalCacheTest : FunSpec() {
    init {
        val endpoint =
            "https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data"
                .toCodeArtifactEndpoint()
        test("cache works") {
            val cache = LocalCache(tempdir())
            val expectedToken =
                CodeArtifactToken(endpoint, "my_token", Instant.now().plusSeconds(3600))

            var cacheMisses = 0
            var token =
                cache.load(endpoint) {
                    cacheMisses++
                    expectedToken
                }

            cacheMisses shouldBe 1
            token shouldBe expectedToken
            token.expired shouldBe false

            token =
                cache.load(endpoint) {
                    cacheMisses++
                    expectedToken
                }

            cacheMisses shouldBe 1
            token shouldBe expectedToken
            token.expired shouldBe false
        }
    }
}
