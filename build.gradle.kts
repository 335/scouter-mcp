plugins {
    java
    application
}

group = "scouter.mcp"
version = "0.1.0"

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

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
}

application {
    mainClass.set("scouter.mcp.McpMain")
}

tasks.test {
    useJUnitPlatform()
}

// stdio MCP: slf4j-simple 로그는 반드시 stderr로만 (stdout은 JSON-RPC 전용)
tasks.named<JavaExec>("run") {
    systemProperty("org.slf4j.simpleLogger.logFile", "System.err")
    standardInput = System.`in`
}
