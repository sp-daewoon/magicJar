---
name: magicjar-chart-curator
description: "ARCHIVED 2026-05-21 ADR 0094 — 트리거 금지, 역사 보존. chart-curator 에이전트는 해고되었고 본 스킬은 어떤 의제('차트 추가'·'캔들 결함'·'MTF 정합성'·'LWC 확장' 포함)에도 트리거하지 않는다. 차트/LWC v4 → frontend, 캔들 SLA·정합성 → market-data로 이관됨. 원문은 .claude/skills/_archive/magicjar-chart-curator/SKILL.md 참조."
---

# magicjar-chart-curator — ARCHIVED (2026-05-21 ADR 0094)

본 스킬은 **아카이브**되었다. dispatch·트리거 대상이 아니다 (2026-07-03 감사 P1-⑪ 청산).

- 책임 이관: TradingView LWC v4 차트 컴포넌트 → **frontend** (단일 owner) / 캔들 데이터 SLA·정합성·갭 검출 → **market-data** (spec: `_workspace/specs/2026-05-21-chart-curator-handover.md`)
- 원문(역사 보존): [`.claude/skills/_archive/magicjar-chart-curator/SKILL.md`](../_archive/magicjar-chart-curator/SKILL.md) — 데이터 햇징 14 룰·함정 가드 등 유효한 지식은 이관 owner 문서가 승계 권위.
- `bot+chart-curator@magicjar.local` author 커밋 발행 금지 (역사 evidence 보존을 위해 pre-commit allowed list에만 잔존).
