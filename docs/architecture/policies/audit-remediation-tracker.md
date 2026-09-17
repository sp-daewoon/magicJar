# 2026-07-03 시스템 감사 조치 추적 — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: 감사 보고서 [`_workspace/audit/2026-07-03-system-audit.md`](../../../_workspace/audit/2026-07-03-system-audit.md) (P0 1 · P1 12 · P2 16 · P3 12) · ADR 0137·0138·0139
> **마지막 코드 재검증**: 2026-07-15 (`65652c4d`) — 감사 원문은 2026-07-03 시점 기록으로 보존하고, 현재 상태만 본 tracker에서 갱신한다.

## 현행 규약 (항목별 상태)

상태 값: **완료** (현재 HEAD 구현·테스트 근거 확인) / **부분 완료** (일부 수용 기준만 충족) / **이연** (별도 ADR·사용자 승인 또는 운영 검증 필요).

### P0

| # | 항목 | 상태 |
|---|------|------|
| P0-1 | KIS WS 재연결 시 호가·VI·시간외 재구독 누락 (VI 보호 silent 상실) | **완료** — `KisWebSocketBridge`가 H0UNASP0·H0UNMKO0·H0STOUP0 Set snapshot을 재발사하고 reconnect 회귀 테스트가 이를 고정 |

### P1

| # | 항목 | 상태 |
|---|------|------|
| ① | BoundaryAggregator 1m 풀스캔 (단조 악화) | **완료** — `ConsumerCandleJpaRepository.findTsByCodeAndIntervalAndTsBetween` 범위 조회 사용 |
| ② | LEGACY 백테스트 SL/TP 미평가 | **완료** — LEGACY 경로 SL/TP 산식 및 회귀 테스트 반영. INTRADAY_REALISTIC 기본 승격은 별도 정책 의제 |
| ③ | WS slot priority eviction 미구현 | **완료** — ADR 0138 Accepted + priority victim 선택·HOLDING/PENDING_BUY 비축출 회귀 테스트 반영 |
| ④ | DSL close 수정주가 vs 지표 원주가 혼합 | **이연 → 후속 트랙 승계** — raw 고정으로 1차 교정 완료(감사 §1-⑤). ADJUSTED 모드 양측 threading은 [chart-data-integrity-remediation.md](chart-data-integrity-remediation.md) **P2-A**로 승계 (2026-09-17 감사 재확인) |
| ⑤ | tick 0원 캔들 영속 (sanity 가드 0) | **완료** — parser 가격 가드 + `TickToMinuteAggregator` OHLC sanity reject counter·테스트 반영 |
| ⑥ | NEW_MKOP_CLS_CODE 세션 미파싱 (ADR 0124 수용 기준 위반) | **부분 완료** — `KisWsMessageParser.parseH0Uncnt0`가 index 34를 `TickEvent.session`으로 전파. 코드값별 예상체결 제외 정책은 KIS 원문/라이브 evidence 확보 전 이연 |
| ⑦ | "WS 30초 단절→전 종목 polling" 폴백 no-op·문서 오정보 | **문서 정정 완료** (candle-data-policy-v2 §6-A·CLAUDE.md) · HOLDING 한정 폴백 편입 여부는 후속 ADR 의제 |
| ⑧ | Prometheus alert rule 0·Alertmanager 부재 | **완료(구성 기준)** — `magicjar-alerts.yml` 5개 규칙 + Alertmanager compose/Slack·blackhole routing 존재. 실제 운영 알림 전송은 배포 환경 검증 대상 |
| ⑨ | 프론트 STOMP 재연결 후 재구독 부재 | **완료** — connection generation 기반 topic 재구독과 `useStompReconnect` 회귀 테스트 반영 |
| ⑩ | CLAUDE.md/INDEX 3주 동결·수치 stale·GH Actions 3중 모순 | **완료** — 모듈 9 실측·ADR 수치 링크화·GH Actions 사실 정정·observability 위치·감사 링크 등재 |
| ⑪ | chart-curator 유령 (frontend.md 이관 지시·live SKILL·cross-review) | **완료** — frontend.md ADR 0094 재작성·SKILL 아카이브+tombstone·backend-core 매트릭스 qa 재지정 |
| ⑫ | ADR 라이프사이클 붕괴·인덱스 "불일치 0" 거짓 | **완료** — 0114·0117·0123 Accepted 승격·0073 헤더 정합·0090 owner 재지정·훅 ADR 번호 치환. 정합 검증 스크립트 release 편입·분기별 Proposed triage는 **이연** |
| ⑬ | smoke.sh 3 시나리오 공허 | **완료(계약 기준)** — 실 `/api/backtests`, Kafka tick→signal DB 확인, symbol news endpoint로 교체하고 FAIL/SKIP 종료 계약 명시. 실제 통합 smoke는 앱·인프라 기동 환경에서 검증 |
| ⑭ | KIS 실계좌 키 평문 파일 레포 내 존재 | **이연(사용자 액션)** — 레포 밖 이동 + 예방적 재발급 권장 (git 미추적 확인됨) |
| ⑮ | api/consumer/batch 3중 복붙 (rate limiter 등) | **ADR 0139 Proposed 발행 완료** — 구현은 빌드 검증 필요로 보류, 사용자 결재 대기 |

