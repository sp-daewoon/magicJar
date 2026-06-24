# spec — autopilot 일일 universe 자동 재생성 (Daily Universe Regeneration)

- 작성: 2026-06-24, architect
- 상태: **제안 (사용자=최고 관리자 자동화 결재 — 2026-06-24 단절 지적)**
- 동반 ADR: **ADR 0131** (본 spec 과 1:1) — 매핑정책·보유종목 처리·게이트·롤백·owner 격리 결정 본문
- 관련: ADR 0117(발굴 레이어)·0124(WS owner)·0129(autopilot 하드닝)·0130(계획 진입+lot 소유권)·0104(거래일 게이트)
- 데이터 계약 LOCK 의무: 본 §3·§4 계약은 R1~R2 sub-agent 가 그대로 따른다. 임의 변경 금지.

---

## §1. 방향성 가드 분석 (`.claude/skills/magicjar-direction-guard`)

```text
기존 방향:
  - autopilot 6 전략(owner=AUTOPILOT, 100~105)이 고정 16 대형주 universe(strategy.codes)를 매일 그대로 평가.
  - ActiveStrategyCodeProvider.activeCodesByStrategy()가 codes를 read → 시세 구독(STRATEGY owner, ADR 0124) + BUY 평가.
  - 청산은 strategy_position lot ledger(ADR 0110·0130) 기준 — codes와 직교.
  - 발굴 레이어(DiscoveryScorecardSnapshotService, ADR 0117·0121)는 매 영업일 08:30(DiscoveryScorecardScheduler)
    활성 빌트인 DAYTRADE_* 6 프리셋을 발굴해 discovery_scorecard에 TOP N INSERT(KIS 0·candle DB·AI 0).
  - 두 시스템이 단절 — 발굴이 매일 새 후보를 내지만 autopilot codes는 박제.

변경 의도: "발굴 TOP N → autopilot universe 자동 동기화" — 매 영업일 08:35 deduped 가중 TOP N으로
  owner=AUTOPILOT 평가 전략의 codes를 갱신. 보유 lot은 hold-until-exit(plan·안전망 청산).

방향성 영향: 유지 (보수적 확장 — 단절 연결). 신규 진입 모델 아님, codes write 경로만 자동화.
  ADR 0130 lot 소유권이 전략 교차를 이미 차단하므로 동일 universe 안전.
확인할 리스크: 자본흐름(매수 종목 자체 변경 = 최고위험 → 게이트 OFF·fail-safe·hold-until-exit) ·
  데이터(snapshot 0건 시 빈 universe 사고 → skip) · API(codes drift consumer 매핑 — ADR 0128 교훈) ·
  운영(WS slot N≤40 ADR 0124 예산 · 거래일 게이트 ADR 0104) · owner 격리(USER/SYSTEM byte-동일).
```

### 1-1. 충돌인가 확장인가 → **확장 (충돌 아님)**

발굴 레이어(read-only 후보 산출)와 autopilot codes(매수 universe)는 이미 존재하는 두 자원이다. 본 spec 은 **둘을 잇는 codes write 경로**만 추가한다. 신규 진입 모델·신규 테이블·신규 KIS 흡수 0. ADR 0130 의 lot 소유권 모델이 전략 간 자본 교차를 차단하므로 "전 전략 동일 universe" 가 안전하다. → 보수적 확장.

### 1-2. owner 분리 + 자본 흐름 가드

- **owner=AUTOPILOT 격리**: refresh 는 `owner='AUTOPILOT' AND status IN ('ACTIVE','BUY_BLOCKED')` SQL where 로 한정. USER/SYSTEM codes touch 0.
- **보유 lot hold-until-exit**: codes 갱신은 BUY 평가 대상만 바꾼다. universe 밖 보유분은 강제 청산 0 — ADR 0130 plan(L2)·안전망(L3)이 계획대로 청산. 매일 장전 비계획 청산·강제 손절 사고 차단.
- **빈 universe 사고 차단**: snapshot 0건·빈 TOP N → codes 갱신 skip(기존 universe 유지) + WARN.

---

## §2. 자원 audit (ADR 0102 정합)

**Flyway 최신 = V95**(`V95__strategy_position_trade_plan.sql`). 본 spec 은 **스키마 변경 0**(codes 기존 컬럼 UPDATE) → V96 미발행 권장.

