# 신규 도구 5종 + 자원 가드레일 강화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** scouter-mcp에 list_threads / get_thread_detail / get_object_env / get_summary / get_counter_stat 도구를 추가하고, 응답 텍스트 절삭(P0)·패스 상한(P1)·저선택도 힌트(P1)·스키마 가이드(P2)·텔레메트리·마스킹을 구현한다.

**Architecture:** 기존 계층 구조를 그대로 따른다 — `ScouterClient`(인터페이스) → `TcpScouterClient`(RequestCmd TCP 구현) → `PackMapper`/DTO(record) → `Tools.render*`(JSON 렌더) → `Schemas`(입력 스키마) → `McpMain`(도구 등록). 모든 신규 명령은 Scouter v2.20.0 프로토콜 조사 결과(하단 부록)에 근거한다.

**Tech Stack:** Java 17, scouter-common(v2.20.0), MCP Java SDK 2.0.0, JUnit5 + AssertJ + Mockito.

**프로토콜 근거 요약 (조사 완료, v2.20.0 태그 기준):**
- `OBJECT_THREAD_LIST`: 요청 `{objHash:int}` → 단일 MapPack, 컬럼형 ListValue `id/name/stat/cpu/txid/elapsed/service` (txid/elapsed/service는 NullValue 가능)
- `OBJECT_THREAD_DETAIL`: 요청 `{objHash:int, id:long(필수 키), txid:long}` → 단일 MapPack, **공백 포함 스칼라 키** (`"Thread Name"`, `"State"`, `"Stack Trace"`, `"Thread Cpu Time"`, `"Blocked Count"`, `"Lock Name"`, `"Service Txid"`, `"Service Name"`, `"Service Elapsed"`, `"SQL"`, `"SQLActiveBindVar"`, `"Subcall"` 등). java agent는 txid로 활성 TraceContext를 못 찾으면 `{"Thread Name":"[No Thread] End","State":"end"}`만 반환 → **txid 필수**
- `OBJECT_ENV`: 요청 `{objHash:int}` → 단일 MapPack, JVM 시스템 프로퍼티가 `키=프로퍼티명, 값=TextValue`로 평면 저장 (고정 키 없음)
- `LOAD_SERVICE_SUMMARY`/`LOAD_SQL_SUMMARY`/`LOAD_APICALL_SUMMARY`/`LOAD_IP_SUMMARY`/`LOAD_UA_SUMMARY`/`LOAD_SERVICE_ERROR_SUMMARY`/`LOAD_ALERT_SUMMARY`: 요청 전부 동일 `{date:"yyyyMMdd", stime:long, etime:long, objType?:text, objHash:int(0=전체)}`, **하루 단위** getSingle → 단일 MapPack 컬럼형:
  - service: `id(서비스해시)/count/error/elapsed(합계)/cpu/mem`
  - sql·apiCall: `id/count/error/elapsed`
  - ip: `id(IP int 인코딩)/count`, userAgent: `id(UA해시)/count`
  - error: `id/error/service/message/count/txid(long)/sql/apicall/fullstack` (전부 해시)
  - alert: `id(title해시)/title(text)/count/level(int)`
- `COUNTER_PAST_LONGDATE_ALL`: 요청 `{counter, sDate:"yyyyMMdd", eDate:"yyyyMMdd", objHash:ListValue<int>}` (**sDate/eDate camelCase**, objType/objHash 둘 다 없으면 무응답) → (날짜×objHash)별 멀티 MapPack `{objHash, time:ListValue, value:ListValue}` 스트림, **5분 해상도**(하루 288포인트)
- 텍스트 사전: service=`TextTypes.SERVICE`, sql=`TextTypes.SQL`, apicall=`TextTypes.APICALL`, userAgent=`TextTypes.USER_AGENT`, error/message=`TextTypes.ERROR`, IP는 사전이 아니라 int→IP 문자열 변환(`IPUtil.toString(DataInputX.toBytes(int))` — 구현 시 javap로 시그니처 확인)

---

### Task 1: P0 — 응답 텍스트 절삭 + 프로파일 스텝 상한

**Files:**
- Create: `src/main/java/scouter/mcp/policy/Truncate.java`
- Modify: `src/main/java/scouter/mcp/policy/Limits.java` (상수 추가)
- Modify: `src/main/java/scouter/mcp/scouter/PackMapper.java` (`toXLogRow` error 절삭, `toDetail` sql/error 절삭 + 스텝 상한)
- Modify: `src/main/java/scouter/mcp/scouter/dto/XLogDetailDto.java` (totalSteps/stepsTruncated 필드)
- Test: `src/test/java/scouter/mcp/policy/TruncateTest.java` (신규), `src/test/java/scouter/mcp/scouter/DetailMappingTest.java` (케이스 추가)

- [ ] **Step 1: 실패하는 테스트 작성** — `TruncateTest`:

```java
package scouter.mcp.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruncateTest {

    @Test
    void returnsShortTextUnchanged() {
        assertThat(Truncate.text("abc", 10)).isEqualTo("abc");
        assertThat(Truncate.text(null, 10)).isNull();
    }

    @Test
    void cutsLongTextWithMarker() {
        String s = "x".repeat(100);
        String out = Truncate.text(s, 10);
        assertThat(out).startsWith("xxxxxxxxxx");
        assertThat(out).contains("truncated").contains("100");
        assertThat(out.length()).isLessThan(60);
    }
}
```

DetailMappingTest에 추가 (기존 테스트의 TextResolver/Step 헬퍼 재사용):

```java
@Test
void truncatesOversizedSqlTextAndCapsSteps() {
    // SQL 텍스트가 SQL_TEXT_MAX_CHARS를 넘으면 절삭 마커가 붙는다
    // 스텝이 DETAIL_MAX_STEPS를 넘으면 steps가 상한에서 잘리고 stepsTruncated=true, totalSteps=원본 수
    SqlStep sql = new SqlStep();
    sql.hash = 7;
    sql.elapsed = 5;
    Step[] steps = new Step[Limits.DETAIL_MAX_STEPS + 10];
    for (int i = 0; i < steps.length; i++) {
        MethodStep m = new MethodStep();
        m.hash = 1;
        steps[i] = m;
    }
    steps[0] = sql;
    String longSql = "SELECT ".repeat(1000); // 7000자
    PackMapper.TextResolver dict = new PackMapper.TextResolver() {
        public String service(long ymd, int h) { return null; }
        public String error(long ymd, int h) { return null; }
        public String objName(int h) { return null; }
        public String sql(long ymd, int h) { return longSql; }
        public String method(long ymd, int h) { return "m"; }
    };
    XLogDetailDto d = PackMapper.toDetail(null, steps, 20260703L, true, dict);
    assertThat(d.steps()).hasSize(Limits.DETAIL_MAX_STEPS);
    assertThat(d.stepsTruncated()).isTrue();
    assertThat(d.totalSteps()).isEqualTo(steps.length);
    assertThat(d.sqls().get(0).sql()).contains("truncated").hasSizeLessThan(longSql.length());
}
```

- [ ] **Step 2: 테스트 실패 확인** — `./gradlew test --tests 'scouter.mcp.policy.TruncateTest' --tests 'scouter.mcp.scouter.DetailMappingTest'` → 컴파일 에러(Truncate 없음, record 필드 없음) 예상
- [ ] **Step 3: 구현**

`Limits.java`에 추가:

```java
// --- response text budgets ---
/** Max chars of a single SQL text in responses; longer texts get a truncation marker (token bound). */
public static final int SQL_TEXT_MAX_CHARS = 1500;
/** Max chars of a single error message text (stack-trace-like messages can reach tens of KB). */
public static final int ERROR_TEXT_MAX_CHARS = 500;
/** Max profile steps rendered by get_xlog_detail. Upstream caps blocks (MAX_PROFILE_BLOCK) but not steps. */
public static final int DETAIL_MAX_STEPS = 150;
/** Max chars of a thread stack trace in get_thread_detail. */
public static final int STACK_TEXT_MAX_CHARS = 4000;
/** Max chars of a single env value in get_object_env (java.class.path can be enormous). */
public static final int ENV_VALUE_MAX_CHARS = 500;
```

`Truncate.java`:

```java
package scouter.mcp.policy;

/** Response-size guard: cut oversized text with an explicit marker instead of feeding it to the LLM whole. */
public final class Truncate {

    private Truncate() {
    }

    /** Cut s to max chars, appending a marker carrying the original length. Null-safe. */
    public static String text(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(truncated, " + s.length() + " chars total)";
    }
}
```

`XLogDetailDto.java` — 필드 추가 (NON_NULL 직렬화라 null이면 응답에서 빠짐):

```java
package scouter.mcp.scouter.dto;

import java.util.List;

public record XLogDetailDto(
        XLogRowDto summary,
        List<StepDto> steps,
        List<SqlStepDto> sqls,
        List<String> errors,
        Integer totalSteps,
        Boolean stepsTruncated) {
}
```

