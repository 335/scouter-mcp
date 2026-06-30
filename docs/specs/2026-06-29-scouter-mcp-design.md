# Scouter MCP 설계 (Design Spec)

- 작성일: 2026-06-29
- 상태: 구현 완료 (코드가 최종 기준)
- 목적: Scouter APM의 XLog/지표를 AI(MCP)로 빠르게 탐색하고, OpenSearch/Datadog 로그와 교차 분석하여 오류 원인 파악을 가속한다.

> **구현 반영 정정(Errata).** 아래는 구현 과정에서 확정된 설계 대비 차이다(코드가 최종 기준).
> - §4.2 XLog row의 시각 필드는 `endTime(epochMs)` 단일 대신 `endTimeMillis`(epochMs) + `endTimeIso`(ISO, 교차검색용) 두 개로 제공한다.
> - §4.3 `get_xlog_detail`의 `sqls[]`에 `rows?` 필드는 두지 않는다(상류 SqlStep에 신뢰 가능한 row count가 없어 제외).
> - 시간 처리·민감정보 마스킹 등 순수 로직은 TDD로 검증됨. 도구 실행 실패는 MCP tool-error(`isError=true`)로 `{code, message, hints}`를 반환한다.

---

## 1. 배경 / 문제

- 현재 Scouter는 Eclipse 기반 RCP 클라이언트로 접근해 XLog 지표(응답속도, 오류)를 보고, 특정 XLog 상세에서 주로 DB 바인드 파라미터를 확인하는 용도로 쓰인다.
- 같은 사건을 Datadog / OpenSearch 로그와 함께 봐야 하는데 도구가 분리되어 크로스 탐색이 번거롭다.
- AI(Claude Code)가 Scouter 데이터를 직접 조회할 수 있게 하면, 특정 기간의 지표 탐색과 오류 원인 파악을 한 자리에서 빠르게 할 수 있다.

## 2. 확정된 핵심 결정 (브레인스토밍 결과)

| 항목 | 결정 | 비고 |
|---|---|---|
| Scouter 접근 경로 | **Collector 서버 TCP** | 사내에서 `scouter.webapp`은 통상 사용하지 않음 |
| TCP 처리 방식 | **MCP가 TCP 프로토콜 직접 사용** | webapp 브리지 미사용 |
| 구현 스택 | **Kotlin/Java + `scouter.common`(+필요시 `scouter.client`) 재사용** | Pack 직렬화/TcpProxy/로그인 공식 코드 재사용으로 프로토콜 리스크 최소화 |
| 크로스 탐색 책임 | **Scouter MCP는 Scouter 전용**, 상관은 AI가 오케스트레이션 | 이미 붙은 Datadog/OpenSearch MCP와 AI가 조합 |
| 패키징/트랜스포트 | **단일 Java/Kotlin MCP 서버 (stdio)** | Claude Code가 로컬 jar 기동, 단일 사용자 |
| 동작 범위 | **읽기 전용** | collector에 쓰기 커맨드 미노출 |

## 3. 아키텍처 개요

```
Claude Code (MCP client)
        │  stdio (JSON-RPC, MCP)
        ▼
Scouter MCP Server (Kotlin/Java, 단일 jar)
  ├─ MCP Layer        : 도구 정의/입출력 스키마, 입력 검증
  ├─ Service Layer    : 기간/필터 → Scouter 요청 변환, 결과 정규화(JSON)
  ├─ Scouter Client   : scouter.client TcpProxy + 로그인 세션 풀/재연결
  └─ Config           : collector host:port, id/pw(env), 기본 타임존/리밋
        │  Scouter TCP (pack 프로토콜, 기본 6100)
        ▼
Scouter Collector Server (기존 사내 서버, 무변경)
```

원칙:
- **읽기 전용**: 조회 커맨드만 사용.
- **공식 코드 재사용**: Pack 직렬화·`TcpProxy`·로그인은 `scouter.common`/`scouter.client` 그대로. 자체 작성 범위는 도구 래핑 + 결과 정규화.
- **상관은 출력으로 위임**: XLog 결과에 `txid`, `gxid`, `objName`, `endTime`(epochMs)을 항상 포함시켜 AI가 OpenSearch/Datadog 검색어로 그대로 사용.

## 4. 도구(MCP Tool) 명세

모든 도구: 읽기 전용 / 입력 스키마 검증 / 정규화된 JSON 출력.

### 4.1 `list_objects` — 오브젝트·서비스 목록 (탐색 기초)
- 입력: `from`, `to`(미지정 시 현재), `objType?`, `nameLike?`
- 내부 커맨드: `OBJECT_LIST_REAL_TIME` / `OBJECT_LIST_LOAD_DATE`
- 출력: `[{ objHash, objName, objType, address, alive }]`

