# ADR 0131 — autopilot 일일 universe 자동 재생성 (Daily Universe Regeneration)

- **상태**: Accepted (사용자=최고 관리자 지적 2026-06-24 — 자동화 결재)
- **날짜**: 2026-06-24
- **작성**: architect
- **관련**: ADR 0117(발굴 레이어 신설 — DaytradeDiscoverService·V75 시드) · ADR 0118/0120(일봉 universe·롤업) · ADR 0104(거래일 게이트) · ADR 0124(KIS WS 구독 owner 우선순위 — STRATEGY) · ADR 0129(autopilot 매매행위 하드닝 — EOD 청산·오버나잇 토글) · ADR 0130(계획 기반 진입 + lot 소유권 청산 — L3 안전망) · ADR 0067(AutoPauseGuard) · 메모리 `project_daytrade_discovery_layer`(scorecard 08:30 snapshot)
- **롤백 앵커**: v3.14.0 (ADR 0129/0130 와 동일 베이스라인)
- **범위 게이트**: owner=AUTOPILOT 한정 · ENV 게이트 **default OFF** · USER/SYSTEM **zero-regression**
- **자본 경로**: 최고위험 (어떤 종목이 REAL 자동매매되는가 = 매수 universe 자체를 매일 바꿈)

---

## 1. 맥락 (왜 지금)

magicjar-autopilot 6 전략(100~105)은 **고정 16 대형주 universe**를 매일 그대로 평가한다. 어제·그제·오늘이 동일 종목이다. 그런데 시스템에는 이미 **매일 오늘의 단타 후보 TOP N 을 생성하는 발굴(Discovery) 레이어**가 가동 중이다:

- `DiscoveryScorecardSnapshotService`(ADR 0117·0121) 가 매 영업일 **08:30 KST**(`DiscoveryScorecardScheduler.runSnapshot`)에 활성 빌트인 단타 프리셋(`DAYTRADE_*` 6종 — MOMENTUM_BREAKOUT·VOLUME_BREAKOUT·GAP_AND_GO·GAP_RVOL_TURNOVER·RANGE_BREAKOUT·VOLATILITY_EXPANSION)을 각각 발굴해 TOP N 후보를 `discovery_scorecard` 테이블(snapshot_date·preset_key·rank·instrument_code·score)에 INSERT 한다.
- 이 발굴은 **전일 일봉 기준 룰 평가**(KIS 호출 0·candle DB read·AI 0)로 "오늘 단타 후보"를 수치로 제시한다.

### 1-1. 단절 (핵심 결함)

**발굴 레이어와 autopilot universe 가 연결되어 있지 않다.** 발굴은 매일 새 후보를 산출하지만, autopilot 6 전략의 `strategy.codes` 는 박제된 16 종목 그대로다. 결과:

> autopilot 이 "오늘의 단타 후보"가 아니라 **고정 대형주만** 매매한다.

- 단타 alpha(갭·RVOL·거래대금·체결강도 우위 종목)는 매일 바뀌는데, autopilot 은 그 신호를 받지 못한다. 발굴이 산출한 "오늘 가장 단타 적합한 종목"이 매수 대상에 들어오지 못한다.
- 2026-06-23 첫 손실일(ADR 0129)의 증폭요인 중 하나가 "6 전략이 같은 16 종목 universe 를 공유 → 같은 이름에 몰려 추격"(ADR 0129 §line29)이었다. 고정 universe 는 후보 다양성도 떨어뜨린다.

### 1-2. 임시 조치 (오늘 수동)

orchestrator 가 오늘(2026-06-24) **수동으로** autopilot universe 를 discovery TOP16 으로 갱신했다(`strategy.codes` 직접 UPDATE). 이는 일회성 임시 조치이며, **매일 장전에 자동 반복**되어야 한다. 본 ADR 이 그 자동화의 구조·정책·안전 게이트를 확정한다.

---

## 2. 결정 — 발굴 → autopilot universe 일일 자동 동기화

