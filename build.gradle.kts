import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
    doFirst { delete(layout.buildDirectory.dir("host")) } // stale files must never ship
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

// UI tests: a real Rider with the built plugin and samples/SampleSolution, driven through actions and IDE APIs (no OS
// mouse/keyboard, so they don't need focus). Rider from CSHARPREPL_RIDER, else %LOCALAPPDATA%\Programs\Rider.
val uiTest by tasks.registering(Test::class) {
    description = "UI tests in a real Rider (slow; see src/rider/uiTest)"
    group = "verification"
    val uiTestSources = sourceSets.getByName("uiTest")
    testClassesDirs = uiTestSources.output.classesDirs
    classpath = uiTestSources.runtimeClasspath
    // Starter brings the vintage engine without JUnit 4. The docs/screenshots PNGs (ScreenshotsUiTest) only with
    // -PuiTestScreenshots, and then only those.
    val screenshots = providers.gradleProperty("uiTestScreenshots").isPresent
    useJUnitPlatform {
        excludeEngines("junit-vintage")
        if (screenshots) includeTags("screenshots") else excludeTags("screenshots")
    }
    val prepareSandbox = tasks.named<PrepareSandboxTask>("prepareSandbox")
    dependsOn(prepareSandbox)
    val pluginDir = prepareSandbox.flatMap { it.pluginDirectory }
    val rider = providers.environmentVariable("CSHARPREPL_RIDER")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOfNotNull(
            "-Dcsharprepl.plugin=${pluginDir.get().asFile.absolutePath}",
            "-Dcsharprepl.sample=${file("samples/SampleSolution").absolutePath}",
            // Sample copies and their build output: on disk, not in a small /tmp RAM disk.
            "-Djava.io.tmpdir=${layout.buildDirectory.dir("ui-test-tmp").get().asFile.apply { mkdirs() }.absolutePath}",
            "-Dcsharprepl.screenshots=${layout.buildDirectory.dir("screenshots").get().asFile.absolutePath}",
            "-Dcsharprepl.docsScreenshots=${file("docs/screenshots").absolutePath}",
            rider.orNull?.let { "-Dcsharprepl.rider=$it" },
        )
    }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

tasks.test {
    enabled = false // the IntelliJ test harness is not needed; see unitTest
    dependsOn(unitTest)
}

// publishLocal stamps a build number so Rider sees every local build as an update.
val isLocalPublish = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "publishLocal" }
// The plugin version must parse as System.Version (as for a Rider backend): at most 4 parts, each <= Int32.MaxValue.
version = (extra["PluginVersion"] as String).let { base ->
    if (!isLocalPublish) base
    else base.split('.').take(2).joinToString(".") + "." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd.HHmm"))
}.also { v ->
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
    // UI tests: Starter launches a real Rider with the built plugin, Driver talks to it (see src/rider/uiTest).
    create("uiTest") {
        kotlin.srcDir("src/rider/uiTest/kotlin")
    }
}

val uiTestImplementation: Configuration by configurations.getting

