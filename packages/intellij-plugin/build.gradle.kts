plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.ghostline"
version = "0.2.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.10.1")
}

intellij {
  version.set("2023.1")   // build against 2023.1 — ensures no APIs newer than that slip in
  type.set("IC")
  plugins.set(listOf())
}

// Prevent Kotlin stdlib conflict with the version bundled in IntelliJ
configurations.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.jetbrains.kotlin") {
      useVersion("2.1.0")
    }
  }
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }
  patchPluginXml {
    sinceBuild.set("231")   // IntelliJ 2023.1+
    untilBuild.set("")      // no upper limit — works on 2023.x, 2024.x, 2025.x and beyond
  }
  // Not needed in CI — just indexes settings for the IDE search bar
  buildSearchableOptions {
    enabled = false
  }
}