### P2 (16건)

| # | 항목 | 상태 |
|---|------|------|
| 1~4 | 백테스트 체결가/정규장 필터/MAX_HOLD 기준/트레일 영속·stale tick | **이연** — Phase 3 트랙화 (자본 흐름 결합, ADR 발의 대상) |
| 5~6 | position qty/quantity 이중·TradingMode enum 2벌 | **이연** — Phase 3 (rate limiter 공유화와 함께 상환) |
| 7~9 | stale-jar 가드 사각·dev jar mount 착시·healthy 대기 무조건 exit 0 | **이연** — scripts/docker 소유권 밖 (OPS 후속) |
| 10~11 | OPSP8997 무교정·sendSink 반환 미확인 | **부분 완료** — `emitChecked`로 sink emission 결과를 관측·경고. OPSP8997 별도 교정 정책은 후속 DATA 의제 |
| 12~15 | 공휴일 3중 불일치·Naver/DART 실패 위장·AI 비용 dead code·DLQ 메트릭 0 | **이연** — Phase 3 |
| 16 | 종목상세 리렌더 + **backlog 3,376줄 아카이브** + fallback BUY default | backlog 아카이브 **완료**(`_workspace/backlog/done/pre-v3.15.md` — 19§ 759줄 이전) · 리렌더는 FRONT 구현 Wave 대상 · fallback default는 이연 |
| ⑨(거버넌스) | 훅 주석 옛 ADR 번호 | **완료** — pre-commit/pre-push 0031·0036 체계 치환 (로직 무변경) |

### P3 (12건)

전부 **인지 등재** 상태 — 수학·계약상 무해(감사 §7). consumer admin REST 7종 보안 검토만 우선순위 상향 권고(문서에는 "내부망 한정" 명문화 완료 — master-architecture §6-1). market-trend-engine 주석 과장 정정은 소스 파일이라 본 문서 Wave에서 제외(보고만).

## 적용 위치

- 감사 보고서: `_workspace/audit/2026-07-03-system-audit.md`
- 이번 Wave 문서 정정: CLAUDE.md · `_workspace/INDEX.md` · `_workspace/arch/adr/`(0137~0139 + README) · `.claude/agents/frontend.md`·`backend-core.md` · `.claude/skills/magicjar-chart-curator/`(아카이브) · `.husky/pre-commit`·`pre-push` · `docs/architecture/master-architecture.md`·`code-convention.md`·`candle-data-policy-v2.md` · `_workspace/backlog.md`(+`backlog/done/`)

## 변경 이력

- 2026-07-15: 현재 HEAD 재검증 — P0-1, P1 ①·②·③·⑤·⑧·⑨·⑬ 완료 전환; P1 ⑥·P2 10~11 부분 완료로 정정. 운영 실증과 코드 존재를 구분.
- 2026-07-03: 신설 — 감사 개선 Wave 거버넌스/문서 트랙 구현분 반영 (ADR 0137·0138·0139)