### 4.2 `search_xlog` — XLog 검색 (응답속도·에러 파악)
- 입력: `from`, `to`(필수), `objHash?|objName?`, `service?`(URL/서비스명 like), `minElapsedMs?`, `onlyError?`, `limit?`(기본 100, 상한 1000)
- 내부 커맨드: `SEARCH_XLOG_LIST`(조건검색) 우선, 단순 기간은 `TRANX_LOAD_TIME`
- 출력 row: `{ txid, gxid, objName, service, elapsedMs, error?, cpuMs, sqlCount, endTime(epochMs), endTimeIso }`
- 상관 키(txid/gxid/endTime/objName) 항상 포함.

### 4.3 `get_xlog_detail` — XLog 상세 (바인드 파라미터·SQL)
- 입력: `txid`(필수), `gxid?`, `includeBindParams?`(기본 true), `maskSensitive?`(기본 true)
- 내부 커맨드: `XLOG_READ_BY_TXID` + 프로파일(스텝/SQL/바인드) 로드
- 출력: `{ summary{...}, steps:[{ type, name, elapsedMs }], sqls:[{ sql, bindParams[], elapsedMs, rows? }], errors:[...] }`
- 마스킹 정책 적용(§6).

### 4.4 `get_xlog_by_gxid` — 분산 트랜잭션 묶음 조회
- 입력: `gxid`(필수)
- 내부 커맨드: `XLOG_LOAD_BY_GXID`
- 출력: 동일 gxid의 XLog row 배열(서비스 간 호출 흐름).

### 4.5 `get_counter` — 카운터/지표 시계열
- 입력: `objHash|objType`, `counter`(예: `Elapsed`, `TPS`, `ErrorRate`, `GcTime`), `from`, `to`, `interval?`
- 내부 커맨드: `COUNTER_PAST_TIME` / `COUNTER_PAST_DATE` (실시간은 `COUNTER_REAL_TIME`)
- 출력: `{ counter, unit, points:[{ time(epochMs), value }] }`

### 4.6 `list_counters` — (보조) objType 지원 카운터명 디스커버리
- 입력: `objType`
- 출력: `[{ counter, displayName, unit }]`
- 용도: 4.5를 쓰기 전 카운터명 확인.

## 5. 데이터 흐름 · 연결/세션 관리

### 연결/인증
- collector TCP(기본 6100) 로그인 세션 필요. `scouter.client`의 `TcpProxy.getTcp(server)` + `LOGIN` 흐름 사용.
- 접속정보/자격증명은 **환경변수 주입만** 허용(하드코딩 금지):
  - `SCOUTER_COLLECTOR_HOST`, `SCOUTER_COLLECTOR_PORT`, `SCOUTER_USER`, `SCOUTER_PASSWORD`
  - 다중 collector: `SCOUTER_SERVERS=host1:6100,host2:6100`(선택)
  - 타임존: `SCOUTER_TZ`(기본 Asia/Seoul)
- 세션 풀링 + 만료 시 재로그인. `CHECK_SESSION`으로 유효성 확인 후 끊기면 자동 재연결.

### 전형적 호출 흐름 (오류 원인 파악)
```
1. list_objects(from,to)              → objHash 확보
2. search_xlog(onlyError=true, ...)   → 에러 트랜잭션 목록 + txid/gxid/endTime
3. get_xlog_detail(txid)              → SQL/바인드 파라미터/스텝
   (분산호출이면 get_xlog_by_gxid(gxid)로 연관 서비스 확장)
4. [AI가 직접] OpenSearch/Datadog MCP에 objName + endTime윈도우 + txid/gxid로 교차검색
5. get_counter(ErrorRate/Elapsed, 해당 구간) → 추세로 영향범위 확인
```

### 시간 처리
- 입력은 ISO8601 또는 상대표현(`now-1h`) 허용 → 내부 epochMs 변환.
- 출력 시각은 `epochMs` + `iso`(KST 기본) 둘 다 제공 → 타 MCP 검색에 그대로 사용.

### 페이지네이션/리밋
- `search_xlog` 기본 100, 상한 1000. 초과 시 `truncated:true` 표시 + 기간 축소 힌트(조용한 절단 금지).

## 6. 에러 처리 · 민감정보 마스킹 · 보안

### 민감정보 마스킹 (바인드 파라미터)
- `get_xlog_detail`의 바인드 파라미터/SQL에 회원 도메인 PII(주민번호 유사값, 카드번호, 연락처, 이메일, 비밀번호/토큰)가 포함될 수 있음.
- 정책:
  - `maskSensitive`(기본 `true`): 패턴 기반 마스킹(카드/주민 유사 13~16자리, 이메일, 전화번호, `password|passwd|pwd|token|secret` 키 인접 값).
  - 마스킹 룰은 설정/환경변수로 패턴 추가 가능.
  - `maskSensitive:false`를 **명시**해야만 원문 노출, 이 경우 감사 로그 기록.