매 영업일 장전, `discovery_scorecard` 의 오늘 snapshot 을 **deduped 가중 TOP N** 으로 합성해 owner=AUTOPILOT 평가 전략의 `strategy.codes` 를 갱신하는 **UniverseRefreshWorker** 를 신설한다. 보유 lot 은 건드리지 않는다(매수 대상만 바꾼다 — §2-3).

### 2-1. 매핑 정책 — **옵션 B(deduped 가중 TOP N) 채택**

세 가지 안을 비교했다(상세 §3). 채택안은 **B: 전 전략 동일 deduped TOP N(code별 max score)**:

> 오늘 snapshot 의 **모든 활성 프리셋 후보를 합집합**하고, **같은 종목은 프리셋 가로질러 max(score)** 로 dedup 한 뒤, **score 내림차순 TOP N(default 16)** 을 뽑아 **owner=AUTOPILOT 평가 전략 전부에 동일 codes 로** 적용한다.

- **N(default)** = **16** (`magicjar.autopilot.universe-refresh.size`, 1~40). 오늘 수동 조치(TOP16)·기존 고정 16 과 연속성. 상한 40 = KIS WS slot 예산(ADR 0124 — 40 slot 매매 전용) 과 정합.
- **dedup** = `instrument_code` 별 `MAX(score)` (한 종목이 여러 프리셋 TOP 에 동시 등장하면 가장 강한 신호 점수로 1회만 반영). 같은 종목 중복 점유로 universe 가 좁아지는 것을 방지.
- **프리셋 가중** = **1차는 가중치 1.0 균등**(score 자체가 프리셋별 정규화된 0~100 척도 — `DiscoveryScorecardScoringService` 산식). 프리셋별 forward-test hit rate 기반 동적 가중은 **post-MVP(§7)** 로 밀어낸다(scorecard 채점 데이터가 충분히 누적된 후). 1차는 단순·결정적 우선.
- **정렬·tie-break** = score DESC, 동점 시 instrument_code ASC(결정성).

**왜 옵션 B(전 전략 동일) 인가** — 옵션 A(전략 타입별 프리셋 매핑: 100 momentum→MOMENTUM_BREAKOUT, 104 volume→VOLUME_BREAKOUT…)는 직관적이나, ① autopilot 전략 100·102·104 가 **공통 COMPOSITE(개장 모멘텀) 진입**(ADR 0129 §line26·29)이라 1:1 타입 매핑이 깔끔히 떨어지지 않고(102 fvg·103 ob 는 프리셋 대응 종목 없음) ② 전략별 universe 분기는 codes 갱신·WS 구독·warmup 을 전략 수만큼 분기시켜 운영 복잡도가 급증하며 ③ ADR 0130 의 lot 소유권 모델("산 전략만 자기 lot 청산")이 이미 전략 간 교차를 차단하므로, **universe 가 동일해도 각 전략은 자기 DSL 로 자기 진입·자기 lot 만 관리**한다 → 동일 universe 가 안전하다. 옵션 B 가 단순·안전·연속적이다. (옵션 A 는 forward-test 로 프리셋별 alpha 가 충분히 분화된 뒤 post-MVP 재검토 — §7.)

### 2-2. 타이밍 — 08:35 KST (snapshot 직후 · 개장 전)

- `@Scheduled(cron, zone="Asia/Seoul")` default `0 35 8 * * *` (`magicjar.autopilot.universe-refresh.cron`).
- **순서 의존 LOCK**: scorecard snapshot 08:30 → universe refresh **08:35**(5분 여유 — snapshot 완료 후 같은 날짜 row read) → 개장 09:00 → 인트라데이 warmup 09:01(`ActiveStrategyIntradayWarmupRunner`)·EOD 토글 등 기존 스케줄. 08:35 는 snapshot 이 끝났고 개장 25분 전이라 WS 구독·warmup 이 새 종목으로 준비될 시간이 충분하다.
- **거래일 게이트(ADR 0104)**: `DiscoveryScorecardScheduler.isBusinessDay` 와 1:1 산식(market_calendar DB row 우선 + 주말 fallback). 주말·휴장일 skip → universe 갱신 0(전 영업일 universe 유지).
- **위치**: api 모듈(발굴 의존 그래프 — `discovery_scorecard` repo·strategy codes write 가 api 에 있음). `DiscoveryScorecardScheduler` 와 동일 모듈 배치 → cross-module 주입 0(ADR 0117 정합).

