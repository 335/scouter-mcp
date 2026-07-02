# Scouter MCP Implementation Plan

> **상태: 구현 완료.** 코드가 최종 기준이다. 아래는 실행 중 발견된 계획서 정오(Errata):
> - **Task 3(TimeRange):** 테스트 상수 `1782486000000L`은 오류(2일 어긋남)였고 실제값 `1782658800000L`(=`2026-06-29T00:00:00+09:00`)로 정정함. 프로덕션 로직은 불변.
> - **Task 11(get_xlog_detail):** `sqls[]`에 `rows?` 미포함. 조회 실패 에러코드는 `INTERNAL`(초안의 PROTOCOL_MISMATCH 대신).
> - **공통:** 모든 도구 핸들러는 예외를 MCP tool-error(`isError=true`, `{code,message,hints}`)로 반환하고 `McpError.toLogLine()`으로 stderr 로깅한다. `txid`/`gxid` 입력 스키마는 64비트 정밀도 보존을 위해 `string`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI(MCP 클라이언트)가 Scouter Collector에 직접 TCP로 붙어 XLog/카운터/오브젝트를 조회하고, 결과에 상관키(txid/gxid/objName/epoch)를 실어 OpenSearch/Datadog 교차분석을 돕는 stdio MCP 서버를 만든다.

**Architecture:** Kotlin/Java 단일 jar. `io.github.scouter-project:scouter-common`(Pack 직렬화·RequestCmd·MapPack 등 프로토콜 1차 타입)을 의존성으로 쓰고, `scouter.webapp`의 헤드리스 TCP 클라이언트 클래스(TcpProxy/ClientTCP/ConnectionPool/LoginMgr/Server)는 우리 패키지로 **포팅(vendoring)**하면서 webapp 프레임워크 의존(configure/exception/ServerManager/lombok/validation)만 얇은 shim으로 치환한다. 그 위에 MCP 도구 6종을 올린다. 읽기 전용.

**Tech Stack:** Java 17, Gradle(Kotlin DSL), `io.modelcontextprotocol.sdk:mcp:2.0.0`(stdio), `io.github.scouter-project:scouter-common:2.20.0`, Lombok, slf4j-simple, JUnit5 + AssertJ + Mockito.

> **언어 메모:** 본문은 Java로 작성한다(Kotlin도 가능하나 scouter-common 상호운용·예제 일관성 때문에 Java 기준). 코딩 표준: 스페이스 4칸, 120자, K&R, `camelCase`/`PascalCase`/`UPPER_SNAKE_CASE`. 로그는 단일 라인 `key=value`, 민감정보 미로깅.

> **커밋 규칙:** 이 repo는 GRIT 무관. Conventional Commits(`<type>: <subject>`), `Co-Authored-By` 미표기. author는 `335 <blowbyblow@gmail.com>`(이미 repo local config에 설정됨). 각 Task 끝에서 커밋.

> **검증된 사실(소스 레퍼런스).** 아래 커맨드/파라미터는 scouter.webapp `layer/consumer/*Consumer.java`에서 직접 확인한 값이다. 모호하면 항상 이 파일들을 1차 출처로 삼는다:
> - XLog: `scouter.webapp/src/main/java/scouterx/webapp/layer/consumer/XLogConsumer.java`
> - Profile: `.../consumer/ProfileConsumer.java`
> - Counter: `.../consumer/CounterConsumer.java`
> - Object: `.../consumer/ObjectConsumer.java`
> - 로그인/네트워크: `.../framework/client/net/{TcpProxy,ClientTCP,ConnectionPool,LoginMgr,INetReader}.java`, `.../framework/client/server/{Server,ServerManager}.java`
> raw 접두사: `https://raw.githubusercontent.com/scouter-project/scouter/v2.20.0/`

---

## File Structure

```
scouter-mcp/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle/wrapper/...               # gradle wrapper
├─ README.md
├─ .gitignore                       # (이미 존재)
├─ docs/
│  ├─ specs/2026-06-29-scouter-mcp-design.md   # (이미 존재)
│  └─ plans/2026-06-29-scouter-mcp.md          # (이 문서)
└─ src/
   ├─ main/java/scouter/mcp/
   │  ├─ McpMain.java               # stdio 진입점, 도구 등록
   │  ├─ config/Config.java         # env 로딩/검증 (순수)
   │  ├─ time/TimeRange.java        # ISO/상대표현 ↔ epochMs, yyyymmdd, TZ (순수)
   │  ├─ error/McpError.java        # 에러코드 + 메시지 모델 (순수)
   │  ├─ client/                    # vendored Scouter TCP 클라이언트 (포팅)
   │  │  ├─ Server.java
   │  │  ├─ ServerRegistry.java     # webapp ServerManager 대체 shim
   │  │  ├─ ClientConfig.java       # webapp ConfigureAdaptor 대체 shim
   │  │  ├─ ConnectionPool.java
   │  │  ├─ ClientTCP.java
   │  │  ├─ INetReader.java
   │  │  ├─ LoginMgr.java
   │  │  └─ TcpProxy.java
   │  ├─ scouter/
   │  │  ├─ ScouterClient.java      # 고수준 조회 API (login/object/counter/xlog/profile)
   │  │  ├─ TextDictionary.java     # 서비스/SQL/메서드 hash → text 디코드
   │  │  └─ PackMapper.java         # Pack → 정규화 Map/JSON
   │  └─ tools/
   │     ├─ Tools.java              # 6개 SyncToolSpecification 빌더 모음
   │     └─ Schemas.java            # 각 도구 JSON 입력 스키마 문자열
   └─ test/java/scouter/mcp/
      ├─ config/ConfigTest.java
      ├─ time/TimeRangeTest.java
      ├─ scouter/PackMapperTest.java
      └─ tools/ToolsContractTest.java
```

원칙: 순수 로직(config/time/error/packmapper)은 외부 의존 없이 단위테스트로 TDD. 네트워크 의존(client/scouter)은 `ScouterClient`를 인터페이스로 두고 도구 계약 테스트에서 mock.

---

## Task 1: 프로젝트 스캐폴딩 (Gradle 빌드)

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `src/main/java/scouter/mcp/McpMain.java`
- Create: `gradle/wrapper/gradle-wrapper.properties` (wrapper 생성 명령으로)