| 자원 | 신규 점유 | main 충돌 | 결론 |
|------|-----------|-----------|------|
| Flyway | 0 (codes 기존 컬럼 UPDATE) | 최신 V95 | 충돌 0 |
| @Scheduled cron | `0 35 8 * * *` (08:35 KST) | snapshot 08:30·warmup 09:01·EOD 15:15 비충돌 | 충돌 0 |
| ENV | `magicjar.autopilot.universe-refresh.{enabled,cron,size}` add-only | autopilot.* 네임스페이스 미점유 | 충돌 0 |
| REST/Kafka/STOMP | 0 (내부 스케줄러) | — | 신규 0 |
| 신규 클래스 | `AutopilotUniverseRefreshWorker`(api/scheduler) · `AutopilotUniverseRefreshService`(api/service/autopilot) | 동명 미존재 | 충돌 0 |

---

## §3. 데이터 계약 LOCK (R1~R2 그대로 따름)

### 3-1. UniverseRefreshService 인터페이스 (api/service/autopilot)

```kotlin
@Service
class AutopilotUniverseRefreshService(
    private val scorecardRepository: DiscoveryScorecardJpaRepository,   // 재사용
    private val strategyJpaRepository: StrategyJpaRepository,            // 재사용 (owner 조회 + codes UPDATE)
    private val strategyPositionReadRepository: ...,                     // 보유 lot 잔존 집계 (재사용)
    private val systemEventPublisher: ...,                              // 가시화 (재사용)
    @Value("\${magicjar.autopilot.universe-refresh.enabled:false}") private val enabled: Boolean,
    @Value("\${magicjar.autopilot.universe-refresh.size:16}") private val size: Int,
) {
    data class RefreshResult(
        val status: String,        // DISABLED | NO_SNAPSHOT | EMPTY_TOPN | UPDATED
        val universeSize: Int,     // 적용된 종목 수
        val strategiesUpdated: Int,
        val droppedCodes: Int,     // universe에서 빠진 종목 수
        val heldLotsRemaining: Int,// 빠졌으나 보유 lot 잔존 종목 수
    )

    /** snapshotDate(=today) 의 scorecard로 deduped TOP N 합성 → owner=AUTOPILOT codes UPDATE. */
    fun refresh(snapshotDate: LocalDate): RefreshResult
}
```

**합성 산식 LOCK (ADR 0131 §6-1)**:
1. `scorecardRepository.findRowsInRange(snapshotDate, snapshotDate, presetKey=null)` — 오늘 전 프리셋 row.
2. `groupBy { instrumentCode }.mapValues { it.maxOf { row -> row.score } }` — code별 max score dedup.
3. `sortedWith(compareByDescending { score }.thenBy { instrumentCode })` — 결정성.
4. `take(size.coerceIn(1, 40))` — default 16, 상한 40(WS slot 예산 ADR 0124).
5. 결과 codes 빈 결과 → `status=EMPTY_TOPN` codes 갱신 skip(fail-safe).

**게이트·fail-safe**:
- `enabled=false` → `status=DISABLED` no-op.
- snapshot row 0건 → `status=NO_SNAPSHOT` codes 갱신 skip + WARN.
- 빈 TOP N → `status=EMPTY_TOPN` skip + WARN.
- 정상 → owner=AUTOPILOT 평가 전략 전체 단일 트랜잭션 codes UPDATE → `status=UPDATED`.

### 3-2. codes UPDATE 계약 (StrategyJpaRepository — 헌법 §6 abstract fun)

```kotlin
/** owner=AUTOPILOT 평가 전략(ACTIVE·BUY_BLOCKED)의 codes 일괄 갱신. 헌법 §6: abstract fun(@Modifying @Query). */
@Modifying
@Query(
    "UPDATE StrategyJpaEntity s SET s.codes = :codesJson, s.updatedAt = CURRENT_TIMESTAMP " +
        "WHERE s.owner = 'AUTOPILOT' AND s.status IN ('ACTIVE','BUY_BLOCKED')",
)
fun updateAutopilotUniverse(@Param("codesJson") codesJson: String): Int
```
- `codesJson` = `ObjectMapper.writeValueAsString(List<String>)` (기존 `StrategyJpaAdapter` codes 직렬화 패턴 1:1 — `["005930","000660",...]`).
- USER/SYSTEM 미포함 SQL where → 격리 LOCK.
- 보유 lot 잔존 집계: refresh 전 `findActiveByCodeAndInterval` 또는 owner codes read 로 직전 universe 확보 → `(직전 ∖ 신규)` 종목의 strategy_position quantity>0 카운트.

