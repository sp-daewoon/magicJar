# CODEX.md

This file gives Codex repository-local guidance for magicJar work.

## Project Direction Guard

magicJar is a KIS-first personal Korean stock trading system. The rule-based quant engine owns trading decisions; AI is an optional analysis helper and must not own order decisions.

Before non-trivial code, design, candle, chart, screener, FVG/order-block, strategy, or autotrading changes, apply the local `magicjar-direction-guard` skill:

- Read current authority first: `CLAUDE.md`, `_workspace/INDEX.md`, `docs/architecture/policies/`, relevant ADR/spec/backlog, then the code.
- Prefer conservative extension over broad rewrites.
- Compare before/after data flow, capital flow, API contracts, DB/Redis/Kafka keys, operations schedule, frontend UX, and verification scope.
- Do not hide missing market data with mock or synthetic candles. Show missing/preparing/queued states or trigger real KIS recollection paths.
- Treat direction changes as decisions. Record options, risks, migration/rollback, and ask for confirmation or update ADR/spec/policy when needed.

## Working Note Template

```text
Current direction: ...
Change intent: ...
Direction impact: preserved / partial change / direction change
Risks to check: data, capital flow, API, UX, operations, verification
```

## FVG / Order Block Work

- Core calculations live in `indicator-impl` and are exposed through the indicator catalog, DSL aliases, strategy engine, and frontend catalog.
- User-facing FVG/OB work should connect strategy candidates, screener usage, and chart validation together. A calculation-only patch is incomplete if the user cannot inspect boxes on the chart.
- Chart overlays should use real indicator output over real candle data. If the indicator endpoint has no data, surface the failure instead of drawing placeholders.