`PackMapper.java` 수정:
- import `scouter.mcp.policy.Limits`, `scouter.mcp.policy.Truncate`
- `toXLogRow`의 error: `error = Truncate.text(errText != null ? errText : "#" + p.error, Limits.ERROR_TEXT_MAX_CHARS);`
- `toDetail`: 루프에 `int emitted = 0;` 카운터. `emitted >= Limits.DETAIL_MAX_STEPS`면 스킵(카운트만). SqlStep의 `sqlText = Truncate.text(..., Limits.SQL_TEXT_MAX_CHARS)`, errors.add 시 `Truncate.text(..., Limits.ERROR_TEXT_MAX_CHARS)`. 반환:

```java
int total = 0;
if (steps != null) { for (Step s : steps) { if (s != null) total++; } }
boolean truncated = stepDtos.size() < total;
return new XLogDetailDto(summary, stepDtos, rawSqls, errors,
        truncated ? total : null, truncated ? Boolean.TRUE : null);
```

(sqls도 같은 emitted 상한 루프 안에서만 수집되므로 함께 잘린다 — 의도된 동작)

- [ ] **Step 4: 전체 테스트** — `./gradlew test` → 기존 `new XLogDetailDto(...)` 4-인자 호출부(테스트 포함)를 6-인자(마지막 `null, null`)로 수정 후 PASS
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(policy): truncate oversized SQL/error text and cap profile steps"`

---

### Task 2: P1 — 패스 상한(fan-out×day-split) + stderr 텔레메트리

**Files:**
- Modify: `src/main/java/scouter/mcp/policy/Limits.java`
- Modify: `src/main/java/scouter/mcp/scouter/TcpScouterClient.java` (`searchXlogImpl`, `getServiceSummaryImpl`)
- Modify: `src/main/resources/messages.properties`, `messages_ko.properties`
- Test: `src/test/java/scouter/mcp/scouter/SearchPolicyTest.java` (케이스 추가)

- [ ] **Step 1: 실패하는 테스트 작성** — SearchPolicyTest에 추가:

```java
@Test
void rejectsQueriesOverThePassBudget() {
    assertThatThrownBy(() -> TcpScouterClient.ensurePassBudget(Limits.MAX_QUERY_PASSES + 1, java.util.Locale.ENGLISH))
            .isInstanceOf(McpError.class)
            .matches(e -> ((McpError) e).code() == McpError.Code.INVALID_INPUT);
}

@Test
void allowsQueriesWithinThePassBudget() {
    TcpScouterClient.ensurePassBudget(Limits.MAX_QUERY_PASSES, java.util.Locale.ENGLISH); // no throw
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'scouter.mcp.scouter.SearchPolicyTest'` → 컴파일 에러
- [ ] **Step 3: 구현**

`Limits.java`:

```java
/**
 * Hard cap on collector round-trips a single request may fan out to (instances x day segments).
 * Currently unreachable with SEARCH_MAX_OBJ x 2 segments, but guards the invariant if windows widen.
 */
public static final int MAX_QUERY_PASSES = 40;
```

`TcpScouterClient.java`:

```java
/** Fan-out guard: (instances x day segments) round-trips per request must stay within the pass budget. */
static void ensurePassBudget(int passes, java.util.Locale locale) {
    if (passes > Limits.MAX_QUERY_PASSES) {
        throw McpError.of(McpError.Code.INVALID_INPUT,
                        Messages.get(locale, "error.too_many_passes", passes, Limits.MAX_QUERY_PASSES))
                .withHint("passes", String.valueOf(passes))
                .withHint("maxPasses", String.valueOf(Limits.MAX_QUERY_PASSES));
    }
}
```

`searchXlogImpl`: targetHashes 해석 직후 세그먼트 목록을 미리 만들고 가드 + 종료 시 텔레메트리:

```java
List<DaySplitter.Segment> segments =
        DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone());
ensurePassBudget(targetHashes.size() * segments.size(), config.locale());
long startedAt = System.currentTimeMillis();
```

(기존 이중 루프는 미리 만든 `segments` 재사용) 반환 직전:

```java
// Structured stderr telemetry: per-request fan-out/scan volume for post-hoc load analysis.
log.info("search_xlog done: passes={}, examined={}, kept={}, truncated={}, tookMs={}",
        targetHashes.size() * segments.size(), examined[0], kept.size(), truncated,
        System.currentTimeMillis() - startedAt);
```

`getServiceSummaryImpl`: passes 리스트 생성 직후 `ensurePassBudget(passes.size(), config.locale()); long startedAt = ...`, 반환 직전:

```java
log.info("get_service_summary done: passes={}, examined={}, services={}, capped={}, tookMs={}",
        passes.size(), examined[0], byService.size(), capped[0], System.currentTimeMillis() - startedAt);
```

`messages.properties`:

```properties
error.too_many_passes=This query fans out to {0} collector round-trips (instances x days), above the cap of {1}. Narrow the time window or target fewer instances
```

`messages_ko.properties`:

```properties
error.too_many_passes=이 조회는 collector 왕복 {0}회(인스턴스 x 일자)로 퍼져 상한 {1}을 초과합니다. 시간 범위를 좁히거나 대상 인스턴스를 줄이세요
```

(ko 파일은 기존 파일처럼 native2ascii 이스케이프 형식을 따른다 — 기존 파일이 UTF-8 한글 원문이면 그대로 한글로 쓴다. 구현 시 기존 파일 형식 확인.)

- [ ] **Step 4: 테스트 PASS 확인** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(policy): cap per-request fan-out passes and log scan telemetry`

---

### Task 3: P1 — 클라이언트 필터 저선택도 힌트

**Files:**
- Modify: `src/main/java/scouter/mcp/tools/Tools.java` (`renderSearchXlog`)
- Modify: `messages.properties`, `messages_ko.properties`
- Test: `src/test/java/scouter/mcp/tools/ToolsContractTest.java`

- [ ] **Step 1: 실패하는 테스트** — ToolsContractTest 추가:

```java
@Test
void searchXlogWarnsWhenClientSideFilterDiscardsAlmostEverything() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.searchXlog(any())).thenReturn(
            new XlogSearchResult(List.of(), false, false, 5000, false, List.of()));
    SearchXlogParams params = new SearchXlogParams(0, 1000, null, null, null, null, null, null,
            10_000, false, 20); // minElapsedMs=10s: client-side filter
    String json = Tools.renderSearchXlog(Locale.ENGLISH, client, params);
    assertThat(json).contains("client-side");
}
```

- [ ] **Step 2: 실패 확인** — 해당 테스트만 실행, FAIL 예상
- [ ] **Step 3: 구현** — `renderSearchXlog`의 힌트 체인 최상단(scanCapReached보다 먼저)에:

```java
boolean clientFilter = params.minElapsedMs() != null || params.onlyError();
boolean lowSelectivity = clientFilter && res.examined() >= 1000
        && (long) rows.size() * 100 < res.examined(); // under 1% kept
if (lowSelectivity) {
    result.put("hint", Messages.get(locale, "hint.low_selectivity", rows.size(), res.examined()));
} else if (res.scanCapReached()) {
```

`messages.properties`:

```properties
hint.low_selectivity=Only {0} of {1} scanned rows passed the client-side filters (minElapsedMs/onlyError) - the collector still streamed all of them. Add a server-side filter (service/objNameLike) or use get_service_summary / get_summary for "which is slow/erroring" questions
```

`messages_ko.properties` (한글):

```properties
hint.low_selectivity=스캔한 {1}건 중 {0}건만 클라이언트측 필터(minElapsedMs/onlyError)를 통과했습니다 - collector는 전부 전송했습니다. 서버측 필터(service/objNameLike)를 추가하거나, "뭐가 느린가/에러인가"류 질문에는 get_service_summary / get_summary를 쓰세요
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): warn when client-side filters discard almost all scanned rows`

---

### Task 4: P2 — 스키마 시간범위 가이드 + 클라이언트 필터 비용 명시

**Files:**
- Modify: `src/main/java/scouter/mcp/tools/Schemas.java`

- [ ] **Step 1: 수정** (스키마는 정적 계약 — 기존 ToolsContractTest가 커버하는 JSON 유효성 외 별도 테스트 불요. 문자열만 변경)

SEARCH_XLOG / SERVICE_SUMMARY의 `from`:

```
"Start time (e.g. now-1h, 2026-06-29T10:00). Start narrow (now-1h) and widen stepwise (now-6h -> now-24h) only when results are empty"
```

SEARCH_XLOG / SERVICE_SUMMARY의 `minElapsedMs`:

```
"Filter: minimum elapsed time (ms). CLIENT-SIDE: the collector streams every row before this drops them - always combine with a server-side filter (service/objNameLike), or prefer get_service_summary/get_summary for 'which is slow' questions"
```

`onlyError`:

```
"Filter: error transactions only. CLIENT-SIDE (rows are streamed then dropped) - combine with a server-side filter, or prefer get_summary category=error"
```

- [ ] **Step 2: 빌드 확인** — `./gradlew compileJava test`
- [ ] **Step 3: Commit** — `docs(schema): steer models to narrow windows and server-side filters`

---

### Task 5: list_threads 도구

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/ThreadRowDto.java`, `ThreadListDto.java`
- Modify: `Limits.java`, `ScouterClient.java`, `TcpScouterClient.java`, `Tools.java`, `Schemas.java`, `McpMain.java`, `messages*.properties`
- Test: `ToolsContractTest.java`

