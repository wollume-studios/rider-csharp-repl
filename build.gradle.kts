import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.19.0"     // See https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
}

val ProductVersion: String by project

repositories {
    maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

// Unit tests run without the IntelliJ Platform on the classpath (its bundled JUnit clashes and nothing here needs it).
val unitTestRuntime: Configuration by configurations.creating

val publishHost by tasks.registering(Exec::class) {
    description = "dotnet publish of the REPL host (framework-dependent) into build/host"
    group = "build"
    inputs.files(fileTree("src/host/CSharpRepl.Host") { exclude("bin/**", "obj/**") }).withPropertyName("hostSources")
    outputs.dir(layout.buildDirectory.dir("host"))
    commandLine("dotnet", "publish", "src/host/CSharpRepl.Host", "-c", "Release", "-o", "build/host")
}

val unitTest by tasks.registering(Test::class) {
    description = "Frontend unit tests (no IDE)"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + unitTestRuntime
    dependsOn(publishHost)
    systemProperty("csharprepl.host", layout.buildDirectory.file("host/CSharpRepl.Host.dll").get().asFile.absolutePath)
}

tasks.test {
    enabled = false // the IntelliJ test harness is not needed; see unitTest
    dependsOn(unitTest)
}

// The plugin version is parsed as System.Version by the Rider backend: at most 4 parts, each <= Int32.MaxValue.
version = (extra["PluginVersion"] as String).also { v ->
    val parts = v.split('.')
    require(parts.size <= 4 && parts.all { it.toLongOrNull()?.let { n -> n in 0..Int.MAX_VALUE } == true }) {
        "Plugin version '$v' is not a valid System.Version (max 4 numeric parts, each <= ${Int.MAX_VALUE})"
    }
}

sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
    test {
        kotlin.srcDir("src/rider/test/kotlin")
    }
}

dependencies {
    intellijPlatform {
        rider(ProductVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
    }
    // Gson ships with the platform (main must not bundle its own); the plain unit tests have no platform.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation(libs.kotlinStdLib)
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    unitTestRuntime(libs.kotlinStdLib)
    unitTestRuntime("com.google.code.gson:gson:2.11.0")
    unitTestRuntime("org.junit.jupiter:junit-jupiter:5.13.4")
    unitTestRuntime("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.runIde {
    // Match Rider's default heap size of 1.5Gb (default for runIde is 512Mb)
    maxHeapSize = "1500m"
    // ./gradlew runIde -PopenSolution=path/to/Some.sln
    val openSolution = providers.gradleProperty("openSolution")
    argumentProviders += CommandLineArgumentProvider { listOfNotNull(openSolution.orNull?.let { file(it).absolutePath }) }
    // Passing a solution explicitly means trusting it: skip the sandbox's modal "Trust solution" dialog.
    jvmArgumentProviders += CommandLineArgumentProvider { if (openSolution.isPresent) listOf("-Didea.trust.all.projects=true") else emptyList() }
}

tasks.patchPluginXml {
    sinceBuild.set("263")
    untilBuild.set(provider { null })
}

// All sandbox tasks (buildPlugin uses prepareSandbox, runIde uses prepareSandbox_runIde): ship the published host.
tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(publishHost)
    from(layout.buildDirectory.dir("host")) { into("${rootProject.name}/host") }
}

// JetBrains Marketplace: PUBLISH_TOKEN=<token> ./gradlew publishPlugin [-PPublishChannel=eap]
// The first upload of a new plugin must be done manually on plugins.jetbrains.com.
intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("PublishChannel").map { listOf(it) }.orElse(listOf("eap"))
    }
}
