# scouter-mcp

> For the English document, see [README.md](README.md).

Scouter Collector에 TCP로 직접 붙어 XLog/카운터/오브젝트를 조회하는 stdio MCP 서버다.
AI를 통해 Scouter 지표를 빠르게 탐색하고 오류 원인을 파악하는 것을 목적으로 한다.
조회 결과에 포함된 `txid`/`gxid`/`objName`/`endTimeIso`를 키로 OpenSearch/Datadog 등
다른 관측 도구와 교차 분석할 수 있다.

## 아키텍처

Java 17 기반이며 `scouter-common`을 재사용하고 `scouter.webapp`의 net/server 계열
클래스를 `scouter.mcp.client` 패키지로 포팅했다. MCP는 Java SDK 2.0.0의 stdio 트랜스포트를
사용한다. Collector에 대한 모든 동작은 읽기 전용이다.

## 빌드

```bash
./gradlew shadowJar
# 산출물: build/libs/scouter-mcp-0.1.0-all.jar
```

`.mcpb` 번들은 릴리스 CI에서만 이 jar를 감싸 생성한다. 로컬 빌드는 jar만 만든다.

## 등록 (Claude Code, Claude Desktop 등)

collector 하나만 쓸 때는 [GitHub Release](../../releases)의 `.mcpb` 번들을 설치하면 원클릭으로
끝난다. 또는 `.mcp.json.example`를 복사하고 fat jar(release에서 다운로드하거나 직접 빌드)를 가리키게
한 뒤 자격증명을 채운다. 여러 collector는 [다중 Collector](#다중-collector) 참고.

| 환경변수 | 설명 |
|---|---|
| `SCOUTER_COLLECTOR_HOST` | Collector 호스트 |
| `SCOUTER_COLLECTOR_PORT` | Collector TCP 포트 (기본 6100) |
| `SCOUTER_USER` | 로그인 사용자 |
| `SCOUTER_PASSWORD` | 로그인 비밀번호 |
| `SCOUTER_TZ` | 시간대 (예: `Asia/Seoul`) |
| `SCOUTER_LOCALE` | 사용자 메시지 로케일: `en` 또는 `ko`. 미지정 시 JVM 기본값에서 유도(JVM 언어가 한국어일 때만 한국어, 그 외 영어) |
| `SCOUTER_INCLUDE_BIND_PARAMS` | `get_xlog_detail`의 SQL 바인드 파라미터 운영자 kill-switch(기본 `true`). `false`면 호출 파라미터와 무관하게 서버측에서 바인드 값을 제거한다(LLM이 다시 켤 수 없음). 바인드 값에 개인정보가 있을 수 있을 때 사용. |

### 다중 Collector

공식 릴리스는 `.mcpb` 번들 **하나**(원클릭 설치, collector 하나)와 단독 fat jar를 함께 제공한다.
`.mcpb`는 자격증명 한 세트를 가진 서버 하나만 정의하므로 번들 하나로 두 collector를 동시에 등록할
수 없다. collector마다 host/port뿐 아니라 user/password까지 연결정보 세트 전체가 다른 것이 보통이라,
여러 collector는 jar를 직접 써서 collector마다 항목을 하나씩 둔다:

1. [GitHub Release](../../releases)에서 `scouter-mcp-<version>-all.jar`를 다운로드한다.
2. 클라이언트 설정(`.mcp.json` / `claude_desktop_config.json`)에 같은 jar를 가리키는 `mcpServers`
   항목을 collector마다 하나씩, 각자 자체 env 세트로 추가한다. 자격증명이 항목별로 격리된다:

```json
{
  "mcpServers": {
    "scouter-prod": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/scouter-mcp-0.1.0-all.jar"],
      "env": {
        "SCOUTER_COLLECTOR_HOST": "prod-collector", "SCOUTER_COLLECTOR_PORT": "6100",
        "SCOUTER_USER": "prod-user", "SCOUTER_PASSWORD": "***", "SCOUTER_TZ": "Asia/Seoul"
      }
    },
    "scouter-stg": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/scouter-mcp-0.1.0-all.jar"],
      "env": {
        "SCOUTER_COLLECTOR_HOST": "stg-collector", "SCOUTER_COLLECTOR_PORT": "6100",
        "SCOUTER_USER": "stg-user", "SCOUTER_PASSWORD": "***", "SCOUTER_TZ": "Asia/Seoul"
      }
    }
  }
}
```

교차 조합은 AI가 오케스트레이션한다.

## 도구 (14종)

| 이름 | 용도 | 주요 입력 |
|---|---|---|
| `list_objects` | 오브젝트/에이전트 목록 | `objType?`, `nameLike?`(대소문자 무시) |
| `search_xlog` | XLog 검색(응답속도/에러) | `from`, `to`, `objNameLike?`, `objHash?`, `service?`, `login?`, `ip?`, `desc?`, `minElapsedMs?`, `onlyError?`, `limit?`(기본 20, 최대 200) |
| `get_service_summary` | 서비스별 집계(count/avg/max/p95/errorRate), 상위 50 | `from`, `to`, `search_xlog`와 동일 필터 |
| `get_summary` | collector 일간 사전집계(SQL/서비스/에러 등 Top 50, 스캔 없음) | `category`(service/sql/apiCall/ip/userAgent/error/alert), `from`, `to`(최대 31일), `objType?`, `objNameLike?`, `objHash?` |
| `get_xlog_detail` | XLog 상세(SQL/바인드 파라미터) | `txid`, `date?`/`at?`, `includeBindParams?`(기본 true) |
| `get_xlog_by_gxid` | 분산 트랜잭션 묶음 | `gxid`, `date?`/`at?` |
| `get_counter` | 카운터 시계열(당일, 풀해상도) | `objNameLike`\|`objHashes`\|`objType`, `counter`, `from`, `to` |
| `get_counter_stat` | 장기 카운터 통계(5분 해상도, 최대 31일) | `objNameLike`\|`objHashes`\|`objType`, `counter`, `from`, `to` |
| `list_counters` | objType 지원 카운터 목록 | `objType` |
| `list_alerts` | 과거 collector 알림 | `from`, `to`, `level?`, `object?`, `key?`, `limit?` |
| `get_active_services` | 지금 실행 중인 서비스 | `objNameLike`\|`objType`\|`objHash` |
| `list_threads` | JVM 스레드 목록(상태 히스토그램 + cpu 상위 50) | `objNameLike`\|`objHash`(alive 인스턴스 최대 5) |
| `get_thread_detail` | 활성 트랜잭션 스레드 상세(스택/락 소유자/현재 SQL) | `txid`(필수, 활성), `id?`, `objNameLike`\|`objHash` |
| `get_object_env` | 에이전트 JVM 시스템 프로퍼티(비밀키 마스킹) | `objNameLike`\|`objHash`, `keyLike?` |

### 퍼지 타깃 지정 (`objNameLike`)

사용자는 앱 이름 조각("shop-order-api")으로 말하지만 실제 objName에는 k8s pod 이름이 들어가
(`/shop-order-api-deployment-5f4b8c7d9-abcde/shop-order-api1`) objHash가 배포마다 바뀌고 한 앱이
여러 인스턴스에 걸친다. `objNameLike`가 이를 해결한다: 대소문자 무시 조각을 **모든** 매칭 인스턴스
(alive 우선, 최대 20)로 해석해 전부 조회한다 — objHash를 알 필요가 전혀 없다. 매칭이 없으면
`NOT_FOUND` 에러에 실제 objName 후보 목록(`candidates` 힌트)이 실려 한 번에 자가 수정할 수 있다.

### 대충 입력한 service 검색

Scouter 서비스명은 `/api/order/.../search-order-info-grade<POST>` 형식이지만 사용자는
"GET orderDetail", "order info grade"처럼 입력한다. `service` 필터가 이런 입력을 정규화한다:
HTTP 메서드를 위치와 무관하게 추출하고(`GET x`, `x POST`, 붙여넣은 `<POST>`), 공백으로 나뉜
입력은 가장 긴 토큰으로 서버측 검색하며, `*` 포함 패턴은 그대로 통과시킨다. 서버측 매칭은
여전히 대소문자를 구분하므로 — 패턴이 아무것도 못 찾으면 같은 구간을 service 필터 없이
(상한 내에서) 재스캔해 쿼리 토큰과 대소문자 무시로 일치하는 **실제 서비스명**을
`serviceCandidates`로 트래픽 순으로 돌려준다. 정확한 이름으로 한 번만 재시도하면 된다.

`service`/`login`/`ip`/`desc`는 기본 부분일치(서버측 `StrMatch`)이므로 짧은 토큰
`search-order-info-grade`만 넣어도 `/api/order/ext/order-info/search-order-info-grade<POST>`가
매칭된다. `objNameLike`/`login`/`ip`/`desc`는 서버측 필터로 인정되어 5분 무필터 구간 제한을
완화한다. `list_counters`도 `objNameLike`를 받아 objType을 유도하므로 Scouter의 타입 체계를
몰라도 된다.

모든 도구는 `readOnlyHint`로 선언된다. `diagnose_root_cause` MCP 프롬프트가 응답속도/에러 조사를
위한 권장 도구 순서를 제공한다.

## 리소스/토큰 안전 정책

운영 Scouter는 5분에 수십만 XLog를 만들 수 있어 `search_xlog`에 안전장치를 둔다
(`scouter.mcp.policy.Limits` 참조).

- 스트리밍 중 `limit` 또는 스캔 상한(검사 5,000건)에 도달하면 소켓을 끊어 Collector의
  스캔/전송도 멈춘다 — 서버 부하·네트워크·MCP 힙을 함께 제한한다.
- `service`/`objHash` 필터가 없으면 최대 5분 구간만 허용하고, 절대 상한은 24시간이다.
- `limit` 기본 20, 최대 200. 결과에 `truncated`/`scanCapReached`와 `hint`를 실어 재조회 대신
  필터를 좁히도록 유도한다.
- `get_service_summary`는 행을 보관하지 않고 서비스별 카운터만 누적하므로 더 높은 스캔 상한
  (200,000)으로 넓은 구간을 저렴하게 커버한다. `scanCapReached`/`examined`도 함께 보고한다.
- `get_counter`는 `objType` 팬아웃을 인스턴스 20개로 제한하고, 긴 시계열은 스파이크/저점을
  보존하는 min/max 방식으로 다운샘플링한다(요약 `min`/`max`/`avg`는 전체 시계열에서 계산).
- 자정을 넘는 구간은 달력일 단위로 분할 조회한다(collector가 XLog/카운터/알림을 일자별로
  파티셔닝). 경계 양쪽 데이터가 누락되지 않는다.
- 응답 텍스트 절삭: SQL 텍스트 1,500자 / 에러 메시지 500자 / 스레드 스택 4,000자 / env 값 500자에서
  잘리고 절삭 마커(원본 길이 포함)가 붙는다. `get_xlog_detail` 프로파일 스텝은 150개에서 잘리며
  `totalSteps`/`stepsTruncated`로 신호한다.
- 요청 하나가 유발하는 collector 왕복(인스턴스 x 일자)은 40회로 제한한다. 클라이언트측 필터
  (`minElapsedMs`/`onlyError`)가 스캔 결과의 99% 이상을 버리면 저선택도 힌트로 서버측 필터나
  `get_summary` 사용을 유도한다.
- `get_summary`/`get_counter_stat`은 collector가 미리 집계한 일간 데이터(스캔 없음)를 읽고 최대
  31일, summary는 카테고리당 상위 50행으로 제한한다. `list_threads`는 alive 인스턴스 5개,
  인스턴스당 스레드 행 50개(상태 히스토그램은 전체 집계)로 제한한다.
- 요청별 텔레메트리(passes/examined/kept/tookMs)를 stderr 구조화 로그로 남겨 사후 부하 분석이
  가능하다.

## 국제화 (i18n)

동적 사용자 출력(도구 에러 메시지·결과 hint·note)만 영어/한국어로 로컬라이즈한다
(`messages.properties` / `messages_ko.properties`). 정적 스키마/도구 설명과 stderr 구조화
로그(`key=value`)는 계약 안정성과 로그 파싱을 위해 영어로 고정한다.

## 보안 주의

- Collector에 대해 읽기 전용으로만 동작한다(쓰기 커맨드 미노출).
- 자격증명은 환경변수로만 주입한다(파일/인자에 평문 금지). 최소권한/읽기전용 계정 사용을 권장한다.
- **전송은 평문 TCP**다(Scouter 프로토콜에 TLS 없음): SHA-256 비밀번호 다이제스트, 세션 토큰,
  모든 XLog/카운터 데이터가 암호화 없이 오간다. 신뢰 네트워크 내부에서만 사용하거나 SSH/VPN으로
  터널링하라. collector 포트를 공용 인터넷에 노출하지 말 것.
- `get_xlog_detail`의 바인드 파라미터에는 개인정보가 있을 수 있다. `SCOUTER_INCLUDE_BIND_PARAMS=false`로
  서버측에서 제거할 수 있다(LLM이 다시 켤 수 없음). 위 환경변수 표 참조.
  `get_thread_detail`의 실시간 바인드 값(`SQLActiveBindVar`)도 같은 kill-switch를 따른다.
- `get_object_env`는 password/secret/token/credential/private에 해당하는 키의 값을 **무조건**
  마스킹한다(LLM이 해제할 수 없는 서버측 정책).
- stdout은 JSON-RPC 전용이므로 모든 로그는 stderr로만 출력한다.

## 라이선스/고지

`scouter.mcp.client` 패키지는 Scouter v2.20.0(Apache License 2.0)의 클라이언트 코드를
포팅한 것이다. 자세한 내용은 [NOTICE](NOTICE) 참조.

## 알려진 한계

1. `search_xlog`/`get_service_summary`의 `minElapsedMs`/`onlyError`/`limit`는 collector 측
   파라미터가 없어 클라이언트에서 필터/절단한다. `truncated=true`는 반환건수==limit일 때의
   휴리스틱이며 거짓양성이 가능하다.
2. 세션 만료(`INVALID_SESSION`) 시 1회 재로그인 후 요청을 재시도한다. 두 번째도 실패하면
   `SCOUTER_AUTH_FAILED`로 표면화한다(무한 재시도 없음). 상류의 2초 주기 시간 delta 갱신
   데몬은 여전히 미포팅이라, 장시간 구동 프로세스의 실시간 상대조회에 미세 드리프트가 가능하다.
   과거 시점(명시적 epoch) 조회에는 영향이 없다.
3. `list_alerts`/`get_active_services`는 상류 프로토콜을 포팅했고 스모크 테스트(`SmokeIT`)로
   collector에 대해 검증한다. 필드 커버리지는 collector 버전에 따라 다를 수 있다.
