# chart-curator 책임 이관 매트릭스 — frontend(LWC v4) + market-data(캔들 SLA) 인수 절차

> **위치**: `_workspace/specs/2026-05-21-chart-curator-handover.md`
> **연관 ADR**: `_workspace/arch/adr/0094-ops-stage-transition.md` Accepted §2-5 (본 spec의 책임 이관 본문)
> **작성**: architect (worktree `magic_ch1_architect_ops_transition`, branch `agent/architect/ops-transition-plan`)
> **결재**: 사용자(최고 관리자) 2026-05-21 KST 위임 + orchestrator 자율 결정 (8 → 7 hierarchy + chart-curator 해고 명확화)
> **Superseder**: 본 spec은 ADR 0085 (chart-curator 영입) 책임을 frontend·market-data 두 영역으로 이관하는 적용 매트릭스. ADR 0094가 ADR 0085 supersede.

본 spec은 ADR 0094 §2-2-A "chart-curator 해고" 결정의 적용 매트릭스. 책임·파일·테스트·디스패치 사이클 명세.

---

## §0. 이관 trigger evidence

| evidence | 인용 |
|----------|------|
| 사용자 1단계 결재 | "에이전트 전격적으로 해고 절차와 투입하는걸 다시 만들 거야" |
| ADR 0094 §2-2-A (해고 사유) | 8 hierarchy 운영 부담 ↑ + LWC v4와 캔들 SLA는 영역 본질이 frontend·market-data 각각 단일 owner 가능 |
| ADR 0085 §1-2 (단일 owner 부재 evidence) | 한국석유 일봉 5개월 stale dogfooding → chart-curator 영입으로 1차 fix → 본 ADR amend로 frontend·market-data 책임 명확 분리로 영구 fix |

---

## §1. 책임 이관 매트릭스 (3 영역 → 2 영역 단일 owner)

### 1-1. 영역 본질 분리

| 책임 영역 | 본질 | **이관 대상** |
|----------|------|-------------|
| TradingView LWC v4 컴포넌트 | 차트 그림·인터랙션·플러그인 — React/TS 컴포넌트 | **frontend** (LWC v4 단일 owner) |
| 캔들 적재 워커 + KIS 캔들 endpoint | KIS REST/WS adapter 일부 — KIS API 호출·DB 영속 | **market-data** (KIS API 단일 owner 정합) |
| 캔들 SLA 정합성 (`last_candle_at` 모니터링·갭 검출·자동 backfill) | 시계열 데이터 정합성 lens — backend 영역 | **market-data** (캔들 SLA 단일 owner) |
| Corporate action candle adjustment | 종목 행위(분할·배당) 캔들 조정 — backend 영역 | **market-data** |
| 휴장일·시간외·NXT 매트릭스 | 시장 운영 시간 정책 — backend 영역 | **market-data** |
| 캔들 Visual 회귀 (chart 컴포넌트 렌더링 검증) | frontend Visual 영역 | **qa Visual** (자동 dispatch 트리거 §3-2 정합) |

### 1-2. 영역 owner 매트릭스 (운영 단계 LOCK)

| owner | 책임 범위 |
|-------|----------|
| **frontend** | `frontend/src/components/chart/*` (LWC v4 컴포넌트 전체) · 차트 인터랙션 · 플러그인 · 멀티차트 layout · 사용자 UX |
| **market-data** | KIS 캔들 REST/WS adapter · 적재 워커 · `last_candle_at` 모니터링 · 갭 검출 · 자동 backfill · corporate action · NXT 매트릭스 · 캔들 SLA 정합성 |
| **qa Visual** | 차트 컴포넌트 렌더링 Visual 회귀 (자동 dispatch — 본 spec §3) |

---

## §2. 이관 대상 파일 매트릭스

### 2-1. frontend 인수 파일 (LWC v4 컴포넌트)

| 경로 | 책임 |
|------|------|
| `frontend/src/components/chart/*` | TradingView LWC v4 컴포넌트 전체 (series·overlay·marker·priceLine·crossSign·panes·multipane MTF) |
| `frontend/src/hooks/useChart*` (있을 시) | 차트 hook |
| `frontend/src/types/chart*.ts` (있을 시) | 차트 타입 |
| `frontend/src/pages/Chart*` (있을 시) | 차트 페이지 |

### 2-2. market-data 인수 파일 (캔들 SLA + KIS adapter)

| 경로 | 책임 |
|------|------|
| `consumer/.../ws/*KisCandle*` | KIS WS 캔들 stream adapter |
| `api/.../CandleRefreshService.kt` (또는 동급) | 캔들 적재 service |
| `api/.../ws/*Kis*Candle*` (REST 폴백) | KIS REST 캔들 endpoint adapter |
| `consumer/.../candle/*` (적재 워커) | 캔들 적재 워커 (Watchlist Ingestion Worker 일부) |
| `api/.../candle/*SlaMonitor*` (있을 시 또는 신규) | `last_candle_at` SLA 모니터링 — 신규 발행 P1 |
| Flyway migration `V*_*candle*` | 캔들 테이블 스키마 (이미 있음 — 이관만, 신규 발행 0) |

