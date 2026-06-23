# spec — autopilot 매매행위 하드닝 (2026-06-23 첫 손실일 복기)

- **ADR**: [0129](../arch/adr/0129-autopilot-trading-behavior-hardening.md) (SoT — 결정·근거·롤백·owner 격리)
- **상태**: 발행 (구현 대기 — orchestrator 장마감 후 배포)
- **작성**: architect (opus 4.8 1M)
- **자본경로 critical** — 보수적 default + owner=AUTOPILOT 격리 + 롤백 토글 의무

이 spec은 ADR 0129의 5 레버를 구현 가능한 인터페이스·데이터모델·파라미터·라운드 경계로 번역한다. 결정 근거·대안·롤백은 ADR이 권위 — 본 문서는 *어떻게* 만드는가만 다룬다.

---

## §1. 자원 audit 확정 (main 점유 + 신규)

| 자원 | main 최신 | 신규 |
|------|-----------|------|
| Flyway | `V93__autopilot_paper_shadow.sql` | **`V94__strategy_allow_overnight.sql`** |
| ENV/yml | `magicjar.autopilot.*` · `magicjar.autotrading.*` | `magicjar.autotrading.eod-liquidation.*` · `magicjar.autotrading.open-blackout.*` · `magicjar.autopilot.reentry-price-guard-pct` |
| Redis | autopilot reentry cooldown 키(ts) | 값 포맷 확장(ts → `ts:price`). 신규 키 0 |
| Kafka/STOMP/REST/KIS/AI/mock | (재사용) | **0** |

기존 자원 재사용 LOCK: `AutopilotLiquidationService`·`AutopilotLiquidator`·`OrderIntentKafkaProducer.publishExitIntent`·`AutoOrderIdempotencyKeys`·`StrategyPositionJpaRepository.findAutopilotHeldPositions`·`AutopilotReentryCooldownGuard`·`MinHoldGuard`·`min_hold_bars` 컬럼·`AutoPauseGuard.BENIGN_AUTO_PAUSE_EXCLUDED_RULES`.

---

## §2. 데이터 모델 — V94 (레버 1)

```sql
-- V94__strategy_allow_overnight.sql
-- ADR 0129 레버 1 — 오버나잇 홀드 전략별 토글.
-- default true = 기존 USER/SYSTEM/AUTOPILOT 전략 현행 거동 보존(zero-regression).
-- autopilot 신규 시드만 false (단타 본질 — 갭 리스크 default 회피).
ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS allow_overnight BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN strategy.allow_overnight IS
    'ADR 0129 — 오버나잇 보유 허용 토글. false + owner=AUTOPILOT 이면 EOD 청산 워커가 장마감 전(default 15:15 KST) 시장가 청산 + 14:30 이후 신규 BUY 금지. USER/SYSTEM 전략은 owner 격리로 EOD 워커·게이트 미적용(값 무관). default true = 현행 거동 보존.';

-- 기존 AUTOPILOT 전략은 소급 변경하지 않는다(true 유지 — 사용자/orchestrator 의도적 전환 대기).
-- 단, 향후 autopilot 시드 템플릿(V89 류·자율 생성)은 INSERT 시 allow_overnight=false 명시.
-- (선택) autopilot 시드 전략 min_hold_bars 기본값 주입 — 레버 4. 시드 행이 존재하면:
--   UPDATE strategy SET min_hold_bars = 3 WHERE owner = 'AUTOPILOT' AND min_hold_bars IS NULL;
--   (보수: 사용자가 이미 설정한 값은 덮지 않음 — IS NULL 조건)
```

**rollback down SQL** (단방향 Flyway — 수동 적용):
```sql
ALTER TABLE strategy DROP COLUMN IF EXISTS allow_overnight;
-- min_hold_bars 주입 롤백: UPDATE strategy SET min_hold_bars = NULL WHERE owner='AUTOPILOT';
```

**도메인 매핑**: `StrategyDefinition`(domain)에 `allowOvernight: Boolean = true` 추가. `ConsumerStrategyJpaEntity`·`ConsumerStrategyLookupJpaAdapter` 매핑 동반(ADR 0128 `scale_in_config` 매핑 누락 drift 교훈 — 엔티티 컬럼 매핑 LOCK, 메모리 `project_session_2026_06_18_strategy_start.md`).

---

## §3. 파라미터 (yml + ENV — 전부 보수적 default + 게이트)

```yaml
magicjar:
  autotrading:
    eod-liquidation:                 # 레버 1
      enabled: true                  # ENV MAGICJAR_EOD_LIQUIDATION_ENABLED · false=워커 no-op(롤백)
      cutoff: "15:15"                # KST 시장가 청산 시각 (allow_overnight=false AND owner=AUTOPILOT)
      late-entry-cutoff: "14:30"     # KST 이후 신규 BUY 금지 (allow_overnight=false AND owner=AUTOPILOT)
    open-blackout:                   # 레버 2
      enabled: true                  # ENV MAGICJAR_OPEN_BLACKOUT_ENABLED · false=윈도우 해제(롤백)
      start: "09:00"                 # KST 개장 진입금지 시작
      end: "09:15"                   # KST 종료 (BUY만 억제·SELL/손절/익절 무영향)
  autopilot:
    reentry-price-guard-pct: 1.0     # 레버 3 · ENV MAGICJAR_AUTOPILOT_REENTRY_PRICE_GUARD_PCT
                                     # 직전 청산가 대비 +X% 이상 추격매수 차단 · 0/음수=가격축 비활성(롤백)
```

