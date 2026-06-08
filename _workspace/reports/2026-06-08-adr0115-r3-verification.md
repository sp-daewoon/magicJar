# ADR 0115 R3 — incident 재현 e2e 검증 보고

- **작성**: strategy-engine
- **날짜**: 2026-06-08
- **트랙**: T-O · ADR 0115 R3 (R1·R2 머지 후 사슬 회귀 검증)
- **입력**: `docs/assessments/2026-06-08-mock-alarm-flood-rootcause.html` (MOCK 알람 폭주 근본원인 분석)
- **브랜치**: `agent/strategy/position-integrity-r3`

---

## 1. 목적

R1(순수 산정) + R2(어댑터 가드)가 **2026-06-08 실제 사고 데이터를 막는지** 6 incident 시나리오로 일괄
검증한다. R2가 이미 단위/통합 테스트를 다수 넣었으므로 **중복 금지** — gap만 신규 충당하고, 나머지는
"시나리오 → 테스트 파일·메서드" 매핑표로 커버를 고정한다.

오늘 incident 실측 데이터(회귀 고정 대상):
- strategy_position **7** vs position(KIS) **5** drift (전략 #8, 005930)
- backfill `fillSource=REST_BACKFILL` 과거 체결 재발행 → 중복 익절(4·6·5·6·7주)
- 손절가 매 tick 발사 → `IDEMPOTENCY_SKIPPED` 4,920건/30분
- ACK 정체 주문 **144**(ACK) · **176**(PARTIALLY_FILLED) — WS 체결통보 누락

---

## 2. 6 시나리오 → 테스트 매핑표 (검증 결과)

| # | 시나리오 | 가드 (ADR 0115) | 검증 테스트 (파일 · 메서드) | 신규/기존 | 결과 |
|---|----------|-----------------|-----------------------------|-----------|------|
| **1** | **drift 보정**: strategy_position 7 + position(KIS) 5 → 7→5 정렬, realized_pnl 불변, 유령 매도 0 | P1-1 | `ConsumerExecutionStrategyPositionDriftReconcileTest`<br>· `초과 drift 7 vs position 5 — strategy_position 5로 보정 UPDATE + realized_pnl 불변`<br>· `초과분 전량 보정으로 0 도달 — avgPrice·filledLots·executedExitStages reset`<br>· `부족 drift — 보정하지 않음(WarnUnderflow 경로…)`<br>· `보정 비활성(ENV off) — 초과여도 보정 upsert 0` | 기존 R2 (4건) | ✅ PASS |
| **2** | **replay 가드**: fillSource=REST_BACKFILL → TP 미발행 / WS → TP 발행 | P1-2 | `AutoExitOrderCoordinatorTest`<br>· `P1-2 replay — fillSource REST_BACKFILL BUY 체결은 TP 미발행(인덱스·구독은 유지)`<br>· `P1-2 replay — executedAt이 신선도 임계 초과 과거(WS source여도) TP 미발행`<br>· `P1-2 — 신선한 WS 체결은 정상 TP 발행(가드 false negative 0)` | 기존 R2 (3건) | ✅ PASS |
| **3** | **watcher 쿨다운**: 손절가 연속 tick → auto-sl intent 1회만 | P2-1 | `PositionStopLossTickWatcherTest`<br>· `P2-1 cooldown — 같은 포지션 상태 연속 tick은 1회만 intent (재발행 0)`<br>· `P2-1 cooldown — 손절가 미도달 복귀 시 cooldown 해제(다음 도달 즉시 발화)`<br>· `P2-1 cooldown — 포지션 상태(updatedAtEpoch) 변화 시 즉시 재발화` | 기존 R2 (3건) | ✅ PASS |
| **4** | **유령 가드**: PAUSED + position 0 → 손절 미발사 / PAUSED + 실보유 → 발사 유지 | P2-3 | `PositionStopLossTickWatcherTest`<br>· `P2-3 — position(KIS 권위) 잔량 0(유령) 이면 손절 미발사`<br>· `P2-3 — position 권위 잔량 보유면 손절 발사 유지(PAUSED 전략도 실보유 보호)` | 기존 R2 (2건) | ✅ PASS |
| **5** | **거부 자동정지**: 같은 종목 SELL 거부 N회(10) → 자동 PAUSE + 알림 1회(디바운스) | P2-2 | `SellRejectAutoPauseTrackerTest`<br>· `임계 도달 — autoDisable 1회 + 알림 1회`<br>· `임계 미달 — autoDisable 0` / `BUY 거부는 집계 0` / `MANUAL sentinel(strategyId 0) 집계 0`<br>· `성공 체결 reset — 누적 끊김` / `rate limiter 디바운스 — 윈도 차단이면 알림 emit 0` | 기존 R2 (6건) | ✅ PASS |
| **6** | **ACK 재조회**: ACK 정체 주문 → worker가 KIS 당일체결 대조 → FILLED 정정 | P2-4 | **`StuckAckExecutionResyncWorkerTest` (신규)**<br>· `incident 주문 144·176 — ACK·PARTIALLY_FILLED 정체 발견 시 syncUserTodayExecutions 발사`<br>· `동일 (userId, mode) 묶음당 1회만 sync — 중복 KIS 호출 회피`<br>· `서로 다른 (userId, mode) — 각각 1회 sync`<br>· `정체 0건 — sync 0 (early return)`<br>· `ENV off — worker no-op`<br>· `backfill 서비스 미합류 — graceful skip` | **신규 R3 (6건)** | ✅ PASS |

**합계**: 24건(기존 R2 18건 + 신규 R3 6건). 6 시나리오 전부 GREEN.

---

## 3. 오늘 incident 데이터와의 대조

| incident 실측 | 검증 사슬 | 막혔는가 |
|---------------|-----------|----------|
| strategy_position 7 vs position 5 | 시나리오 1 — `reconcileDrift`가 7→5 UPDATE, realized 30000 불변 | ✅ 보정됨 |
| backfill REST_BACKFILL 재발행 → 중복 익절 4·6·5·6·7 | 시나리오 2 — replay면 TP 발행 skip | ✅ 중복 익절 0 |
| 손절 매 tick → IDEMPOTENCY_SKIPPED 4,920/30분 | 시나리오 3 — 같은 상태 cooldown 내 1회만 | ✅ intent 폭주 차단 |
| 유령 7주 손절 발사 (PAUSED여도) | 시나리오 4 — position==0이면 미발사 | ✅ 유령 발사 0 |
| 반복 SELL 거부 + 매번 알림 | 시나리오 5 — N회 자동정지 + 알림 1회 | ✅ 정지·디바운스 |
| ACK 정체 주문 144·176 | 시나리오 6 — timeout 정체 발견 → KIS 재조회 발사 | ✅ FILLED 정정 트리거 |

incident 인과 사슬(분석 §1)의 [전환]·[누적]·[폭주]·[증폭] 각 단계가 대응 가드로 끊김을 테스트로 고정했다.

---

## 4. 신규 충당 근거 (중복 금지 준수)

- R2가 P1-1·P1-2·P2-1·P2-2·P2-3에 대해 단위/통합 테스트를 이미 배치 → R3는 **재작성하지 않고 매핑만**.
- 유일한 zero-coverage 컴포넌트는 **`StuckAckExecutionResyncWorker`(P2-4, R2 신설)** — 테스트 0건이었음.
  R3가 이 단일 gap을 `StuckAckExecutionResyncWorkerTest`(6건)로 충당. incident 주문 144·176을 고정 데이터로 사용.
- ScenarioReplayEngine(ADR 0107)은 **캔들 리플레이 백테스트 엔진**이라 order-chain incident(체결·드리프트·ACK)
  리플레이에는 부적합 → 컴포넌트 슬라이스 테스트(mockk)로 검증(R2 동일 패턴 유지).

---

## 5. 미해결 / backlog 이관

| 항목 | 상태 | 이관 |
|------|------|------|
| 인메모리 cooldown(P2-1)·reject counter(P2-2) **다중 consumer 인스턴스 비대응** | 현 단일 배포 가정으로 OK. 확장 시 Redis SETEX 승격 필요 | ADR 0115 §4 잔여 risk · P2-3 backlog (Redis 승격) |
| 부족 방향 drift(strategySum < position) 미보정 | 설계 의도(없는 물량 인식 회피) — backfill 경로 의존 | ADR 0115 §P1-1 LOCK (정상) |
| StuckAck worker의 `syncUserTodayExecutions` 실제 KIS 대조 결과(FILLED 전이) e2e | 본 R3는 sync **트리거**까지 검증(KIS 대조 로직은 `KisExecutionBackfillService` 기존 테스트 책임) | 기존 backfill 테스트 범위 |

---

## 6. 검증 명령 결과

- `./gradlew :consumer:test --tests "…StuckAckExecutionResyncWorkerTest"` → **BUILD SUCCESSFUL** (6/6 PASS)
- `./gradlew check` + bootJar 3종(api·batch·consumer) + ktlint → GREEN (PR 본문·반환 메시지에 인용)
- 운영 코드(`*.kt` main) **0 touch** — 테스트 신규 1파일 + CHANGELOG + 검증 보고 + task 파일만.