### 2-3. 테스트 이관

| 경로 | owner |
|------|-------|
| `consumer/.../test/.../KisCandle*Test*.kt` | market-data |
| `api/.../test/.../Candle*Test*.kt` | market-data |
| `frontend/tests/visual/*chart*.spec.ts` (qa Visual 격상 후 신규) | qa Visual |

---

## §3. 디스패치 사이클 명세 (운영 단계 LOCK)

### 3-1. 캔들·차트 의제 디스패치 우선순위 (orchestrator SKILL.md amend 본문)

| 의제 | **1순위 actor** | 2순위 actor |
|------|---------------|------------|
| 차트 컴포넌트 추가/수정 (`frontend/src/components/chart/*`) | **frontend** | qa Visual (회귀 자동) |
| 캔들 데이터 결함 (stale·갭·MTF 정합성 불일치 — 한국석유 dogfooding 패턴) | **market-data** | architect (SLA ADR 발행 시) |
| KIS 캔들 endpoint 신규 통합 (일/주/월/분/틱) | **market-data** | architect (spec) |
| Corporate action candle adjustment | **market-data** | strategy-engine (지표 영향) |
| 휴장일·시간외·NXT 매트릭스 캔들 처리 | **market-data** | architect (정책 ADR) |
| 차트 시각 회귀 detection | **qa Visual** | frontend (정정) |

**ADR 0085 §캔들·차트 dispatch 룰 → 본 spec §3-1 supersede.**

### 3-2. orchestrator SKILL.md amend 본문 (chart-curator 제거)