- [ ] **Step 1: settings + build 파일 작성**

`settings.gradle.kts`:
```kotlin
rootProject.name = "scouter-mcp"
```

`build.gradle.kts`:
```kotlin
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
```

- [ ] **Step 2: 최소 진입점 작성(컴파일 통과용 스텁)**

`src/main/java/scouter/mcp/McpMain.java`:
```java
package scouter.mcp;

public final class McpMain {
    private McpMain() {
    }

    public static void main(String[] args) {
        // Task 9에서 실제 MCP 서버 기동으로 대체된다.
        System.err.println("scouter-mcp boot placeholder");
    }
}
```

- [ ] **Step 3: gradle wrapper 생성**

Run: `cd ~/repositories/scouter-mcp && gradle wrapper --gradle-version 8.8`
(로컬에 gradle이 없으면: `brew install gradle` 후 재실행. wrapper 생성 후로는 `./gradlew` 사용.)
Expected: `gradlew`, `gradle/wrapper/gradle-wrapper.jar/properties` 생성.

- [ ] **Step 4: 의존성 해석 및 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. scouter-common, mcp 의존성이 받아져야 함. 실패 시 좌표/버전 재확인(`io.modelcontextprotocol.sdk:mcp-bom:2.0.0`, `io.github.scouter-project:scouter-common:2.20.0`).

- [ ] **Step 5: Commit**
```bash
cd ~/repositories/scouter-mcp
git add -A
git commit -m "build: gradle 스캐폴딩 및 의존성 설정"
```

---

## Task 2: 에러 모델 (McpError)

**Files:**
- Create: `src/main/java/scouter/mcp/error/McpError.java`
- Test: `src/test/java/scouter/mcp/error/McpErrorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/scouter/mcp/error/McpErrorTest.java`:
```java
package scouter.mcp.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpErrorTest {

    @Test
    void rendersCodeAndHintsAsSingleLineKeyValue() {
        McpError e = McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED, "collector unreachable")
                .withHint("host", "scouter-collector")
                .withHint("port", "6100");
        String s = e.toLogLine();
        assertThat(s).isEqualTo(
                "code=SCOUTER_CONNECT_FAILED msg=\"collector unreachable\" host=scouter-collector port=6100");
        assertThat(s).doesNotContain("\n");
    }

    @Test
    void neverEchoesCredentialsHelper() {
        // 자격증명은 hint에 절대 담지 않는다는 계약을 문서화하는 테스트
        McpError e = McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, "login rejected");
        assertThat(e.toLogLine()).doesNotContain("pass");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "scouter.mcp.error.McpErrorTest"`
Expected: FAIL (McpError 없음).

- [ ] **Step 3: 구현**

`src/main/java/scouter/mcp/error/McpError.java`:
```java
package scouter.mcp.error;

import java.util.LinkedHashMap;
import java.util.Map;

public final class McpError extends RuntimeException {

    public enum Code {
        SCOUTER_CONNECT_FAILED,
        SCOUTER_AUTH_FAILED,
        SCOUTER_PROTOCOL_MISMATCH,
        INVALID_INPUT,
        NOT_FOUND,
        INTERNAL
    }

    private final Code code;
    private final Map<String, String> hints = new LinkedHashMap<>();

    private McpError(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static McpError of(Code code, String message) {
        return new McpError(code, message);
    }

    public McpError withHint(String key, String value) {
        hints.put(key, value);
        return this;
    }

    public Code code() {
        return code;
    }

    public Map<String, String> hints() {
        return hints;
    }

    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("code=").append(code.name());
        sb.append(" msg=\"").append(getMessage() == null ? "" : getMessage()).append('"');
        for (Map.Entry<String, String> e : hints.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "scouter.mcp.error.McpErrorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "feat: McpError 에러 모델 추가"
```

---

## Task 3: 시간 처리 (TimeRange)

scouter는 시간 파라미터로 epochMillis와 `yyyymmdd`(날짜키)를 함께 쓴다(XLog/Profile은 DATE=yyyymmdd 필요). 입력은 ISO8601 또는 상대표현(`now`, `now-1h`, `now-30m`, `now-2d`)을 받는다. 기본 TZ는 Asia/Seoul.

**Files:**
- Create: `src/main/java/scouter/mcp/time/TimeRange.java`
- Test: `src/test/java/scouter/mcp/time/TimeRangeTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/scouter/mcp/time/TimeRangeTest.java`:
```java
package scouter.mcp.time;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeTest {

    private final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void parsesIso8601ToEpochMillis() {
        long ms = TimeRange.parseInstant("2026-06-29T00:00:00+09:00", KST, 1_000_000L);
        assertThat(ms).isEqualTo(1782486000000L); // 2026-06-29T00:00:00+09:00
    }

    @Test
    void parsesRelativeExpressionAgainstNow() {
        long now = 1_000_000_000_000L;
        assertThat(TimeRange.parseInstant("now", KST, now)).isEqualTo(now);
        assertThat(TimeRange.parseInstant("now-1h", KST, now)).isEqualTo(now - 3_600_000L);
        assertThat(TimeRange.parseInstant("now-30m", KST, now)).isEqualTo(now - 1_800_000L);
        assertThat(TimeRange.parseInstant("now-2d", KST, now)).isEqualTo(now - 2L * 86_400_000L);
    }

    @Test
    void rejectsUnknownFormat() {
        assertThatThrownBy(() -> TimeRange.parseInstant("yesterday", KST, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsYyyymmddInZone() {
        // 2026-06-29T00:00:00+09:00
        assertThat(TimeRange.yyyymmdd(1782486000000L, KST)).isEqualTo("20260629");
    }

    @Test
    void formatsIsoOutput() {
        assertThat(TimeRange.toIso(1782486000000L, KST)).isEqualTo("2026-06-29T00:00:00+09:00");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "scouter.mcp.time.TimeRangeTest"`
Expected: FAIL.

- [ ] **Step 3: 구현**

