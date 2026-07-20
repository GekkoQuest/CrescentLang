import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

plugins {
    idea
    application
    `maven-publish`
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

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Jar>("javadocJar") {
    description = "Assembles the maintained Crescent documentation artifact."
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("README.md", "LICENSE")
    from("docs") {
        into("docs")
    }
}

val publicationRepository = layout.buildDirectory.dir("repository")
val publicationGroup = group.toString()
val publicationVersion = version.toString()
val publicationCoordinates = "$publicationGroup:crescent-lang:$publicationVersion"
val distributionRootName = "$name-$publicationVersion"

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "crescent-lang"
            from(components["java"])

            pom {
                name.set("CrescentLang")
                description.set("An experimental Kotlin/JVM implementation of the Crescent programming language.")
                url.set("https://github.com/GekkoQuest/CrescentLang")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("GekkoQuest")
                        name.set("Camden")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/GekkoQuest/CrescentLang.git")
                    developerConnection.set("scm:git:ssh://git@github.com/GekkoQuest/CrescentLang.git")
                    url.set("https://github.com/GekkoQuest/CrescentLang")
                }
            }
        }
    }

    repositories {
        maven {
            name = "verification"
            url = uri(publicationRepository)
        }
    }
}

val verifyMavenPublicationContents = tasks.register("verifyMavenPublicationContents") {
    group = "verification"
    description = "Publishes locally and verifies the Maven artifacts and metadata."

    val publicationDirectory = publicationRepository.map {
        it.dir("dev/twelveoclock/lang/crescent-lang/$publicationVersion")
    }
    dependsOn("publishMavenPublicationToVerificationRepository")
    inputs.dir(publicationDirectory)

    doLast {
        val directory = publicationDirectory.get().asFile
        val artifactPrefix = "crescent-lang-$publicationVersion"
        val mainJar = directory.resolve("$artifactPrefix.jar")
        val sourcesJar = directory.resolve("$artifactPrefix-sources.jar")
        val documentationJar = directory.resolve("$artifactPrefix-javadoc.jar")
        val pomFile = directory.resolve("$artifactPrefix.pom")
        val moduleFile = directory.resolve("$artifactPrefix.module")

        listOf(mainJar, sourcesJar, documentationJar, pomFile, moduleFile).forEach { artifact ->
            check(artifact.isFile) { "Publication is missing ${artifact.name}" }
            check(artifact.length() > 0L) { "Published artifact ${artifact.name} is empty" }
        }

        val mainEntries = mutableSetOf<String>()
        zipTree(mainJar).visit {
            if (!isDirectory) mainEntries += relativePath.pathString
        }
        check("dev/twelveoclock/lang/crescent/Main.class" in mainEntries) {
            "Main artifact does not contain the Crescent entry point"
        }
        check("crescent/stdlib/modules.list" in mainEntries) {
            "Main artifact does not contain the active standard-library manifest"
        }

        val sourceEntries = mutableSetOf<String>()
        zipTree(sourcesJar).visit {
            if (!isDirectory) sourceEntries += relativePath.pathString
        }
        check("dev/twelveoclock/lang/crescent/Main.kt" in sourceEntries) {
            "Sources artifact does not contain the Crescent entry-point source"
        }

        val documentationEntries = mutableSetOf<String>()
        zipTree(documentationJar).visit {
            if (!isDirectory) documentationEntries += relativePath.pathString
        }
        setOf("README.md", "LICENSE", "docs/language-reference.md").forEach { required ->
            check(required in documentationEntries) {
                "Documentation artifact is missing $required"
            }
        }

        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val pom = documentBuilderFactory.newDocumentBuilder().parse(pomFile)
        val xpath = XPathFactory.newInstance().newXPath()
        fun pomValue(path: String): String = xpath.evaluate(path, pom).trim()
        fun projectValue(element: String): String = pomValue(
            "/*[local-name()='project']/*[local-name()='$element']/text()",
        )

        check(projectValue("groupId") == "dev.twelveoclock.lang")
        check(projectValue("artifactId") == "crescent-lang")
        check(projectValue("version") == publicationVersion)
        check(projectValue("name") == "CrescentLang")
        check(projectValue("description").isNotBlank())
        check(projectValue("url") == "https://github.com/GekkoQuest/CrescentLang")
        check(pomValue("//*[local-name()='license']/*[local-name()='name']/text()") == "MIT License")
        check(pomValue("//*[local-name()='license']/*[local-name()='url']/text()") ==
            "https://opensource.org/license/mit")
        check(pomValue("//*[local-name()='developer']/*[local-name()='id']/text()") == "GekkoQuest")
        check(pomValue("//*[local-name()='developer']/*[local-name()='name']/text()") == "Camden")
        check(pomValue("//*[local-name()='scm']/*[local-name()='connection']/text()") ==
            "scm:git:https://github.com/GekkoQuest/CrescentLang.git")
        check(pomValue("//*[local-name()='scm']/*[local-name()='developerConnection']/text()") ==
            "scm:git:ssh://git@github.com/GekkoQuest/CrescentLang.git")
        check(pomValue("//*[local-name()='dependency']/*[local-name()='artifactId']/text()") ==
            "kotlin-stdlib")

        val moduleText = moduleFile.readText()
        check("\"group\": \"dev.twelveoclock.lang\"" in moduleText)
        check("\"module\": \"crescent-lang\"" in moduleText)
        check("\"version\": \"$publicationVersion\"" in moduleText)
        check("\"org.gradle.docstype\": \"sources\"" in moduleText)
        check("\"org.gradle.docstype\": \"javadoc\"" in moduleText)
        listOf(
            "$artifactPrefix.jar",
            "$artifactPrefix-sources.jar",
            "$artifactPrefix-javadoc.jar",
        ).forEach { artifactName ->
            check("\"name\": \"$artifactName\"" in moduleText) {
                "Gradle module metadata does not describe $artifactName"
            }
        }
    }
}

