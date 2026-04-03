package com.ghostline

import com.intellij.openapi.components.*

@State(name = "GhostlineSettings", storages = [Storage("ghostline.xml")])
@Service
class GhostlineSettings : PersistentStateComponent<GhostlineSettings.State> {
  data class State(
    var githubRepo: String = "",
    var githubUsername: String = "",
    var flushIntervalMinutes: Int = 5
  )

  private var state = State()

  var githubRepo: String
    get() = state.githubRepo
    set(v) { state.githubRepo = v }

  var githubUsername: String
    get() = state.githubUsername
    set(v) { state.githubUsername = v }

  var flushIntervalMinutes: Int
    get() = state.flushIntervalMinutes
    set(v) { state.flushIntervalMinutes = v }

  override fun getState() = state
  override fun loadState(s: State) { state = s }

  companion object {
    fun getInstance(): GhostlineSettings = service()
  }
}