`src/main/java/scouter/mcp/time/TimeRange.java`:
```java
package scouter.mcp.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeRange {

    private static final Pattern RELATIVE = Pattern.compile("^now(?:-(\\d+)([smhd]))?$");

    private TimeRange() {
    }

    public static long parseInstant(String expr, ZoneId zone, long nowMillis) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("time expression is blank");
        }
        String v = expr.trim();
        Matcher m = RELATIVE.matcher(v);
        if (m.matches()) {
            if (m.group(1) == null) {
                return nowMillis;
            }
            long amount = Long.parseLong(m.group(1));
            long unitMs = switch (m.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> throw new IllegalArgumentException("unsupported unit: " + m.group(2));
            };
            return nowMillis - amount * unitMs;
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            // zone 미지정 ISO local → 기본 zone 적용
            return java.time.LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("unrecognized time expression: " + expr);
        }
    }

    public static String yyyymmdd(long epochMillis, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    public static String toIso(long epochMillis, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "scouter.mcp.time.TimeRangeTest"`
Expected: PASS. (만약 `parsesIso8601` 기대값이 환경 차로 어긋나면, 테스트의 기대 epoch을 `OffsetDateTime.parse(...).toInstant().toEpochMilli()`로 계산해 고정하라 — 구현이 아니라 테스트 상수를 맞춘다.)

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "feat: TimeRange 시간 파싱/포맷 추가"
```

---

## Task 5: 설정 로딩 (Config)

env로만 접속정보 주입. 자격증명은 객체 내부에 보관하되 `toString`/로그에 노출 금지.

**Files:**
- Create: `src/main/java/scouter/mcp/config/Config.java`
- Test: `src/test/java/scouter/mcp/config/ConfigTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/scouter/mcp/config/ConfigTest.java`:
```java
package scouter.mcp.config;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @Test
    void loadsRequiredFieldsFromEnvMap() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "collector",
                "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "admin",
                "SCOUTER_PASSWORD", "secret"));
        assertThat(c.host()).isEqualTo("collector");
        assertThat(c.port()).isEqualTo(6100);
        assertThat(c.user()).isEqualTo("admin");
        assertThat(c.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void failsWhenRequiredMissing() {
        assertThatThrownBy(() -> Config.fromEnv(Map.of("SCOUTER_COLLECTOR_HOST", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCOUTER_COLLECTOR_PORT");
    }

    @Test
    void toStringNeverLeaksPassword() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "topsecret"));
        assertThat(c.toString()).doesNotContain("topsecret");
    }

    @Test
    void overridesTimezone() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p", "SCOUTER_TZ", "UTC"));
        assertThat(c.zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "scouter.mcp.config.ConfigTest"`
Expected: FAIL.

- [ ] **Step 3: 구현**

`src/main/java/scouter/mcp/config/Config.java`:
```java
package scouter.mcp.config;

import java.time.ZoneId;
import java.util.Map;

public final class Config {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final ZoneId zone;

    private Config(String host, int port, String user, String password, ZoneId zone) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.zone = zone;
    }

    public static Config fromEnv(Map<String, String> env) {
        String host = require(env, "SCOUTER_COLLECTOR_HOST");
        int port = Integer.parseInt(require(env, "SCOUTER_COLLECTOR_PORT"));
        String user = require(env, "SCOUTER_USER");
        String password = require(env, "SCOUTER_PASSWORD");
        String tz = env.getOrDefault("SCOUTER_TZ", "Asia/Seoul");
        return new Config(host, port, user, password, ZoneId.of(tz));
    }

    private static String require(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required env: " + key);
        }
        return v;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public ZoneId zone() {
        return zone;
    }

    @Override
    public String toString() {
        return "Config{host=" + host + ", port=" + port + ", user=" + user + ", password=***, zone=" + zone + "}";
    }
}
```

- [ ] **Step 4: 통과 확인 + Commit**

Run: `./gradlew test --tests "scouter.mcp.config.ConfigTest"`
Expected: PASS.
```bash
git add -A && git commit -m "feat: env 기반 Config 로딩 추가"
```

---

## Task 6: Scouter TCP 클라이언트 포팅 (vendoring) — Walking Skeleton

webapp의 net/server 클래스를 `scouter.mcp.client` 패키지로 포팅한다. **프로토콜 코드(소켓 open 핸드셰이크, TcpProxy.process 프레이밍, LoginMgr 로그인 시퀀스)는 그대로 보존**하고, webapp 프레임워크 의존만 치환한다.

치환 매핑:
- `scouterx.webapp.framework.configure.ConfigureAdaptor/ConfigureManager` → `scouter.mcp.client.ClientConfig`(net_local_addr, tcp_so_timeout, connection pool size, connection timeout 등 net 관련 값만 보유. 기본값은 webapp Configure 기본값을 그대로 복사).
- `scouterx.webapp.framework.client.server.ServerManager` → `scouter.mcp.client.ServerRegistry`(`getServer(id)`, `addServer`, `getDefaultServer`만 제공).
- `scouterx.webapp.framework.exception.ErrorState*` → `scouter.mcp.error.McpError`(연결/인증 실패 매핑).
- `scouterx.webapp.framework.client.thread.XLogRetrieveThread` 참조(Server 내부) → 제거(우리는 실시간 스트림 스레드 미사용).
- `lombok` → 의존성 이미 추가됨. 그대로 사용.

**Files:**
- Create: `src/main/java/scouter/mcp/client/ClientConfig.java`
- Create: `src/main/java/scouter/mcp/client/ServerRegistry.java`
- Create: `src/main/java/scouter/mcp/client/Server.java`
- Create: `src/main/java/scouter/mcp/client/INetReader.java`
- Create: `src/main/java/scouter/mcp/client/ClientTCP.java`
- Create: `src/main/java/scouter/mcp/client/ConnectionPool.java`
- Create: `src/main/java/scouter/mcp/client/LoginMgr.java`
- Create: `src/main/java/scouter/mcp/client/TcpProxy.java`

- [ ] **Step 1: 원본 net/server 소스 받기**

Run:
```bash
cd ~/repositories/scouter-mcp
mkdir -p /tmp/scouter-src
base="https://raw.githubusercontent.com/scouter-project/scouter/v2.20.0/scouter.webapp/src/main/java/scouterx/webapp/framework/client"
for f in net/TcpProxy net/ClientTCP net/ConnectionPool net/LoginMgr net/INetReader server/Server server/ServerManager; do
  curl -s "$base/$f.java" -o "/tmp/scouter-src/$(basename $f).java"
done
ls -l /tmp/scouter-src
```
Expected: 7개 .java 파일. 이 파일들을 레퍼런스로 삼아 아래 포팅을 수행한다.

- [ ] **Step 2: shim 클래스 작성 (ClientConfig, ServerRegistry)**

`ClientConfig.java` — webapp Configure에서 net 관련 기본값만 복사한 정적 보관소:
```java
package scouter.mcp.client;

public final class ClientConfig {
    // webapp Configure 기본값 복사 (net 관련만)
    public static final int NET_TCP_CLIENT_SO_TIMEOUT_MS = 30000;
    public static final int NET_TCP_CLIENT_POOL_SIZE = 12;
    public static final int NET_TCP_CLIENT_POOL_TIMEOUT_MS = 30000;
    public static final int NET_TCP_CLIENT_CONNECTION_TIMEOUT_MS = 3000;
    public static final String NET_LOCAL_UDP_ADDR = null;

    private ClientConfig() {
    }
}
```

`ServerRegistry.java`:
```java
package scouter.mcp.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class ServerRegistry {
    private static final Map<Integer, Server> SERVERS = new ConcurrentHashMap<>();

    private ServerRegistry() {
    }

    public static void add(Server server) {
        SERVERS.put(server.getId(), server);
    }

    public static Server get(int id) {
        return SERVERS.get(id);
    }
}
```

- [ ] **Step 3: Server / INetReader 포팅**

`/tmp/scouter-src/Server.java`, `INetReader.java`를 `scouter.mcp.client` 패키지로 옮긴다. 편집 규칙:
- `package`를 `scouter.mcp.client`로 변경.
- import 중 `scouterx.webapp.framework.configure.*`, `...client.thread.XLogRetrieveThread`, `...client.net.ConnectionPool` 제거/치환.
- `ConfigureManager.getConfigure()` 호출 → `ClientConfig` 상수로 치환.
- `XLogRetrieveThread` 필드/메서드 제거.
- `Server`의 connection pool 접근은 우리 `ConnectionPool` 사용.
- `scouter.lang.*`, `scouter.io.*`, `scouter.net.*`, `scouter.util.*`, `scouter.value.*` import는 **그대로 둔다(scouter-common 제공)**.

`INetReader.java`는 거의 그대로(`scouter.io.DataInputX`만 사용). 패키지명만 변경.

- [ ] **Step 4: ConnectionPool / ClientTCP / LoginMgr / TcpProxy 포팅**

같은 규칙으로 4개 파일 포팅:
- 패키지 → `scouter.mcp.client`.
- `ConfigureManager.getConfigure().getXxx()` → `ClientConfig.XXX`.
- `ServerManager.getInstance().getServer(id)` → `ServerRegistry.get(id)`.
- `ErrorState.XXX.newBizException()` 등 → `McpError.of(Code.SCOUTER_CONNECT_FAILED, ...)` 또는 `SCOUTER_AUTH_FAILED`.
- `scouter.*`(common 제공) import는 보존.
- **`ClientTCP.open()`의 소켓 연결/핸드셰이크 바이트 순서, `TcpProxy.process/getSingle`의 `TcpFlag.HasNEXT` 루프, `LoginMgr.login`의 sha256·MapPack(id/pass/version/hostname/internal)·session 파싱은 한 줄도 바꾸지 않는다.**
- `TcpProxy.loginByCleanConnection`이 참조하는 내부 메서드까지 함께 포팅.

각 파일 상단에 출처 주석 추가:
```java
// Ported from scouter.webapp v2.20.0 (Apache License 2.0)
// Source: scouterx.webapp.framework.client.net.<Class>
```

- [ ] **Step 5: 컴파일 통과 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. 미해결 심볼이 있으면 그 심볼이 (a) scouter-common에 있는지(import 경로 수정) 또는 (b) webapp 프레임워크 잔재인지(치환/제거) 판별해 처리.

- [ ] **Step 6: Commit**
```bash
git add -A && git commit -m "feat: Scouter TCP 클라이언트(net/server) 포팅 및 프레임워크 의존 제거"
```

---

## Task 7: ScouterClient 인터페이스 + 로그인/오브젝트 조회 + 스모크

도구 계층이 의존할 고수준 API를 인터페이스로 정의하고, 포팅한 TcpProxy로 구현한다. 첫 실제 기능: `connect()`(login) + `listObjects()`.

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/ScouterClient.java` (interface)
- Create: `src/main/java/scouter/mcp/scouter/dto/SObjectDto.java`
- Create: `src/main/java/scouter/mcp/scouter/TcpScouterClient.java` (impl)
- Test(수동/통합): `src/test/java/scouter/mcp/scouter/SmokeIT.java` (env 있을 때만)

- [ ] **Step 1: 인터페이스 + DTO 정의**

`ScouterClient.java`:
```java
package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.SObjectDto;
import java.util.List;

public interface ScouterClient extends AutoCloseable {
    void connect();                       // 로그인 + 세션 확보

    List<SObjectDto> listObjects();       // 오브젝트(에이전트) 목록

    @Override
    void close();
}
```

`SObjectDto.java`:
```java
package scouter.mcp.scouter.dto;

public record SObjectDto(int objHash, String objName, String objType, String address, boolean alive) {
}
```

- [ ] **Step 2: 구현 — connect + listObjects**

`TcpScouterClient.java` (핵심: `OBJECT_LIST_REAL_TIME` 사용, ObjectConsumer.java:64 패턴):
```java
package scouter.mcp.scouter;

import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.Pack;
import scouter.net.RequestCmd;
import scouter.mcp.client.LoginMgr;
import scouter.mcp.client.Server;
import scouter.mcp.client.ServerRegistry;
import scouter.mcp.client.TcpProxy;
import scouter.mcp.config.Config;
import scouter.mcp.error.McpError;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.ArrayList;
import java.util.List;

public final class TcpScouterClient implements ScouterClient {

    private final Config config;
    private Server server;

    public TcpScouterClient(Config config) {
        this.config = config;
    }

    @Override
    public void connect() {
        // Server 생성: host/port/user/password 주입 (포팅한 Server의 생성/세터에 맞춤)
        Server s = new Server(config.host(), config.port());
        s.setUserId(config.user());
        s.setPassword(config.password());
        ServerRegistry.add(s);
        try {
            var result = LoginMgr.login(s);  // 포팅한 LoginMgr 시그니처에 맞춤
            if (!s.isOpen()) {
                throw McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, "login rejected")
                        .withHint("host", config.host());
            }
            this.server = s;
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED, String.valueOf(e.getMessage()))
                    .withHint("host", config.host())
                    .withHint("port", String.valueOf(config.port()));
        }
    }

    @Override
    public List<SObjectDto> listObjects() {
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            List<SObjectDto> out = new ArrayList<>();
            List<Pack> packs = tcp.process(RequestCmd.OBJECT_LIST_REAL_TIME, null);
            for (Pack p : packs) {
                ObjectPack op = (ObjectPack) p;
                out.add(new SObjectDto(op.objHash, op.objName, op.objType, op.address, op.alive));
            }
            return out;
        } finally {
            TcpProxy.close(tcp);
        }
    }

    @Override
    public void close() {
        // 풀이 연결을 관리. 명시적 종료 필요 시 ServerRegistry/풀 정리.
    }
}
```
> 주의: `Server`의 실제 생성자/세터 이름은 Task 6에서 포팅한 결과에 맞춘다(`setUserId`/`setPassword`/`isOpen`/`getId` 등). `ObjectPack`의 필드명(`objHash/objName/objType/address/alive`)은 scouter-common `scouter.lang.pack.ObjectPack` 정의를 확인해 일치시킨다. `LoginMgr.login` 시그니처도 포팅 결과에 맞춰 호출.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (ObjectPack/Server 심볼 불일치 시 scouter-common javadoc/소스로 필드·메서드명 확정 후 수정.)

- [ ] **Step 4: 통합 스모크 테스트(env 있을 때만)**

`SmokeIT.java`:
```java
package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import scouter.mcp.config.Config;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void connectsAndListsObjects() {
        Config c = Config.fromEnv(System.getenv());
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            var objects = client.listObjects();
            assertThat(objects).isNotNull();
            System.err.println("object count=" + objects.size());
        }
    }
}
```

Run (dev collector 자격증명 export 후):
```bash
SCOUTER_COLLECTOR_HOST=... SCOUTER_COLLECTOR_PORT=6100 SCOUTER_USER=... SCOUTER_PASSWORD=... \
  ./gradlew test --tests "scouter.mcp.scouter.SmokeIT"
```
Expected: PASS, stderr에 object count 출력. env 없으면 자동 skip(=정상).

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "feat: ScouterClient connect/listObjects 및 스모크 테스트"
```

---

## Task 8: MCP stdio 서버 + list_objects 도구 (end-to-end 최초 동작)

**Files:**
- Create: `src/main/java/scouter/mcp/tools/Schemas.java`
- Create: `src/main/java/scouter/mcp/tools/Tools.java`
- Modify: `src/main/java/scouter/mcp/McpMain.java`
- Test: `src/test/java/scouter/mcp/tools/ToolsContractTest.java`

- [ ] **Step 1: 입력 스키마 작성**

`Schemas.java` (JSON Schema 문자열 상수):
```java
package scouter.mcp.tools;

public final class Schemas {
    private Schemas() {
    }

    public static final String LIST_OBJECTS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "필터: 오브젝트 타입(예: tomcat)"},
            "nameLike": {"type": "string", "description": "필터: objName 부분일치"}
          }
        }
        """;
    // 이후 Task에서 search_xlog/get_counter 등 스키마 추가
}
```

- [ ] **Step 2: 도구 계약 테스트 작성 (mock ScouterClient)**

`ToolsContractTest.java`:
```java
package scouter.mcp.tools;

import org.junit.jupiter.api.Test;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolsContractTest {

    @Test
    void listObjectsToolReturnsJsonWithFilters() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.listObjects()).thenReturn(List.of(
                new SObjectDto(1, "app-1", "tomcat", "10.0.0.1", true),
                new SObjectDto(2, "redis-1", "redis", "10.0.0.2", true)));

        String json = Tools.renderListObjects(client, "tomcat", null);

        assertThat(json).contains("app-1").contains("tomcat");
        assertThat(json).doesNotContain("redis-1"); // objType 필터 적용
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "scouter.mcp.tools.ToolsContractTest"`
Expected: FAIL.

- [ ] **Step 4: Tools.renderListObjects 구현**

`Tools.java`:
```java
package scouter.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Tools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Tools() {
    }

    public static String renderListObjects(ScouterClient client, String objType, String nameLike) {
        List<SObjectDto> objects = client.listObjects().stream()
                .filter(o -> objType == null || objType.equalsIgnoreCase(o.objType()))
                .filter(o -> nameLike == null || o.objName().contains(nameLike))
                .collect(Collectors.toList());
        try {
            return MAPPER.writeValueAsString(Map.of("count", objects.size(), "objects", objects));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```
> Jackson은 mcp 의존성(`mcp` 번들이 Jackson 3 포함)으로 사용 가능. 만약 클래스패스 충돌 시 `com.fasterxml.jackson.core:jackson-databind`를 명시적으로 testImplementation/implementation에 추가.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "scouter.mcp.tools.ToolsContractTest"`
Expected: PASS.

- [ ] **Step 6: McpMain에 stdio 서버 + 도구 등록**

`McpMain.java`:
```java
package scouter.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import scouter.mcp.config.Config;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.TcpScouterClient;
import scouter.mcp.tools.Schemas;
import scouter.mcp.tools.Tools;

import java.util.List;

public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnv(System.getenv());
        ScouterClient client = new TcpScouterClient(config);
        client.connect();

        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(io.modelcontextprotocol.json.McpJsonDefaults.getMapper());

        SyncToolSpecification listObjects = SyncToolSpecification.builder()
                .tool(Tool.builder("list_objects", Schemas.LIST_OBJECTS)
                        .description("Scouter 오브젝트(에이전트/서비스) 목록 조회. objType/nameLike로 필터.")
                        .build())
                .callHandler((exchange, request) -> {
                    String objType = (String) request.arguments().get("objType");
                    String nameLike = (String) request.arguments().get("nameLike");
                    String json = Tools.renderListObjects(client, objType, nameLike);
                    return CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(json)))
                            .build();
                })
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("scouter-mcp", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .build();
        server.addTool(listObjects);

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```
> 정확한 import 경로(`io.modelcontextprotocol.json.McpJsonDefaults`, `McpServerFeatures.SyncToolSpecification` 등)는 mcp 2.0.0 jar로 확정한다. Step 7에서 컴파일로 검증하고 어긋나면 IDE/jar 인덱스로 정정.

- [ ] **Step 7: 컴파일 + 수동 기동 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.
수동 확인(env 있을 때): `./gradlew run` 후 stdin으로 MCP initialize/list tools JSON-RPC를 흘려 응답 확인. 또는 Claude Code의 `.mcp.json`에 등록 후 `list_objects` 호출(Task 12).

- [ ] **Step 8: Commit**
```bash
git add -A && git commit -m "feat: stdio MCP 서버 및 list_objects 도구"
```

---

## Task 9: get_counter / list_counters 도구

`CounterConsumer.java` 레퍼런스: 과거 시계열은 `COUNTER_PAST_TIME_ALL`(objHashes), MapPack에 `ParamConstant.OBJ_HASH`(ListValue), 카운터명, `startTimeMillis`/`endTimeMillis`. 응답은 objHash별 MapPack에 `ParamConstant.TIME`(ListValue)+`ParamConstant.VALUE`(ListValue). 카운터 디스커버리는 `RequestCmd.GET_XML_COUNTER`(counter XML) 사용(LoginMgr.getCounterXmlServer 참고).

**Files:**
- Modify: `src/main/java/scouter/mcp/scouter/ScouterClient.java` (+`getCounter`, `listCounters`)
- Create: `src/main/java/scouter/mcp/scouter/dto/CounterSeriesDto.java`
- Create: `src/main/java/scouter/mcp/scouter/dto/CounterMetaDto.java`
- Modify: `TcpScouterClient.java`
- Modify: `Tools.java`, `Schemas.java`, `McpMain.java`
- Test: `src/test/java/scouter/mcp/scouter/PackMapperTest.java` (TIME/VALUE → points 변환 단위테스트)

- [ ] **Step 1: PackMapper 단위테스트 작성 (순수 변환)**

`PackMapper.java`에 `toPoints(ListValue time, ListValue value)` 같은 순수 함수를 두고 테스트. (ListValue는 scouter-common 제공.)
```java
package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.value.ListValue;
import scouter.lang.value.DoubleValue;
import scouter.lang.value.DecimalValue;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PackMapperTest {

    @Test
    void mapsTimeValueListsToPoints() {
        ListValue time = new ListValue();
        time.add(1000L);
        time.add(2000L);
        ListValue value = new ListValue();
        value.add(1.5d);
        value.add(2.5d);

        List<PackMapper.Point> points = PackMapper.toPoints(time, value);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).timeMillis()).isEqualTo(1000L);
        assertThat(points.get(0).value()).isEqualTo(1.5d);
    }
}
```
> ListValue의 add 오버로드(long/double)와 getLong/getDouble 접근자는 scouter-common 정의에 맞춘다. 테스트가 빨간 줄이면 실제 API에 맞춰 add/get 호출만 조정.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "scouter.mcp.scouter.PackMapperTest"`
Expected: FAIL.

- [ ] **Step 3: PackMapper.toPoints 구현**

`PackMapper.java`:
```java
package scouter.mcp.scouter;

import scouter.lang.value.ListValue;
import java.util.ArrayList;
import java.util.List;

public final class PackMapper {

    private PackMapper() {
    }

    public record Point(long timeMillis, double value) {
    }

    public static List<Point> toPoints(ListValue time, ListValue value) {
        List<Point> out = new ArrayList<>();
        int n = Math.min(time.size(), value.size());
        for (int i = 0; i < n; i++) {
            out.add(new Point(time.getLong(i), value.getDouble(i)));
        }
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "scouter.mcp.scouter.PackMapperTest"`
Expected: PASS.

- [ ] **Step 5: ScouterClient에 getCounter/listCounters 추가 + 구현**

인터페이스:
```java
List<CounterSeriesDto> getCounter(List<Integer> objHashes, String counter, long fromMillis, long toMillis);

List<CounterMetaDto> listCounters(String objType);
```
DTO:
```java
public record CounterSeriesDto(int objHash, String counter, List<PackMapper.Point> points) {}
public record CounterMetaDto(String counter, String displayName, String unit) {}
```
`TcpScouterClient.getCounter` 구현 — `CounterConsumer.java:96~165` 패턴을 그대로 따른다:
- `MapPack paramPack = new MapPack();`
- `ListValue objHashLv = paramPack.newList(ParamConstant.OBJ_HASH);` + objHashes 추가
- `paramPack.put(ParamConstant.COUNTER, new TextValue(counter));` (정확한 키는 CounterConsumer에서 확인 — counter name param)
- `paramPack.put(ParamConstant.START_TIME, ...)` / `END_TIME` (실제 키명은 CounterConsumer 확인: startTimeMillis/endTimeMillis 세팅 라인)
- `tcpProxy.process(RequestCmd.COUNTER_PAST_TIME_ALL, paramPack, in -> {...})`로 스트리밍 수신, MapPack마다 `getInt(OBJ_HASH)`, `getList(TIME)`, `getList(VALUE)` → `PackMapper.toPoints`.

`listCounters` 구현 — `RequestCmd.GET_XML_COUNTER`로 counter XML을 받아 objType의 카운터명/단위/표시명 파싱(LoginMgr.getCounterXmlServer 참고). XML 파싱은 `javax.xml`/`scouter.lang.counters.CounterEngine`(scouter-common 제공) 활용 가능하면 그쪽 사용.

- [ ] **Step 6: Tools.renderGetCounter / renderListCounters + 계약테스트 + 스키마/등록**

`ToolsContractTest`에 mock 기반 케이스 추가(시계열 JSON에 points/counter 포함, 빈 결과 시 빈 배열+hint). `Schemas`에 `GET_COUNTER`, `LIST_COUNTERS` 추가. `McpMain`에 두 도구 등록.
입력 스키마 핵심:
- get_counter: `objHashes`(array of int) 또는 `objType`(둘 중 하나 필수), `counter`(string 필수), `from`(string 필수), `to`(string 필수). 시간 문자열은 `TimeRange.parseInstant`로 변환.
- list_counters: `objType`(string 필수).

- [ ] **Step 7: 빌드/테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**
```bash
git add -A && git commit -m "feat: get_counter/list_counters 도구 및 PackMapper.toPoints"
```

---

## Task 10: search_xlog 도구

`XLogConsumer.java:240~294` 레퍼런스: `RequestCmd.SEARCH_XLOG_LIST`, MapPack에 `ParamConstant.OBJ_HASH`(long, !=0일 때), 그리고 `SearchXLogRequest`의 필드(시작/종료 시각, service 패턴, 응답시간 하한 등). 응답은 `tcpProxy.process(...)`로 받은 `List<Pack>`(XLogPack). 서비스/SQL 등은 텍스트 hash이므로 `TextDictionary`로 디코드 시도.

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/XLogRowDto.java`
- Create: `src/main/java/scouter/mcp/scouter/TextDictionary.java`
- Modify: `ScouterClient.java`/`TcpScouterClient.java`/`Tools.java`/`Schemas.java`/`McpMain.java`
- Test: `src/test/java/scouter/mcp/scouter/XLogRowMappingTest.java`

- [ ] **Step 1: SearchXLogRequest 실제 필드 확인**

Run:
```bash
curl -s "https://raw.githubusercontent.com/scouter-project/scouter/v2.20.0/scouter.webapp/src/main/java/scouterx/webapp/request/SearchXLogRequest.java" | grep -nE "private|param|MapPack|ParamConstant|@" | head -60
curl -s "https://raw.githubusercontent.com/scouter-project/scouter/v2.20.0/scouter.webapp/src/main/java/scouterx/webapp/layer/consumer/XLogConsumer.java" | sed -n '238,300p'
```
Expected: SEARCH_XLOG_LIST가 채우는 MapPack 필드명 전체 확보(시작/종료, service, 응답시간, 한도 등). 이 값을 구현에 그대로 반영.

- [ ] **Step 2: XLogRowDto + 매핑 단위테스트**

`XLogRowDto.java`:
```java
package scouter.mcp.scouter.dto;

public record XLogRowDto(
        long txid, long gxid, int objHash, String objName,
        String service, int elapsedMs, String error,
        int cpuMs, int sqlCount, long endTimeMillis, String endTimeIso) {
}
```
`XLogRowMappingTest`: XLogPack(필드 직접 세팅) + 고정 ZoneId → XLogRowDto 변환에서 endTimeIso/endTimeMillis/elapsed 매핑 검증. (서비스 hash 디코드는 mock TextDictionary로.)

- [ ] **Step 3: 실패 확인 → 매핑 구현 → 통과**

`PackMapper`에 `XLogPack → XLogRowDto`(zone, dict 주입) 변환 추가. XLogPack 필드명은 scouter-common `scouter.lang.pack.XLogPack`(txid, gxid, objHash, service(int hash), elapsed, error(int hash), cpu, sqlCount, endTime) 확인해 일치. `endTime`은 epochMillis. `service`/`error`는 dict로 텍스트화, 실패 시 `#<hash>`.
Run: `./gradlew test --tests "scouter.mcp.scouter.XLogRowMappingTest"` → PASS.

- [ ] **Step 4: TextDictionary 구현**

`TextProxy`/dictionary 로드 커맨드로 service/error/sql hash를 텍스트로 디코드. 레퍼런스: `DictionaryConsumer.java` + `scouter.net.RequestCmd` 텍스트 관련 커맨드. 날짜별 텍스트 사전을 조회하는 메서드 `String service(long yyyymmdd, int hash)` 제공. 디코드 불가 시 null 반환(상위에서 `#hash`로 표시).

- [ ] **Step 5: searchXlog 구현 + 도구화**

`ScouterClient.searchXlog(SearchXlogParams)` 추가. 파라미터: from/to(millis), objHash(optional), service(optional), minElapsedMs(optional), onlyError(optional), limit(기본 100, 상한 1000). `SEARCH_XLOG_LIST` 호출 후 limit으로 자르고 `truncated` 플래그 계산.
`Tools.renderSearchXlog`: 결과를 `{count, truncated, rows:[XLogRowDto...]}` JSON으로. 빈 결과는 `rows:[]` + hint("기간/필터를 넓혀보세요"). `Schemas.SEARCH_XLOG` 추가, `McpMain` 등록.

- [ ] **Step 6: 빌드/테스트 + 스모크(env)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.
가능하면 `SmokeIT`에 최근 1시간 onlyError 검색 케이스 추가(env-gated).

- [ ] **Step 7: Commit**
```bash
git add -A && git commit -m "feat: search_xlog 도구 및 XLog 매핑/텍스트 사전"
```

---

## Task 11: get_xlog_detail / get_xlog_by_gxid 도구

`XLogConsumer.java`: by txid = `XLOG_READ_BY_TXID`(param DATE=yyyymmdd, XLOG_TXID) → getSingle → XLogPack. by gxid = `XLOG_READ_BY_GXID`(param DATE, XLOG_GXID) → process → List. Profile(바인드/SQL) = `ProfileConsumer.java`: `RequestCmd.TRANX_PROFILE`(param DATE, XLOG_TXID, PROFILE_MAX) → getSingle → Step[] 디코드.

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/XLogDetailDto.java`
- Create: `src/main/java/scouter/mcp/scouter/dto/StepDto.java`
- Modify: `ScouterClient.java`/`TcpScouterClient.java`/`Tools.java`/`Schemas.java`/`McpMain.java`
- Test: `src/test/java/scouter/mcp/scouter/PackMapperTest.java`

- [ ] **Step 1: Step 디코드 경로 확인**

Run:
```bash
curl -s "https://raw.githubusercontent.com/scouter-project/scouter/v2.20.0/scouter.webapp/src/main/java/scouterx/webapp/layer/consumer/ProfileConsumer.java" | sed -n '40,90p'
```
scouter-common `scouter.lang.step.*`(Step, StepEnum, SqlStep, SqlStep2/3, MessageStep, ApiCallStep 등) 구조 확인. SQL 텍스트/바인드는 SqlStep의 hash(sql) + param(바인드 문자열). dict로 sql 텍스트 디코드, 바인드 문자열은 그대로 반환.

- [ ] **Step 2: Profile→StepDto 매핑 단위테스트**

`PackMapperTest`: SqlStep(샘플 sql + 바인드 "1234567812345678, hong@example.com") → StepDto 변환 시 sql 텍스트/바인드/elapsed가 그대로 매핑되는지 검증.
```java
// 핵심 단언
assertThat(detail.sqls().get(0).bindParams()).anyMatch(s -> s.contains("1234567812345678"));
```

- [ ] **Step 3: 실패 확인 → 구현 → 통과**

`StepDto`/`XLogDetailDto` 정의. `PackMapper.toDetail(XLogPack summary, Step[] steps, zone, includeBindParams, dict)` 구현:
- summary: XLogRowDto와 동일 필드 + 전체 elapsed/cpu.
- steps: type/name/elapsedMs.
- sqls: SqlStep만 추려 `{sql(dict 디코드), bindParams[], elapsedMs}`.
- errors: error hash 디코드.
Run: `./gradlew test --tests "scouter.mcp.scouter.PackMapperTest"` → PASS.

- [ ] **Step 4: ScouterClient.getXlogDetail / getXlogByGxid + 도구화**

`getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams)`:
- `XLOG_READ_BY_TXID`로 XLogPack, `TRANX_PROFILE`로 Step[] → `toDetail`.
`getXlogByGxid(long gxid, String yyyymmdd)`: `XLOG_READ_BY_GXID` → List<XLogRowDto>.
`Tools.renderXlogDetail` / `renderXlogByGxid` + `Schemas` + `McpMain` 등록.
> yyyymmdd는 호출자가 안 주면 txid 검색 시점/`to` 기준으로 `TimeRange.yyyymmdd`로 산출. 입력 스키마에 `date`(yyyymmdd optional), `at`(ISO/상대, optional) 중 하나로 날짜 결정.

- [ ] **Step 5: 빌드/테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add -A && git commit -m "feat: get_xlog_detail/get_xlog_by_gxid 도구"
```

---

## Task 12: 패키징 · 등록 문서 · README

**Files:**
- Modify: `build.gradle.kts` (shadow/fat jar)
- Create: `README.md`
- Create: `.mcp.json.example`

- [ ] **Step 1: fat jar 설정**

`build.gradle.kts`에 shadow 플러그인 추가:
```kotlin
plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.0"
}
```
Run: `./gradlew shadowJar`
Expected: `build/libs/scouter-mcp-0.1.0-all.jar` 생성.

- [ ] **Step 2: Claude Code 등록 예시 작성**

`.mcp.json.example`:
```json
{
  "mcpServers": {
    "scouter": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/scouter-mcp/build/libs/scouter-mcp-0.1.0-all.jar"],
      "env": {
        "SCOUTER_COLLECTOR_HOST": "your-collector-host",
        "SCOUTER_COLLECTOR_PORT": "6100",
        "SCOUTER_USER": "your-user",
        "SCOUTER_PASSWORD": "your-password",
        "SCOUTER_TZ": "Asia/Seoul"
      }
    }
  }
}
```
> stdout은 JSON-RPC 전용이므로 slf4j-simple은 stderr로만 로그하도록 jar의 `simplelogger.properties`(`org.slf4j.simpleLogger.logFile=System.err`)를 `src/main/resources`에 포함.

- [ ] **Step 3: README 작성**

`README.md`에: 개요, 아키텍처 1문단, 빌드(`./gradlew shadowJar`), 등록 방법(`.mcp.json.example`), 도구 6종 표(이름/용도/주요 입력), 보안 주의(읽기 전용·자격증명 env), Apache-2.0 포팅 코드 고지(`scouter.mcp.client` 패키지는 scouter.webapp v2.20.0에서 포팅, NOTICE 포함).

- [ ] **Step 4: 실제 Claude Code 연동 검증(env 있을 때)**

`.mcp.json.example`을 실제 값으로 채워 등록 후, `list_objects` → `search_xlog`(onlyError) → `get_xlog_detail` 순으로 호출되는지 확인. 결과 JSON에 txid/gxid/endTimeIso가 실려 OpenSearch/Datadog 검색어로 쓸 수 있는지 눈으로 확인.

- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "build: fat jar 및 등록 문서/README 추가"
```

---

## Self-Review (작성자 체크 결과)

**Spec 커버리지** (design §4 도구 6종):
- list_objects → Task 8 ✓
- search_xlog → Task 10 ✓
- get_xlog_detail → Task 11 ✓
- get_xlog_by_gxid → Task 11 ✓
- get_counter → Task 9 ✓
- list_counters → Task 9 ✓
- 연결/세션(§5) → Task 6/7 ✓ / 에러모델(§6) → Task 2 ✓ / 시간처리(§5) → Task 3 ✓ / 패키징·등록 → Task 12 ✓

**미해결(구현 중 1차 출처로 확정할 항목, placeholder 아님):**
- `SEARCH_XLOG_LIST`/`COUNTER_PAST_TIME_ALL`의 정확한 MapPack 키명·타입 → Task 9 Step5 / Task 10 Step1에서 해당 Consumer 소스로 확정(명령·접근 위치 명시됨).
- scouter-common의 `XLogPack`/`ObjectPack`/`ListValue`/`Step` 실제 필드·접근자명 → 각 Task의 컴파일 단계에서 확정(어느 클래스를 볼지 명시됨).
- mcp 2.0.0 import 경로 → Task 8 Step7 컴파일로 확정.
이들은 "무엇을·어디서 확인하는지"가 구체적으로 적혀 있어 실행 가능하다.

**타입 일관성:** `ScouterClient`(Task 7 정의) 메서드가 Task 9/10/11에서 확장되며 동일 시그니처 사용. `PackMapper.Point`/`toPoints`(Task 9) → Task 10/11에서 재사용. `Config`/`TimeRange`/`McpError` 명칭 전 Task 일관.