- [ ] **Step 1: DTO 작성**

```java
package scouter.mcp.scouter.dto;

/** One JVM thread row (OBJECT_THREAD_LIST). txid/service are set only while a service is running on it. */
public record ThreadRowDto(long id, String name, String state, long cpuMs,
                           String txid, Long elapsedMs, String service) {
}
```

```java
package scouter.mcp.scouter.dto;

import java.util.List;
import java.util.Map;

/** Per-instance thread list: full state histogram + top rows by cpu (bounded by THREAD_MAX_ROWS). */
public record ThreadListDto(int objHash, String objName, int totalThreads,
                            Map<String, Integer> stateCounts, List<ThreadRowDto> threads, boolean truncated) {
}
```

- [ ] **Step 2: 실패하는 테스트** — ToolsContractTest:

```java
@Test
void listThreadsRendersStateCountsAndRows() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.listThreads(eq("app"), isNull())).thenReturn(List.of(
            new ThreadListDto(1, "/pod/app1", 3,
                    Map.of("RUNNABLE", 2, "BLOCKED", 1),
                    List.of(new ThreadRowDto(11, "http-1", "RUNNABLE", 120, "ztx", 500L, "/api/x")),
                    true)));
    String json = Tools.renderListThreads(Locale.ENGLISH, client, "app", null);
    assertThat(json).contains("RUNNABLE").contains("http-1").contains("\"totalThreads\":3");
    assertThat(json).contains("\"truncated\":true");
}
```

- [ ] **Step 3: 구현**

`Limits.java`:

```java
// --- list_threads ---
/** Max alive instances a fuzzy target may fan out to (a JVM can hold hundreds of threads each). */
public static final int THREAD_MAX_OBJ = 5;
/** Max thread rows returned per instance (top by cpu); the state histogram always covers all threads. */
public static final int THREAD_MAX_ROWS = 50;
```

`ScouterClient.java`:

```java
// JVM thread list per instance (OBJECT_THREAD_LIST). Live snapshot; alive instances only for fuzzy targets.
List<ThreadListDto> listThreads(String objNameLike, Long objHash);
```

`TcpScouterClient.java` — 단일/퍼지 타깃 해석 헬퍼(이후 태스크 재사용) + 구현:

```java
/** Resolve a fuzzy/explicit target to alive instances, capped. objHash wins over objNameLike. */
private List<SObjectDto> resolveAliveTargets(String objNameLike, Long objHash, int cap) {
    List<SObjectDto> all = listObjectsImpl();
    if (objHash != null && objHash != 0L) {
        for (SObjectDto o : all) {
            if (o.objHash() == objHash.intValue()) {
                return List.of(o);
            }
        }
        return List.of(new SObjectDto(objHash.intValue(), "#" + objHash, null, null, true));
    }
    List<SObjectDto> alive = new ArrayList<>();
    for (SObjectDto o : TargetResolver.match(all, objNameLike)) {
        if (o.alive()) {
            alive.add(o);
        }
    }
    if (alive.isEmpty()) {
        throw McpError.of(McpError.Code.NOT_FOUND,
                        Messages.get(config.locale(), "error.target_not_found", String.valueOf(objNameLike).trim()))
                .withHint("candidates", String.join(", ", TargetResolver.suggest(all, 10)));
    }
    return alive.size() > cap ? alive.subList(0, cap) : alive;
}

@Override
public List<ThreadListDto> listThreads(String objNameLike, Long objHash) {
    return SessionRetry.execute(() -> listThreadsImpl(objNameLike, objHash), this::relogin);
}

private List<ThreadListDto> listThreadsImpl(String objNameLike, Long objHash) {
    // OBJECT_THREAD_LIST: {objHash} -> single MapPack of parallel ListValues (id/name/stat/cpu/txid/elapsed/service).
    // The collector relays to the agent, so only alive instances can answer.
    List<ThreadListDto> out = new ArrayList<>();
    for (SObjectDto o : resolveAliveTargets(objNameLike, objHash, Limits.THREAD_MAX_OBJ)) {
        MapPack param = new MapPack();
        param.put(ParamConstant.OBJ_HASH, o.objHash());
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            Pack p = tcp.getSingle(RequestCmd.OBJECT_THREAD_LIST, param);
            if (!(p instanceof MapPack mp)) {
                continue;
            }
            ListValue id = mp.getList("id");
            ListValue name = mp.getList("name");
            ListValue stat = mp.getList("stat");
            ListValue cpu = mp.getList("cpu");
            ListValue txid = mp.getList("txid");
            ListValue elapsed = mp.getList("elapsed");
            ListValue service = mp.getList("service");
            int n = id != null ? id.size() : 0;
            List<ThreadRowDto> rows = new ArrayList<>(n);
            Map<String, Integer> states = new java.util.TreeMap<>();
            for (int i = 0; i < n; i++) {
                String state = lvStr(stat, i);
                states.merge(state != null ? state : "UNKNOWN", 1, Integer::sum);
                String tx = lvStr(txid, i);
                rows.add(new ThreadRowDto(lvLong(id, i), lvStr(name, i), state, lvLong(cpu, i),
                        tx, tx != null ? lvLong(elapsed, i) : null, lvStr(service, i)));
            }
            rows.sort((a, b) -> Long.compare(b.cpuMs(), a.cpuMs())); // busiest first
            boolean truncated = rows.size() > Limits.THREAD_MAX_ROWS;
            if (truncated) {
                rows = new ArrayList<>(rows.subList(0, Limits.THREAD_MAX_ROWS));
            }
            out.add(new ThreadListDto(o.objHash(), o.objName(), n, states, rows, truncated));
        } catch (Exception e) {
            if (!isEof(e)) {
                throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
            }
            // EOF: agent did not answer (down/foreign type) - skip this instance.
        } finally {
            TcpProxy.close(tcp);
        }
    }
    return out;
}
```

`Tools.java`:

```java
public static String renderListThreads(Locale locale, ScouterClient client, String objNameLike, Long objHash) {
    List<ThreadListDto> lists = client.listThreads(objNameLike, objHash);
    List<Map<String, Object>> instances = new ArrayList<>(lists.size());
    for (ThreadListDto t : lists) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objHash", t.objHash());
        m.put("objName", t.objName());
        scouter.mcp.scouter.AppLabel.Label label = scouter.mcp.scouter.AppLabel.of(t.objName());
        m.put("app", label.app());
        if (label.instance() != null) {
            m.put("instance", label.instance());
        }
        m.put("totalThreads", t.totalThreads());
        m.put("stateCounts", t.stateCounts());
        if (t.truncated()) {
            m.put("truncated", true);
        }
        m.put("threads", t.threads());
        instances.add(m);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", lists.size());
    result.put("instances", instances);
    if (lists.isEmpty()) {
        result.put("hint", Messages.get(locale, "hint.threads_empty"));
    }
    try {
        return MAPPER.writeValueAsString(result);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

`Schemas.java`:

```java
public static final String LIST_THREADS = """
    {
      "type": "object",
      "properties": {
        "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Resolved to alive instances, max 5 (a JVM holds hundreds of threads). One of objNameLike/objHash is required."},
        "objHash": {"type": "integer", "description": "Target a single object hash (advanced; prefer objNameLike)"}
      }
    }
    """;
```

`McpMain.java` — 기존 패턴 그대로 등록 (설명):

```java
McpSchema.Tool listThreads = readOnlyTool(jsonMapper, "list_threads",
        "List JVM threads on an agent right now (live snapshot): per-instance state histogram (RUNNABLE/BLOCKED/WAITING...) plus the top 50 threads by cpu, each with id/name/state/cpu and txid/service when a service is running on it. Use for hang, thread-pool exhaustion, or lock-contention triage; drill into one thread with get_thread_detail(txid). One of objNameLike/objHash required.",
        Schemas.LIST_THREADS);
```

핸들러 본문: `objNameLike`/`objHash` 파싱, 둘 다 null이면 INVALID_INPUT("one of objNameLike/objHash is required"), `Tools.renderListThreads(...)`. 기존 catch 3종 패턴 복사. `.tools(...)` 목록에 spec 추가.

`messages.properties` / `_ko`:

```properties
hint.threads_empty=No threads returned. The agent may be down or not a Java agent - check list_objects (alive) first
```
```properties
hint.threads_empty=스레드가 조회되지 않았습니다. 에이전트가 죽었거나 Java 에이전트가 아닐 수 있습니다 - 먼저 list_objects로 alive 여부를 확인하세요
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): add list_threads (OBJECT_THREAD_LIST) with state histogram and cpu-top rows`

---

### Task 6: get_thread_detail 도구

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/ThreadDetailDto.java`
- Modify: `ScouterClient.java`, `TcpScouterClient.java`, `Tools.java`, `Schemas.java`, `McpMain.java`, `messages*.properties`
- Test: `ToolsContractTest.java`

- [ ] **Step 1: DTO**

