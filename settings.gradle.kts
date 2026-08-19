include(":core")
include(":bootstrap-standalone")
project(":bootstrap-standalone").projectDir = file("bootstrap/standalone")

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}