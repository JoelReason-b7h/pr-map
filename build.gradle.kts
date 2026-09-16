plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "dev.joelreason"
version = "0.1.0"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    intellijIdeaCommunity("2024.1")
    // Git4Idea is not used: the plugin shells out to git in the project root, so
    // it loads in any IDE that has a terminal, including Ultimate.
    instrumentationTools()
  }
}

// Everything targets JVM 17. sdkman has 17 and 25 and no 21, Gradle 8.10 refuses to run
// on 25, and a toolchain of 21 would try to download a JDK. Platform 2024.1 is the last
// line that runs on 17, and a plugin built against it still loads in a newer IDE.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
tasks.withType<JavaCompile> { options.release.set(17) }

intellijPlatform {
  pluginConfiguration {
    id = "dev.joelreason.prmap"
    name = "PR Map"
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "241"
      untilBuild = provider { null }   // keep loading on newer IDEs
    }
  }
}

// Runs the parsing and the drawing rules outside the IDE, so an empty diagram can be
// traced against a real topology file.
// The IDE supplies the Kotlin stdlib and Gson at runtime, and the plugin deliberately
// does not bundle them, so this task carries its own copies.
val diagnoseRuntime: Configuration by configurations.creating
dependencies {
  diagnoseRuntime("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
  diagnoseRuntime("com.google.code.gson:gson:2.11.0")
}
tasks.register<JavaExec>("diagnose") {
  mainClass.set("dev.joelreason.prmap.Diagnose")
  classpath = sourceSets["main"].output + diagnoseRuntime
  args(project.findProperty("map")?.toString() ?: "",
       project.findProperty("page")?.toString() ?: "")
}
