package com.tonsai.thaihighlight

object CaptureState {
    @Volatile var running = false
    @Volatile var message = "ยังไม่เริ่มจับภาพ"
}