KST 시각 비교는 `java.time.LocalTime` + `ZoneId.of("Asia/Seoul")`. 모든 게이트는 owner=AUTOPILOT 한정(§5 격리).

---

## §4. 인터페이스 (레버별)

### 레버 1 — EOD 청산 워커 + late-entry 게이트

```kotlin
// 신규: StrategyPositionJpaRepository 쿼리 변형 (findAutopilotHeldPositions 의 allow_overnight=false 필터판)
//   기존 쿼리는 s.owner='AUTOPILOT' 조인. 본 변형은 추가로 s.allow_overnight = false 필터.
fun findAutopilotHeldPositionsNoOvernight(userId: String?): List<HeldPositionProjection>

// 신규: AutopilotLiquidationService 에 selective 청산 추가 (liquidateAll 패턴 재사용)
//   liquidateAll 의 findAutopilotHeldPositions → findAutopilotHeldPositionsNoOvernight 로만 교체.
fun liquidateNoOvernightAutopilot(userId: String?, reason: String): AutopilotLiquidationOutcome

// 신규: @Scheduled EOD 워커 (EntryTimeoutCancelWorker cron 패턴 선례)
//   cutoff(15:15 KST) 도달 시 liquidateNoOvernightAutopilot("EOD_NO_OVERNIGHT") 1회 발사.
//   멱등: AutoOrderIdempotencyKeys.autopilotLiquidation 가 reason+ts 차원으로 흡수 + KST 날짜 1회 가드.
@Scheduled(...)  // fixedDelay 폴링 + LocalTime cutoff 비교 OR cron("0 15 15 * * MON-FRI")
class AutopilotEodLiquidationWorker(...)
```

late-entry 게이트는 `applyAutopilotBuyGuard`에 삽입(아래 레버 2와 동일 위치 — owner=AUTOPILOT 확정 지점). `allow_overnight=false` AND now ≥ late-entry-cutoff 이면 Block(사유 `AUTOPILOT_LATE_ENTRY_BLOCKED`).

### 레버 2 — open-blackout 게이트

```kotlin
// applyAutopilotBuyGuard 내부, owner=AUTOPILOT BUY 확정 후 dedup 직전 삽입.
// now(KST) ∈ [open-blackout.start, open-blackout.end) 이면:
return AutopilotGateResult.Block(
    autopilotBlocked(allowed, signal, RiskRule.AUTOPILOT_OPEN_BLACKOUT, "open-blackout 09:00~09:15 신규 BUY 억제"),
)
// 청산(SELL)은 함수 진입부 if (signal.side != BUY) return Proceed 로 이미 제외.
```

### 레버 3 — 가격 기반 재진입 가드 (AutopilotReentryCooldownGuard 확장)

```kotlin
// recordLiquidation: Redis 값 포맷 ts → "ts:price" 확장.
fun recordLiquidation(userId: String, code: String, strategyId: Long, exitPrice: BigDecimal)
//   set(key, "${System.currentTimeMillis()}:${exitPrice}", cooldownWindow)
//   exitPrice = 청산 SELL 평단(없으면 직전 진입 평단 fallback). 호출자(ConsumerExecutionStrategyPositionAdapter)가 전달.

// evaluate: currentPrice 인자 추가 + 가격 축 판정(시간 축과 직교).
fun evaluate(userId: String, code: String, strategyId: Long, currentPrice: BigDecimal): Decision
//   1. 시간 축(기존): hasKey → Suppress (cooldown window 내). 윈도우 만료면 키 자연 소멸.
//   2. 가격 축(신규): 키가 살아있고 currentPrice > recordedExitPrice × (1 + pct/100) → Suppress.
//      (윈도우 경과 후엔 키가 없어 가격 축도 자연 무효 — 세션 내 직전 청산 기준만 적용)
//   fail-open: Redis 결함·파싱 실패·pct≤0 → Proceed.
```

호출부 `applyAutopilotBuyGuard`에서 `cooldownGuard.evaluate(..., signal.priceAtSignal)` 로 현재가 전달. Suppress 사유는 시간 축이면 `AUTOPILOT_REENTRY_COOLDOWN`(기존), 가격 축이면 신규 사유 문자열 `AUTOPILOT_REENTRY_PRICE_GUARD`(rule은 `AUTOPILOT_REENTRY_COOLDOWN` 재사용 또는 신규 rule — 아래 §6 결정).

### 레버 4 — min_hold_bars autopilot 시드 적용 (코드 0)

