import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    idea
    application
    kotlin("jvm") version "2.4.0"
}

group = "dev.twelveoclock.lang"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.twelveoclock.lang.crescent.Main")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
