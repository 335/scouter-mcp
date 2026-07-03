plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.0"
}

group = "scouter.mcp"
// Overridden by CI (tag release) via -PappVersion=<tag>; local default is 0.3.1.
version = (findProperty("appVersion") as String?) ?: "0.3.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.github.scouter-project:scouter-common:2.20.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    // Our Tools.java JSON rendering uses Jackson 2.x explicitly to control the version directly.
    // (The mcp bundle exposes Jackson 3 under the tools.jackson package; we do not depend on that.)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
}

application {
    mainClass.set("scouter.mcp.McpMain")
    // Route slf4j-simple logs to stderr even on the distribution/jar run path (stdout is JSON-RPC only)
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.logFile=System.err")
}

tasks.test {
    useJUnitPlatform()
    // Forward the smoke-test system property to the test JVM.
    systemProperty("SCOUTER_SMOKE_OBJ_TYPE", System.getProperty("SCOUTER_SMOKE_OBJ_TYPE", ""))
}

// stdio MCP: slf4j-simple logs must go to stderr only (stdout is JSON-RPC only)
tasks.named<JavaExec>("run") {
    systemProperty("org.slf4j.simpleLogger.logFile", "System.err")
    standardInput = System.`in`
}

// fat jar: build/libs/scouter-mcp-<version>-all.jar (includes the Main-Class manifest)
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "scouter.mcp.McpMain"
    }
}

// .mcpb bundle assembly. `version` (this file's single source of truth, overridable via
// -PappVersion) drives both the jar filename and the manifest's version field, so the two can
// never drift apart. Used by both local builds (`./gradlew mcpbPack`) and release CI.
val mcpbStageDir = layout.buildDirectory.dir("mcpb")

val prepareMcpbManifest by tasks.registering(Copy::class) {
    // Filter closures aren't tracked as task inputs by Gradle, so without this, switching
    // -PappVersion between runs would silently reuse a stale UP-TO-DATE copy of the manifest.
    inputs.property("mcpVersion", version.toString())
    from("mcpb/manifest.json")
    into(mcpbStageDir)
    // Regex on the "version" field itself, not a literal placeholder match: safe regardless of
    // whatever value is currently checked into manifest.json (kept in sync by the release-PR flow).
    filter { line -> line.replace(Regex("\"version\":\\s*\"[^\"]*\""), "\"version\": \"${version}\"") }
}

val prepareMcpbJar by tasks.registering(Copy::class) {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(mcpbStageDir)
    rename { "scouter-mcp.jar" }
}

tasks.register<Exec>("mcpbPack") {
    dependsOn(prepareMcpbManifest, prepareMcpbJar)
    val outputFile = layout.buildDirectory.file("libs/scouter-mcp-${version}.mcpb")
    outputs.file(outputFile)
    doFirst { outputFile.get().asFile.parentFile.mkdirs() }
    commandLine(
        "npx", "-y", "@anthropic-ai/mcpb", "pack",
        mcpbStageDir.get().asFile.absolutePath,
        outputFile.get().asFile.absolutePath,
    )
}
