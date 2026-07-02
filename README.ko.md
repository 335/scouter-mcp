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

## 등록 (Claude Code, Claude Desktop 등)

`.mcp.json.example`를 복사한 뒤 jar 절대경로와 자격증명을 채운다.

| 환경변수 | 설명 |
|---|---|
| `SCOUTER_COLLECTOR_HOST` | Collector 호스트 |
| `SCOUTER_COLLECTOR_PORT` | Collector TCP 포트 (기본 6100) |
| `SCOUTER_USER` | 로그인 사용자 |
| `SCOUTER_PASSWORD` | 로그인 비밀번호 |
| `SCOUTER_TZ` | 시간대 (예: `Asia/Seoul`) |
| `SCOUTER_LOCALE` | 사용자 메시지 로케일: `en` 또는 `ko`. 미지정 시 JVM 기본값에서 유도(JVM 언어가 한국어일 때만 한국어, 그 외 영어) |

## 도구 (6종)

| 이름 | 용도 | 주요 입력 |
|---|---|---|
| `list_objects` | 오브젝트/에이전트 목록 | `objType?`, `nameLike?` |
| `search_xlog` | XLog 검색(응답속도/에러) | `from`, `to`, `objHash?`, `service?`, `minElapsedMs?`, `onlyError?`, `limit?`(기본 20, 최대 200) |
| `get_xlog_detail` | XLog 상세(SQL/바인드 파라미터) | `txid`, `date?`/`at?`, `includeBindParams?`(기본 true) |
| `get_xlog_by_gxid` | 분산 트랜잭션 묶음 | `gxid`, `date?`/`at?` |
| `get_counter` | 카운터 시계열 | `objHashes`\|`objType`, `counter`, `from`, `to` |
| `list_counters` | objType 지원 카운터 목록 | `objType` |

`service`는 기본 부분일치(서버측 `StrMatch`)이므로 짧은 토큰 `search-order-info-grade`만
넣어도 `/api/order/ext/order-info/search-order-info-grade<POST>`가 매칭된다.

## 리소스/토큰 안전 정책

운영 Scouter는 5분에 수십만 XLog를 만들 수 있어 `search_xlog`에 안전장치를 둔다
(`scouter.mcp.policy.Limits` 참조).

- 스트리밍 중 `limit` 또는 스캔 상한(검사 5,000건)에 도달하면 소켓을 끊어 Collector의
  스캔/전송도 멈춘다 — 서버 부하·네트워크·MCP 힙을 함께 제한한다.
- `service`/`objHash` 필터가 없으면 최대 5분 구간만 허용하고, 절대 상한은 24시간이다.
- `limit` 기본 20, 최대 200. 결과에 `truncated`/`scanCapReached`와 `hint`를 실어 재조회 대신
  필터를 좁히도록 유도한다.
- `get_counter`는 `objType` 팬아웃을 인스턴스 20개로 제한한다.

## 국제화 (i18n)

동적 사용자 출력(도구 에러 메시지·결과 hint·note)만 영어/한국어로 로컬라이즈한다
(`messages.properties` / `messages_ko.properties`). 정적 스키마/도구 설명과 stderr 구조화
로그(`key=value`)는 계약 안정성과 로그 파싱을 위해 영어로 고정한다.

## 보안 주의

- Collector에 대해 읽기 전용으로만 동작한다.
- 자격증명은 환경변수로만 주입한다(파일/인자에 평문 금지).
- stdout은 JSON-RPC 전용이므로 모든 로그는 stderr로만 출력한다.

## 라이선스/고지

`scouter.mcp.client` 패키지는 Scouter v2.20.0(Apache License 2.0)의 클라이언트 코드를
포팅한 것이다. 자세한 내용은 [NOTICE](NOTICE) 참조.

## 알려진 한계

1. `get_counter`는 collector의 일자별 파일 경계를 고려한 per-day 분할을 하지 않는다.
   따라서 여러 날에 걸친 범위는 일부 구간 데이터가 누락될 수 있다(단일 날짜 범위 권장).
2. `search_xlog`의 service/error/SQL 텍스트 디코딩은 행당 최대 2회 TCP round-trip이
   발생한다(캐시로 완화). 대량 결과(수백~1000건)에서 느릴 수 있다. 배치 디코드는 후속 대상.
3. `search_xlog`의 `minElapsedMs`/`onlyError`/`limit`는 collector 측 파라미터가 없어
   클라이언트에서 필터/절단한다. `truncated=true`는 반환건수==limit일 때의 휴리스틱이며
   거짓양성이 가능하다.
4. 로그인 시 서버 시간 delta를 1회만 동기화한다(상류의 2초 주기 갱신 데몬은 미포팅).
   장시간 구동 프로세스의 실시간 상대조회에 미세 드리프트가 가능하다. 과거 시점(명시적
   epoch) 조회에는 영향이 없다.