val preparePublicationConsumer = tasks.register("preparePublicationConsumer") {
    group = "verification"
    description = "Creates a clean standalone Java consumer of the locally published Kotlin API."

    val consumerDirectory = layout.buildDirectory.dir("publication-consumer")
    outputs.dir(consumerDirectory)

    doLast {
        val directory = consumerDirectory.get().asFile
        directory.deleteRecursively()
        directory.resolve("src/main/java/consumer").mkdirs()
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"crescent-publication-consumer\"\n")
        directory.resolve("build.gradle.kts").writeText(
            """
            plugins { java }

            repositories {
                exclusiveContent {
                    forRepository {
                        maven {
                            name = "crescentVerification"
                            url = uri("${publicationRepository.get().asFile.toURI()}")
                        }
                    }
                    filter { includeGroup("dev.twelveoclock.lang") }
                }
                mavenCentral()
            }

            dependencies {
                implementation("$publicationCoordinates")
            }

            java {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            }
            """.trimIndent() + "\n",
        )
        directory.resolve("src/main/java/consumer/CrescentConsumer.java").writeText(
            """
            package consumer;

            import dev.twelveoclock.lang.crescent.iterator.PeekingCharIterator;

            public final class CrescentConsumer {
                public static boolean publicApiIsUsable() {
                    return new PeekingCharIterator("crescent").hasNext();
                }
            }
            """.trimIndent() + "\n",
        )
    }
}

val verifyMavenPublicationConsumer = tasks.register<GradleBuild>("verifyMavenPublicationConsumer") {
    group = "verification"
    description = "Resolves the local publication from a clean build and compiles against its public API."
    dependsOn(verifyMavenPublicationContents, preparePublicationConsumer)
    dir = layout.buildDirectory.dir("publication-consumer").get().asFile
    tasks = listOf("compileJava")
    startParameter.isOffline = gradle.startParameter.isOffline
}

val verifyMavenPublication = tasks.register("verifyMavenPublication") {
    group = "verification"
    description = "Verifies the isolated Maven publication and clean-consumer resolution."
    dependsOn(verifyMavenPublicationContents, verifyMavenPublicationConsumer)
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
        val distributionRoot = distributionRootName
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
        dependsOn(verifyDistributionContents, verifyMavenPublication)
    }
}