`MinHoldGuard`·`min_hold_bars` 기구현. V94 시드 UPDATE(§2) + autopilot 템플릿 INSERT 시 `min_hold_bars=3` 주입만. `RealtimeSignalGenerator`가 이미 `MinHoldGuard.shouldSkipDslSell`을 DSL SELL에 적용 중(기구현 LOCK — 신규 wiring 0).

### 레버 5 — AutoPauseGuard 거버넌스 (B+C)

```kotlin
// B) benign set 확장 (owner 무관 안전 — autopilot 전용 룰)
val BENIGN_AUTO_PAUSE_EXCLUDED_RULES: Set<String> = setOf(
    RiskRule.SELL_WITHOUT_POSITION,
    RiskRule.MAX_CONCURRENT_POSITIONS,    // 이미 존재
    RiskRule.SELL_QUANTITY_UNAVAILABLE,
    RiskRule.AUTOPILOT_REENTRY_COOLDOWN,  // 신규 B — 자기억제 churn 가드
    RiskRule.AUTOPILOT_CODE_DEDUP,        // 신규 B — 자기억제 dedup 가드
).map { it.name }.toSet()

// C) owner=AUTOPILOT 한정 POSITION_LIMIT 추가 제외 (USER 격리)
//   benign set 에 POSITION_LIMIT 을 전역 추가하면 USER 전략에서 실제 위험 신호를 놓침 → owner 분기 필요.
val AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES: Set<String> =
    (BENIGN_AUTO_PAUSE_EXCLUDED_RULES + setOf(
        RiskRule.POSITION_LIMIT.name,
        // MAX_CONCURRENT_POSITIONS 는 이미 benign — autopilot 공유풀 full=정상 정합
    ))

// checkAndPauseIfNeeded 가 owner 를 인지하도록 시그니처/호출 정합:
fun checkAndPauseIfNeeded(strategyId: Long, autoPauseThreshold: Int, ownerIsAutopilot: Boolean): Boolean
//   excludedRules = if (ownerIsAutopilot) AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES else BENIGN_AUTO_PAUSE_EXCLUDED_RULES
//   호출자(SignalGeneratedKafkaListener)가 이미 보유한 owner 판정(carriedOwner / ownerIsAutopilot)을 전달.
```

---

## §5. owner 격리 검증 (모든 레버 — zero-regression 의무)

- 레버 1·2·3은 `applyAutopilotBuyGuard`의 **owner=AUTOPILOT 확정 지점 이후**에만 삽입. USER/SYSTEM은 비-AUTOPILOT early-return으로 구조적 제외. EOD 워커는 `findAutopilotHeldPositionsNoOvernight`가 `s.owner='AUTOPILOT'` 조인으로 격리.
- 레버 4는 전략 단위 설정값(owner 무관) — autopilot 시드에만 default 주입.
- 레버 5C는 `ownerIsAutopilot` 분기로 USER는 현행 BENIGN set 그대로 LOCK.
- **검증 테스트 의무** (R3): 각 레버에 USER 전략이 영향받지 않음을 단언하는 테스트(헌법 §11-1 모듈 경계 합류 + owner 격리). `findAutopilotHeldPositionsNoOvernight`가 USER 보유분을 절대 반환하지 않음 단언.

---

## §6. 미결정 사항 (구현자 자율 — orchestrator/sub-agent 판단)

1. **레버 3 rule 분리 여부**: 가격 축 Suppress를 신규 `RiskRule.AUTOPILOT_REENTRY_PRICE_GUARD`로 분리할지, 기존 `AUTOPILOT_REENTRY_COOLDOWN` 재사용할지. **권장**: 관측 구분(§6-3)을 위해 신규 rule 추가(benign set에도 동반 추가 — 자기억제 가드). benign 동반 추가 시 V94 무관(코드 set).
2. **EOD 워커 스케줄 방식**: cron(`0 15 15 * * MON-FRI`) vs fixedDelay 폴링 + LocalTime cutoff 비교. **권장**: 시장 캘린더 휴장일 게이트(ADR 0104) 정합 위해 fixedDelay + cutoff 비교 + 거래일 확인(휴장일 미발사). EntryTimeoutCancelWorker 패턴 참조.
3. **min_hold_bars default 값**: 3봉(1분봉=3분) vs 5봉. **권장**: 보수적 3봉 시작(과도하면 정당 청산 지연 risk). 후속 튜닝 backlog.

---

## §7. 라운드 경계 (개발 룰 3 — development-rules.md)

| 라운드 | 범위 | 테스트 |
|--------|------|--------|
| R1 | domain `StrategyDefinition.allowOvernight` + V94 + yml/ENV properties + repository 쿼리 시그니처 + 가드 시그니처 확장 | 0 OK |
| R2 | EOD 워커 + open-blackout 게이트 + 가격 축 wiring + AutoPauseGuard B+C + 엔티티 매핑 | 0 OK |
| R3 | 단위·시나리오 테스트 일괄 (owner 격리·각 레버 발동/비발동·청산 멱등·KST 시각 경계) | 일괄 |

레버 5(B+C)는 add-only set + owner 분기라 단일 PR로 R1~R3 통합 가능(backend-core 판단).
