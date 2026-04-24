package com.ghostline

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service
class SessionStore {
  @Volatile var totalLines: Int = 0
  @Volatile var paused: Boolean = false

  fun reset() {
    totalLines = 0
  }

  companion object {
    fun getInstance(): SessionStore = service()
  }
}