### 2-3. 갱신 방식 + **보유 lot 처리 (자본 안전 핵심)**

- **갱신 대상**: owner=AUTOPILOT 이고 status ∈ (ACTIVE, BUY_BLOCKED) 인 전략의 `strategy.codes` 컬럼만. 같은 deduped TOP N JSON 배열로 일괄 UPDATE.
- **codes 의 의미 = "BUY 평가 대상 종목"** 일 뿐, **보유분 청산 대상이 아니다**. `ActiveStrategyCodeProvider.activeCodesByStrategy()` 가 codes 를 읽어 시세 구독·BUY 평가 universe 를 만든다(매수 경로). 청산(SELL)은 `strategy_position` lot ledger(ADR 0110·0130) 기준이지 codes 기준이 아니다.

> **보유 lot 처리 정책 — hold-until-exit (LOCK)**: universe 에서 빠지는 종목에 autopilot lot 이 있어도 **강제 청산하지 않는다.** 그 lot 은 ADR 0130 의 **TradePlan(L2) + 항상-on 안전망(L3: VI·EOD·dll·종목 floor)** 이 계획대로 청산한다. codes 에서 빠지면 **추가 매수만 멈출 뿐**(그 종목이 더 이상 BUY 평가 대상이 아님), 보유분의 출구는 plan·안전망이 보장한다.

  - **근거**: codes 갱신 시점에 보유분을 일괄 시장가 청산하면 (a) 계획(plan)이 정한 TP/SL 을 무시한 비계획 청산이 매일 장전 발생 (b) 슬리피지·세금·수수료 누수 (c) "어제 진입, 오늘 universe 밖" 종목이 정상 추세 중이어도 강제 손절될 위험 — 자본에 더 위험하다. hold-until-exit 가 ADR 0130 의 "계획대로 보장 청산" 정신과 정합한다.
  - **단, allow_overnight=false 전략의 당일 진입분**은 어차피 EOD(15:15) 청산(ADR 0129 레버1)되므로 universe 밖 잔존이 하루를 넘기지 않는다. **allow_overnight=true 전략**은 의도된 오버나잇이므로 plan·안전망이 관리한다.
  - **가시화**: refresh 결과 로그에 "universe 에서 빠졌으나 보유 lot 잔존 종목 수" 를 집계해 `SystemEvent`(INFO) 로 노출(운영 관측 — orchestrator/사용자가 hold 중인 잔존을 인지). silent 진행 0(헌법 §11-2).

- **신규 종목 WS 구독·warmup 연계**: codes 갱신 후 새로 들어온 종목은 ① `ActiveStrategyCodeProvider` 가 다음 호출에서 즉시 반영(시세 universe owner 집합 — STRATEGY owner, ADR 0124) ② `ActiveStrategyIntradayWarmupRunner`(09:01) 가 ACTIVE 전략 종목 each 에 인트라데이 캔들 warmup. 08:35 갱신 → 09:01 warmup 사이 26분 여유로 새 종목 준비 완료. **신규 배선 0 — 기존 자원이 codes 를 read 하므로 자동 연계.**

### 2-4. 게이트 · 수동 override · 롤백

