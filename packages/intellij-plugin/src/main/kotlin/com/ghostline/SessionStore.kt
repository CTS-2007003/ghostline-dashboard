package com.ghostline

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service
class SessionStore {
  var totalLines: Int = 0
  var aiLines: Int = 0

  fun reset() {
    totalLines = 0
    aiLines = 0
  }

  companion object {
    fun getInstance(): SessionStore = service()
  }
}
