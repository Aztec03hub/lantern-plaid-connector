import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

val kotlinVersion: String by project
val kotlinCoroutinesVersion: String by project
val exposedVersion: String by project
val ktorVersion: String by project
val jacksonVersion: String by project

plugins {
    id("org.openapi.generator") version "7.14.0"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.6"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

group = "net.djvk"
version = "1.5.1"
java.sourceCompatibility = JavaVersion.VERSION_17

// Generates META-INF/build-info.properties so the app can report its own version (see WebhookService)
springBoot {
    buildInfo()
}

// Newer than the versions Spring Boot's BOM manages; these carry fixes for published advisories (found with an
//  OSV scan of the runtime classpath). Remove an override once Boot's own BOM catches up.
extra["jackson-bom.version"] = "2.22.3"
extra["log4j2.version"] = "2.26.1"
extra["kotlin-coroutines.version"] = "1.11.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.openapi)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.logging)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.semver4j:semver4j:5.8.0")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.mock)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

var generatePlaidClient = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generatePlaidClient") {
    generatorName.set("kotlin")
    inputSpec.set(layout.projectDirectory.dir("specs").file("plaid-2020-09-14.yml").toString())
    cleanupOutput.set(true)
    outputDir.set(layout.buildDirectory.dir("generated-plaid").get().toString())
    apiPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.apis")
    invokerPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.invoker")
    modelPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.models")
    globalProperties.put("modelDocs", "false")
    globalProperties.put("apiDocs", "false")
    globalProperties.put("modelTests", "false")
    globalProperties.put("apiTests", "false")
    configOptions.put("groupId", "net.djvk")
    configOptions.put("packageName", "net.djvk.fireflyPlaidConnector2.api.plaid")
    configOptions.put("library", "jvm-ktor")
    configOptions.put("dateLibrary", "java8")
    configOptions.put("serializationLibrary", "jackson")
    configOptions.put("additionalModelTypeAnnotations", "@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)")
    configOptions.put("requestDateConverter", "toString")
    configOptions.put("typeMappings", "BigDecimal=Double")
    configOptions.put("omitGradleWrapper", "true")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(Paths.get(generatePlaidClient.get().outputDir.get(), "src", "main", "kotlin"))
        }
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    dependsOn(generatePlaidClient)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// verifyTestReport (U39): every @Test method must show up in the XML report (a skipped one counts), and the
// skips are printed by reason even on a green build. Keep the regexes in step with TestReportCheckTest.kt.
val verifyTestReport = tasks.register("verifyTestReport") {
    val testTask = tasks.named<Test>("test")
    val partial = provider { (testTask.get().filter as org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter).let { it.includePatterns.isNotEmpty() || it.commandLineIncludePatterns.isNotEmpty() } }
    val srcDir = layout.projectDirectory.dir("src/test/kotlin")
    val xmlDir = layout.buildDirectory.dir("test-results/test")
    inputs.dir(srcDir)
    inputs.dir(xmlDir).optional()
    outputs.upToDateWhen { false }
    mustRunAfter(testTask)
    doLast {
        if (partial.get()) {
            logger.lifecycle("verifyTestReport: partial run (--tests filter), count check skipped")
            return@doLast
        }
        val annotated = Regex("""(?m)^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b(?:\([^)]*\))?(?:\s*@\w+(?:\([^)]*\))?)*\s*(?:(?:internal|private|public|override)\s+)*fun\s+(`[^`]+`|\w+)""")
        val testcase = Regex("""<testcase\s+name="([^"]*)"\s+classname="([^"]*)"[^>]*?(/>|>)""")
        val skippedTag = Regex("""<skipped(?:\s+message="([^"]*)")?""")
        val want = sortedSetOf<String>()
        srcDir.asFile.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            // parameterized names are display names, so those only need their class in the report ("Class.*")
            annotated.findAll(f.readText()).forEach { want += f.nameWithoutExtension + "." + (if (it.groupValues[1] == "Test") it.groupValues[2].trim('`') else "*") }
        }
        val have = mutableSetOf<String>()
        val haveClasses = mutableSetOf<String>()
        val skips = sortedMapOf<String, Int>()
        xmlDir.get().asFile.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { x ->
            val text = x.readText()
            testcase.findAll(text).forEach {
                val cls = it.groupValues[2].substringAfterLast('.').substringBefore('$')
                haveClasses += cls + ".*"
                have += cls + "." + it.groupValues[1].replace(Regex("""\[\d+]$"""), "").substringBefore('(')
            }
            skippedTag.findAll(text).forEach { skips.merge(it.groupValues[1].ifEmpty { "(no message)" }, 1, Int::plus) }
        }
        val missing = want - have - haveClasses
        logger.lifecycle("verifyTestReport: ${want.size} annotated, ${want.size - missing.size} executed or skipped, ${missing.size} missing")
        logger.lifecycle("verifyTestReport: skipped ${skips.values.sum()}")
        skips.forEach { (m, n) -> logger.lifecycle("  %3d  %s".format(n, m)) }
        if (skips.keys.any { it.contains("ACCEPTANCE GATE SKIPPED") }) {
            logger.lifecycle("*** pairing acceptance gate did not run: see docs/handoff/U39-report.md for the one command ***")
        }
        if (missing.isNotEmpty()) throw GradleException("annotated tests missing from the report: $missing")
    }
}
tasks.test { finalizedBy(verifyTestReport) }
