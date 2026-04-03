package com.ghostline

import java.util.concurrent.atomic.AtomicBoolean

object AiAcceptanceFlag {
  private val flag = AtomicBoolean(false)

  fun set() = flag.set(true)

  fun isPending() = flag.get()

  fun consume() = flag.set(false)
}