- MCP 서버 로그도 단일 라인 `key=value`, 바인드 값은 미로깅(개수/길이만).

### 에러 처리 (정직한 표면화)
- 연결 실패: `SCOUTER_CONNECT_FAILED` + 점검 항목(host/port/방화벽). 무한 재시도 금지(짧은 백오프 후 명확 실패).
- 인증 실패: `SCOUTER_AUTH_FAILED` (자격증명 값 미에코).
- 빈 결과: 정상 응답 `rows:[]` + 기간/필터 조정 힌트(에러 위장 금지).
- 부분 실패: 성공분 반환 + `warnings:[...]`.
- 버전/프로토콜 불일치: scouter.common ↔ collector 버전 차이 감지 시 명확 메시지.

### 보안 원칙 (전역 보안정책 준수)
- 읽기 전용 — 쓰기 커맨드 미노출(원칙 8).
- 자격증명 env 주입(원칙 2, 9), 신뢰 경계 내부 TCP 사용.
- 입력값 검증 후 collector 요청 구성(원칙 1, 5).
- stdio 로컬 단일 사용자 → 외부 노출면 없음.

## 7. 프로젝트 구조 (Gradle, Kotlin)

```
scouter-mcp/
├─ build.gradle.kts          # scouter.common, scouter.client, MCP Java SDK 의존성
├─ src/main/kotlin/.../mcp/
│  ├─ McpServer.kt           # stdio 진입점, 도구 등록
│  ├─ tools/                 # 도구 6종 (1파일=1도구)
│  │   ├─ ListObjectsTool.kt
│  │   ├─ SearchXlogTool.kt
│  │   ├─ GetXlogDetailTool.kt
│  │   ├─ GetXlogByGxidTool.kt
│  │   ├─ GetCounterTool.kt
│  │   └─ ListCountersTool.kt
│  ├─ scouter/
│  │   ├─ ScouterClient.kt   # TcpProxy 래핑, 로그인/세션 풀/재연결
│  │   ├─ RequestCmds.kt     # 사용 커맨드 상수 모음
│  │   └─ PackMapper.kt      # Pack ↔ 정규화 JSON 변환
│  ├─ masking/Masker.kt      # 민감정보 마스킹 룰
│  ├─ time/TimeRange.kt      # ISO/상대표현 ↔ epochMs, TZ
│  └─ config/Config.kt       # env 로딩/검증
└─ src/test/kotlin/...
```

- 레이어: `MCP tool → Service(매핑/검증) → ScouterClient → collector`. 도구는 입출력만, 프로토콜 세부는 ScouterClient에 캡슐화.

## 8. 테스트 전략

- 단위(외부 의존 없음):
  - `PackMapper`: 샘플 Pack ↔ 정규화 JSON 골든 테스트.
  - `Masker`: 카드/주민/이메일/전화/비밀키 마스킹, false 설정 시 원문.
  - `TimeRange`: `now-1h`/ISO/경계값, TZ 변환.
  - `Config`: env 누락/형식오류 검증.
- 계약(MCP 레벨): 각 도구 입력 스키마 검증·에러코드 반환을 ScouterClient mock으로 검증.
- 통합(옵션, 수동): 실제 dev collector 스모크. 자격증명 env 있을 때만 도는 태그드 테스트(CI 비활성).
- TDD: PackMapper·Masker·TimeRange 등 순수 로직부터 테스트 우선.

## 9. 사용하는 Scouter 요청 커맨드 (확인됨, `scouter.common/RequestCmd`)

- 로그인/세션: `LOGIN`, `CHECK_SESSION`
- 오브젝트: `OBJECT_LIST_REAL_TIME`, `OBJECT_LIST_LOAD_DATE`
- XLog: `SEARCH_XLOG_LIST`, `TRANX_LOAD_TIME`, `XLOG_READ_BY_TXID`, `XLOG_READ_BY_GXID`, `XLOG_LOAD_BY_GXID`
- 카운터: `COUNTER_PAST_TIME`, `COUNTER_PAST_DATE`, `COUNTER_REAL_TIME`

## 10. 범위 밖(YAGNI, 1차 제외)

- HTTP/SSE 공유형 트랜스포트, 멀티유저 인증.
- Scouter MCP 내부에서 Datadog/OpenSearch 직접 호출(상관은 AI가 조합).
- 알림/대시보드/쓰기 기능.

## 11. 미해결/구현 단계에서 확정할 항목

- `scouter.common`/`scouter.client` 정확한 버전 및 사내 collector 버전과의 호환 확인.
- `SEARCH_XLOG_LIST` 파라미터 MapPack 스키마(필드명/타입) 실물 확인.
- 프로파일(바인드 파라미터) 로드 커맨드/디코딩 경로 실물 확인.
- 사용할 MCP Java SDK(공식) 버전/아티팩트 좌표.
