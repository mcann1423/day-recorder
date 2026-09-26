package com.example.dayrecorder.transfer

object TransferProtocol {
  const val AUDIO_PREFIX = "/day-recorder/audio/"
  const val ACK_PREFIX = "/day-recorder/ack/"
  const val KEY_ASSET = "audio"
  const val KEY_FILE_NAME = "file_name"
  const val KEY_SIZE = "size"
  const val KEY_SHA256 = "sha256"
  const val KEY_RECORDED_AT = "recorded_at"
  const val KEY_ACKNOWLEDGED_AT = "acknowledged_at"

  fun idFor(fileName: String): String = fileName.removeSuffix(".m4a")
}
