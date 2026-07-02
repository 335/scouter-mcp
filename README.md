# scouter-mcp

> 한국어 문서는 [README.ko.md](README.ko.md) 를 참고하세요.

A stdio MCP server that connects directly to a Scouter Collector over TCP and queries XLogs,
counters, and objects. Its purpose is to let an AI quickly explore Scouter metrics and diagnose
root causes. Each result carries `txid` / `gxid` / `objName` / `endTimeIso`, which you can use as
keys to cross-analyze with other observability tools such as OpenSearch or Datadog.

## Architecture

Java 17. It reuses `scouter-common` and ports the `scouter.webapp` net/server classes into the
`scouter.mcp.client` package. MCP uses the Java SDK 2.0.0 stdio transport. All operations against
the Collector are read-only.

## Build

```bash
./gradlew shadowJar
# output: build/libs/scouter-mcp-0.1.0-all.jar
```

The `.mcpb` bundle is produced only by the release CI (which wraps this jar); local builds just
produce the jar.

## Registration (Claude Code, Claude Desktop, ...)

For a single collector, install the `.mcpb` bundle from the [GitHub Release](../../releases) for a
one-click setup — or copy `.mcp.json.example`, point it at the fat jar (downloaded from the release or
built locally), and fill in the credentials. For multiple collectors, see
[Multiple collectors](#multiple-collectors).

| Env var | Description |
|---|---|
| `SCOUTER_COLLECTOR_HOST` | Collector host |
| `SCOUTER_COLLECTOR_PORT` | Collector TCP port (default 6100) |
| `SCOUTER_USER` | Login user |
| `SCOUTER_PASSWORD` | Login password |
| `SCOUTER_TZ` | Time zone (e.g. `Asia/Seoul`) |
| `SCOUTER_LOCALE` | User-facing message locale: `en` or `ko`. If unset, derived from the JVM default (Korean only when the JVM language is Korean, otherwise English) |
| `SCOUTER_INCLUDE_BIND_PARAMS` | Operator kill-switch for SQL bind parameters in `get_xlog_detail` (default `true`). Set to `false` to strip bind params server-side regardless of the per-call argument — an LLM cannot re-enable them. Use when bind values may contain PII. |

### Multiple collectors

The official release ships **one** `.mcpb` bundle (one-click install, a single collector) plus the
standalone fat jar. A `.mcpb` defines exactly one server with one credential set, so it cannot register
two collectors at once. For multiple collectors — which usually differ in their whole connection set
(host/port **and** user/password) — use the jar directly and add one entry per collector:

1. Download `scouter-mcp-<version>-all.jar` from the [GitHub Release](../../releases).
2. Add one `mcpServers` entry per collector to your client config (`.mcp.json` /
   `claude_desktop_config.json`), all pointing at that same jar, each with its own env set. This keeps
   each credential isolated:

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

The AI then orchestrates across the collectors.

## Tools (14)

| Name | Purpose | Key inputs |
|---|---|---|
| `list_objects` | List objects/agents | `objType?`, `nameLike?` (case-insensitive) |
| `search_xlog` | Search XLogs (latency/errors) | `from`, `to`, `objNameLike?`, `objHash?`, `service?`, `login?`, `ip?`, `desc?`, `minElapsedMs?`, `onlyError?`, `limit?` (default 20, max 200) |
| `get_service_summary` | Per-service aggregate (count/avg/max/p95/errorRate), top 50 | `from`, `to`, same filters as `search_xlog` |
| `get_summary` | Collector's daily pre-aggregated stats (top-50 SQL/service/error/... — no scanning) | `category` (service/sql/apiCall/ip/userAgent/error/alert), `from`, `to` (up to 31 days), `objType?`, `objNameLike?`, `objHash?` |
| `get_xlog_detail` | XLog detail (SQL/bind params) | `txid`, `date?`/`at?`, `includeBindParams?` (default true) |
| `get_xlog_by_gxid` | Distributed-transaction group | `gxid`, `date?`/`at?` |
| `get_counter` | Counter time series (same-day, full resolution) | `objNameLike`\|`objHashes`\|`objType`, `counter`, `from`, `to` |
| `get_counter_stat` | Long-range counter stats (5-min resolution, up to 31 days) | `objNameLike`\|`objHashes`\|`objType`, `counter`, `from`, `to` |
| `list_counters` | Available counters for an objType | `objType` |
| `list_alerts` | Past collector alerts | `from`, `to`, `level?`, `object?`, `key?`, `limit?` |
| `get_active_services` | Services running right now | `objNameLike`\|`objType`\|`objHash` |
| `list_threads` | JVM thread list (state histogram + top 50 by cpu) | `objNameLike`\|`objHash` (max 5 alive instances) |
| `get_thread_detail` | Live thread of an ACTIVE transaction (stack/lock owner/current SQL) | `txid` (required, active), `id?`, `objNameLike`\|`objHash` |
| `get_object_env` | Agent JVM system properties (secrets masked) | `objNameLike`\|`objHash`, `keyLike?` |

### Fuzzy targeting (`objNameLike`)

Users say app-name fragments ("shop-order-api"), but real objNames embed the k8s pod name
(`/shop-order-api-deployment-5f4b8c7d9-abcde/shop-order-api1`), so objHash changes on every deploy
and an app spans multiple instances. `objNameLike` solves this: a case-insensitive fragment is
resolved to **all** matching instances (alive first, capped at 20) and queried across them — no
objHash needed, ever. If nothing matches, the error is `NOT_FOUND` with a `candidates` hint listing
actual objNames so the caller can self-correct in one step.

### Sloppy service queries

Scouter service names look like `/api/order/.../search-order-info-grade<POST>`, but users type
"GET orderDetail" or "order info grade". The `service` filter normalizes such input: an HTTP method is
extracted from any position (`GET x`, `x POST`, pasted `<POST>`), whitespace-separated words fall back
to the longest token server-side, and explicit `*` patterns pass through untouched. Server-side
matching is still case-sensitive — so when a pattern matches nothing, the same window is re-scanned
(bounded) without the service filter and real service names matching the query tokens
case-insensitively are returned as `serviceCandidates`, ordered by traffic. One retry with an exact
name resolves it.

`service`/`login`/`ip`/`desc` use substring match by default (server-side `StrMatch`), so a short token
like `search-order-info-grade` matches `/api/order/ext/order-info/search-order-info-grade<POST>`.
`objNameLike`/`login`/`ip`/`desc` count as server-side filters, so they relax the 5-minute
unfiltered-window cap. `list_counters` also accepts `objNameLike` and derives the objType, so users
never need to know Scouter's type taxonomy.

All tools are advertised with `readOnlyHint`. A `diagnose_root_cause` MCP prompt exposes the
recommended tool order for latency/error investigations.

## Resource / token safety policy

Production Scouter can produce hundreds of thousands of XLogs in five minutes, so `search_xlog`
enforces guardrails (see `scouter.mcp.policy.Limits`):

- During streaming, it stops once the `limit` or the scan cap (5,000 examined packs) is reached and
  closes the socket, which also stops the Collector's scan/transfer — bounding server load, network,
  and MCP heap together.
- Without a `service` or `objHash` filter, only windows up to 5 minutes are allowed; the absolute
  window cap is 24 hours.
- `limit` defaults to 20 and is capped at 200. Results include `truncated`/`scanCapReached` and a
  `hint` so the caller can narrow filters instead of refetching.
- `get_service_summary` retains no rows (only per-service counters), so it uses a higher scan cap
  (200,000) to cover wider windows cheaply; it reports `scanCapReached`/`examined` too.
- `get_counter` caps the per-`objType` fan-out at 20 instances, and downsamples long series with a
  min/max scheme that preserves spikes/dips (summary `min`/`max`/`avg` are computed from the full series).
- Windows crossing midnight are split per calendar day (the collector partitions XLogs/counters/alerts
  by day), so no data is lost on either side of the boundary.
- Response text budgets: SQL text is cut at 1,500 chars, error messages at 500, thread stack traces at
  4,000, env values at 500 — each with a truncation marker carrying the original length. `get_xlog_detail`
  profile steps are capped at 150, signalled via `totalSteps`/`stepsTruncated`.
- A single request may fan out to at most 40 collector round-trips (instances x day segments). When
  client-side filters (`minElapsedMs`/`onlyError`) discard over 99% of scanned rows, a low-selectivity
  hint steers the model toward server-side filters or `get_summary`.
- `get_summary`/`get_counter_stat` read the collector's daily pre-aggregated data (no scanning), capped at
  31 days; summary returns the top 50 rows per category. `list_threads` caps at 5 alive instances and 50
  thread rows each (the state histogram always covers all threads).
- Per-request telemetry (passes/examined/kept/tookMs) is logged to stderr as structured `key=value` lines
  for post-hoc load analysis.

## Internationalization

Only dynamic, user-facing output (tool error messages, result hints, notes) is localized, in English
and Korean, via `messages.properties` / `messages_ko.properties`. Static schema/tool descriptions and
structured stderr logs (`key=value`) remain English for a stable contract and log parsing.

## Security notes

- Read-only against the Collector (no write commands are exposed).
- Credentials are injected via environment variables only (never in files or arguments as plaintext).
  Prefer a least-privilege / read-only Scouter account.
- **Transport is plaintext TCP** (the Scouter protocol has no TLS): the SHA-256 password digest, the
  session token, and all XLog/counter data cross the wire unencrypted. Run only inside a trusted network,
  or tunnel over SSH/VPN. Do not expose the collector port over the public internet.
- `get_xlog_detail` bind parameters can contain PII. Set `SCOUTER_INCLUDE_BIND_PARAMS=false` to strip
  them server-side (the LLM cannot re-enable them). See the env table above. `get_thread_detail`'s live
  bind values (`SQLActiveBindVar`) obey the same kill-switch.
- `get_object_env` **unconditionally** masks values of keys matching password/secret/token/credential/
  private — a server-side policy the LLM cannot opt out of.
- stdout is reserved for JSON-RPC, so all logs go to stderr only.

## License / Notice

The `scouter.mcp.client` package is ported from Scouter v2.20.0 client code (Apache License 2.0).
See [NOTICE](NOTICE) for details.

## Known limitations

1. `search_xlog`/`get_service_summary` `minElapsedMs`/`onlyError`/`limit` are applied client-side because
   the Collector has no native parameters for them. `truncated=true` is a heuristic (returned count ==
   limit) and can be a false positive.
2. On a session-expiry (`INVALID_SESSION`) the client re-logs in once and retries the request; a second
   failure surfaces as `SCOUTER_AUTH_FAILED` (no infinite retry loop). The upstream 2-second time-delta
   refresh daemon is still not ported, so long-running processes may drift slightly for real-time
   relative queries. Absolute-epoch (historical) queries are unaffected.
3. `list_alerts`/`get_active_services` were ported from the upstream protocol and validated against a
   collector via the smoke tests (`SmokeIT`); field coverage may vary by collector version.