```java
package scouter.mcp.scouter.dto;

/**
 * Live thread detail (OBJECT_THREAD_DETAIL). The java agent resolves the target via the ACTIVE txid;
 * when the transaction has finished it returns state="end" with no stack. Numeric times are as
 * reported by the agent. sqlBindVar is cleared when bind params are disabled by the operator.
 */
public record ThreadDetailDto(int objHash, String objName,
                              String threadName, Long threadId, String state, String stackTrace,
                              Long cpuTime, Long userTime,
                              Long blockedCount, Long blockedTime, Long waitedCount, Long waitedTime,
                              String lockName, Long lockOwnerId, String lockOwnerName,
                              String serviceTxid, String serviceName, Long serviceElapsedMs,
                              String sql, String sqlBindVar, String subcall) {
}
```

- [ ] **Step 2: 실패하는 테스트** — ToolsContractTest:

```java
@Test
void threadDetailRendersStackAndHintsWhenStale() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.getThreadDetail(isNull(), eq(1L), eq(11L), eq(77L), eq(true))).thenReturn(
            new ThreadDetailDto(1, "/pod/app1", "[No Thread] End", null, "end", null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null));
    String json = Tools.renderThreadDetail(Locale.ENGLISH, client, null, 1L, 11L, 77L, true);
    assertThat(json).contains("\"state\":\"end\"");
    assertThat(json).contains("hint"); // stale txid -> refresh guidance
}
```

- [ ] **Step 3: 구현**

`ScouterClient.java`:

```java
// Live thread detail for an ACTIVE transaction (OBJECT_THREAD_DETAIL). txid is how the java agent
// locates the thread; threadId is optional. includeBindParams=false clears SQLActiveBindVar.
ThreadDetailDto getThreadDetail(String objNameLike, Long objHash, Long threadId, long txid,
                                boolean includeBindParams);
```

`TcpScouterClient.java`:

```java
@Override
public ThreadDetailDto getThreadDetail(String objNameLike, Long objHash, Long threadId, long txid,
                                       boolean includeBindParams) {
    return SessionRetry.execute(
            () -> getThreadDetailImpl(objNameLike, objHash, threadId, txid, includeBindParams), this::relogin);
}

private ThreadDetailDto getThreadDetailImpl(String objNameLike, Long objHash, Long threadId, long txid,
                                            boolean includeBindParams) {
    // OBJECT_THREAD_DETAIL: {objHash, id, txid} -> single MapPack of scalar keys (space-separated names).
    // The server ignores the request when the "id" key is absent, so id is always sent (0 = let the
    // agent find the thread by txid). A finished txid yields {"Thread Name":"[No Thread] End","State":"end"}.
    SObjectDto target = resolveAliveTargets(objNameLike, objHash, 1).get(0);
    MapPack param = new MapPack();
    param.put(ParamConstant.OBJ_HASH, target.objHash());
    param.put("id", threadId != null ? threadId : 0L);
    param.put("txid", txid);
    TcpProxy tcp = TcpProxy.getTcpProxy(server);
    try {
        Pack p = tcp.getSingle(RequestCmd.OBJECT_THREAD_DETAIL, param);
        if (!(p instanceof MapPack mp)) {
            throw McpError.of(McpError.Code.NOT_FOUND,
                    Messages.get(config.locale(), "error.thread_detail_empty"));
        }
        return new ThreadDetailDto(
                target.objHash(), target.objName(),
                textOrNull(mp, "Thread Name"), longOrNull(mp, "Thread Id"),
                textOrNull(mp, "State"),
                Truncate.text(textOrNull(mp, "Stack Trace"), Limits.STACK_TEXT_MAX_CHARS),
                longOrNull(mp, "Thread Cpu Time"), longOrNull(mp, "Thread User Time"),
                longOrNull(mp, "Blocked Count"), longOrNull(mp, "Blocked Time"),
                longOrNull(mp, "Waited Count"), longOrNull(mp, "Waited Time"),
                textOrNull(mp, "Lock Name"), longOrNull(mp, "Lock Owner Id"), textOrNull(mp, "Lock Owner Name"),
                textOrNull(mp, "Service Txid"), textOrNull(mp, "Service Name"), longOrNull(mp, "Service Elapsed"),
                textOrNull(mp, "SQL"),
                includeBindParams ? textOrNull(mp, "SQLActiveBindVar") : null,
                textOrNull(mp, "Subcall"));
    } catch (McpError e) {
        throw e;
    } catch (Exception e) {
        if (isEof(e)) {
            throw McpError.of(McpError.Code.NOT_FOUND,
                    Messages.get(config.locale(), "error.thread_detail_empty"));
        }
        throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
    } finally {
        TcpProxy.close(tcp);
    }
}

private static String textOrNull(MapPack mp, String key) {
    String v = mp.getText(key);
    return v == null || v.isEmpty() ? null : v;
}

private static Long longOrNull(MapPack mp, String key) {
    return mp.containsKey(key) ? mp.getLong(key) : null;
}
```

`Tools.java`:

```java
public static String renderThreadDetail(Locale locale, ScouterClient client, String objNameLike,
                                        Long objHash, Long threadId, long txid, boolean includeBindParams) {
    ThreadDetailDto d = client.getThreadDetail(objNameLike, objHash, threadId, txid, includeBindParams);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("detail", d);
    if ("end".equalsIgnoreCase(d.state()) || (d.threadName() != null && d.threadName().startsWith("[No Thread]"))) {
        // The txid finished between the list call and this drill-down: live snapshot only.
        result.put("hint", Messages.get(locale, "hint.thread_detail_stale"));
    }
    try {
        return MAPPER.writeValueAsString(result);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

`Schemas.java`:

```java
public static final String GET_THREAD_DETAIL = """
    {
      "type": "object",
      "properties": {
        "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment. Resolved to the first alive matching instance. One of objNameLike/objHash is required."},
        "objHash": {"type": "integer", "description": "Target object hash (advanced; prefer objNameLike)"},
        "txid": {"type": "string", "description": "ACTIVE transaction id from get_active_services or list_threads rows (decimal or Hexa32). The agent locates the thread by this; a finished txid returns state='end' - fetch a fresh one and retry immediately."},
        "id": {"type": "integer", "description": "Thread id from list_threads/get_active_services (optional; enables cpu/lock/stack fields)"}
      },
      "required": ["txid"]
    }
    """;
```

`McpMain.java` 등록:

```java
McpSchema.Tool getThreadDetail = readOnlyTool(jsonMapper, "get_thread_detail",
        "Inspect one ACTIVE transaction's thread right now: stack trace, thread state, lock owner (who blocks it), cpu/blocked/waited counters, current SQL and bind values, and the service it runs. Live snapshot - take txid (and id) from get_active_services or list_threads and call this immediately; a finished txid returns state='end'. The standard drill-down for hangs and lock contention.",
        Schemas.GET_THREAD_DETAIL);
```

핸들러: `objNameLike`/`objHash` 파싱(둘 다 null이면 INVALID_INPUT), `txid = requireLong(arguments, "txid")`, `Long id = asLong(arguments, "id")`, `boolean includeBindParams = config.bindParamsEnabled()` (bind kill-switch 재사용, per-call 인자 없음), `Tools.renderThreadDetail(...)`.

`messages.properties` / `_ko`:

```properties
error.thread_detail_empty=No thread detail returned. The agent may be down, or the txid/thread is gone
hint.thread_detail_stale=The transaction already finished (thread detail is a live snapshot). Get a fresh txid from get_active_services or list_threads and call again immediately
```
```properties
error.thread_detail_empty=스레드 상세가 조회되지 않았습니다. 에이전트가 죽었거나 txid/스레드가 이미 사라졌습니다
hint.thread_detail_stale=트랜잭션이 이미 종료되었습니다(스레드 상세는 실시간 스냅샷). get_active_services나 list_threads에서 새 txid를 받아 즉시 다시 호출하세요
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): add get_thread_detail (OBJECT_THREAD_DETAIL) live stack/lock drill-down`

---

### Task 7: get_object_env 도구 (+ 비밀값 마스킹)

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/EnvDto.java`
- Modify: `ScouterClient.java`, `TcpScouterClient.java`, `Tools.java`, `Schemas.java`, `McpMain.java`, `messages*.properties`
- Test: `ToolsContractTest.java`

- [ ] **Step 1: DTO**

```java
package scouter.mcp.scouter.dto;

import java.util.Map;

/** JVM system properties of one agent (OBJECT_ENV). Values are raw; masking/truncation is a render concern. */
public record EnvDto(int objHash, String objName, Map<String, String> properties) {
}
```

- [ ] **Step 2: 실패하는 테스트** — ToolsContractTest (마스킹이 핵심 계약):

```java
@Test
void objectEnvMasksSecretsAndFiltersKeys() {
    ScouterClient client = mock(ScouterClient.class);
    java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("java.version", "17.0.9");
    props.put("db.password", "supersecret");
    props.put("api.token", "abc123");
    props.put("java.class.path", "x".repeat(2000));
    when(client.getObjectEnv(eq("app"), isNull())).thenReturn(new EnvDto(1, "/pod/app1", props));

    String json = Tools.renderObjectEnv(Locale.ENGLISH, client, "app", null, null);
    assertThat(json).contains("17.0.9");
    assertThat(json).doesNotContain("supersecret").doesNotContain("abc123");
    assertThat(json).contains("***");
    assertThat(json).contains("truncated"); // class.path cut to ENV_VALUE_MAX_CHARS

    String filtered = Tools.renderObjectEnv(Locale.ENGLISH, client, "app", null, "version");
    assertThat(filtered).contains("java.version").doesNotContain("class.path");
}
```