### 3-3. Worker 계약 (api/scheduler — DiscoveryScorecardScheduler 패턴 1:1)

```kotlin
@Component
class AutopilotUniverseRefreshWorker(
    private val refreshService: AutopilotUniverseRefreshService,
    private val marketCalendarJpaRepository: MarketCalendarJpaRepository,  // 거래일 게이트 ADR 0104
) {
    private val lastRefreshedDate = AtomicReference<LocalDate?>(null)  // KST 거래일 1회 멱등

    @Scheduled(cron = "\${magicjar.autopilot.universe-refresh.cron:0 35 8 * * *}", zone = "Asia/Seoul")
    fun runRefresh() {
        val today = LocalDate.now(KST)
        if (!isBusinessDay(today)) return                 // 주말/휴장 skip (DiscoveryScorecardScheduler.isBusinessDay 1:1)
        if (lastRefreshedDate.get() == today) return       // 당일 1회 멱등
        val result = refreshService.refresh(today)
        if (result.status == "UPDATED") lastRefreshedDate.set(today)
        // SystemEvent(INFO) — universe 회전·held-lots 가시화 (ADR 0131 §6-3)
    }

    private fun isBusinessDay(date: LocalDate): Boolean { /* market_calendar row 우선 + 주말 fallback */ }
}
```

---

## §4. 라운드 계획 + 영역 매핑

| 순번 | 라운드 | 담당 sub-agent | 범위 | 의존 | 병렬 |
|---|---|---|---|---|---|
| **1** | **R1** | strategy-engine | `AutopilotUniverseRefreshService`(합성·codes UPDATE·보유 lot 집계) + `AutopilotUniverseRefreshWorker`(08:35 cron·거래일 게이트·1일 1회 멱등) + ENV 3건 + StrategyJpaRepository.updateAutopilotUniverse @Query | 없음 — scorecard·strategy codes 기존 자원 read/write only | R1 단독 (api 모듈 add-only) |
| **2** | **R2** | strategy-engine + qa | 테스트(dedup 산식·owner 격리·fail-safe 3종·멱등·hold-until-exit) + MOCK 라이브 검증(consumer ActiveStrategyCodeProvider codes read + 09:01 warmup 연계 + universe 밖 보유 lot 청산 안 됨) | R1 후 | R2 내부: 테스트(strategy) → 라이브 검증(qa) |
| **3(선택)** | **R-aux** | frontend | autopilot status 화면 "오늘 universe + 회전 종목 + held-lots" 노출 | R1 후 | 후속 — 관측 가시화 |

**의존 그래프**: `R1` → `R2(테스트 ∥ 라이브)` → `(선택)R-aux`.

- **R1 의존 0 검증**: scorecard 는 08:30 snapshot 이 이미 영속(read), strategy.codes 는 기존 컬럼(write), 거래일 게이트는 market_calendar(기존). 신규 외부 의존 0.
- **자본 안전 검증 의무(R2)**: ① owner=AUTOPILOT 만 codes 변경 + USER/SYSTEM 무변경 ② universe 밖 보유 lot 강제 청산 0(hold-until-exit) ③ snapshot 0건 시 codes 갱신 skip ④ consumer 가 갱신 codes 실제 read.

---

## §5. ADR 필요 판단 → **ADR 0131 발행 완료**

본 변경은 (1) 매핑 정책 결정(옵션 A/B/C 트레이드오프) (2) 보유 lot 처리 정책(자본 안전 — hold-until-exit) (3) 최고위험 자본 경로(매수 universe 자동 교체)의 게이트·롤백 (4) owner 격리 — **결정 근거 누적 가치 충분**. ADR 0117(발굴) amend 로는 부족(발굴은 read-only 후보 산출, 본 결정은 codes write·자본 경로). → **ADR 0131 신설**(본 spec 과 1:1).

---

## §6. CHANGELOG 슬롯 (ADR 0050 정합)

R1·R2 슬롯 row + anchor 를 CHANGELOG.md 상단에 사전 prepend(본 spec PR diff). 구현 PR 이 anchor 를 채운다.

---

## §7. 사용자 결정 필요 (없음 — architect 자율 LOCK)

- 매핑 정책 = 옵션 B(deduped 가중 TOP N) · 보유 lot = hold-until-exit · 게이트 default OFF · N default 16 — 모두 ADR 0131 본문 결정·근거 기반 architect 자율 LOCK.
- 게이트 on 시점(검증 후 자동 가동 결재)만 사용자/orchestrator 결재 — 배포·canary 단계 의제.
