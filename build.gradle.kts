import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = "chatbot-helper"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Compile against the local SLF4J JAR. We embed its classes manually below.
    compileOnly(files("slf4j-api-2.0.16.jar"))

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}

// Embed SLF4J classes into every JAR the project produces. This is what
// actually puts org/slf4j/*.class inside the final plugin JAR.
tasks.withType<Jar>().configureEach {
    if (name == "jar" || name == "instrumentedJar") {
        from({ zipTree(file("slf4j-api-2.0.16.jar")) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "200"
        }
    }
}

// Safety net: ensure the plugin's SLF4J wins over the IDE's bundled copy.
configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}

changelog {
    version.set("${project.version}")
    path.set("${project.projectDir}/CHANGELOG.md")
}