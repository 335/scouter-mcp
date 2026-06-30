plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.0"
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
    // 우리 Tools.java JSON 렌더링은 버전을 직접 통제하기 위해 Jackson 2.x를 명시적으로 사용한다.
    // (mcp 번들은 tools.jackson 패키지의 Jackson 3을 노출하므로 그것에 의존하지 않는다)
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
    // distribution/jar 실행 경로에서도 slf4j-simple 로그를 stderr로 라우팅한다(stdout은 JSON-RPC 전용)
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.logFile=System.err")
}

tasks.test {
    useJUnitPlatform()
    // 스모크 테스트용 시스템 프로퍼티를 테스트 JVM 으로 포워딩한다.
    systemProperty("SCOUTER_SMOKE_OBJ_TYPE", System.getProperty("SCOUTER_SMOKE_OBJ_TYPE", ""))
}

// stdio MCP: slf4j-simple 로그는 반드시 stderr로만 (stdout은 JSON-RPC 전용)
tasks.named<JavaExec>("run") {
    systemProperty("org.slf4j.simpleLogger.logFile", "System.err")
    standardInput = System.`in`
}

// fat jar: build/libs/scouter-mcp-0.1.0-all.jar (Main-Class 매니페스트 포함)
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "scouter.mcp.McpMain"
    }
}
