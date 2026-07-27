import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import org.jetbrains.intellij.platform.gradle.utils.asPath
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

group = "org.arend.lang"
version = "1.11.0.3"

val baseName = "intellij-arend"

plugins {
    idea
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.18.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":base"))
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.scilab.forge:jlatexmath:1.0.7")
    implementation("com.github.vlsi.mxgraph:jgraphx:4.2.2")
    implementation("com.fifesoft:rsyntaxtextarea:3.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.6.1")
    testImplementation("junit:junit:4.13.1")
    implementation("org.apache.xmlgraphics:batik-svggen:1.19")
    implementation("org.apache.xmlgraphics:batik-dom:1.19")

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdea, "2026.2")
        bundledPlugins("com.intellij.modules.json", "org.jetbrains.plugins.yaml", "com.intellij.java", "com.intellij.modules.jcef")
        testBundledModules("intellij.platform.navbar", "intellij.platform.navbar.backend")
        plugins("IdeaVIM:2.28.0", "com.jetbrains.edu:2026.6-2026.2-98")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

/*
tasks["jar"].dependsOn(
        task(":api:jar"),
        task(":proto:jar"),
        task(":base:jar")
)
*/

val generated = arrayOf("src/main/doc-lexer", "src/main/lexer", "src/main/parser")

sourceSets {
    main {
        java.srcDirs(*generated)
    }
}

idea {
    module {
        generatedSourceDirs.addAll(generated.map(::file))
        outputDir = file("${layout.buildDirectory}/classes/main")
        testOutputDir = file("${layout.buildDirectory}/classes/test")
    }
}

tasks {
    val test by getting(Test::class) {
        isScanForTestClasses = false
        // Only run tests from classes that end with "Test"
        include("**/*Test.class")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Arend"
    }
    instrumentCode = true
}

configurations.all {
    exclude("xml-apis", "xml-apis")
}

tasks.named<JavaExec>("runIde") {
    jvmArgs = listOf("-Xmx4g")
}

tasks.withType<PatchPluginXmlTask>().configureEach {
    version = project.version.toString()
    pluginId.set(project.group.toString())
    changeNotes.set(file("src/main/html/change-notes.html").readText())
    pluginDescription.set(file("src/main/html/description.html").readText())
}

tasks.withType<BuildPluginTask>().configureEach {
  archiveBaseName = baseName
}

val generateArendLexer = tasks.register<GenerateLexerTask>("genArendLexer") {
    description = "Generates lexer"
    group = "build setup"
    sourceFile.set(file("src/main/grammars/ArendLexer.flex"))
    targetOutputDir.set(file("src/main/lexer/org/arend/lexer"))
    purgeOldFiles.set(true)
}

val generateArendParser = tasks.register<GenerateParserTask>("genArendParser") {
    description = "Generates parser"
    group = "build setup"
    sourceFile.set(file("src/main/grammars/ArendParser.bnf"))
    targetRootOutputDir.set(file("src/main/parser"))
    pathToParser.set("/org/arend/parser/ArendParser.java")
    pathToPsiRoot.set("/org/arend/psi")
    purgeOldFiles.set(true)
}

val generateArendDocLexer = tasks.register<GenerateLexerTask>("genArendDocLexer") {
    description = "Generates doc lexer"
    group = "build setup"
    sourceFile.set(file("src/main/grammars/ArendDocLexer.flex"))
    targetOutputDir.set(file("src/main/doc-lexer/org/arend/lexer"))
    purgeOldFiles.set(true)
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(generateArendLexer, generateArendParser, generateArendDocLexer)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.set(listOf("-Xjvm-default=all"))
    }
    dependsOn(generateArendLexer, generateArendParser, generateArendDocLexer)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2048m"
    testLogging {
        if (prop("showTestStatus") == "true") {
            events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        }
        exceptionFormat = TestExceptionFormat.FULL
    }
    // The JetBrains Academy plugin (a hard dependency of ours, see plugin.xml) ships its own outdated copies
    // of the `ai.grazie.*` libraries. In the IDE every plugin gets its own class loader, but tests run from a
    // single flat class path where those jars come first and shadow the copies bundled with the Grazie plugin.
    // Grazie's spell checker then dies with `NoSuchMethodError: ai.grazie.nlp.langs.Language.getEntries()`,
    // failing every test that reaches a name suggestion provider (e.g. the inplace renamer started by the
    // generate-function intentions). Keep the Academy jars available, but let the IDE's own copies win.
    val isAcademyJar = { file: File -> file.path.contains("JetBrainsAcademy") }
    classpath = classpath.filter { !isAcademyJar(it) } + classpath.filter(isAcademyJar)
}

// Keep the expensive whole-standard-library formatter stress test out of the normal suite; it runs
// only through its own `formatterStressTest` task below (see ArendLibReformatStressTest).
tasks.named<Test>("test") {
    exclude("**/ArendLibReformatStressTest*")
}

// Dedicated task for the whole-standard-library formatter stress test, analogous to the root
// project's `roundTripTest`. It clones the platform configuration of the default `test` task (test
// framework classpath, sandbox JVM arguments and its task dependencies), then narrows it to the
// stress test, builds arend-lib's StdExtension (loaded at runtime so metas resolve) and gives the
// JVM enough heap to resolve+reformat all of arend-lib. Run with:
//   ./gradlew :intellij:formatterStressTest
tasks.register<Test>("formatterStressTest") {
    val defaultTest = tasks.named<Test>("test").get()
    group = "verification"
    description = "Reformats every arend-lib source file to surface formatter exceptions"
    testClassesDirs = defaultTest.testClassesDirs
    classpath = defaultTest.classpath
    jvmArgumentProviders.addAll(defaultTest.jvmArgumentProviders)
    systemProperties(defaultTest.systemProperties)
    dependsOn(defaultTest.dependsOn)
    dependsOn(":arend-lib:meta:classes")
    maxHeapSize = "6g"
    filter { includeTestsMatching("org.arend.formatting.ArendLibReformatStressTest") }
}

tasks.register<Copy>("prelude") {
    from(projectDir.resolve("lib/Prelude.ard"))
    into("src/main/resources/lib")
    // dependsOn(task(":cli:buildPrelude"))
}

tasks.withType<Wrapper> {
    gradleVersion = "9.6.1"
}

tasks.register<RunIdeTask>("generateArendLib") {
    systemProperty("java.awt.headless", true)
    dependsOn(tasks.prepareSandbox)

    val sandbox = tasks.runIde.get().sandboxDirectory.get()
    systemProperty("idea.plugins.path", sandbox.dir("plugins").asFile.absolutePath)

    splitMode.set(false)
    splitModeTarget.set(SplitModeAware.SplitModeTarget.BOTH)
    args = listOf("generateArendLib") +
            (project.findProperty("pathToArendLib") as? String ?: "") +
            (project.findProperty("pathToArendLibInArendSite") as? String ?: "") +
            (project.findProperty("versionArendLib") as? String ?: "null") +
            (project.findProperty("updateColorScheme") as? String ?: "") +
            layout.projectDirectory.asPath.toString() +
            (project.findProperty("classes") as? String ?: "")
}

// Utils

fun prop(name: String): Any? = extra.properties[name]