- **ENV 게이트 `magicjar.autopilot.universe-refresh.enabled` default `false`(보수적 LOCK)**. autopilot 자동매매 핵심 자본 경로이므로 default OFF — 검증 후 사용자/orchestrator 가 명시 on. off 면 worker no-op(고정 universe 유지 = 현행 byte-동일 거동).
  - **보수적 default 근거**: 본 결정은 "매일 매수 종목 자체가 바뀐다"는 최고위험 자본 경로다. ADR 0129/0130 의 게이트 default OFF 정책과 동일하게, REAL 자동매매에 영향을 주는 변경은 명시 승인 전까지 비활성이 안전하다. (오늘 수동 조치는 사용자/orchestrator 가 직접 확인한 일회성이므로 자동화 default 와 별개.)
- **수동 override 경로**: 사용자/orchestrator 가 오늘처럼 `strategy.codes` 를 직접 UPDATE 하면 그 값이 다음 08:35 refresh 전까지 유효. refresh 가 매일 덮어쓰므로, **수동 고정을 원하면 게이트 off** 또는 `magicjar.autopilot.universe-refresh.pin-codes`(미래 후보 — §7) 로 핀 고정. 1차는 "게이트 on = 매일 자동 / off = 수동 고정" 이분으로 단순.
- **고정 universe fallback**: snapshot 이 0건(발굴 실패·휴장 직후 등)이거나 TOP N 산출이 빈 결과면 → **codes 갱신 skip**(기존 universe 유지). 빈 universe 로 덮어써서 autopilot 이 매수 대상 0 이 되는 사고를 차단. ERROR 로그 + SystemEvent(WARN) 가시화(헌법 §11-2 — silent fallback 금지).
- **롤백**: 게이트 `=false` 1줄 → worker no-op = v3.14.0 거동 복귀. codes 는 마지막 값 잔존(무해 — 다음 진입부터 그 universe 사용, 보유분은 plan 청산). 즉시 되돌림 가능.

### 2-5. owner 격리 (zero-regression LOCK)

- refresh 대상은 **owner='AUTOPILOT' AND status IN ('ACTIVE','BUY_BLOCKED')** 전략으로 SQL where 격리. **USER/SYSTEM 전략의 codes 는 절대 touch 0**(byte-동일).
- owner 판정 출처 = `strategy.owner` 컬럼(`StrategyOwner.AUTOPILOT` — 기존). 신규 lookup 0.
- USER 전략은 사용자가 직접 종목을 지정하는 모델이므로 자동 재생성 대상이 아니다(직관·계약 유지).

---

## 3. 대안 비교

### 3-1. 매핑 정책

| 대안 | 내용 | 장점 | 단점 | 판정 |
|------|------|------|------|------|
| **A. 전략 타입별 프리셋 매핑** | 100 momentum→MOMENTUM_BREAKOUT · 104 volume→VOLUME_BREAKOUT · 나머지 통합 TOP | 전략 의도와 프리셋 의도 직결(이론상 alpha↑) | autopilot 100·102·104 공통 COMPOSITE 진입이라 1:1 안 떨어짐 · 102 fvg·103 ob 대응 프리셋 없음 · 전략별 universe 분기로 codes·WS·warmup 복잡도 급증 · 프리셋별 alpha 분화 미검증 | ✗ (post-MVP §7) |
| **B. 전 전략 동일 deduped 가중 TOP N** | 전 프리셋 합집합 + code별 max score dedup + TOP N 균등가중 | 단순·결정적·연속(오늘 수동 TOP16 과 동형) · ADR 0130 lot 소유권이 전략 교차 차단하므로 동일 universe 안전 · 운영 복잡도 최소 | 프리셋별 특화 alpha 를 universe 단계에서 분리 못함(전략 DSL 이 자기 진입 필터로 흡수) | **✓ 채택** |
| **C. 합집합 전체(dedup만, N 제한 0)** | 모든 프리셋 TOP N 후보 전부 universe 화 | 후보 누락 0 | universe 폭주 → WS slot(40) 초과 · 평가 부하 · 단타 집중도 희석 | ✗ |

