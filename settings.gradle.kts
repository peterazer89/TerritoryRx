pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

// Guard against KSP2 StandaloneAnalysisAPISession / BinaryFileTypeDecompilers teardown race on AWT-EventQueue-0
val kspAwtExceptionFilter = Thread.UncaughtExceptionHandler { thread, throwable ->
  val isKspAwtTeardownRace =
    thread.name.startsWith("AWT-EventQueue") &&
      (throwable.message?.contains("ksp.com.intellij.openapi.application.Application") == true ||
        throwable.stackTrace.any { it.className.startsWith("ksp.com.intellij.") })
  if (!isKspAwtTeardownRace) {
    System.err.print("Exception in thread \"${thread.name}\" ")
    throwable.printStackTrace(System.err)
  }
}
Thread.setDefaultUncaughtExceptionHandler(kspAwtExceptionFilter)
Thread.getAllStackTraces().keys
  .filter { it.name.startsWith("AWT-EventQueue") }
  .forEach { it.uncaughtExceptionHandler = kspAwtExceptionFilter }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "TerritoryRx"

include(":app")
