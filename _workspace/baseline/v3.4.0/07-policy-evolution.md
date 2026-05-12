# 07 — MD 룰 정책 진화 (v3.3.0 → v3.4.0)

> **작성자**: architect (PR-51)
> **시점**: 2026-05-12 v3.4.0 태그 컷 직후
> **사용자 명시 의도**: "우리 md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" (v3.1.0 baseline에서 도입 — v3.4.0까지 누적 갱신)

본 문서는 **v3.3.0 → v3.4.0 정책 진화**를 추적한다. 추측 0 — 모든 룰은 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § + ADR 0049(Superseded)~0066 + backlog 처리 매트릭스로 추적 가능.

각 § 본문은 **도입 전 / 도입 후 / 효과 / 사례 PR** 4단 구조다 (v3.1.0·v3.2.0·v3.2.2·v3.3.0 baseline 정합).

**v3.3.0 baseline 보존**: §1~§5 정책 진화 7건 (v3.2.2 5건 + v3.3.0 2건) + §6~§7 다음 Wave + 누적 매트릭스는 v3.4.0 사이클에서 **갱신 0** (안정화 + 보존). 본 v3.4.0에서는 §1·§2·§3 3건 신규 정책 진화 + §6 다음 Wave 정책 후보 갱신 + §7 누적 매트릭스 갱신.

**v3.4.0 사이클 정책 진화 3건**:
1. **ADR 0061 — Backtest 결과 영속 (3 테이블 분리 + REST 5 endpoint + KPI 12종 계산 룰)** — T-BT W3 트랙 마감 (R2 영속 + R3 frontend `/strategy/backtest`)
2. **ADR 0066 — T-SIG 실시간 시그널 엔진 (룰 매칭 + Kafka boundary listen + 영속 + STOMP fanout)** — 자동매매 본체 매매 결정 노드 가동 baseline (W3 두 번째 트랙)
3. **ADR 0049 Superseded — GitHub Actions release workflow 폐기 (사용자 결제 안 함 결정)** — 로컬 docker compose + scripts/build-and-deploy.sh 단독 운영 단순화 + 사용자 메모리 신규(`feedback_no_github_actions.md` · `feedback_kis_key_realonly_for_data.md` 추가 정합)

---

## §0. 운영 헌법 7 § (보존 — v3.1.0·v3.2.0·v3.2.2·v3.3.0 baseline 그대로)

