package com.example.dayrecorder.recording

class BatteryPolicy(
  private val stopAtPercent: Int = RecorderContract.LOW_BATTERY_PERCENT,
) {
  fun shouldStop(capacityPercent: Int): Boolean =
    capacityPercent in 0..stopAtPercent
}