기존 (ADR 0085 정합):
```
### 캔들·차트 의제 dispatch 우선순위

다음 의제는 **chart-curator 우선 dispatch**:
- 차트 컴포넌트 추가/수정 (`frontend/src/components/chart/*`)
- 캔들 데이터 결함 (stale·갭·MTF 정합성 불일치 — 한국석유 dogfooding 패턴)
- KIS 캔들 endpoint 신규 통합 (일/주/월/분/틱)
- Corporate action candle adjustment
- 휴장일·시간외·NXT 매트릭스 캔들 처리
```

신규 (ADR 0094 정합):
```
### 캔들·차트 의제 dispatch 우선순위 (chart-curator 해고 후 — ADR 0094 §2-2-A)

| 의제 | 1순위 actor | 2순위 actor |
|------|------------|------------|
| 차트 컴포넌트 추가/수정 (`frontend/src/components/chart/*`) | **frontend** | qa Visual (회귀 자동) |
| 캔들 데이터 결함 (stale·갭·MTF 정합성 불일치) | **market-data** | architect (SLA ADR 발행 시) |
| KIS 캔들 endpoint 신규 통합 (일/주/월/분/틱) | **market-data** | architect (spec) |
| Corporate action candle adjustment | **market-data** | strategy-engine (지표 영향) |
| 휴장일·시간외·NXT 매트릭스 캔들 처리 | **market-data** | architect (정책 ADR) |
| 차트 시각 회귀 detection | **qa Visual** | frontend (정정) |
```

---

## §4. 적용 절차 (본 PR 통합 — Phase A·B 동시)

### 4-1. 본 PR 적용 매트릭스

| # | 작업 | 파일 | 산출물 |
|---|------|------|--------|
| 1 | ADR 0094 amend Accepted | `_workspace/arch/adr/0094-ops-stage-transition.md` | §2-2-A chart-curator 해고 결정 |
| 2 | ADR 0085 status Superseded | `_workspace/arch/adr/0085-chart-curator-agent-introduction.md` | status row + Supersedes 표시 |
| 3 | `.claude/agents/chart-curator.md` archive | `.claude/agents/chart-curator.md` | 파일 archive (삭제 또는 archived/ 이동) |
| 4 | `.claude/agents/frontend.md` 페르소나 amend | `.claude/agents/frontend.md` | 보수 "안정빌드" 흡수 + LWC v4 책임 명시 |
| 5 | `.claude/agents/market-data.md` 페르소나 amend | `.claude/agents/market-data.md` | 보수 "안정빌드" 흡수 + 캔들 SLA 책임 명시 |
| 6 | orchestrator SKILL.md amend | `.claude/skills/magicjar-orchestrator/SKILL.md` | 7 actor 매트릭스 + 본 spec §3-2 dispatch 룰 |
| 7 | 운영 헌법 §10 신설 | `_workspace/arch/agent-charter.md` | ADR 0094 §4-2 본문 |
| 8 | master §10 ADR 인덱스 갱신 | `docs/architecture/master-architecture.md` | ADR 0094 row |
| 9 | ADR README 인덱스 갱신 | `_workspace/arch/adr/README.md` | 0094 row + 0085 status 변경 |
| 10 | CHANGELOG row | `CHANGELOG.md` | 본 amend 사이클 row |

### 4-2. 본 PR 이후 후속 작업 (별도 PR — Phase C)

이관 대상 코드 파일은 **본 PR에서 touch 0** (헌법 §2 단일 책임 코드 + 사용자 메모리 `feedback_spec_plan_scope` 정합). 후속 PR에서 처리:

| Phase | actor | 작업 |
|-------|-------|------|
| **Phase C-1** | market-data | 캔들 SLA `last_candle_at` 모니터링 service 신규 발행 (한국석유 일봉 stale 영구 fix) — P0-OPS-6 동반 가능 |
| Phase C-2 | frontend | LWC v4 차트 컴포넌트 점진 개선 — W5 T-LWC 트랙 진입 (사용자 결재 후) |
| Phase C-3 | qa Visual | 차트 시각 회귀 시나리오 발행 (`tests/visual/chart*.spec.ts`) — qa 격상 P0 dispatch 동반 |

---

## §5. 검증 — 사용자 메모리 정합 매트릭스

| 사용자 메모리 | 본 spec 정합 |
|--------------|------------|
| `feedback_v3_skepticism_no_regression` | chart-curator 해고는 fitness gap lens (단일 owner의 본질 영역 분리 명확) — v1/v2 잔재 0 |
| `feedback_kis_first_policy` | 캔들 SLA → market-data 인수는 KIS API 단일 owner 정합 |
| `project_system_vision` | 자본 path 직결 캔들 정합성 (룰 본체·백테스트·실거래 시그널 영향)는 market-data 단일 owner로 명확 |
| `feedback_orchestrator_active_parallel` | dispatch 매트릭스 §3-1 명확 — orchestrator 적극 병렬 디스패치 정합 |
| `feedback_spec_plan_scope` | sub-agent 해고 + ADR supersede + 책임 이관 매트릭스 → spec 발행 의무 충족 |
| `feedback_no_mock_data` | 캔들 SLA 모니터링 신규 발행 시 mock 합성 0 LOCK 보존 (KIS-first raw fetch) |
| `feedback_release_policy_patch_first` | 본 amend = patch (v3.7.x patch) 후 P0 OPS 해소 누적 후 v3.8.0 minor cut |
| `project_v3_baseline` | 본 amend는 v3.7.x patch — v3.8.0 운영 단계 baseline 직전 정합 |

### 5-1. 권위 ADR 정합

- ADR 0085 (chart-curator 영입) — 본 ADR 0094가 supersede (책임 이관 + 페르소나 보존은 영역 owner 페르소나 흡수)
- ADR 0080 (전체 opus 통일) — chart-curator archive 후에도 7 actor opus 4.7 LOCK 유지
- ADR 0087 (페르소나 매트릭스) — frontend·market-data 보수 "안정빌드" 흡수 amend
- ADR 0094 §2-2-A (chart-curator 해고 결정) — 본 spec이 적용 매트릭스
- ADR 0019 (캔들 저장 정책) · ADR 0028 (Watchlist Ingestion Worker) · ADR 0029 (당일 진행 캔들 Redis 캐시) · ADR 0057 (KIS-only) · ADR 0062 (합산 가공 5종) · ADR 0063 (NXT/통합거래 매트릭스) — 모두 market-data 단독 owner로 정합

### 5-2. 운영 영향 (ADR 0094 §10-5 양식 적용)

- **SLO 영향**: 본 amend는 메타·하네스 — SLO 직접 영향 0. Phase C-1 (market-data 캔들 SLA 모니터링) 처리 후 §10-1 SLO에 캔들 stale 신규 metric 추가 가능 (`last_candle_at` lag < 24h 일봉 기준).
- **rollback plan**: 본 amend revert 가능 — `.claude/agents/chart-curator.md` 복구 + frontend·market-data 페르소나 amend revert + orchestrator SKILL amend revert + ADR 0085 status 복구. 코드 touch 0이라 revert 단순.
- **관측 매트릭스**: Phase C-1 처리 후 backend-core dashboard에 캔들 SLA panel (timeframe별 stale ratio + 갭 detection 빈도) 추가 + qa Visual 차트 컴포넌트 회귀 detect 빈도.

---

## §6. 변경 이력

| 날짜 | 변경 | 작성자 |
|------|------|--------|
| 2026-05-21 | spec 신규 발행 — chart-curator 책임 이관 매트릭스 (ADR 0094 §2-2-A 본문 + 영역 owner 매트릭스 + 파일 이관 매트릭스 + dispatch 사이클 명세 + 적용 절차 10단계) | architect (사용자 위임 결재) |
