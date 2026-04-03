plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.9.21"
  id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.ghostline"
version = "0.1.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.10.1")
}

intellij {
  version.set("2025.1")
  type.set("IC") // IntelliJ IDEA Community
  plugins.set(listOf())
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }
  patchPluginXml {
    sinceBuild.set("233")
    // untilBuild not set — compatible with all future IntelliJ versions
  }
}
