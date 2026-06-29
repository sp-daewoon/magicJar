# ADR 0136 — KIS 실잔고 ↔ strategy_position self-heal (체결→ledger 정합 누락 근본fix + 자동 정합 워커)

- **상태**: Accepted (오케스트라 위임 결재 2026-06-29 — 자본 신뢰성 P0 2건)
- **날짜**: 2026-06-29
- **작성**: backend-core
- **관련**: ADR 0084(WS_PREINSERT race·event_id 멱등) · ADR 0110(strategy_position lot ledger) · ADR 0115(strategy_position drift 보정·rebuild 대안 B) · ADR 0114(HOLDING·PENDING_BUY evict 금지) · ADR 0070(reconcile cron·분산락) · `feedback_kis_ground_truth_crosscheck`(브로커=진실원 과대조) · `feedback_verification_strict`(단위테스트≠라이브)
- **롤백 앵커**: v3.14.x
- **범위 게이트**: 신규 워커 1개(consumer) + 어댑터 rebuild authority 주입 오버로드 1개 + 라우터 merge replay 보강. **평가·주문 로직 변경 0** — 정합(DB 귀속 정렬) 경로만. 신규 Flyway·Kafka 토픽·STOMP 0(기존 reconcile 락·KIS 포트·rebuild 엔진 재사용).
- **자본 경로**: 고위험 인접(strategy_position = 익절·손절·POSITION_LIMIT 판정 ledger). 단, 정정은 **KIS 실측 일치 동기화만**(임의 wipe 금지)·HOLDING·PENDING_BUY 보호·기본 게이트 off.

---

## 1. 맥락 (왜 지금 — 2026-06-29 라이브 P0)

오늘 실거래에서 `strategy_position`(전략 lot ledger)이 KIS 실잔고와 전면 drift 나 익절·매수가 막혔다.

### 1-1. 결함1 — 체결→ledger 정합 누락 (실측 단서, 095500 미래나노텍)

| 시각 | 이벤트 | position 어댑터 | strategy_position 어댑터 |
|------|--------|----------------|--------------------------|
| 09:20 | BUY 체결 | ✅ 반영 | ✅ upsert 로그 |
| 09:40 | BUY 체결(signalId=16298·strategyId=103·26주) | ✅ 반영 | ❌ **upsert 로그 없음 = 누락** |

결과: KIS 26주 보유인데 ledger 0 → 익절 신호가 `SELL_WITHOUT_POSITION` 으로 차단(못 팜). 동시에 다른 종목은 역방향 유령(ledger 보유·KIS 0)으로 `POSITION_LIMIT` 점유 → 신규 BUY 차단.

#### 근본 원인 (정밀 분석 — 왜 09:40 만 누락)

두 어댑터([ConsumerExecutionPositionUpdateAdapter] vs [ConsumerExecutionStrategyPositionAdapter])는 **같은 체결 이벤트**를 [ExecutionPersistenceService.saveAndApplyPosition] 의 같은 트랜잭션에서 순차 호출한다. 그런데:

- `position` 어댑터는 **user+code** 키로 갱신 → `strategyId` 무관, 항상 반영.
- `strategy_position` 어댑터는 **첫 줄에서 `if (request.strategyId == 0L) return`** (MANUAL/EXTERNAL sentinel skip). 이 early-return 은 upsert **로그 자체를 남기지 않는다** → "로그 없음" 증상과 정확히 일치.

09:40 은 **재진입(이미 보유한 095500 을 같은 전략 103 이 재매수)** 이고 ADR 0084 P0-3 WS_PREINSERT race 가 겹쳤다:

