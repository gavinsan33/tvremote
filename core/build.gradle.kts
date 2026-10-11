plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Java 17 bytecode so the Android app can consume this module.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Ed25519/X25519/ChaCha20-Poly1305/HKDF that behave the same on the JVM and on
    // Android before API 33 (the platform lacks Ed25519/X25519 there).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
}

application { mainClass.set("tvremote.CliKt") }

tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }
