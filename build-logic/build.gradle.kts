plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Provides the typed Android LibraryExtension used by the convention plugin.
    implementation("com.android.tools.build:gradle:9.2.1")
}