- [ ] **Step 3: 구현**

`ScouterClient.java`:

```java
// JVM system properties of one agent (OBJECT_ENV). Fuzzy targets resolve to the first alive instance.
EnvDto getObjectEnv(String objNameLike, Long objHash);
```

`TcpScouterClient.java`:

```java
@Override
public EnvDto getObjectEnv(String objNameLike, Long objHash) {
    return SessionRetry.execute(() -> getObjectEnvImpl(objNameLike, objHash), this::relogin);
}

private EnvDto getObjectEnvImpl(String objNameLike, Long objHash) {
    // OBJECT_ENV: {objHash} -> single MapPack with the whole System.getProperties() flattened (no fixed keys).
    SObjectDto target = resolveAliveTargets(objNameLike, objHash, 1).get(0);
    MapPack param = new MapPack();
    param.put(ParamConstant.OBJ_HASH, target.objHash());
    TcpProxy tcp = TcpProxy.getTcpProxy(server);
    try {
        Pack p = tcp.getSingle(RequestCmd.OBJECT_ENV, param);
        Map<String, String> props = new java.util.TreeMap<>();
        if (p instanceof MapPack mp) {
            java.util.Enumeration<String> en = mp.keys();
            while (en.hasMoreElements()) {
                String k = en.nextElement();
                props.put(k, mp.getText(k));
            }
        }
        return new EnvDto(target.objHash(), target.objName(), props);
    } catch (Exception e) {
        if (isEof(e)) {
            return new EnvDto(target.objHash(), target.objName(), java.util.Map.of());
        }
        throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
    } finally {
        TcpProxy.close(tcp);
    }
}
```

(`mp.keys()` 반환 타입은 구현 시 scouter-common MapPack 시그니처에 맞춘다 — `Enumeration<String>`이 아니면 캐스팅.)

`Tools.java` — 마스킹은 서버측 정책(LLM이 끌 수 없음):

```java
// Env values routinely carry credentials (-Ddb.password=...). Masking is unconditional server-side
// policy, mirroring the bind-param kill-switch philosophy: the LLM cannot opt back in.
private static final java.util.regex.Pattern SECRET_KEY =
        java.util.regex.Pattern.compile("(?i).*(password|passwd|secret|token|credential|private).*");

public static String renderObjectEnv(Locale locale, ScouterClient client, String objNameLike,
                                     Long objHash, String keyLike) {
    EnvDto env = client.getObjectEnv(objNameLike, objHash);
    String needle = keyLike == null ? null : keyLike.toLowerCase();
    Map<String, String> rendered = new LinkedHashMap<>();
    env.properties().forEach((k, v) -> {
        if (needle != null && !k.toLowerCase().contains(needle)) {
            return;
        }
        rendered.put(k, SECRET_KEY.matcher(k).matches() ? "***"
                : scouter.mcp.policy.Truncate.text(v, Limits.ENV_VALUE_MAX_CHARS));
    });
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("objHash", env.objHash());
    result.put("objName", env.objName());
    result.put("count", rendered.size());
    result.put("properties", rendered);
    result.put("note", Messages.get(locale, "note.env_masked"));
    if (rendered.isEmpty()) {
        result.put("hint", Messages.get(locale, "hint.env_empty"));
    }
    try {
        return MAPPER.writeValueAsString(result);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

`Schemas.java`:

```java
public static final String GET_OBJECT_ENV = """
    {
      "type": "object",
      "properties": {
        "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment. Resolved to the first alive matching instance. One of objNameLike/objHash is required."},
        "objHash": {"type": "integer", "description": "Target object hash (advanced; prefer objNameLike)"},
        "keyLike": {"type": "string", "description": "Filter: case-insensitive substring on property keys (e.g. 'mem', 'timezone', 'version'). Recommended - the full property set is large."}
      }
    }
    """;
```

`McpMain.java` 등록:

```java
McpSchema.Tool getObjectEnv = readOnlyTool(jsonMapper, "get_object_env",
        "Read an agent JVM's system properties (java version, -D flags, timezone, paths) for configuration diagnosis. Values of secret-looking keys (password/token/...) are masked server-side. Use keyLike to filter (the full set is large). One of objNameLike/objHash required.",
        Schemas.GET_OBJECT_ENV);
```

핸들러: objNameLike/objHash(둘 다 null → INVALID_INPUT), keyLike 파싱 → `Tools.renderObjectEnv`.

`messages.properties` / `_ko`:

```properties
hint.env_empty=No matching properties. The agent may be down, or relax keyLike
note.env_masked=Values of keys matching password/secret/token/credential/private are masked by server-side policy
```
```properties
hint.env_empty=일치하는 프로퍼티가 없습니다. 에이전트가 죽었거나 keyLike를 완화해 보세요
note.env_masked=password/secret/token/credential/private에 해당하는 키의 값은 서버측 정책으로 마스킹됩니다
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): add get_object_env (OBJECT_ENV) with unconditional secret masking`

---

### Task 8: get_summary 도구 (7개 카테고리)

**Files:**
- Create: `src/main/java/scouter/mcp/scouter/dto/SummaryRowDto.java`, `ErrorSummaryRowDto.java`, `AlertSummaryRowDto.java`, `SummaryResult.java`
- Modify: `Limits.java`, `ScouterClient.java`, `TcpScouterClient.java`, `Tools.java`, `Schemas.java`, `McpMain.java`, `messages*.properties`
- Test: `ToolsContractTest.java`

- [ ] **Step 1: DTO 4종**

```java
package scouter.mcp.scouter.dto;

/** One row of a daily summary (service/sql/apiCall: full metrics; ip/userAgent: name+count only). */
public record SummaryRowDto(String name, long count, Long errorCount, Long totalElapsedMs, Double avgMs) {
}
```

```java
package scouter.mcp.scouter.dto;

/** One row of the service-error daily summary. sampleTxid feeds get_xlog_detail for drill-down. */
public record ErrorSummaryRowDto(String error, String service, String message, long count, String sampleTxid) {
}
```

```java
package scouter.mcp.scouter.dto;

/** One row of the alert daily summary. */
public record AlertSummaryRowDto(String title, String level, long count) {
}
```

```java
package scouter.mcp.scouter.dto;

import java.util.List;

/** get_summary result: exactly one of rows/errorRows/alertRows is non-null depending on category. */
public record SummaryResult(String category, int totalRows, boolean truncated,
                            List<SummaryRowDto> rows,
                            List<ErrorSummaryRowDto> errorRows,
                            List<AlertSummaryRowDto> alertRows) {
}
```

- [ ] **Step 2: 실패하는 테스트** — ToolsContractTest:

```java
@Test
void summaryRendersSqlCategoryTopRows() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.getSummary(eq("sql"), anyLong(), anyLong(), isNull(), isNull(), eq("app"))).thenReturn(
            new SummaryResult("sql", 2, false,
                    List.of(new SummaryRowDto("SELECT * FROM t", 100, 1L, 5000L, 50.0),
                            new SummaryRowDto("UPDATE t SET x=1", 10, 0L, 100L, 10.0)),
                    null, null));
    String json = Tools.renderSummary(Locale.ENGLISH, client, "sql", 0, 1000, null, null, "app");
    assertThat(json).contains("SELECT * FROM t").contains("\"category\":\"sql\"");
    assertThat(json).doesNotContain("errorRows"); // NON_NULL omission
}

@Test
void summaryEmptyGetsHint() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.getSummary(eq("error"), anyLong(), anyLong(), isNull(), isNull(), isNull())).thenReturn(
            new SummaryResult("error", 0, false, null, List.of(), null));
    String json = Tools.renderSummary(Locale.ENGLISH, client, "error", 0, 1000, null, null, null);
    assertThat(json).contains("hint");
}
```

- [ ] **Step 3: 구현**

`Limits.java`:

```java
// --- get_summary / get_counter_stat (daily pre-aggregated stats) ---
/** Max calendar days a daily-stat query may span. Cheap per day, but bounded regardless. */
public static final int DAILY_STAT_MAX_DAYS = 31;
/** Max rows per get_summary category (top by count). */
public static final int SUMMARY_TOOL_MAX_ROWS = 50;
```

`ScouterClient.java`:

```java
// Daily pre-aggregated summary (LOAD_*_SUMMARY). category: service|sql|apiCall|ip|userAgent|error|alert.
// Windows are split per calendar day; hashes are text-resolved after merging.
SummaryResult getSummary(String category, long fromMillis, long toMillis,
                         String objType, Long objHash, String objNameLike);
```

`TcpScouterClient.java` — 핵심 구현 (카테고리 → 명령 매핑, 일분할 getSingle, id 병합, 텍스트 복원):

```java
@Override
public SummaryResult getSummary(String category, long fromMillis, long toMillis,
                                String objType, Long objHash, String objNameLike) {
    return SessionRetry.execute(
            () -> getSummaryImpl(category, fromMillis, toMillis, objType, objHash, objNameLike), this::relogin);
}

