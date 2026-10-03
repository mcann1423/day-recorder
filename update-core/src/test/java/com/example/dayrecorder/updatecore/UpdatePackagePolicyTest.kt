package com.example.dayrecorder.updatecore

import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePackagePolicyTest {
  @Test
  fun `accepts the expected newer package and signer`() {
    UpdatePackagePolicy.validate("app", 11, 10, setOf("signer"), "app", 11, setOf("signer"))
  }

  @Test
  fun `rejects the wrong package`() {
    assertFailure("package name") {
      UpdatePackagePolicy.validate("app", 11, 10, setOf("signer"), "other", 11, setOf("signer"))
    }
  }

  @Test
  fun `rejects a mismatched or non newer version`() {
    assertFailure("does not match") {
      UpdatePackagePolicy.validate("app", 11, 10, setOf("signer"), "app", 12, setOf("signer"))
    }
    assertFailure("not newer") {
      UpdatePackagePolicy.validate("app", 10, 10, setOf("signer"), "app", 10, setOf("signer"))
    }
  }

  @Test
  fun `rejects a different signer`() {
    assertFailure("not signed") {
      UpdatePackagePolicy.validate("app", 11, 10, setOf("signer"), "app", 11, setOf("other"))
    }
  }

  private fun assertFailure(message: String, block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    assertTrue(error is IllegalStateException)
    assertTrue(error?.message.orEmpty().contains(message))
  }
}