1. 재진입 BUY 제출 → KIS ACK 의 새 ODNO 가 canonical order(strategyId=103) 에 기록되기 **전에** WS 체결 통보가 먼저 도달(race).
2. `KisExecutionWsListener` 가 ODNO 로 canonical 을 못 찾음(150ms retry 후도 null) → **WS_PREINSERT stub(strategyId=0·source=EXTERNAL)** 생성, 체결을 stub 에 귀속.
3. `saveAndApplyPosition`: position 어댑터는 반영 ✅ / strategy_position 어댑터는 **stub 의 strategyId=0 으로 early-return** ❌ (로그 0).
4. 이후 ACK 도착 → `updateKisAck` → `mergeWsPreinsertIfPresent` 가 execution 을 canonical(103·AUTO)로 reassign + `replayStrategyPositionAfterWsPreinsertMerge` 호출.
5. **그러나 [StrategyPositionExecutionReplayService.replayIfMissing] 가 "strategy_position 행 기존재 → skip"** ("under-count 명세 고정" — over-count 회피 의도). 재진입이라 직전 lot 의 행이 이미 존재 → **26주 execution 영구 드롭**.
6. 5분 주기 self-heal([StrategyPositionReconcileWorker] → [rebuildStrategyPositionFromLedger])도 회복 못 함: rebuild 의 교차검산이 **내부 position 투영**(`position.quantity`)을 권위로 쓰는데, 그 투영 자체가 정상이어도 — race 변종(merge 미완·updateKisAck 실패)에서는 execution 이 stub(strategyId=0·source=EXTERNAL)에 남아 rebuild 의 `WHERE strategyId<>0 AND source=AUTO` 에서 **제외** → `rebuildTotal(0) ≠ authority(26)` → 영구 SKIP.

**한 줄 요약**: 재진입 + WS_PREINSERT race 에서 체결이 sentinel stub 에 stranded 되고, 자동 회복 경로(replay·rebuild) 둘 다 그 케이스를 구조적으로 드롭한다.

### 1-2. 결함2 — 자동 정합 감지·수정 부재 (최고 관리자 지시)

> "autopilot 이 이런 drift 를 스스로 찾아 고쳐야 한다. 매번 사람이 지적해야 발견되면 안 된다."

현재 drift 를 **사람이 발견**했다. KIS 실잔고를 권위로 한 주기 크로스체크·자동 정정이 없었다(기존 self-heal 은 내부 position 투영을 권위로 쓰므로 broker 진실원 대조가 아니다 — MEMORY `kis_ground_truth_crosscheck`).

## 2. 결정

### 2-1. 결함1 — merge 직후 rebuild 동반 (재진입 stranded 흡수)

`replayStrategyPositionAfterWsPreinsertMerge` 에 **rebuild 동반 호출**을 추가한다(replay 와 belt-and-suspenders). merge 직후 order_execution(진실원)은 canonical(strategyId≠0·AUTO)에 재귀속됐으므로, **event-멱등·KIS 교차검산 [rebuildStrategyPositionFromLedger]** 가 replay 가 드롭한 재진입 execution 을 흡수한다. 교차검산 불일치면 미적용(없는 물량 인식 risk 0). graceful(부재·실패 시 ACK·replay 무손상).

`replayIfMissing` 의 "행 기존재 skip" 는 **유지**한다(over-count 회피 본질 정당) — rebuild 가 그 빈틈(재진입)을 덮는 2층 구조.

### 2-2. 결함2 — KIS 실잔고 self-heal 워커 ([KisLedgerSelfHealWorker])

장중 주기(default 10분 cron) **KIS inquire-balance(9443·TTTC8434R) 실보유 ↔ strategy_position 합** 크로스체크 워커를 신설한다.

- KIS 실잔고 흡수: [ReconcileKisBalancePort.fetchAllBalances](BalanceReconcileService 와 동일 포트 — DRY).
- 순회 범위 union: KIS 보유(qty>0) ∪ strategy_position 잔존(qty>0) — under/over 양방향.
- drift(`|kisQty − ledgerSum| ≥ 임계`) 종목 → **KIS 실보유를 권위로 주입**한 rebuild([rebuildStrategyPositionFromKisAuthority]) 로 자동 정정.
- 적용 성공 → WARN `KIS_LEDGER_SELF_HEAL_CORRECTED` + in-memory 손절 인덱스 refresh.
- 적용 불가(rebuild 교차검산 불일치 — 외부 주문 혼재·진실원 누락) → **CRITICAL `KIS_LEDGER_SELF_HEAL_UNRESOLVED` P0 알림**(임의 정정 보류, 사람 판단).