private static String summaryCmd(String category) {
    return switch (category) {
        case "service" -> RequestCmd.LOAD_SERVICE_SUMMARY;
        case "sql" -> RequestCmd.LOAD_SQL_SUMMARY;
        case "apiCall" -> RequestCmd.LOAD_APICALL_SUMMARY;
        case "ip" -> RequestCmd.LOAD_IP_SUMMARY;
        case "userAgent" -> RequestCmd.LOAD_UA_SUMMARY;
        case "error" -> RequestCmd.LOAD_SERVICE_ERROR_SUMMARY;
        case "alert" -> RequestCmd.LOAD_ALERT_SUMMARY;
        default -> null;
    };
}

/** Accumulator for id-keyed summary rows merged across days/instances. */
private static final class SumAcc {
    long count;
    long error;
    long elapsed;
    long anyYmd;
    long sampleTxid;
    int serviceHash;
    int messageHash;
    String title;
    int level;
}

private SummaryResult getSummaryImpl(String category, long fromMillis, long toMillis,
                                     String objType, Long objHash, String objNameLike) {
    String cmd = summaryCmd(category);
    if (cmd == null) {
        throw McpError.of(McpError.Code.INVALID_INPUT,
                Messages.get(config.locale(), "error.summary_bad_category", String.valueOf(category)));
    }
    if (toMillis - fromMillis <= 0) {
        throw McpError.of(McpError.Code.INVALID_INPUT, Messages.get(config.locale(), "error.from_after_to"));
    }
    List<DaySplitter.Segment> segments = DaySplitter.splitByCalendarDay(fromMillis, toMillis, config.zone());
    if (segments.size() > Limits.DAILY_STAT_MAX_DAYS) {
        throw McpError.of(McpError.Code.INVALID_INPUT,
                Messages.get(config.locale(), "error.summary_window_too_long", Limits.DAILY_STAT_MAX_DAYS));
    }
    // Target: explicit objHash > fuzzy objNameLike (per-instance fan-out) > objType (server-side) > all.
    List<Integer> hashes = new ArrayList<>();
    if (objHash != null && objHash != 0L) {
        hashes.add(objHash.intValue());
    } else if (objNameLike != null && !objNameLike.isBlank()) {
        for (SObjectDto o : resolveAliveTargets(objNameLike, null, Limits.SEARCH_MAX_OBJ)) {
            hashes.add(o.objHash());
        }
    } else {
        hashes.add(0); // 0 = no objHash restriction (whole objType / whole collector)
    }
    ensurePassBudget(hashes.size() * segments.size(), config.locale());
    long startedAt = System.currentTimeMillis();

    Map<String, SumAcc> acc = new LinkedHashMap<>();
    for (Integer h : hashes) {
        for (DaySplitter.Segment seg : segments) {
            MapPack param = new MapPack();
            String ymd = scouter.mcp.time.TimeRange.yyyymmdd(seg.fromMillis(), config.zone());
            param.put(ParamConstant.DATE, ymd);
            param.put(ParamConstant.STIME, seg.fromMillis());
            param.put(ParamConstant.ETIME, seg.toMillis());
            if (objType != null && !objType.isBlank()) {
                param.put(ParamConstant.OBJ_TYPE, objType.trim());
            }
            param.put(ParamConstant.OBJ_HASH, h);
            TcpProxy tcp = TcpProxy.getTcpProxy(server);
            try {
                Pack p = tcp.getSingle(cmd, param);
                if (p instanceof MapPack mp) {
                    mergeSummaryDay(category, mp, Long.parseLong(ymd), acc);
                }
            } catch (Exception e) {
                if (!isEof(e)) {
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
                }
                // EOF: no summary data for this day/target - routine.
            } finally {
                TcpProxy.close(tcp);
            }
        }
    }
    SummaryResult result = buildSummaryResult(category, acc);
    log.info("get_summary done: category={}, passes={}, rows={}, tookMs={}",
            category, hashes.size() * segments.size(), acc.size(), System.currentTimeMillis() - startedAt);
    return result;
}

private static void mergeSummaryDay(String category, MapPack mp, long ymd, Map<String, SumAcc> acc) {
    ListValue id = mp.getList("id");
    int n = id != null ? id.size() : 0;
    ListValue count = mp.getList("count");
    ListValue error = mp.getList("error");
    ListValue elapsed = mp.getList("elapsed");
    ListValue service = mp.getList("service");
    ListValue message = mp.getList("message");
    ListValue txid = mp.getList("txid");
    ListValue title = mp.getList("title");
    ListValue level = mp.getList("level");
    for (int i = 0; i < n; i++) {
        String key;
        if ("error".equals(category)) {
            key = id.getInt(i) + ":" + (error != null ? error.getInt(i) : 0)
                    + ":" + (service != null ? service.getInt(i) : 0);
        } else {
            key = String.valueOf(id.getInt(i));
        }
        SumAcc a = acc.computeIfAbsent(key, k -> new SumAcc());
        if (a.anyYmd == 0) {
            a.anyYmd = ymd;
        }
        a.count += count != null && i < count.size() ? count.getLong(i) : 0;
        if ("error".equals(category)) {
            a.serviceHash = service != null ? service.getInt(i) : 0;
            a.messageHash = message != null ? message.getInt(i) : 0;
            a.error = error != null ? error.getInt(i) : 0; // error text hash (not a count)
            if (a.sampleTxid == 0 && txid != null && i < txid.size()) {
                a.sampleTxid = txid.getLong(i);
            }
        } else {
            a.error += error != null && i < error.size() ? error.getLong(i) : 0;
            a.elapsed += elapsed != null && i < elapsed.size() ? elapsed.getLong(i) : 0;
        }
        if ("alert".equals(category)) {
            if (a.title == null && title != null && i < title.size()) {
                a.title = title.getString(i);
            }
            a.level = level != null && i < level.size() ? level.getInt(i) : 0;
        }
    }
}

private SummaryResult buildSummaryResult(String category, Map<String, SumAcc> acc) {
    // Sort by count desc, cap rows, then resolve hashes to text in one batch per (type, day).
    List<Map.Entry<String, SumAcc>> entries = new ArrayList<>(acc.entrySet());
    entries.sort((a, b) -> Long.compare(b.getValue().count, a.getValue().count));
    boolean truncated = entries.size() > Limits.SUMMARY_TOOL_MAX_ROWS;
    if (truncated) {
        entries = entries.subList(0, Limits.SUMMARY_TOOL_MAX_ROWS);
    }
    TextDictionary dict = new TextDictionary(server, null);
    String textType = switch (category) {
        case "service" -> TextTypes.SERVICE;
        case "sql" -> TextTypes.SQL;
        case "apiCall" -> TextTypes.APICALL;
        case "userAgent" -> TextTypes.USER_AGENT;
        default -> null;
    };
    if (textType != null) {
        Map<Long, Set<Integer>> byYmd = new HashMap<>();
        for (Map.Entry<String, SumAcc> e : entries) {
            byYmd.computeIfAbsent(e.getValue().anyYmd, k -> new LinkedHashSet<>())
                    .add(Integer.parseInt(e.getKey()));
        }
        byYmd.forEach((ymd, hs) -> dict.prefetch(textType, ymd, hs));
    }
    if ("error".equals(category)) {
        Map<Long, Set<Integer>> errByYmd = new HashMap<>();
        Map<Long, Set<Integer>> svcByYmd = new HashMap<>();
        for (Map.Entry<String, SumAcc> e : entries) {
            SumAcc a = e.getValue();
            errByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add((int) a.error);
            errByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add(a.messageHash);
            svcByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add(a.serviceHash);
        }
        errByYmd.forEach((ymd, hs) -> dict.prefetch(TextTypes.ERROR, ymd, hs));
        svcByYmd.forEach((ymd, hs) -> dict.prefetch(TextTypes.SERVICE, ymd, hs));
    }

    List<SummaryRowDto> rows = null;
    List<ErrorSummaryRowDto> errorRows = null;
    List<AlertSummaryRowDto> alertRows = null;
    switch (category) {
        case "service", "sql", "apiCall" -> {
            rows = new ArrayList<>(entries.size());
            for (Map.Entry<String, SumAcc> e : entries) {
                SumAcc a = e.getValue();
                int hash = Integer.parseInt(e.getKey());
                String raw = resolveSummaryText(dict, textType, a.anyYmd, hash);
                String name = scouter.mcp.policy.Truncate.text(raw, Limits.SQL_TEXT_MAX_CHARS);
                double avg = a.count == 0 ? 0 : (double) a.elapsed / a.count;
                rows.add(new SummaryRowDto(name, a.count, a.error, a.elapsed,
                        Math.round(avg * 10) / 10.0));
            }
        }
        case "ip" -> {
            rows = new ArrayList<>(entries.size());
            for (Map.Entry<String, SumAcc> e : entries) {
                int ipInt = Integer.parseInt(e.getKey());
                rows.add(new SummaryRowDto(decodeIp(ipInt), e.getValue().count, null, null, null));
            }
        }
        case "userAgent" -> {
            rows = new ArrayList<>(entries.size());
            for (Map.Entry<String, SumAcc> e : entries) {
                SumAcc a = e.getValue();
                String name = resolveSummaryText(dict, textType, a.anyYmd, Integer.parseInt(e.getKey()));
                rows.add(new SummaryRowDto(name, a.count, null, null, null));
            }
        }
        case "error" -> {
            errorRows = new ArrayList<>(entries.size());
            for (Map.Entry<String, SumAcc> e : entries) {
                SumAcc a = e.getValue();
                String err = dict.error(a.anyYmd, (int) a.error);
                String svc = dict.service(a.anyYmd, a.serviceHash);
                String msg = dict.error(a.anyYmd, a.messageHash);
                errorRows.add(new ErrorSummaryRowDto(
                        scouter.mcp.policy.Truncate.text(err != null ? err : "#" + a.error, Limits.ERROR_TEXT_MAX_CHARS),
                        svc != null ? svc : "#" + a.serviceHash,
                        scouter.mcp.policy.Truncate.text(msg != null ? msg : "#" + a.messageHash, Limits.ERROR_TEXT_MAX_CHARS),
                        a.count,
                        a.sampleTxid != 0 ? Hexa32.toString32(a.sampleTxid) : null));
            }
        }
        case "alert" -> {
            alertRows = new ArrayList<>(entries.size());
            for (Map.Entry<String, SumAcc> e : entries) {
                SumAcc a = e.getValue();
                alertRows.add(new AlertSummaryRowDto(
                        a.title != null ? a.title : "#" + e.getKey(),
                        AlertLevel.getName((byte) a.level), a.count));
            }
        }
        default -> {
        }
    }
    return new SummaryResult(category, entries.size(), truncated, rows, errorRows, alertRows);
}