운영 헌법 7 §는 v3.1.0 PR-26(#224)에서 통합 발행된 후 v3.2.0·v3.2.2·v3.3.0 사이클에서 보존 + v3.4.0 사이클 동안 **본문 갱신 0**. 안정화 검증 — 본 v3.4.0 18 PR 사이클에서 다음 영역 모두 헌법 정합:

| § | 제목 | v3.4.0 사이클 검증 |
|---|------|---------------------|
| §1 | task.mjs 4단계 의무 | 모든 sub-agent 디스패치 사이클(PR #330·#331·#332·#334·#335·#336·#337·#338·#51) start·check·done 4단계 호출 — 누적 task archive PR #328·#329 정합 |
| §2 | 머지 후 정리 4단계 | PR #330~#338 머지 후 즉시 worktree 제거 + task archive (orchestrator가 v3.4.0 cut PR #339 + GHA 폐기 PR #340·#341 동시에 chore archive 묶음 처리 — 헌법 §2 정합) |
| §3 | KIS-first raw fetch 의무 | v3.4.0 사이클은 KIS 외부 데이터 규격 신규 결정 0건 — T-SIG는 Kafka 내부 토픽만 listen + T-BT 영속은 KIS API 추가 호출 0. 헌법 §3 면제 cycle. 단, v3.3.0 baseline 인용(H0STCNT0 ccnl_krx cols[1] raw URL) 보존 |
| §4 | CHANGELOG 슬롯 채번룰 | PR #330·#331·#332·#334·#335·#336·#337·#338·#339·#340·#341·#342 모두 anchor 사전 발행 + sub-agent in-place edit. modify-shared 충돌 0건. **본 PR-51 baseline 사이클도 anchor 4건(`<!-- pr-51-slot-architect/backend-core/market-data/frontend -->`) 사전 발행 정합** (`orchestrator` slot 1건 포함하여 총 5 anchor) |
| §5 | PR 생성 직전 rebase 의무 | 본 PR-51 포함 모든 사이클 적용 — 통합 빌드 fail 0건 |
| §6 | JPA Repository default fun 함정 | ADR 0066 §6·§11-1 sub-agent 디스패치 매트릭스에서 `StrategyJpaAdapter` · `SignalJpaAdapter` (ADR 0051 default fun 함정 회피 — abstract 함수 body 없음) 정합 명시. PR #337 본격 구현에 inline 주석 검증 |
| §7 | sub-agent fail 분기 룰 | PR #330·#331·#332·#334·#335·#336·#337·#338·#340·#341 모든 본격 사이클 — 재디스패치 0건. orchestrator 자율 진행(헌법 §7 자율 진행 정합) |

**baseline 정합**: v3.2.0 시점 통합한 헌법이 v3.2.2·v3.3.0(실시간 fanout 미러스톤) + v3.4.0(T-BT W3 마감 + T-SIG W3 두 번째 트랙 마감 + GHA 폐기) 사이클까지 완주 — 갱신 의제 0건.

---

## §1. ADR 0061 — Backtest 결과 영속 (3 테이블 분리 + REST 5 endpoint + KPI 계산)

### 도입 전

v3.3.0 baseline 시점 T-BT(Backtest Engine, ADR 0056)는 **stateless POST /api/backtest/run** 단일 endpoint:
- 매번 재실행해야 결과 확인 가능 — "지난 결과 비교" 불가
- T-SIG 활성화 사전 게이트("최근 N일 내 백테스트 통과") 구현 불가 — DB 영속 필수
- UI 부재 — POST /api/backtest/run만 존재, 목록·조회·삭제 0건
- frontend `/strategy/builder` 까지만 가동 — 사용자가 룰 작성한 후 시뮬 → 결과 확인 → 활성화 결정 워크플로우 미완

ADR 0056 §3-7은 후속 ADR 0080+ 5건 예약 + §2-6은 1차 stateless 결정 + 영속을 backlog §27-2로 미루었다. PR-50 plan(2026-05-12)이 fitness gap을 분석:
- **사용자 가치 미달**: 매번 재실행해야 결과 확인 가능
- **운영 정합 미달**: T-SIG 활성화 사전 게이트 구현 불가
- **UI 부재**: POST /api/backtest/run만 존재

### 도입 후

ADR 0061 (PR #330 plan + PR #331 Accepted+본격) — 사용자 결정 6건 권장안 채택:
- **A-1 본 사이클 R2 영속** — T-SIG 활성화 게이트 사전 준비
- **B-1 3 테이블 분리** — `backtest_run` + `backtest_trade` + `backtest_position`
- **C-1 5 endpoint** — POST run + GET list + GET detail + GET result + DELETE
- **D-1 단일 페이지** — `/strategy/backtest` (R3 frontend — 본 PR 범외, PR #332에서 본격)
- **E-1 모델 확장 본 사이클 0** — 세금·수수료·슬리피지 모델 현행 유지(ADR 0056 default 3종 보존)
- **F-1 R2 → R3 직렬** — R2 머지 후 R3 frontend 진입

**Flyway V12·V13** (PR #331):
- V12 `backtest_run` 12 컬럼: id BIGSERIAL · user_id · code · interval · rule_dsl · range_from · range_to · status · started_at · completed_at · KPI 12종(total_return · win_rate · mdd · sharpe · sortino · trade_count · position_count) · detail_json JSONB
- V13 `backtest_trade` + `backtest_position` — run_id FK CASCADE DELETE + 시계열
- 인덱스 `idx_backtest_run_user_executed`·`idx_backtest_run_strategy_executed`·`idx_backtest_trade_run_ts`

**REST 5 endpoint** (PR #331):
- `POST /api/backtests` — 실행 + 즉시 영속 (PENDING→RUNNING→SUCCEEDED/FAILED)
- `GET /api/backtests?page=&size=` — 사용자 목록 페이징
- `GET /api/backtests/{id}` — 단건 메타 + KPI
- `GET /api/backtests/{id}/result` — 거래·포지션 시계열 페이징
- `DELETE /api/backtests/{id}` — CASCADE DELETE

기존 stateless `POST /api/backtest/run` (ADR 0056 §5-1) **유지** — stateless run 경로 별도 보존. 권한 모든 endpoint userId 본인 검증 + 위반 시 403 (ADR 0010 multi-tenant 정합).

**`BacktestPersister`·`BacktestQueryService`·`BacktestController`** (PR #331, api):
- `@Component BacktestPersister` `@Transactional` — run + trades + positions 단일 트랜잭션
- `@Service BacktestQueryService` — listByUser·findById·findResult·delete (CASCADE)
- `BacktestController` 5 endpoint + Swagger 4단 어노테이션 + 한국어 설명 (사용자 메모리 `feedback_swagger_pattern.md` 정합)

**frontend `/strategy/backtest`** (PR #332):
- `pages/strategy/Backtest.tsx` 단일 페이지 (왼쪽 폼 + 오른쪽 목록 + 상세 모달)
- 6 컴포넌트: `BacktestRunForm`·`BacktestRunList`·`BacktestDetailModal`·`BacktestKpiCards` 12종·`BacktestEquityCurve` LWC line series·`BacktestTradeMarker` LWC + 매매 마커
- `api/backtest.ts` REST client + routing 확장

**fitness gap 분석 (ADR 0061 §1)**:
- **자동매매에 필요한가? — 필요** — T-SIG 활성화 사전 게이트 baseline · 사용자 워크플로우(룰 작성 → 백테스트 → 활성화 결정) 필수
- **v1/v2 잔재 아닌가? — 잔재 아님** — ADR 0056(2026-05 v3.2.2 신규)의 R2 후속 정합
- **회귀 risk 검증** — 기존 stateless `POST /api/backtest/run` 보존 + sandbox(strategy-eval-pool · timeout 1000ms — ADR 0047) 영향 0
- **사용자 비전 정합** — 사용자=최고 관리자/설계자 워크플로우(룰 작성→시뮬→결과 확인→활성화) 완성

### 효과

- **사용자 워크플로우 완성** — `/strategy/builder` → `/strategy/backtest` 시뮬 → 결과 확인 → 활성화 결정 완전 경로
- **T-SIG 활성화 사전 게이트 baseline 확립** — strategy 활성화 시 "최근 N일 내 백테스트 통과" 검증 후속 구현 가능
- **시계열 시각화 가능** — LWC equity curve + 매매 마커 + 페이징 시계열 조회
- **CASCADE DELETE 정합** — run 삭제 시 trades·positions 일괄 정리 (audit 보존 필요시 별도 archive 후속)
- **운영 부담 미미** — Flyway V12·V13 단순 + REST 5 endpoint + 사용자 1명 가정 row 누적 미미
- **ADR 0056 §2-6 supersede** — 1차 stateless 결정이 영속 결정으로 진화 (점진적 baseline 진화 정합)

### 사례 PR

- **PR #330** (architect plan) — PR-50 plan W3 T-BT R2/R3 잔여 + 결정 의제 6건 사용자 검토 위임
- **PR #331** (backend-core 본격) — T-BT R2 영속 + Flyway V12·V13 + REST 5 endpoint + ADR 0061 Accepted
- **PR #332** (frontend 본격) — T-BT R3 `/strategy/backtest` 페이지 + 6 컴포넌트 + LWC equity curve
- **PR #333** (release) — v3.3.1 minor release T-BT W3 트랙 마감

---

## §2. ADR 0066 — T-SIG 실시간 시그널 엔진 (자동매매 본체 매매 결정 노드 가동)

### 도입 전

v3.3.1 baseline 시점 자동매매 본체 매매 결정 노드 **부재**:
- ADR 0023(Signal Engine = 룰 매칭 only, AI 호출 0) 권위만 발행 — 실제 가동 0
- ADR 0047 DSL Sandbox(strategy-eval-pool · timeout 100ms · auto-disable baseline) 자산만 가동 — strategy 영속 본체 0
- ADR 0064 boundary fanout(Kafka `market.candle.{interval}.closed.v1` 5종 + STOMP) 활성 — 시그널 평가 trigger 진입점만 LOCK
- T-DSL(W2 완료) + T-IND(80 지표) + T-PAT(57 패턴) + T-BT(v3.3.1 영속·UI 완성) 사전 자산 풀

**자동매매 본체 정합 결함**:
- **매매 결정 노드 0** — magicJar 비전(`project_system_vision.md`) "룰 기반 quant trading 본체" 핵심 노드 부재
- **사용자 룰 등록·관리 UI 0** — 룰 작성(`/strategy/builder`)·시뮬(`/strategy/backtest`)까지는 가능하지만 **활성화 후 실시간 평가 0**
- **시그널 history audit 0** — T-J(Trading Journal) baseline 부재 · MCP 외부 client query 부재 · 백테스트 결과와 actual 시그널 비교 부재
- magicJar는 **백테스트 도구로만 동작** — 실시간 매매 결정 0

### 도입 후

ADR 0066 (PR #334 plan + PR #335 Accepted + PR #336 Phase 1 + PR #337 Phase 2 + PR #338 Phase 3) — 사용자 결정 의제 6건 권장안 채택:

**의제 A 트리거 시점**: A-1 Kafka boundary listen 5 listener 채택 — `market.candle.{5m·15m·30m·90m·1h}.closed.v1` 5 토픽 ADR 0064 baseline 100% 활용. latency 최소(<50ms boundary→평가→fanout) + partition by code 단일 thread race 0 + 멱등 단순(boundary ts deterministic) + audit trail Kafka topic retention 7d.

**의제 B 룰 source**: B-1 strategy 영속 DB 채택 — `strategy` 테이블(Flyway V14) + 사용자 REST `POST /api/strategies` 등록 + UI 등록 가능 + 활성/비활성 토글 + 사용자별 격리 + 영속 = 재기동 후 자동 평가 재진입. Redis cache(`strategy:active:by_code:{code}:{interval}`)로 평가 path I/O 0.

**의제 C 시그널 영속**: C-1 신규 `signal` 테이블 채택(Flyway V15) — T-J·MCP·dogfooding 모두 필수 + ADR 0061 T-BT 패턴 정합 + 1차 단일 테이블 + index(userId·strategy·code) + row ≥ 5M 도달 시 monthly partition 후속 ADR(가칭 0085+).

**의제 D 사용자 알림**: D-1 STOMP fanout 1차 채택 — `/topic/signal.user.{userId}` ADR 0064 STOMP 인프라 100% 활용 + multi-tenant(ADR 0010) 격리. D-2 옵션(Slack webhook · 이메일 · Web Push) T-NOTIFY 트랙(ADR 0044 합류) 진입 시 별도 PR.

**의제 E 중복 시그널**: E-1 DB UQ + Redis SETNX 30s window 보조 채택 — `UNIQUE (strategy_id, code, interval, ts)` deterministic boundary ts 정합 + DB level 강제 + Redis SETNX fast-path skip + DUPLICATE_KEY handling(`log.debug + skip`).

**의제 F 비활성 룰**: F-1 status enum 4단(ACTIVE·PAUSED·DISABLED·DELETED) + disabled_reason 채택 — ADR 0047 §2-6 V26 baseline 정합 + 4가지 의도 명확(ACTIVE 평가 진입·PAUSED 사용자 토글·DISABLED sandbox auto·DELETED soft delete) + transition 매트릭스(사용자 액션 ACTIVE↔PAUSED·재활성화 DISABLED→ACTIVE·삭제·시스템 액션 ACTIVE→DISABLED).

**`domain/.../signal` + `domain/.../strategy` 확장** (PR #336):
- `Signal` data class(id·strategyId·userId·code·interval·ts·side·strength·reason·priceAtSignal·contextJson·createdAt) + `SignalSide`(BUY·SELL·HOLD) + `SignalContext`(code·interval·boundaryTs·nowKst·indicatorCache) + `SignalGenerator`·`SignalRepository`·`SignalDispatcher` port (Pure Kotlin · Spring·JPA 0)
- `Strategy` 확장(StrategyStatus enum 4단 + StrategyDisabledReason + StrategyComplexity ADR 0047 §2-1-B + EvalMetricSummary + StrategyRepository port)

**`RealtimeSignalGenerator`** (PR #336, consumer @Component) — ADR 0066 §7-2 1:1 매핑:
1. boundary payload 수신 → `onBoundaryClosed(BoundaryClosedEvent payload)`
2. `StrategyRepository.findActiveByCode(code, interval)` Redis cache 우선
3. FOR strategy IN active:
   - `SignalDedupeGuard.tryAcquire` SETNX 30s — fast-path skip
   - candles fetch — recent N개 (Redis hot path 또는 DB)
   - `JvmStrategyEvaluator.evaluate` sandbox — timeout 100ms · CPU budget 50ms · strategy-eval-pool 4 fixed (ADR 0047 정합)
   - TIMEOUT/CPU_BUDGET_EXCEEDED 시 `strategy.status=DISABLED` + `risk.violation.v1` publish (ADR 0044 정합)
   - `Signal?` 발행(BUY·SELL·HOLD) — null skip
   - `SignalPersister.save` — DB UQ DUPLICATE_KEY 시 `log.debug + skip` (E-1 보호망)
   - `SignalKafkaProducer.publish(signal.generated.v1, key=userId)` — 후행 T-O·T-RISK 진입점
   - `SignalDispatcher.dispatch(signal)` STOMP fanout
   - StrategyEvalMetric INSERT (eval_time_ms · cpu_time_ms · outcome)

**`BoundaryClosingKafkaListener` 5건** (PR #336, consumer):
- `@KafkaListener` × 5 (`market.candle.{5m·15m·30m·90m·1h}.closed.v1`)
- partition key=code → 단일 thread 보장
- latency <5ms (ADR 0066 §7-3)

**`SignalDedupeGuard`·`SignalKafkaProducer`·`RedisStrategyCache`·`StompSignalDispatcher`** (PR #336·#337):
- SignalDedupeGuard Redis SETNX 30s — fast-path skip (DB UQ 보조)
- SignalKafkaProducer `signal.generated.v1` partition by userId retention 7d (T-O·T-RISK 진입점)
- RedisStrategyCache `strategy:active:by_code:{code}:{interval}` cache + register/toggle/delete evict (메모리 ~750KB)
- StompSignalDispatcher Kafka consumer → STOMP `/topic/signal.user.{userId}` fanout (multi-tenant 격리)

**Flyway V14·V15** (PR #337):
- V14 `strategy` — status enum 4단 default ACTIVE + disabled_reason + complexity_json JSONB + eval_metric_summary JSONB + idx_strategy_user_status + partial idx_strategy_active_lookup ON strategy(status) WHERE status='ACTIVE'
- V15 `signal` — UQ (strategy_id, code, interval, ts) + idx_signal_user_ts·strategy_ts·code_ts

**REST 8 endpoint** (PR #337):
- Strategy 6: POST(등록 + complexity 계산)·GET list·GET detail·PUT toggle?status=PAUSED·PUT reactivate·DELETE soft delete
- Signal 2: GET list 페이징(?userId·strategyId·code·from·to·page·size)·GET detail

**frontend `/strategy/list`** (PR #338):
- `pages/strategy/StrategyListPage.tsx` (목록 · 토글 · 삭제 · 재활성화)
- 5 컴포넌트: `StrategyCard`(status 배지 + complexity 표시 + lastSignalAt) · `SignalListPanel`(시계열 페이징 + 종목 필터 + 인터벌 필터) · `StrategyStatusBadge` · `StrategyToggleButton` · `StrategyDetailModal`
- `useStompSignal` STOMP `/topic/signal.user.{userId}` subscribe + 토스트
- `useStrategies`·`useSignals` REST hook (TanStack Query) + routing 확장

**fitness gap 분석 (ADR 0066 §1-3)**:
- **자동매매에 필수** — magicJar 비전 정의 "룰 기반 quant trading 본체. 매매=룰/시그널". T-SIG가 그 룰/시그널 본체. 부재 시 magicJar는 백테스트 도구로만 동작
- **v1/v2 잔재 아님** — ADR 0023(2026-05-03)·ADR 0047(2026-05-07)·ADR 0064(2026-05-12) 모두 v3.x 발행. 본 ADR이 그 자산의 W3 구현
- **사용자 비전 정합** — magicJar = 룰 기반 본체 → 본 ADR 매매 결정 권위 · AI는 옵션 보조 → LLM 호출 0건 검증 의무(§2-4·§8) · MCP 서버 노출(ADR 0027) → 향후 외부 client signal query API 정합

### 효과

- **자동매매 본체 매매 결정 노드 가동** — 룰 기반 BUY/SELL/HOLD 시그널 boundary 마감 시점 실시간 발행 (latency <100ms)
- **ADR 0023 권위 W3 구현** — 룰 매칭 only · AI 호출 0 검증 의무 (`grep -rE "import (org\.springframework\.ai|com\.anthropic|com\.openai|com\.google\.cloud\.aiplatform)"` T-SIG 영역 결과 0건)
- **ADR 0047 sandbox 실행 path 통합** — strategy-eval-pool · timeout 100ms · auto-disable 정합
- **ADR 0064 boundary fanout 활용** — Kafka 5종 listen으로 latency 최소
- **사용자 UX 완성** — `/strategy/list` 페이지 + 실시간 토스트 + 시그널 history 시계열 + status 토글·재활성화
- **T-O·T-RISK 후행 의존 자산 LOCK** — `signal.generated.v1` Kafka topic이 후행 트랙 진입점
- **MCP 외부 client 정합 후속** — signal 시계열 endpoint(`GET /api/signals`)를 후행 ADR 0027(MCP)에서 노출 가능
- **운영 영향 미미** — DB ~365K row/년 (5MB) · Kafka 0.012 msg/s · STOMP 0.012 msg/s · Redis ~2MB · KIS REST 추가 호출 0건 · strategy-eval-pool burst wall-clock ~750ms (4 thread 분산)

### 사례 PR

- **PR #334** (architect plan) — ADR 0066 plan 발행 (Status `Proposed`, 사용자 결정 의제 6건 대기)
- **PR #335** (architect Accepted) — ADR 0066 Proposed → Accepted (사용자 A-1·B-1·C-1·D-1·E-1·F-1 채택)
- **PR #336** (strategy-engine Phase 1 본격) — domain Signal + Strategy 확장 + RealtimeSignalGenerator + BoundaryClosingKafkaListener 5건 + SignalDedupeGuard + SignalKafkaProducer + 단위 테스트
- **PR #337** (backend-core Phase 2 본격) — Flyway V14·V15 + JPA + REST 8 endpoint + RedisStrategyCache + StompSignalDispatcher + 통합 테스트
- **PR #338** (frontend Phase 3 본격) — `/strategy/list` 페이지 + 5 컴포넌트 + useStompSignal 토스트 + REST hook + routing
- **PR #339** (release) — v3.4.0 minor release T-SIG W3 두 번째 트랙 마감

---

## §3. ADR 0049 Superseded — GitHub Actions release workflow 폐기

### 도입 전

v3.3.0 baseline 시점 GHA 운영:
- ADR 0049 Accepted (2026-05-07) — GitHub Actions = release 트리거 한정 정책
- `.github/workflows/release.yml` — `v*.*.*` 태그 push + `release/v*` 브랜치 push 시 ghcr.io 이미지 + GitHub Release draft 발행
- PR 검증·main push 이미지 발행은 pre-push hook(`./gradlew check`) + 로컬 docker compose로 단일화

**운영 결함**:
- **GHA billing 결제 실패 누적** — v3.2.2·v3.2.3·v3.3.0 모두 release tag push 후 CI 실행 실패. 원인 = GitHub 계정 결제 결함 (`"recent account payments have failed or your spending limit needs to be increased"`)
- **결과적으로 release 자동화 0** — 실제 ghcr.io 이미지 + Release draft 자동 생성이 release tag마다 fail
- **로컬 docker compose 운영은 정상** — `scripts/build-and-deploy.sh` + docker compose 단독 운영으로 매 release 사용자 직접 처리

backlog §36 A 등록(PR #329) — "GitHub Actions 결제" 사용자 결정 위임 의제.

### 도입 후

사용자 결정(2026-05-12) "GitHub Actions 결제 안 함" 명시:
- **PR #340** — `.github/workflows/release.yml` 파일 삭제. `.github/workflows/` 디렉토리 비워짐 (관련 파일 0건)
- **PR #341** — ADR 0049 status `Accepted (2026-05-07)` → **`Superseded (2026-05-12 — GHA 결제 폐기 사용자 결정, feedback_no_github_actions.md 정합)`** 헤더 갱신 + 폐기 노트 추가

> ADR 0049 폐기 노트 본문 인용:
> > "2026-05-12 폐기 노트 — 사용자(최고 관리자) 결정 'GitHub Actions 결제 안 함' 명시. `.github/workflows/release.yml` 삭제 + GHA 자동화 trigger 폐기. release tag + brunch 매커니즘은 그대로 유지 (ADR 0031 정합). 로컬 `scripts/build-and-deploy.sh` 단독 운영. 사용자 메모리 `feedback_no_github_actions.md` 정합. 향후 다중 환경 운영 진입 시점에 alternative CI 도입 재검토 ADR 신규 발행 의무."

**사용자 메모리 `feedback_no_github_actions.md` 신규** (2026-05-12 등록):
- magicJar는 GitHub Actions를 사용하지 않는다. release 자동화도 로컬 운영으로만.
- **룰 1**: GHA workflow 신규 발행 금지 — `.github/workflows/*.yml` 신규 파일 생성하지 말 것. release CI·image build·테스트 모두 로컬에서.

**사용자 메모리 `feedback_kis_key_realonly_for_data.md` 추가 정합** (v3.2.x 사이클 등록):
- 시장 데이터 조회는 prod credential 단일 채널 — vps(모의) credential은 주문 액션에만 사용 (ADR 0057 정합)

**backlog §36 처리**:
- A(GHA 결제) DONE 처리 — PR #340·#341 완료
- B(local agent/* branch 잔재) cosmetic 유지 — 다음 사이클 force 위임

**release immutable tag 매커니즘 보존** (ADR 0031 정합):
- `v*.*.*` 태그 push + `release/v*` 브랜치 push는 그대로 — pre-push hook이 ref update/delete 차단
- baseline 폴더 immutable(ADR 0052)도 그대로

### 효과

- **외부 의존 비용 0** — GHA billing 결제 의존 폐기 + alternative CI 도입 전까지 단순 로컬 운영
- **release 자동화 → 로컬 단일** — `scripts/build-and-deploy.sh` + docker compose 단독 운영 단순화 + 사용자 직접 처리 정합 보존
- **ADR 0031 immutable tag 매커니즘 그대로 유지** — release 자동화만 폐기, tag 정책은 살아있음
- **다중 환경 운영 진입 시 alternative CI 도입 재검토 의무** — ADR 신규 발행 의무 명시
- **사용자 메모리 신규** — `feedback_no_github_actions.md` (GHA 발행 금지 룰) + `feedback_kis_key_realonly_for_data.md` (시장 데이터 prod 단일 채널) 정합

### 사례 PR

- **PR #340** (meta · GHA workflow 파일 삭제) — `.github/workflows/release.yml` 파일 삭제
- **PR #341** (meta · ADR Superseded + backlog) — ADR 0049 status 갱신 + backlog §36 A DONE + 사용자 메모리 `feedback_no_github_actions.md` 정합 추가

---

## §4. ~~ADR 0057 — KIS-only~~ (v3.2.2 baseline 보존 — v3.4.0 갱신 0)

v3.2.2 baseline 07-policy-evolution.md §2 + v3.3.0 §3 보존. v3.4.0 사이클에서 본 정책은 보존 — prod credential 단일 채널 LOCK 유지 + Yahoo `@Deprecated` 보존(v3.5.x minor 일괄 삭제 예정).

**T-SIG는 KIS REST/WS 직접 호출 0건** — Kafka 내부 토픽만 listen이라 KIS-only 정책 영향 없음 (ADR 0066 §1 정합). T-BT 영속도 KIS API 추가 호출 0건 — DB 영속만.

사용자 메모리 `feedback_kis_key_realonly_for_data.md`(v3.2.x 사이클 등록) 보강 정합 보존.

---

## §5. ~~ADR 0062·0063·0064 — 합산 가공·NXT cover·boundary fanout~~ (v3.3.0 baseline 보존 — v3.4.0 갱신 0)

v3.3.0 baseline 07-policy-evolution.md §1 + v3.2.2 baseline §3·§4 보존. v3.4.0 사이클에서 본 정책은 모두 보존:
- ADR 0062 합산 가공 plan — boundary fanout 트랙 완료 후속 정합 보존
- ADR 0063 NXT/통합거래 cover — 08:00~20:00 KST cover baseline 보존
- ADR 0064 boundary fanout — Kafka `market.candle.{interval}.closed.v1` 5종 + STOMP `/topic/market.candle.{interval}.{code}` — **T-SIG `BoundaryClosingKafkaListener` 5건의 consumer 진입점으로 LOCK** (ADR 0066 §7-1 A-1 정합)

**v3.5.x minor 진입 시 architect 단독 PR 의제** (ADR 0023·0029·0048·0062 본문 갱신 + T-SIG 합류 정합 LOCK):
- ADR 0023 §2-1·§2-2·§2-3 본문 — Signal 데이터 클래스 확장 + 평가 흐름 boundary listener 단계 + RealtimeSignalGenerator 권위 인용
- ADR 0047 §2-6 V26·V27 → V14·V15 baseline 재할당 + §3-1 T-SIG 합류 매트릭스 본 ADR 권위
- ADR 0064 §7-1 sub-agent 매트릭스 — boundary listener consumer는 ADR 0066 권위 명시

---

## §6. 다음 Wave 정책 후보 (W3 잔여 진입 시 발행 예정)

W3 잔여 진입(Screener · Risk Guard · Order Executor · Reconciliation · Performance · Notification) 시 발행 예정 ADR/정책:

### 6-1. ADR 후보

| ADR 후보 | 주제 | 트리거 |
|---------|------|--------|
| ADR (1건) | ADR 0019·0023·0028·0029·0047·0048·0056·0062·0064 본문 갱신 통합 (T-SIG·T-BT 영속 후속 정합 + 매트릭스 NXT/합산/fanout/signal/strategy) | v3.5.x minor 진입 시 architect 단독 PR |
| **ADR 0065** | **Screener 표준 (universe 분류·매트릭스 응답)** | W3 T-SC 본격 사이클 시 (slot 보존) |
| ADR 0067 | Risk Guard chain 6단 표준 (ADR 0024 합류 — kill-switch·MDD·일일 한도·포지션 한도·종목 한도·시간 한도) | W3 T-RISK 본격 사이클 시 |
| ADR 0068 | Order Executor 표준 (TTTC8001U·VTTC0801U 분기·idempotency·재시도·KIS 체결통보 H0STCNI0 합류) | W3 T-O 본격 사이클 시 |
| ADR 0069 | Notification 표준 (Slack webhook·outbox 패턴·`signal.generated.v1` 합류 ADR 0044) — T-SIG 후속 다채널 확장(이메일·Web Push) baseline | W3 T-NOTIFY 본격 사이클 시 |
| ADR 0070 | Performance 표준 (Sharpe·MDD·Calmar·Sortino 계산 baseline — ADR 0056 KPI 12종 정합) | W3 T-PERF 본격 사이클 시 |
| ADR 0071 | Reconciliation 표준 (체결 정합·KIS H0STCNI0 vs internal order_audit 비교) | W3 T-RECON 본격 사이클 시 |
| ADR (slot) | signal monthly partition (row ≥ 5M 도달 시 발동 — ADR 0066 §9-1 후속) | 사용자 dogfooding row 누적 +5년 도달 시 |

### 6-2. 정책 진화 후보

| 정책 후보 | 주제 |
|----------|------|
| Yahoo Finance 완전 삭제 (v3.5.x minor 일괄) | `YahooCandleFetcherAdapter.kt` · `YahooFinanceClient.kt` · `application.yml` yahoo.* · 일회성 SQL · 테스트 mock 케이스 모두 삭제 (사용자 메모리 `feedback_kis_first_policy.md` 정합) |
| strategy_eval_metric V16 발행 (ADR 0047 §2-6 V27 재할당) | backend-core 단일 PR — V14·V15와 동시 발행이 적합했으나 본 v3.4.0 사이클에서 미발행 (별도 후속) |
| frontend `/screener`·`/dashboard`·`/orders` 라우트 발행 | W3 잔여 본격 사이클 |
| **W3 T-O (Order Executor) 본격 — ADR 0066 signal.generated.v1 baseline 활용** | T-O가 `signal.generated.v1` Kafka 토픽 consume → KIS TTTC/VTTC 분기 → order intent 발행 |
| **W3 T-RISK (Risk Guard chain 6단) — ADR 0024 합류 + signal/order 진입점 사이 게이트** | signal → order intent → risk guard chain → 통과/차단 결정 |
| **W3 T-NOTIFY (Slack webhook + `signal.generated.v1` 합류) — ADR 0044 정합 + T-SIG 후속** | signal/order/violation 멀티채널 알림 (사용자 메모리 + 사용자 dogfooding 누적 시 발동) |
| MCP 서버 노출 (Spring AI MCP starter 활용) — ADR 0027 | W4 본격 사이클 — **ADR 0066 signal/strategy REST API가 MCP 외부 client baseline LOCK** |
| Risk Guard kill-switch 활성화 (ADR 0042 정합) | W3 T-RISK 진입 시 |
| KIS 1m 과거 데이터 cover 확장 (현재 30 거래일 → 60·90·180 거래일) | 백테스트 long-period 정합 + KIS Rate Limit 영향 측정 |
| alternative CI 도입 재검토 (ADR 0049 Superseded 후속) | 다중 환경 운영 진입 시점 — Cloud Build·CircleCI·Jenkins 등 후보 평가 |

### 6-3. v3.4.0 baseline 보존 정합 의무

W3 잔여 진입 시점에 본 v3.4.0 baseline은 `_workspace/baseline/v3.4.0/` immutable(ADR 0031). v3.5.x 또는 v3.6.0 신규 baseline 컷(`_workspace/baseline/v3.X.X/`) 시 본 §1·§2·§3 정책 진화 3건은 신규 baseline 07-policy-evolution.md에 **누적 인용** — `_workspace/baseline/v3.4.0/07-policy-evolution.md §X` 직접 참조 + 신규 정책만 add.

---

## §7. v3.0.1 → v3.4.0 누적 정책 진화 요약 (5 baseline cumulative)

본 baseline 시점까지 누적된 MD 룰 정책 진화는 **v3.1.0 (4건)** + **v3.2.0 (4건)** + **v3.2.2 (5건)** + **v3.3.0 (2건)** + **v3.4.0 (3건)** = **18건**. 모두 ADR로 추적 가능:

| baseline | 사이클 | 정책 진화 | ADR 신규 |
|---------|--------|-----------|----------|
| v3.1.0 | PR-26~PR-29 | 운영 헌법 7 § 통합 + ADR 0049 baseline + ADR 0050 슬롯 채번룰 + ADR 0051 Repository default fun | 0049·0050·0051 |
| v3.2.0 | PR-31~PR-45 | ADR 0052 baseline 사이클 정형화 + ADR 0053 Living Policy Docs + ADR 0054 pattern-engine 모듈 분리 + ADR 0055 Strategy Builder UI 컴포넌트 스택 | 0052·0053·0054·0055 |
| v3.2.2 | PR-47~PR-48 | ADR 0056 W3 T-BT + ADR 0057 KIS-only + ADR 0062 합산 가공 + ADR 0063 NXT cover + 사용자 메모리 보강 2건 | 0056·0057·0062·0063 |
| v3.3.0 | PR-49 | ADR 0064 boundary fanout + v3.2.3 minor doc cut (ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 보강 + KIS 500 retry 3회) | 0064 |
| **v3.4.0** | **PR-50 + PR-51** | **ADR 0061 T-BT 영속 baseline + ADR 0066 T-SIG W3 두 번째 트랙 가동 baseline + ADR 0049 Superseded GHA 폐기 (사용자 메모리 `feedback_no_github_actions.md` 신규 + `feedback_kis_key_realonly_for_data.md` 추가 정합 보존)** | **0061·0066 (실제 신규 2건) + 0049 Superseded** |

**누적 ADR 63건** (v3.1.0 베이스 51건 → v3.2.0 55건 → v3.2.2 60건 → v3.3.0 61건 → **v3.4.0 63건**, 실제 신규 7건 + 슬롯 4건 + Superseded 1건). ADR 0019 부분 supersede revoke 지속(boundary fanout 트랙 완료 + T-SIG 트랙 진입 → v3.5.x minor §2 본문 재통합 architect 단독 PR 예정).

**baseline cumulative 정합**: v3.1.0 baseline `07-policy-evolution.md` (4건) + v3.2.0 baseline (4건) + v3.2.2 baseline (5건) + v3.3.0 baseline (2건) + 본 v3.4.0 baseline (3건) = **18건 모두 누적 보존** + ADR/메모리/헌법 추적 가능. 사용자 명시 의도 "md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" 정합.

**v3.4.0 사이클 핵심 메시지**: **T-SIG 실시간 시그널 엔진 가동(ADR 0066) — 자동매매 본체 매매 결정 노드 진입** + **T-BT W3 트랙 완성(ADR 0061 R2 영속 + R3 frontend)** — magicJar 비전 "룰 기반 quant trading 본체"의 W3 핵심 노드 + 사용자 워크플로우 완성(룰 작성 → 백테스트 → 활성화 → 실시간 시그널). + GHA 폐기(ADR 0049 Superseded)로 외부 의존 비용 0 + 로컬 단순 운영. AI 호출 0 grep 검증 보존(ADR 0023 §2-4 + ADR 0066 §8) — magicJar = 룰 기반 본체, AI는 옵션 보조 분리 보존. W3 잔여(T-SC·T-RISK·T-O·T-NOTIFY·T-PERF·T-RECON) 진입의 baseline이 본 ADR 0061·0066으로 LOCK.

**v3.4.0 사이클 사용자 메모리 정합 매트릭스**:
- `feedback_no_github_actions.md` (신규 v3.4.0) — GHA 폐기 결정 정합
- `feedback_kis_key_realonly_for_data.md` (v3.2.x 등록 정합 보존) — 시장 데이터 prod credential 단일 채널 LOCK + T-SIG는 KIS API 직접 호출 0건이라 정합 영향 0
- `feedback_v3_skepticism_no_regression.md` — ADR 0061·0066 fitness gap 분석 보고 의무 정합 (자동매매 필요·v1/v2 잔재 0·회귀 risk·사용자 비전 정합 모두 통과)
- `feedback_kis_first_policy.md` — Yahoo `@Deprecated` 유지 + KIS 단일 채널 보존
- `feedback_3tier_hierarchy.md` — architect plan PR(ADR 0061·0066) → 사용자 결정 → sub-agent 직렬·병렬 디스패치 (Phase 1·2·3 직렬) 정합
- `feedback_spec_plan_scope.md` — T-BT R2/R3 + T-SIG W3 신규 트랙 둘 다 spec/plan 발행 의무 충족 (hotfix·결함 정정·작은 묶음 면제 조건 미해당)
- `feedback_conflict_resolution_autonomy.md` — modify-shared 충돌 0건 + sub-agent fail 분기 재디스패치 0건 정합
- `feedback_dev_priority_qa_batch.md` — 본 사이클 hotfix 0건 + 단일 사이클 8 PR 본격 진행 (개발 진도 우선 정합)
- `feedback_verification_strict.md` — PR #336·#337·#338 모두 `:domain:check`·`:consumer:check`·`:api:check`·`pnpm test/build` GREEN 인용 + 사용자 dogfooding evidence 의무 정합
