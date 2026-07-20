import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    idea
    application
    kotlin("jvm") version "2.4.10"
}

group = "dev.twelveoclock.lang"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.twelveoclock.lang.crescent.Main")

    applicationDistribution.from("LICENSE", "README.md")
    applicationDistribution.from("docs") {
        into("docs")
    }
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

    processResources {
        exclude("**/*.unimplemented")
    }

    named<CreateStartScripts>("startScripts") {
        applicationName = "crescent"
    }

    val verifyDistributionContents = register("verifyDistributionContents") {
        group = "verification"
        description = "Verifies documentation is shipped and archived sketches are excluded."

        val distributionArchive = named<Zip>("distZip").flatMap { it.archiveFile }
        val distributionRoot = "${project.name}-${project.version}"
        dependsOn("distZip")
        inputs.file(distributionArchive)

        doLast {
            val entries = mutableSetOf<String>()
            zipTree(distributionArchive.get().asFile).visit {
                if (!isDirectory) entries += relativePath.pathString
            }

            listOf(
                "$distributionRoot/LICENSE",
                "$distributionRoot/README.md",
                "$distributionRoot/docs/language-reference.md",
            ).forEach { required ->
                check(required in entries) { "Distribution is missing $required" }
            }
            check(entries.none { it.endsWith(".unimplemented") }) {
                "Distribution contains archived .unimplemented resource sketches"
            }
        }
    }

    named("check") {
        dependsOn(verifyDistributionContents)
    }
}