**근거**: 1차 목표는 "발굴↔autopilot 단절 해소"이고, ADR 0130 의 lot 소유권 모델이 이미 전략 간 자본 교차를 막으므로 **동일 universe 가 가장 단순하고 안전**하다. 프리셋별 특화는 forward-test alpha 가 분화 검증된 뒤 옵션 A 로 진화(post-MVP). N 상한은 WS slot 예산(40) 정합으로 폭주를 막는다.

### 3-2. 보유 lot 처리

| 대안 | 내용 | 판정 |
|------|------|------|
| **hold-until-exit (채택)** | universe 밖 종목 보유분은 plan(L2)·안전망(L3)이 계획대로 청산. codes 는 매수 대상만 | **✓** — ADR 0130 정신 정합 · 비계획 청산·슬리피지·강제손절 회피 |
| EOD 일괄 청산 | universe 밖 보유분을 매일 장전 시장가 청산 | ✗ — 계획 무시 청산 매일 발생 · 정상 추세 강제손절 위험 · 수수료 누수 |
| 청산 후 재구성 | 매일 전량 청산 + 새 universe 재진입 | ✗ — 과매매 · 자본 누수 극대 · 단타 의미 상실 |

---

## 4. owner 격리 & 롤아웃 (zero-regression LOCK)

- **owner=AUTOPILOT 전용.** USER/SYSTEM 전략 codes byte-동일.
- **ENV 게이트 default OFF.** off 면 worker no-op → 행동 변화 0.
- **fail-safe**: snapshot 0건·빈 TOP N → codes 갱신 skip(기존 universe 유지) + WARN 가시화. 빈 universe 사고 차단.
- **롤백**: 게이트 off 1줄. 롤백 앵커 v3.14.0(거동 byte-동일 복귀).

---

## 5. 자원 audit (feedback_spec_audit 의무 — ADR 0102 5단계)

검증 시점 main 기준. **Flyway 최신 = V95**(`V95__strategy_position_trade_plan.sql`, ADR 0130). 다음 가용 = **V96**.

### 신규 (최소 원칙)

| 자원 | 종류 | 비고 |
|------|------|------|
| `AutopilotUniverseRefreshWorker` | api/scheduler | 08:35 cron + 거래일 게이트 + deduped TOP N 합성 → owner=AUTOPILOT codes UPDATE. `DiscoveryScorecardScheduler` 패턴 1:1. |
| `AutopilotUniverseRefreshService` | api/service | scorecard read → dedup·정렬·TOP N → codes write(transaction). 보유 lot 잔존 집계. |
| `magicjar.autopilot.universe-refresh.enabled` | ENV/yml | default **false**(게이트). |
| `magicjar.autopilot.universe-refresh.cron` | ENV/yml | default `0 35 8 * * *`. |
| `magicjar.autopilot.universe-refresh.size` | ENV/yml | default `16`(1~40). |
| **Flyway V96** (선택) | Flyway | **신규 테이블/컬럼 불요로 판단** — codes 는 기존 컬럼 UPDATE. 단 refresh 이력 audit 가 필요하면 `autopilot_universe_history`(snapshot_date·codes JSON·source) add-only 후보(§7). 1차는 로그+SystemEvent 로 충분 → **V96 미발행 권장**(스키마 변경 0). |

### 재사용 (LOCK — 신규 생성 금지)

