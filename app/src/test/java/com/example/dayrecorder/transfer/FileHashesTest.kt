package com.example.dayrecorder.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FileHashesTest {
  @Test
  fun hashesFileContentsWithSha256() {
    val file = File.createTempFile("day-recorder", ".m4a")
    try {
      file.writeText("abc")
      assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        FileHashes.sha256(file),
      )
    } finally {
      file.delete()
    }
  }
}
