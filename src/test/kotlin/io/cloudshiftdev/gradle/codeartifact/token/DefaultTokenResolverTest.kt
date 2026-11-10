package io.cloudshiftdev.gradle.codeartifact.token

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

class DefaultTokenResolverTest :
    FunSpec({
        test("one resolver per Gradle user home") {
            val home1 = tempdir()
            val home2 = tempdir()

            DefaultTokenResolver.forGradleUserHome(home1) shouldBeSameInstanceAs
                DefaultTokenResolver.forGradleUserHome(home1.resolve(".").canonicalFile)
            DefaultTokenResolver.forGradleUserHome(home1) shouldNotBeSameInstanceAs
                DefaultTokenResolver.forGradleUserHome(home2)
        }
    })
