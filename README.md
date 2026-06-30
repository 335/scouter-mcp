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

## Registration (Claude Code, Claude Desktop, ...)

Copy `.mcp.json.example` and fill in the absolute jar path and credentials.

| Env var | Description |
|---|---|
| `SCOUTER_COLLECTOR_HOST` | Collector host |
| `SCOUTER_COLLECTOR_PORT` | Collector TCP port (default 6100) |
| `SCOUTER_USER` | Login user |
| `SCOUTER_PASSWORD` | Login password |
| `SCOUTER_TZ` | Time zone (e.g. `Asia/Seoul`) |
| `SCOUTER_LOCALE` | User-facing message locale: `en` or `ko`. If unset, derived from the JVM default (Korean only when the JVM language is Korean, otherwise English) |

## Tools (6)

| Name | Purpose | Key inputs |
|---|---|---|
| `list_objects` | List objects/agents | `objType?`, `nameLike?` |
| `search_xlog` | Search XLogs (latency/errors) | `from`, `to`, `objHash?`, `service?`, `minElapsedMs?`, `onlyError?`, `limit?` (default 20, max 200) |
| `get_xlog_detail` | XLog detail (SQL/bind params) | `txid`, `date?`/`at?`, `includeBindParams?` (default true), `maskSensitive?` (default true) |
| `get_xlog_by_gxid` | Distributed-transaction group | `gxid`, `date?`/`at?` |
| `get_counter` | Counter time series | `objHashes`\|`objType`, `counter`, `from`, `to` |
| `list_counters` | Available counters for an objType | `objType` |

`service` uses substring match by default (server-side `StrMatch`), so a short token like
`search-order-info-grade` matches `/api/order/ext/order-info/search-order-info-grade<POST>`.

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
- `get_counter` caps the per-`objType` fan-out at 20 instances.

## Internationalization

Only dynamic, user-facing output (tool error messages, result hints, notes) is localized, in English
and Korean, via `messages.properties` / `messages_ko.properties`. Static schema/tool descriptions and
structured stderr logs (`key=value`) remain English for a stable contract and log parsing.

## Security notes

- Read-only against the Collector.
- Credentials are injected via environment variables only (never in files or arguments as plaintext).
- Bind parameters are masked by default. Setting `maskSensitive=false` writes a single audit log line;
  the actual values are never written to logs.
- stdout is reserved for JSON-RPC, so all logs go to stderr only.

## License / Notice

The `scouter.mcp.client` package is ported from Scouter v2.20.0 client code (Apache License 2.0).
See [NOTICE](NOTICE) for details.

## Known limitations

1. `get_counter` does not split queries per day across the Collector's daily file boundaries, so
   multi-day ranges may miss data in some sub-ranges (single-day ranges recommended). Planned
   improvement.
2. `search_xlog` decodes service/error/SQL text with up to two TCP round-trips per row (mitigated by
   caching). This can be slow for large result sets (hundreds to ~1000 rows). Batch decoding is a
   future optimization.
3. `search_xlog` `minElapsedMs`/`onlyError`/`limit` are applied client-side because the Collector has
   no native parameters for them. `truncated=true` is a heuristic (returned count == limit) and can be
   a false positive.
4. Server time delta is synchronized only once at login (the upstream 2-second refresh daemon is not
   ported). Long-running processes may drift slightly for real-time relative queries. Absolute-epoch
   (historical) queries are unaffected.