| 자원 | 위치 | 본 ADR 활용 |
|------|------|-------------|
| `DiscoveryScorecardJpaRepository.findRowsInRange` | api/persistence/screener | 오늘 snapshot row read(from=to=today). |
| `discovery_scorecard` 테이블 | DB(V77) | 08:30 snapshot 입력 — read만. write 0. |
| `DiscoveryScorecardSnapshotService` | api/service/screener | 08:30 가 본 worker 의 입력 생산자(의존 순서). |
| `StrategyJpaRepository`(`findIdsByOwner`·codes UPDATE) | api/persistence/strategy | owner=AUTOPILOT 전략 조회 + codes write. **codes UPDATE 는 @Modifying @Query JSONB set**(헌법 §6 abstract fun LOCK). |
| `StrategyOwner.AUTOPILOT` | domain/strategy | owner 격리 단일 식별자. |
| `MarketCalendarJpaRepository` | api/persistence | 거래일 게이트(ADR 0104 — DiscoveryScorecardScheduler 1:1). |
| `ActiveStrategyCodeProvider` | consumer/ingestion | codes 갱신을 다음 호출에서 자동 read(WS universe·BUY 평가) — 신규 배선 0. |
| `ActiveStrategyIntradayWarmupRunner` | consumer/ingestion | 09:01 새 종목 warmup 자동 연계 — 신규 배선 0. |
| `SystemEvent`(INFO/WARN) | domain/risk | refresh 결과·보유 lot 잔존·fail-safe skip 가시화(헌법 §11-2). |

### 점유 충돌 검증

| 자원 | 신규 점유 | main 충돌 | 결론 |
|------|-----------|-----------|------|
| Flyway | V96(미발행 권장) | 최신 V95 | 충돌 0 (스키마 변경 0) |
| @Scheduled cron | `0 35 8` (08:35) | snapshot 08:30·warmup 09:01·EOD 15:15 와 시각 비충돌 | 충돌 0 |
| ENV | `magicjar.autopilot.universe-refresh.*` 3건 add-only | autopilot.* 네임스페이스 내 미점유 | 충돌 0 |
| REST/Kafka/STOMP | 0(내부 스케줄러) | — | 신규 0 |

---

## 6. 정밀 설계 노트

### 6-1. deduped TOP N 합성 (결정성 LOCK)

```
input  : discovery_scorecard rows WHERE snapshot_date = today  (모든 활성 preset)
step1  : group by instrument_code → max(score)            // dedup, 가장 강한 신호
step2  : sort by score DESC, instrument_code ASC          // tie-break 결정성
step3  : take(size)                                        // default 16
output : List<code>  (size 이하)
```
- snapshot row 0건 또는 output 빈 결과 → **codes 갱신 skip**(fail-safe §2-4) + WARN.
- 산식은 candle DB·KIS 호출 0(scorecard 는 이미 영속된 값 read). AI 0.

### 6-2. codes UPDATE 멱등·트랜잭션

- owner=AUTOPILOT 평가 전략 전체를 **단일 트랜잭션**에서 동일 codes JSON 으로 UPDATE(부분 실패 0 — 일부 전략만 새 universe 되는 split-brain 방지).
- **멱등**: 같은 날 재실행(부팅·수동 트리거)해도 같은 snapshot → 같은 output → 같은 codes(부작용 0). 단, 같은 영업일 1회만 발사하는 가드(`AutopilotEodLiquidationWorker.lastLiquidatedDate` 패턴 — KST 거래일 AtomicReference) 동반해 중복 UPDATE·중복 SystemEvent 억제.
- **drift 교훈 LOCK**: codes 는 `strategy.codes`(api `StrategyJpaEntity`) 와 consumer 측 `ConsumerStrategyJpaEntity.codes` 가 **동일 컬럼**을 읽는다(매핑 누락 0 — ADR 0128 scale_in drift 교훈). codes 는 단일 컬럼이라 엔티티 매핑 분기 위험이 scale_in_config 보다 낮으나, 검증 시 consumer `ActiveStrategyCodeProvider` 가 갱신된 codes 를 실제로 읽는지 라이브 확인 의무(§8).

### 6-3. 보유 lot 잔존 가시화

- refresh 후: `(직전 universe ∖ 신규 universe)` 종목 중 owner=AUTOPILOT `strategy_position.quantity > 0` 인 종목 수를 집계 → `SystemEvent(INFO, "autopilot universe rotated: dropped=X, held-lots-remaining=Y")`. orchestrator/사용자가 hold-until-exit 중인 잔존을 인지.

### 6-4. 발굴 입력 품질 의존 명시

