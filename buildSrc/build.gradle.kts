plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Mekanized import (MEKANIZED_PLAN.md Phase 0): reads Mekanism's JSON, writes YAML definitions.
    implementation(libs.gson)
    implementation(libs.snakeyaml)
}
