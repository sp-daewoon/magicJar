# 구현 plan — autopilot 매매행위 하드닝 (에이전트별 task)

- **ADR**: [0129](../arch/adr/0129-autopilot-trading-behavior-hardening.md)
- **spec**: [2026-06-23-autopilot-trading-hardening.md](./2026-06-23-autopilot-trading-hardening.md)
- **배포**: orchestrator가 **장마감 후** (자본경로 critical · charter §12 + 사용자 결재)
- **롤백 앵커**: **v3.14.0** (autopilot baseline · `project_autopilot_initiative.md`)

---

## Task T1 — strategy-engine (레버 1·2·3·4)

> autopilot BUY 가드 체인 + EOD 청산 + 도메인 필드. 자본경로 — owner=AUTOPILOT 격리 LOCK.

### 파일
- `api/src/main/resources/db/migration/V94__strategy_allow_overnight.sql` — 신규 컬럼 + autopilot min_hold_bars 시드 UPDATE (spec §2)
- `domain/.../strategy/StrategyDefinition.kt` — `allowOvernight: Boolean = true` add-only
- `consumer/.../persistence/ConsumerStrategyJpaEntity.kt` + `ConsumerStrategyLookupJpaAdapter.kt` — 컬럼 매핑 (ADR 0128 drift 교훈 — 매핑 누락 0 LOCK)
- `consumer/.../persistence/StrategyPositionJpaRepository.kt` — `findAutopilotHeldPositionsNoOvernight(userId)` 쿼리(`s.owner='AUTOPILOT' AND s.allow_overnight=false`)
- `consumer/.../autopilot/AutopilotLiquidationService.kt` — `liquidateNoOvernightAutopilot(userId, reason)` (liquidateAll 패턴 재사용)
- `consumer/.../autopilot/AutopilotEodLiquidationWorker.kt` — 신규 @Scheduled (EntryTimeoutCancelWorker 패턴 · cutoff 15:15 KST · 거래일 게이트 ADR 0104 · KST 날짜 1회 멱등)
- `consumer/.../autopilot/AutopilotReentryCooldownGuard.kt` — 가격 축 확장(`recordLiquidation`에 exitPrice·`evaluate`에 currentPrice · 값 포맷 ts→ts:price)
- `consumer/.../persistence/ConsumerExecutionStrategyPositionAdapter.kt` — recordLiquidation 호출부에 청산 평단 전달
- `consumer/.../risk/SignalGeneratedKafkaListener.kt` (`applyAutopilotBuyGuard`) — open-blackout 게이트 + late-entry 게이트 삽입(owner=AUTOPILOT 확정 후) + cooldownGuard.evaluate에 priceAtSignal 전달
- `domain/.../risk/RiskRule.kt` — `AUTOPILOT_OPEN_BLACKOUT`·`AUTOPILOT_LATE_ENTRY_BLOCKED`·(선택)`AUTOPILOT_REENTRY_PRICE_GUARD` 추가
- `consumer/.../autopilot/MagicjarAutopilotProperties.kt` — `reentryPriceGuardPct: BigDecimal = 1.0`
- yml: `magicjar.autotrading.eod-liquidation.*`·`open-blackout.*` properties (+ @ConfigurationProperties holder · 또는 기존 autotrading properties 확장)

### 테스트 (R3 일괄)
- open-blackout: 09:00~09:15 owner=AUTOPILOT BUY Block · 09:16 Proceed · USER BUY 무영향 · SELL 무영향
- late-entry: allow_overnight=false + 14:31 BUY Block · allow_overnight=true 무영향 · USER 무영향
- EOD 청산: allow_overnight=false AUTOPILOT lot만 SELL intent · allow_overnight=true·USER lot 미청산 · 멱등(중복 발사 0) · 휴장일 미발사
- 가격 축: 직전 청산가 11,070 → 12,340(+11%) BUY Suppress(pct=1.0) · 11,000(더 쌈) Proceed · Redis 결함 fail-open · pct=0 비활성
- min_hold_bars: 기구현 MinHoldGuard 회귀 0 (DSL SELL skip·STOP/TP 우회 유지)