dependencies {
    intellijPlatform {
        rider(ProductVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
        bundledModule("intellij.rider.rdclient.dotnet") // Rider's frontend project model: ProjectModelEntity, ProjectModelDataKeys
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

    intellijPlatform {
        // Pinned: the UI tests drive any Rider (CSHARPREPL_RIDER) with one Starter/Driver, whatever ProductVersion builds the plugin.
        testFramework(TestFrameworkType.Starter, version = "263.6800.24", configurationName = "uiTestImplementation")
    }
    uiTestImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    uiTestImplementation("org.junit.platform:junit-platform-launcher:1.13.4")
    uiTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    // Starter needs runBlockingK from JetBrains' coroutines fork: take the one this Rider ships, or with an older
    // ProductVersion the one of a 2026.3 Rider (-PuiTestCoroutinesJar=<rider>/lib/intellij.libraries.kotlinx.coroutines.core.jar).
    "uiTestCompileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    val coroutinesJar = providers.gradleProperty("uiTestCoroutinesJar")
    "uiTestRuntimeOnly"(if (coroutinesJar.isPresent) files(coroutinesJar.get()) else files(configurations.named("intellijPlatformClasspath").map { platform ->
        platform.filter { it.name == "intellij.libraries.kotlinx.coroutines.core.jar" }
    }))
}

// Local plugin repository, shared with LamaRider: Settings › Plugins › ⚙ › Manage Plugin Repositories… › add the printed
// updatePlugins.xml URL once. ./gradlew publishLocal [-PlocalRepoDir=<dir>]
val publishLocal by tasks.registering {
    group = "publishing"
    description = "Publish the plugin zip to the local plugin repository shared with LamaRider (updatePlugins.xml)"
    dependsOn(tasks.buildPlugin)
    val repoDir = providers.gradleProperty("localRepoDir")
        .orElse(System.getProperty("user.home") + "/.lamarider/plugin-repo")
    val zip = tasks.buildPlugin.flatMap { it.archiveFile }
    val pluginId = providers.gradleProperty("RiderPluginId").get()
    val pluginVersion = version.toString()
    doLast {
        val dir = file(repoDir.get()).apply { mkdirs() }
        val target = File(dir, zip.get().asFile.name)
        zip.get().asFile.copyTo(target, overwrite = true)
        // keep our three newest builds; other plugins' zips are theirs
        dir.listFiles { f -> f.name.startsWith("rider-csharp-repl-") && f.extension == "zip" }!!
            .sortedByDescending { it.lastModified() }.drop(3).forEach { it.delete() }
        // Merge: every other plugin's entry stays as it is, ours is replaced.
        val xml = File(dir, "updatePlugins.xml")
        val others = if (!xml.exists()) emptyList() else {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            val plugins = doc.documentElement.childNodes
            val serializer = javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
            }
            (0 until plugins.length).map { plugins.item(it) }
                .filterIsInstance<org.w3c.dom.Element>()
                .filter { it.tagName != "plugin" || it.getAttribute("id") != pluginId }
                .map { e -> StringWriter().also { serializer.transform(javax.xml.transform.dom.DOMSource(e), javax.xml.transform.stream.StreamResult(it)) }.toString() }
        }
        val ours = """
            |<plugin id="$pluginId" url="${target.toPath().toUri()}" version="$pluginVersion">
            |    <idea-version since-build="263"/>
            |    <name>C# REPL</name>
            |    <vendor>Wollume Studios</vendor>
            |    <description>C# REPL tool window for Rider (replacement for C# Interactive).</description>
            |  </plugin>""".trimMargin()
        xml.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugins>\n" + (others + ours).joinToString("") { "  $it\n" } + "</plugins>\n")
        println("C# REPL $pluginVersion published. Repository URL: ${xml.toPath().toUri()}")
    }
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

    // docs/plugin-description.html; images need public URLs (Marketplace, Settings › Plugins), e.g.
    // -PScreenshotBaseUrl=https://raw.githubusercontent.com/wollume-studios/rider-csharp-repl/main/docs/screenshots
    val screenshotBaseUrl = providers.gradleProperty("ScreenshotBaseUrl").orElse("")
    val descriptionFile = file("${rootDir}/docs/plugin-description.html")
    inputs.file(descriptionFile)
    pluginDescription.set(screenshotBaseUrl.map { base ->
        val html = descriptionFile.readText().replace(Regex("""(?s)<!--.*?-->\s*"""), "")
        if (base.isBlank()) html.replace(Regex("""<p><img [^>]*></p>\s*"""), "")
        else html.replace("{{screenshots}}", base.trimEnd('/'))
    })

    // The first section of CHANGELOG.md
    val changelogFile = file("${rootDir}/CHANGELOG.md")
    inputs.file(changelogFile)
    changeNotes.set(provider {
        Regex("(?s)(-.+?)(?=##|$)").find(changelogFile.readText())!!.groups[1]!!.value.replace(Regex("\r?\n"), "<br />\n")
    })
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