### 2-3. 어댑터 — authority 주입 rebuild 오버로드 (DRY)

[rebuildStrategyPositionFromLedger] 를 `rebuildStrategyPositionWithAuthority(authority)` private 공통 + 두 진입점으로 분해:
- `rebuildStrategyPositionFromLedger` — 기존(내부 position 투영을 권위로). 시그니처·거동 byte-동일(zero-regression).
- `rebuildStrategyPositionFromKisAuthority(kisAuthorityQuantity)` — **KIS 실잔고를 권위로 주입**(신규, self-heal 워커 전용). fold·교차검산·멱등·미적용 분기 100% 공유.

## 3. 안전 (자본 경로 LOCK)

| 항목 | 보장 |
|------|------|
| 정정 방향 | **KIS 실측 일치 동기화만** — rebuild 가 `totalQuantity == kisQty` 일 때만 upsert. 불일치면 미적용 + P0(임의 wipe 0). |
| HOLDING·PENDING_BUY | rebuild 는 order_execution 진실원 재계산 — 보유분 임의 삭제 불가(KIS 실보유 일치 시만 적용). ADR 0114 evict-금지 자원과 충돌 0. |
| KIS 조회 실패 | graceful skip(그 user 만 — 다음 주기 회복). 워커·정합 본체 무손상. |
| 평가/주문 로직 | 변경 0 — 정합(DB 귀속 정렬) 경로만. KillSwitch·실주문 게이트 무영향. |
| 종목 격리 | 종목별 try-catch — 한 종목 실패가 나머지 정합 차단 0. |
| 동시 실행 | reconcile 분산락([ReconcileLockAdapter]) 공유 — 다중 인스턴스·reconcile cron 과 동시 실행 방지. |

## 4. ENV 게이트 (default 보수적)

| 키 | default | 의미 |
|----|---------|------|
| `magicjar.reconcile.kis-ledger-self-heal.enabled` | **false** | 명시 opt-in. 라이브 검증 후 활성. |
| `magicjar.reconcile.kis-ledger-self-heal.cron` | `0 [매10분] 9-15 * * MON-FRI` (KST) | 장중 10분 — reconcile cron(5분)과 다른 위상으로 KIS 호출 분산. |
| `magicjar.reconcile.kis-ledger-self-heal.drift-threshold` | `1` | abs(diff) ≥ 임계만 정정 시도. |

기존 토글 `magicjar.position.ledger-rebuild.enabled`(default true)가 off 면 KIS authority rebuild 도 no-op(현행 동작 회귀).

## 5. 결과·검증

- 단위 테스트: [KisLedgerSelfHealWorkerTest](재진입 stranded 정정·유령 over-count·교차검산 불일치 P0·KIS 실패 graceful·게이트·lock) + [OrderRouterKafkaListenerTest](merge 후 rebuild 동반·replay 0이어도 rebuild 흡수) + 기존 [LedgerInvariantScenarioTest]·[ConsumerExecutionStrategyPositionAdapterTest]·[StrategyPositionReconcileWorkerTest] GREEN(zero-regression).
- 배포 후 검증법: §6.

## 6. 배포 후 검증법 (라이브)

1. ENV `MAGICJAR_RECONCILE_KIS_LEDGER_SELF_HEAL_ENABLED=true` 설정 후 consumer 재배포.
2. 장중 10분 cron fire 로그: `KisLedgerSelfHealWorker — KIS 실잔고↔ledger self-heal cron fires`.
3. drift 의도 재현(또는 자연 발생) 시 `KisLedgerSelfHealWorker — DRIFT 감지: ... kisQty=26 ledgerSum=0` → `SELF-HEAL 정정 적용(KIS 권위 일치)`.
4. DB 대조: `SELECT sum(quantity) FROM strategy_position WHERE user_id=? AND code='095500'` == KIS inquire-balance 보유 수량.
5. 익절 경로 회복: 이후 익절 신호가 `SELL_WITHOUT_POSITION` 차단 없이 통과(체결).
6. P0 케이스: rebuild 미적용 시 `KIS_LEDGER_SELF_HEAL_UNRESOLVED` CRITICAL SystemEvent → Slack/UI 알림 도달.