### 검증 기준
- `:domain:test :strategy-engine:test :consumer:test ktlintCheck` GREEN
- owner 격리: USER 전략이 어떤 레버에도 영향 0 (테스트 단언)
- V94 적용 후 부팅 smoke(`No property` 0 · 엔티티 매핑 GREEN — 헌법 §6)

### CHANGELOG 슬롯
`| 2026-06-23 | **feat(autopilot): 매매행위 하드닝 레버 1~4 — 오버나잇 토글+EOD청산·개장 blackout·추격 가격가드·min_hold (ADR 0129)** — TBD (작성: strategy) | ... |`

---

## Task T2 — backend-core (레버 5 · B+C)

> auto-pause 거버넌스 — 자기억제 가드 + 공유풀 POSITION_LIMIT 과민 회귀 차단. add-only set + owner 분기.

### 파일
- `consumer/.../risk/AutoPauseGuard.kt` — (B) `BENIGN_AUTO_PAUSE_EXCLUDED_RULES`에 `AUTOPILOT_REENTRY_COOLDOWN`·`AUTOPILOT_CODE_DEDUP` 추가 + (C) `AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES`(benign ∪ POSITION_LIMIT) 신설 + `checkAndPauseIfNeeded`에 `ownerIsAutopilot` 분기
- `consumer/.../risk/SignalGeneratedKafkaListener.kt` — checkAndPauseIfNeeded 호출부에 owner 판정 전달(이미 보유한 carriedOwner/ownerIsAutopilot 재사용 — 신규 lookup 0)
- (선택) `*RiskEventJpaRepository` countRecentBlocked excludedRules 인자가 이미 set 받으므로 변경 최소

### 테스트 (단일 PR R1~R3)
- B: AUTOPILOT_REENTRY_COOLDOWN·AUTOPILOT_CODE_DEDUP BLOCKED가 auto-pause 카운트 미기여(owner 무관)
- C: owner=AUTOPILOT + POSITION_LIMIT 10건 → auto-pause 미발동 · owner=USER + POSITION_LIMIT → 현행대로 카운트(격리)
- 회귀: DAILY_LOSS_LIMIT·TRADE_FREQUENCY 등 실위험 룰은 여전히 카운트(threshold 산식 무손상)

### 검증 기준
- `:consumer:test ktlintCheck` GREEN
- AutoPauseGuardTest 기존 케이스 회귀 0 + 신규 B·C 케이스 GREEN
- owner=USER POSITION_LIMIT auto-pause 거동 v3.14.0 동일(격리 LOCK)

### CHANGELOG 슬롯
`| 2026-06-23 | **fix(autopilot): auto-pause 거버넌스 — 자기억제 가드 benign 제외(B)+AUTOPILOT 공유풀 POSITION_LIMIT 과민 회귀 차단(C) (ADR 0129)** — TBD (작성: backend-core) | ... |`

---

## 완료(기해소) — 행위 결함 A: 좀비 포지션

080220 좀비 포지션(#507)이 풀 잠금 → POSITION_LIMIT 폭주의 1차 원인. **#1113로 이미 fix** — 본 plan 대상 아님. 레버 5(B+C)는 #1113이 닫지 못한 *공유풀 정상 full 상태의 auto-pause 과민*을 직교로 닫는다.

---

## 통합 검증 (orchestrator — 배포 전)

1. R3 전체 GREEN + 부팅 smoke (V94 매핑·`No property` 0)
2. owner 격리 매트릭스(ADR §4) 전 행 테스트 단언 확인
3. 롤백 토글 동작 확인: eod-liquidation.enabled=false / open-blackout.enabled=false / reentry-price-guard-pct=0 각각 no-op
4. 배포 = 장마감 후 (charter §12). canary dwell 후 익일 개장 09:00~09:15 blackout + 15:15 EOD 청산 라이브 실증(메모리 `feedback_verification_strict.md` — 로그·Redis·DB·주문 evidence 인용 의무)
5. 라이브 실증 evidence: open-blackout suppress 로그 · EOD 청산 SELL intent · 가격 축 suppress · auto-pause 발동 0(개장 12분 5/6 죽음 회귀 해소 확인)
