package io.cloudshiftdev.gradle.codeartifact.token

import io.cloudshiftdev.gradle.codeartifact.codeArtifactToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class PersistentCacheTokenResolverTest : FunSpec() {
    init {
        test("cache works") {
            val token = codeArtifactToken()

            val delegate = mockk<TokenResolver>()
            coEvery { delegate.resolve(token.endpoint) } returns token

            val cache = PersistentCacheTokenResolver(delegate, tempdir())

            cache.resolve(token.endpoint) shouldBe token

            repeat(10) { cache.resolve(token.endpoint) }

            coVerify(exactly = 1) { delegate.resolve(token.endpoint) }
        }

        test("cache returns new token when expired") {
            val token = codeArtifactToken()
            val expiredToken = codeArtifactToken(expired = true)

            val delegate = mockk<TokenResolver>()
            coEvery { delegate.resolve(token.endpoint) } returns expiredToken

            val cache = PersistentCacheTokenResolver(delegate, tempdir())

            repeat(10) { cache.resolve(token.endpoint) }

            coVerify(exactly = 10) { delegate.resolve(token.endpoint) }
        }
    }
}
