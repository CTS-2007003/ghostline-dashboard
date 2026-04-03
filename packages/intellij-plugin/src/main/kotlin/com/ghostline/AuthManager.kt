package com.ghostline

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service
class AuthManager {
  private val credentialAttributes = CredentialAttributes("Ghostline GitHub Token")

  fun getToken(): String? {
    return PasswordSafe.instance.getPassword(credentialAttributes)
  }

  fun saveToken(token: String) {
    PasswordSafe.instance.set(credentialAttributes, Credentials("ghostline", token))
  }

  companion object {
    fun getInstance(): AuthManager = service()
  }
}