private static String resolveSummaryText(TextDictionary dict, String textType, long ymd, int hash) {
    String t = textType == null ? null : dictResolve(dict, textType, ymd, hash);
    return t != null ? t : "#" + hash;
}
```

주의: `TextDictionary`에는 service/error/sql/method 전용 getter만 있다 — **`TextDictionary`에 범용 getter를 추가**한다:

```java
/** Generic typed resolver for callers outside the TextResolver interface (apicall, user_agent, ...). */
public String text(String type, long yyyymmdd, int hash) {
    return resolve(type, yyyymmdd, hash);
}
```

그리고 위 `dictResolve(dict, textType, ymd, hash)`는 `dict.text(textType, ymd, hash)` 직접 호출로 대체한다. `decodeIp`:

```java
private static String decodeIp(int ipInt) {
    // Summary stores client IPs int-encoded; decode via scouter's own util (falls back to raw on failure).
    try {
        return scouter.util.IPUtil.toString(scouter.io.DataInputX.toBytes(ipInt));
    } catch (RuntimeException e) {
        return "#" + ipInt;
    }
}
```

(구현 시 `javap`로 scouter-common의 `IPUtil.toString(byte[])`/`DataInputX.toBytes(int)` 시그니처를 확인하고, `AlertLevel.getName`의 파라미터 타입(byte/int)도 확인해 캐스팅을 맞춘다. `TextTypes.APICALL`/`TextTypes.USER_AGENT` 상수 존재도 javap로 확인.)

`Tools.java`:

```java
public static String renderSummary(Locale locale, ScouterClient client, String category,
                                   long fromMillis, long toMillis, String objType, Long objHash,
                                   String objNameLike) {
    SummaryResult res = client.getSummary(category, fromMillis, toMillis, objType, objHash, objNameLike);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("category", res.category());
    result.put("rowCount", res.totalRows());
    if (res.truncated()) {
        result.put("truncated", true);
    }
    if (res.rows() != null) {
        result.put("rows", res.rows());
    }
    if (res.errorRows() != null) {
        result.put("errorRows", res.errorRows());
    }
    if (res.alertRows() != null) {
        result.put("alertRows", res.alertRows());
    }
    boolean empty = (res.rows() == null || res.rows().isEmpty())
            && (res.errorRows() == null || res.errorRows().isEmpty())
            && (res.alertRows() == null || res.alertRows().isEmpty());
    if (empty) {
        result.put("hint", Messages.get(locale, "hint.summary_empty"));
    }
    try {
        return MAPPER.writeValueAsString(result);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

`Schemas.java`:

```java
public static final String GET_SUMMARY = """
    {
      "type": "object",
      "properties": {
        "category": {"type": "string", "enum": ["service", "sql", "apiCall", "ip", "userAgent", "error", "alert"], "description": "What to aggregate: service (per-URL), sql (per-statement - 'which SQL is slow/hot'), apiCall (outbound calls), ip (client IPs), userAgent, error (error type x service with a sample txid), alert (alert titles)"},
        "from": {"type": "string", "description": "Start time (e.g. now-24h, 2026-06-29T10:00). Daily granularity; up to 31 days"},
        "to": {"type": "string", "description": "End time (e.g. now)"},
        "objType": {"type": "string", "description": "Filter: object type (e.g. tomcat). Server-side"},
        "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment, fanned out over matching instances"},
        "objHash": {"type": "integer", "description": "Filter: a single object hash (advanced)"}
      },
      "required": ["category", "from", "to"]
    }
    """;
```

`McpMain.java` 등록:

```java
McpSchema.Tool getSummary = readOnlyTool(jsonMapper, "get_summary",
        "Daily PRE-AGGREGATED stats from the collector (no XLog scanning, no scan cap - the cheapest wide-window tool). category=sql answers 'which SQL is slowest/hottest today', error gives error-type x service counts with a sample txid for get_xlog_detail, service/apiCall/ip/userAgent/alert likewise. Top 50 by count. Prefer this over search_xlog for ranking/frequency questions; note it is daily data, so use from/to at day scale (up to 31 days).",
        Schemas.GET_SUMMARY);
```

핸들러: category(필수), from/to 파싱, objType/objNameLike/objHash 파싱 → `Tools.renderSummary`.

`messages.properties` / `_ko`:

```properties
error.summary_bad_category=Unknown category "{0}". Use one of: service, sql, apiCall, ip, userAgent, error, alert
error.summary_window_too_long=Summary window is too long (max {0} days)
hint.summary_empty=No summary rows. Widen the date range or drop filters; the collector may also have no summary data for this day/category
```
```properties
error.summary_bad_category=알 수 없는 category "{0}". service, sql, apiCall, ip, userAgent, error, alert 중 하나를 쓰세요
error.summary_window_too_long=summary 조회 기간이 너무 깁니다 (최대 {0}일)
hint.summary_empty=summary 데이터가 없습니다. 기간을 넓히거나 필터를 줄여 보세요. 해당 일자/카테고리에 collector summary 데이터가 없을 수도 있습니다
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): add get_summary (LOAD_*_SUMMARY) daily pre-aggregated stats, 7 categories`

---

### Task 9: get_counter_stat 도구 (5분 해상도 장기 통계)

**Files:**
- Modify: `ScouterClient.java`, `TcpScouterClient.java`, `Tools.java`, `Schemas.java`, `McpMain.java`, `messages*.properties`
- Test: `ToolsContractTest.java`

- [ ] **Step 1: 실패하는 테스트** — ToolsContractTest:

```java
@Test
void counterStatRendersSeriesLikeGetCounter() {
    ScouterClient client = mock(ScouterClient.class);
    when(client.getCounterStat(any(), eq("TPS"), eq("20260601"), eq("20260630"))).thenReturn(List.of(
            new CounterSeriesDto(101, "TPS", List.of(
                    new PackMapper.Point(1000L, 10d), new PackMapper.Point(2000L, 20d)))));
    when(client.listObjects()).thenReturn(List.of());
    String json = Tools.renderCounterStat(Locale.ENGLISH, client, List.of(101), "TPS", "20260601", "20260630");
    assertThat(json).contains("\"counter\":\"TPS\"").contains("\"resolution\":\"5m\"");
}
```

- [ ] **Step 2: 실패 확인** — 컴파일 에러 예상
- [ ] **Step 3: 구현**

`ScouterClient.java`:

```java
// Long-range counter stats at fixed 5-minute resolution (COUNTER_PAST_LONGDATE_ALL, daily-stat DB).
// Complements getCounter (full resolution, 24h cap) for week/month trend questions.
List<CounterSeriesDto> getCounterStat(List<Integer> objHashes, String counter, String sDateYmd, String eDateYmd);
```

`TcpScouterClient.java`:

```java
@Override
public List<CounterSeriesDto> getCounterStat(List<Integer> objHashes, String counter,
                                             String sDateYmd, String eDateYmd) {
    return SessionRetry.execute(() -> getCounterStatImpl(objHashes, counter, sDateYmd, eDateYmd), this::relogin);
}

private List<CounterSeriesDto> getCounterStatImpl(List<Integer> objHashes, String counter,
                                                  String sDateYmd, String eDateYmd) {
    // COUNTER_PAST_LONGDATE_ALL: {counter, sDate, eDate, objHash:ListValue} -> per (day x objHash)
    // MapPack{objHash,time,value} stream at fixed 5-min resolution. One round-trip for the whole range
    // (the server iterates days), so no DaySplitter here. Param keys are camelCase: sDate/eDate.
    long startedAt = System.currentTimeMillis();
    MapPack param = new MapPack();
    param.put(ParamConstant.COUNTER, counter);
    param.put(ParamConstant.SDATE, sDateYmd);
    param.put(ParamConstant.EDATE, eDateYmd);
    ListValue lv = param.newList(ParamConstant.OBJ_HASH);
    for (Integer h : objHashes) {
        lv.add((long) h);
    }
    Map<Integer, List<PackMapper.Point>> merged = new LinkedHashMap<>();
    TcpProxy tcp = TcpProxy.getTcpProxy(server);
    try {
        tcp.process(RequestCmd.COUNTER_PAST_LONGDATE_ALL, param, in -> {
            Pack p = in.readPack();
            if (p instanceof MapPack mp) {
                int objHash = mp.getInt(ParamConstant.OBJ_HASH);
                merged.computeIfAbsent(objHash, k -> new ArrayList<>())
                        .addAll(PackMapper.toPoints(mp.getList(ParamConstant.TIME), mp.getList(ParamConstant.VALUE)));
            }
        });
    } catch (Exception e) {
        if (!isEof(e)) {
            throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
        }
    } finally {
        TcpProxy.close(tcp);
    }
    List<CounterSeriesDto> out = new ArrayList<>(merged.size());
    for (Map.Entry<Integer, List<PackMapper.Point>> e : merged.entrySet()) {
        e.getValue().sort(java.util.Comparator.comparingLong(PackMapper.Point::timeMillis));
        out.add(new CounterSeriesDto(e.getKey(), counter, e.getValue()));
    }
    log.info("get_counter_stat done: counter={}, objs={}, series={}, tookMs={}",
            counter, objHashes.size(), out.size(), System.currentTimeMillis() - startedAt);
    return out;
}
```

`Tools.java` — `renderGetCounter`의 시리즈 렌더 본문을 private 헬퍼 `renderSeriesResult(locale, client, counter, series, extra)`로 추출해 공유:

```java
public static String renderCounterStat(Locale locale, ScouterClient client, List<Integer> objHashes,
                                       String counter, String sDateYmd, String eDateYmd) {
    List<CounterSeriesDto> series = client.getCounterStat(objHashes, counter, sDateYmd, eDateYmd);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("resolution", "5m");
    extra.put("sDate", sDateYmd);
    extra.put("eDate", eDateYmd);
    return renderSeriesResult(locale, client, counter, series, extra, "hint.counter_stat_empty");
}
```

(`renderSeriesResult`는 기존 renderGetCounter 로직 그대로: objName/app 라벨, stats, min/max 다운샘플, 빈 결과 힌트 키만 파라미터화. renderGetCounter는 `extra=null`, `"hint.counter_empty"`로 위임.)

`Schemas.java`:

```java
public static final String GET_COUNTER_STAT = """
    {
      "type": "object",
      "properties": {
        "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (max 20 instances). PREFER THIS."},
        "objHashes": {"type": "array", "items": {"type": "integer"}, "description": "Target object hashes (advanced)"},
        "objType": {"type": "string", "description": "Targets all objects of this type (capped at 20 instances)"},
        "counter": {"type": "string", "description": "Counter name (e.g. TPS, Cpu, Heap)"},
        "from": {"type": "string", "description": "Start time (e.g. now-7d, 2026-06-01). Day granularity, up to 31 days"},
        "to": {"type": "string", "description": "End time (e.g. now)"}
      },
      "required": ["counter", "from", "to"]
    }
    """;
```

`McpMain.java` 등록 — get_counter 핸들러와 동일한 타깃 해석/팬아웃 캡 재사용:

```java
McpSchema.Tool getCounterStat = readOnlyTool(jsonMapper, "get_counter_stat",
        "Counter time series over a LONG range (up to 31 days) at fixed 5-minute resolution, from the collector's daily-stat DB - one cheap round-trip. Use for week-over-week trends and 'compare today vs last week'; use get_counter for full-resolution views within 24h.",
        Schemas.GET_COUNTER_STAT);
```

핸들러: objHashes/objType/objNameLike 해석(기존 get_counter와 동일 헬퍼 재사용), from/to → `TimeRange.parseInstant` 후:

```java
String sDate = TimeRange.yyyymmdd(fromMillis, config.zone());
String eDate = TimeRange.yyyymmdd(toMillis, config.zone());
long days = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.LocalDate.parse(sDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
        java.time.LocalDate.parse(eDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)) + 1;
if (days > Limits.DAILY_STAT_MAX_DAYS) {
    throw scouter.mcp.error.McpError.of(scouter.mcp.error.McpError.Code.INVALID_INPUT,
            Messages.get(config.locale(), "error.stat_window_too_long", Limits.DAILY_STAT_MAX_DAYS));
}
```

팬아웃 캡(COUNTER_MAX_OBJ) 및 objTruncated note는 get_counter 핸들러 코드와 동일하게.

`messages.properties` / `_ko`:

```properties
error.stat_window_too_long=Counter stat window is too long (max {0} days)
hint.counter_stat_empty=No results. Check the counter name and dates; this reads the collector's daily 5-minute stat DB
```
```properties
error.stat_window_too_long=counter stat 조회 기간이 너무 깁니다 (최대 {0}일)
hint.counter_stat_empty=데이터가 없습니다. counter 이름과 날짜를 확인하세요. 이 도구는 collector의 일별 5분 통계 DB를 읽습니다
```

- [ ] **Step 4: 테스트 PASS** — `./gradlew test`
- [ ] **Step 5: Commit** — `feat(tools): add get_counter_stat (COUNTER_PAST_LONGDATE_ALL) 5-min long-range stats`

---

### Task 10: 플레이북 갱신 + README 갱신 + 마무리 검증

**Files:**
- Modify: `McpMain.java` (DIAGNOSE_PLAYBOOK)
- Modify: `README.md`, `README.ko.md` (도구 표 14종, 정책 불릿, 보안 불릿)

- [ ] **Step 1: DIAGNOSE_PLAYBOOK 개정** — 순서 재구성:

```
1. list_objects — discovery only.
2. get_summary — FIRST for ranking/frequency questions ("which SQL/service/error is worst today"):
   daily pre-aggregated, no scan cap. category=error rows carry a sample txid for step 5.
3. search_xlog — find specific slow/failing transactions (narrow window + server-side filter).
4. get_service_summary — per-service aggregate within a day window (finer than get_summary's daily grain).
5. get_xlog_detail / get_xlog_by_gxid — drill into one transaction (SQL/binds/steps).
6. get_counter / get_counter_stat — blast radius: full-res within 24h vs 5-min stats up to 31 days
   ("compare with last week" -> get_counter_stat).
7. Live triage: get_active_services -> list_threads (state histogram, cpu-top) ->
   get_thread_detail(txid[, id]) for the stack/lock owner. Thread detail needs an ACTIVE txid -
   take it from the immediately preceding call.
8. get_object_env — JVM flags/timezone/versions when configuration is the suspect.
9. list_alerts / cross-correlate with OpenSearch/Datadog via objName + endTimeIso + txid/gxid.
```

기존 Tips 유지 + 추가:

```
- minElapsedMs/onlyError are client-side: the collector streams rows before they drop. For "which is
  slow/erroring" questions prefer get_summary (sql/error) or get_service_summary instead.
- Thread drill-down is a live snapshot: list_threads/get_active_services -> get_thread_detail must be
  back-to-back; a finished txid returns state="end".
```

- [ ] **Step 2: README 갱신** — 도구 표에 5행 추가(list_threads, get_thread_detail, get_object_env, get_summary, get_counter_stat), "리소스/토큰 안전 정책"에 불릿 추가:
  - SQL/에러 텍스트 절삭(1500/500자), 프로파일 스텝 상한 150
  - 요청당 collector 왕복(passes) 상한 40
  - list_threads 인스턴스 5개/행 50개 상한, get_summary 상위 50행/31일 상한, get_counter_stat 31일 상한
  - 보안 절: get_object_env 비밀키 마스킹(무조건, LLM이 해제 불가), get_thread_detail의 SQLActiveBindVar는 bind kill-switch를 따름
- [ ] **Step 3: 전체 검증** — `./gradlew clean test shadowJar` → BUILD SUCCESSFUL 확인
- [ ] **Step 4: Commit** — `docs: playbook + README for the 5 new tools and tightened guardrails`

---

## Self-Review 체크

- 사용자 요구 커버리지: 신규 도구 4건(스레드 2종=1건 취급) ✅ Task 5,6,7,8,9 / P0 ✅ Task 1 / P1 ✅ Task 2,3 / P2 ✅ Task 4 / 기타제안 4건(플레이북 ✅ Task 10, 효율힌트 ✅ Task 3, 텔레메트리 ✅ Task 2·8·9, 마스킹 ✅ Task 7) — get_thread_dump/get_heap_histogram/get_server_status는 사용자가 제외 지시.
- 타입 일관성: `resolveAliveTargets`(Task 5 정의)를 Task 6,7,8이 재사용. `Truncate.text`(Task 1)를 Task 6,7,8이 재사용. `renderSeriesResult` 추출은 Task 9에서 수행.
- 구현 시 javap 확인 항목: `TextTypes.APICALL`/`USER_AGENT`, `IPUtil.toString(byte[])`, `DataInputX.toBytes(int)`, `AlertLevel.getName` 파라미터 타입, `MapPack.keys()` 반환 타입, `MapPack.getText/getLong/containsKey` 시그니처.
