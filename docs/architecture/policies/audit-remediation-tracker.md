# 2026-07-03 시스템 감사 조치 추적 — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: 감사 보고서 [`_workspace/audit/2026-07-03-system-audit.md`](../../../_workspace/audit/2026-07-03-system-audit.md) (P0 1 · P1 12 · P2 16 · P3 12) · ADR 0137·0138·0139
> **마지막 갱신**: 2026-07-03 (감사 개선 Wave — 거버넌스/문서 트랙 구현분 반영)

## 현행 규약 (항목별 상태)

상태 값: **완료** (이번 Wave 반영) / **구현 Wave 대상** (2026-07-03 개선 Wave 병렬 팀 담당 — 머지 확인 후 완료 전환) / **이연** (사유 명기).

### P0

| # | 항목 | 상태 |
|---|------|------|
| P0-1 | KIS WS 재연결 시 호가·VI·시간외 재구독 누락 (VI 보호 silent 상실) | **구현 Wave 대상** (DATA 트랙 — Phase 1 최우선) |

### P1

| # | 항목 | 상태 |
|---|------|------|
| ① | BoundaryAggregator 1m 풀스캔 (단조 악화) | **구현 Wave 대상** (DATA — 범위 쿼리 + retention) |
| ② | LEGACY 백테스트 SL/TP 미평가 | **구현 Wave 대상** (TRADE — 단기 산식 삽입 · INTRADAY_REALISTIC default 승격은 이연) |
| ③ | WS slot priority eviction 미구현 | **ADR 0138 Accepted 발행 완료** + 구현 Wave 대상 (DATA/TRADE) |
| ④ | DSL close 수정주가 vs 지표 원주가 혼합 | **이연** — 가격 소스 정책 단일화는 자본 흐름 결합, 별도 ADR 발의 후 진행 (감사 §9 권고) |
| ⑤ | tick 0원 캔들 영속 (sanity 가드 0) | **구현 Wave 대상** (DATA — 파서+aggregator 가드) |
| ⑥ | NEW_MKOP_CLS_CODE 세션 미파싱 (ADR 0124 수용 기준 위반) | **이연** — Phase 3 (세션 태그+캔들 반영 정책 명시 필요) |
| ⑦ | "WS 30초 단절→전 종목 polling" 폴백 no-op·문서 오정보 | **문서 정정 완료** (candle-data-policy-v2 §6-A·CLAUDE.md) · HOLDING 한정 폴백 편입 여부는 후속 ADR 의제 |
| ⑧ | Prometheus alert rule 0·Alertmanager 부재 | **이연** — Phase 2 (OPS — 최소 규칙 4개), 인프라 변경이라 본 문서 Wave 범위 밖 |
| ⑨ | 프론트 STOMP 재연결 후 재구독 부재 | **구현 Wave 대상** (FRONT — 5줄 패치) |
| ⑩ | CLAUDE.md/INDEX 3주 동결·수치 stale·GH Actions 3중 모순 | **완료** — 모듈 9 실측·ADR 수치 링크화·GH Actions 사실 정정·observability 위치·감사 링크 등재 |
| ⑪ | chart-curator 유령 (frontend.md 이관 지시·live SKILL·cross-review) | **완료** — frontend.md ADR 0094 재작성·SKILL 아카이브+tombstone·backend-core 매트릭스 qa 재지정 |
| ⑫ | ADR 라이프사이클 붕괴·인덱스 "불일치 0" 거짓 | **완료** — 0114·0117·0123 Accepted 승격·0073 헤더 정합·0090 owner 재지정·훅 ADR 번호 치환. 정합 검증 스크립트 release 편입·분기별 Proposed triage는 **이연** |
| ⑬ | smoke.sh 3 시나리오 공허 | **이연** — scripts는 본 문서 Wave 소유권 밖, Phase 2 (endpoint 정정+FAIL 승격) |
| ⑭ | KIS 실계좌 키 평문 파일 레포 내 존재 | **이연(사용자 액션)** — 레포 밖 이동 + 예방적 재발급 권장 (git 미추적 확인됨) |
| ⑮ | api/consumer/batch 3중 복붙 (rate limiter 등) | **ADR 0139 Proposed 발행 완료** — 구현은 빌드 검증 필요로 보류, 사용자 결재 대기 |

### P2 (16건)

| # | 항목 | 상태 |
|---|------|------|
| 1~4 | 백테스트 체결가/정규장 필터/MAX_HOLD 기준/트레일 영속·stale tick | **이연** — Phase 3 트랙화 (자본 흐름 결합, ADR 발의 대상) |
| 5~6 | position qty/quantity 이중·TradingMode enum 2벌 | **이연** — Phase 3 (rate limiter 공유화와 함께 상환) |
| 7~9 | stale-jar 가드 사각·dev jar mount 착시·healthy 대기 무조건 exit 0 | **이연** — scripts/docker 소유권 밖 (OPS 후속) |
| 10~11 | OPSP8997 무교정·sendSink 반환 미확인 | **구현 Wave 대상** (DATA) 또는 Phase 3 |
| 12~15 | 공휴일 3중 불일치·Naver/DART 실패 위장·AI 비용 dead code·DLQ 메트릭 0 | **이연** — Phase 3 |
| 16 | 종목상세 리렌더 + **backlog 3,376줄 아카이브** + fallback BUY default | backlog 아카이브 **완료**(`_workspace/backlog/done/pre-v3.15.md` — 19§ 759줄 이전) · 리렌더는 FRONT 구현 Wave 대상 · fallback default는 이연 |
| ⑨(거버넌스) | 훅 주석 옛 ADR 번호 | **완료** — pre-commit/pre-push 0031·0036 체계 치환 (로직 무변경) |

### P3 (12건)

전부 **인지 등재** 상태 — 수학·계약상 무해(감사 §7). consumer admin REST 7종 보안 검토만 우선순위 상향 권고(문서에는 "내부망 한정" 명문화 완료 — master-architecture §6-1). market-trend-engine 주석 과장 정정은 소스 파일이라 본 문서 Wave에서 제외(보고만).

## 적용 위치

- 감사 보고서: `_workspace/audit/2026-07-03-system-audit.md`
- 이번 Wave 문서 정정: CLAUDE.md · `_workspace/INDEX.md` · `_workspace/arch/adr/`(0137~0139 + README) · `.claude/agents/frontend.md`·`backend-core.md` · `.claude/skills/magicjar-chart-curator/`(아카이브) · `.husky/pre-commit`·`pre-push` · `docs/architecture/master-architecture.md`·`code-convention.md`·`candle-data-policy-v2.md` · `_workspace/backlog.md`(+`backlog/done/`)

## 변경 이력

- 2026-07-03: 신설 — 감사 개선 Wave 거버넌스/문서 트랙 구현분 반영 (ADR 0137·0138·0139)
