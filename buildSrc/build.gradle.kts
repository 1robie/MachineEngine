plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Mekanized import (MEKANIZED_PLAN.md Phase 0): reads Mekanism's JSON, writes YAML definitions.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.yaml:snakeyaml:2.3")
}