- 본 worker 의 universe 품질 = 발굴(scorecard) 품질에 종속. 발굴 프리셋 alpha 가 검증 중(forward-test scorecard 누적)이므로, **게이트 default OFF + 검증 후 on** 이 정합. scorecard hit rate 가 충분히 누적되면 옵션 A(프리셋별 가중)·동적 가중(§7)으로 진화.

---

## 7. post-MVP (후속 트랙 후보)

1. **프리셋별 동적 가중** — scorecard forward-test hit rate 기반으로 프리셋별 score 가중(잘 맞는 프리셋 후보를 universe 상위로). 채점 데이터 누적 필요.
2. **옵션 A(전략 타입별 프리셋 매핑)** — 프리셋 alpha 분화 검증 후 전략별 특화 universe.
3. **`autopilot_universe_history` 테이블(V96~)** — 일자별 universe 이력 audit·백테스트 재현용.
4. **수동 핀 고정(`pin-codes`)** — 게이트 on 상태에서 특정 종목 고정.

---

## 8. 검증·증명 의무

- 단위 테스트로 끝내지 않는다(메모리 `feedback_verification_strict`). 게이트 on 후 MOCK→canary 에서:
  1. 08:35 worker 발사 로그 + deduped TOP N 산출 결과(scorecard today snapshot 과 대조)
  2. owner=AUTOPILOT 전략 codes 가 실제 갱신됐는지 DB 확인 + **USER/SYSTEM codes 무변경 확인(격리)**
  3. consumer `ActiveStrategyCodeProvider` 가 갱신된 codes 를 read 해 새 종목이 BUY 평가·WS 구독 universe 에 들어왔는지 라이브 확인
  4. 09:01 warmup 이 새 종목에 캔들 backfill 했는지
  5. universe 밖 보유 lot 이 **강제 청산 안 됨**(hold-until-exit) + SystemEvent 가시화 확인
  6. snapshot 0건 시 codes 갱신 skip(fail-safe) 확인
- **SLO 영향**(헌법 §9-1): autopilot 시그널→주문 경로 무변경(universe 만 교체). WS slot 점유는 N≤40 으로 ADR 0124 예산 내. account API·Outbox 무영향.
- **rollback plan**(헌법 §9-2): 게이트 `=false` 1줄 → worker no-op → 고정 universe 복귀. 즉시.
- **관측 매트릭스**(헌법 §9-3): refresh SystemEvent(INFO — universe 회전·held-lots) + fail-safe(WARN — snapshot 0/빈 TOP N) + autopilot 일일 손익 대조(KIS 진실원 크로스체크 — 메모리 `feedback_kis_ground_truth_crosscheck`).

## 9. 라운드 경계 (구현 plan 요약 — 상세는 spec)

| 라운드 | 책임 에이전트 | 내용 |
|--------|---------------|------|
| **R1** | strategy-engine | `AutopilotUniverseRefreshService`(scorecard read → dedup·TOP N → owner=AUTOPILOT codes UPDATE 트랜잭션 + 보유 lot 잔존 집계) + `AutopilotUniverseRefreshWorker`(08:35 cron + 거래일 게이트 + 1일 1회 멱등) + ENV 3건 + StrategyJpaRepository codes UPDATE @Query(abstract fun) |
| **R2** | strategy-engine + qa | 테스트(dedup 산식·격리·fail-safe·멱등) + MOCK 라이브 검증(consumer codes read·warmup 연계) + 데이터 흐름 시나리오 일괄 |
| **R-aux** | (선택) frontend | autopilot status 화면에 "오늘 universe + 회전 종목 + held-lots" 노출(관측 가시화 — 후속) |

## 10. 롤백

게이트 `magicjar.autopilot.universe-refresh.enabled=false` 1줄 → worker no-op = v3.14.0 거동 byte-동일. codes 마지막 값 잔존(무해 — 보유분은 plan·안전망 청산). 스키마 변경 0(down SQL 불요).
