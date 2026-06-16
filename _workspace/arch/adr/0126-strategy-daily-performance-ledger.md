# ADR 0126 — 전략별 일자별 성과 누적 원장 + 시장 레짐/α + 일자별 메모

- 상태: **Accepted (2026-06-16, 최고 관리자 요청 — "전략이 어떤 장에서 효과적이었나"를 데이터로 장기 축적 + 접근법 A 결재)**
- 작성자: architect (opus 4.8, 1M)
- 관계: spec `_workspace/specs/2026-06-16-strategy-daily-performance.md`(설계 SoT — 본 ADR이 결정 근거·모듈 경계 LOCK), ADR 0072(T-J + T-PERF 묶음 — `pnl_daily` V30 + `batch DailyPerformanceJob` 본 ADR이 strategy_id 그룹핑으로 확장), ADR 0110(`strategy_position` 전략별 lot ledger — 전략 귀속 권위), ADR 0114·0115(주문 집행·청산 사슬 — `order_execution` 불변 체결 로그가 왕복 집계 source), ADR 0100(market-trend-engine — `market_index_snapshot` V47이 지수 일봉 롤업 source), ADR 0023(Signal Engine 룰 매칭 only·AI 0 — 레짐 분류도 룰 기반 AI 0 정합), ADR 0102(spec/plan 발행 시 main 영속 자원 audit), 헌법 §3(KIS-first raw)·§13(롱 온리)·§9-2(canary 변경 관리)
- 진입 트랙: 운영 고도화 (전략 성과 회고·전략-시장 적합도 누적 분석 layer)
- 자본 결정권: **0** (전 경로 읽기 전용 집계·표시 — 주문 발사 X·매매 결정 X·신규 KIS 호출 0)

---

## 1. 컨텍스트

magicJar는 REAL canary 운영 단계에 진입했다(2026-06-11 1일차 ~ 4일차). 실전 자동매매가 돌기 시작했으나, **"이 전략이 어떤 장(상승/하락/횡보/고변동)에서 효과적이었나"를 데이터로 누적·회고하는 layer가 없다**. 현재는 user 단위 `pnl_daily`(V30, ADR 0072) 일자 손익만 있고, **전략×일자 단위**로 청산 성과·그날 시장 맥락·초과수익(α)을 묶어 보관하지 않는다. 운영이 길어질수록 "장이 좋아서 번 건지, 전략이 좋아서 번 건지"를 사후에 분리할 수 없게 된다.

최고 관리자 요청(spec §트리거): "전략이 효과가 있었는지 하루하루 누적, 그날 지수와 함께 기록, 전략별 일자 메모로 '이런 장에 이 전략이 좋았다'를 장기 축적."

**기존 자산이 대부분 갖춰져 있다** — 본 결정은 새 데이터 소스를 만드는 것이 아니라 **연결·집계 layer만 추가**한다:

| 재사용 자산 | 역할 |
|---|---|
| `order_execution`(V21/V31) | 불변 체결 로그 — 왕복 집계의 단일 source |
| `order_request.strategy_id`(V43) | 체결의 전략 귀속 키 |
| `strategy_position`(V64/V65, ADR 0110) | 전략별 lot ledger·realized_pnl 교차검증 |
| `pnl_daily`(V30, ADR 0072) | user 단위 일손익 — 전략 합과 교차검증 anchor |
| `market_index_snapshot`(V47, ADR 0100) | 코스피·코스닥 1분 snapshot — 지수 일봉 롤업 source |
| `DailyPerformanceJob`(ADR 0072, 00:30 KST) | 기존 일배치 — strategy_id 그룹핑으로 확장 |
| `trading_journal`/`journal_note`(V29) | 메모 UPSERT 패턴 참고 |

따라서 본 ADR은 신규 영속 3종 + 기존 배치 1개 확장 + 읽기 endpoint·UI로, **새 KIS 호출 0 / 새 Kafka 토픽 0 / 새 AI 호출 0** 으로 성립한다(canary 운영 영향 0).

**KIS-first raw 검증 (헌법 §3)**: 본 ADR은 **새로운 외부 데이터 규격을 도입하지 않는다** — 모든 집계 입력(`order_execution`·`market_index_snapshot`)은 이미 KIS raw에서 적재된 영속 데이터다(체결=KIS 주문/체결 응답·지수 snapshot=KIS `market_index_snapshot` ADR 0100 raw fetch 정합). 따라서 본 ADR에 KIS raw fetch 신규 enforce 항목은 없으며, 자체 추론 0 원칙은 "원천 데이터를 새로 만들지 않고 이미 raw에서 적재된 영속 행만 읽어 집계"하는 방식으로 자동 충족된다.

---

## 2. 검토한 대안 (집계 방식)

성과 누적을 어떻게 채울 것인가에 세 접근법을 비교했다(spec §1·접근법 비교).

| 대안 | 장점 | 단점 |
|------|------|------|
| **A. 일배치 집계 + 영속 일자행 + 백필 (채택)** | 기존 `DailyPerformanceJob`(00:30 KST) 확장 1점 — 인프라 신규 0. 멱등 UPSERT로 재실행·백필 안전. 일자행 영속 = 불변 누적 원장(시장 맥락·α도 그날치 freeze). 읽기는 단순 SELECT — API 부하 0. 운영 시간(장중) 영향 0(야간 T-1 집계) | 당일 성과는 T+1 00:30에야 보임(장중 실시간 X). 백필 1회성 잡 필요 |
| B. 실시간 증분 누적 (체결 이벤트마다 갱신) | 장중 즉시 반영 | 체결마다 누적행 mutate → 멱등·정합 risk(ADR 0115 strategy_position 증분 mutate가 G2·G4·G7 미귀속 영구화의 씨앗이었던 선례). canary 자본 경로(consumer)에 집계 부하 결합 — 운영 안정성 직접 위협. 재계산·교차검증 어려움 |
| C. 읽기 시 계산 (조회마다 order_execution replay) | 영속 테이블 0 — 항상 최신 | 조회마다 전체 체결 replay = O(N) API 부하·지연. 그날 지수 맥락·레짐을 매번 재계산(과거 snapshot 재조회). 누적이 길어질수록 비현실적 |

**선택 = A (일배치 집계 + 영속 + 백필)**. 사유:
1. **운영 안전 우선** — canary 자본 경로(consumer 체결 처리)에 집계 책임을 결합하지 않는다. 집계는 야간 T-1 배치(읽기 전용)로 분리해 장중 매매에 0 영향(헌법 §9-2 canary 무위험 원칙).
2. **불변 누적 원장** — 일자행에 그날의 성과·지수 맥락·α·레짐을 **freeze**하면, 사후에 지수 snapshot이 정리되거나 체결이 보정돼도 과거 성과 해석이 흔들리지 않는다. 비목적(MDD·연속손실일·다일 레짐)은 이 일자행 원천 데이터에서 **후속 무손실 추가**가 가능하다.
3. **멱등 재실행** — UPSERT(UQ 키) 로 배치·백필 재실행 시 중복 0. ADR 0072 `DailyPerformanceJob` 멱등 패턴 1:1 계승.
4. B의 실시간 증분은 ADR 0115가 진단한 "mutable 증분 원장의 미귀속 영구화" risk를 그대로 반복할 수 있어 기각. C의 읽기 시 replay는 누적 데이터가 길어질수록 부하·재계산이 비현실적이라 기각.

**B는 폐기가 아니라 보류** — 장중 실시간 성과 대시보드 수요가 명확해지는 후속 단계에서 "영속 누적행(A) + 당일 미반영분 실시간 오버레이(B-lite)" 하이브리드로 재검토할 수 있다(미래 후보, 본 ADR은 A 단일 LOCK).

---

## 3. 결정 (2026-06-16 최고 관리자 결재 — 6건 LOCK)

### 3-1. 신규 영속 테이블 3종 (Flyway V85 ~ V87 — 마이그레이션 1건당 1 관심사)

전략×일자 불변 누적 원장 3종을 신설한다. **상세 컬럼·UQ·INDEX 정의는 spec §3이 단일 SoT** — 본 ADR은 경계·불변식만 LOCK한다.

| 버전 | 테이블 | 관심사 |
|---|---|---|
| **V85** | `strategy_daily_performance` | 전략×일자 성과 누적 원장 (왕복수·승/패·실현손익·진입금액·수익률·벤치마크·α·레짐). UQ(user_id, strategy_id, trade_date) 멱등 UPSERT |
| **V86** | `market_index_daily` | 지수×일자 OHLC 롤업 (전 전략 공유·`market_index_snapshot` 집계). UQ(index_code, trade_date) 멱등 |
| **V87** | `strategy_daily_note` | 전략×일자 메모 (자유텍스트 + 태그 JSONB). UQ(user_id, strategy_id, trade_date) 단일 메모 UPSERT(수정 가능) |

> **다음 가용 버전 = V85** (현재 main V84까지 적용 — spec §3 audit). 마이그레이션 owner = backend-core 단일(버전 충돌 회피 — §4).

### 3-2. 거래 단위 = 왕복(round-trip)·청산일 귀속 (사용자 결재)

- **1 거래 = 1 왕복**: 진입(매수 체결) → 완전 청산(해당 전략·종목 포지션 수량 0 복귀).
- **수익률(return_pct)** = 청산손익 / 진입금액(cost basis). cost_basis=0 이면 null.
- **승/패**: 왕복 손익 > 0 = 승, < 0 = 패. = 0 은 win/loss 어디에도 안 넣되 round_trip_count 에는 포함.
- **귀속 날짜 = 완전 청산된 날(close date)**. 며칠 걸친 왕복(월 매수→수 매도)은 수요일에 귀속.

### 3-3. 집계 = 기존 `DailyPerformanceJob` 확장 (접근법 A, 00:30 KST T-1)

기존 user 단위 `pnl_daily` 집계 잡(ADR 0072)에 두 책임을 add-only로 확장한다:
1. **지수 일봉 롤업**: 대상일 `market_index_snapshot` → `market_index_daily` UPSERT(open/high/low/close/change_pct/range_pct/regime).
2. **전략별 왕복 집계**: `order_execution` replay 를 `strategy_id` 로 그룹핑 → `strategy_daily_performance` UPSERT(benchmark·alpha·regime 채움).

- (user_id, strategy_id, code)별 체결 **시간순 replay** — 기존 `DailyPerformanceJob` 평균단가 replay 패턴 확장.
- 매수 → 누적 수량·평균단가 갱신 / 매도 → (매도가−평균단가)×수량=실현손익, 진입금액=평균단가×청산수량 / 수량 0 복귀 = 왕복 1건 마감 → close date 귀속.
- 순수 계산·멱등 UPSERT·AI 0·KIS 0 (ADR 0072 §1-4 정합).

### 3-4. α(초과수익) + 레짐 = 룰 기반 (AI 호출 0)

- **벤치마크 선택**: 전략 universe_market 으로 결정(코스피 → `0001` / 코스닥 → `1001` / 혼합·불명 → 기본 `0001`). `market_index_daily`에 두 지수 모두 저장 → 후속 재계산 여지.
- **α**: `alpha_pct = return_pct − benchmark_change_pct`. 둘 중 하나라도 null(거래 없음·지수 snapshot 부재)이면 null.
- **레짐(룰 기반·우선순위 적용, AI 0 — ADR 0023 정합)**: `range_pct ≥ 2.5%` → HIGH_VOL / else `change_pct ≥ +0.6%` → UPTREND / else `≤ −0.6%` → DOWNTREND / else SIDEWAYS. 임계치는 상수/`application.yml` 노출(추후 튜닝). v1 단일일 기준, 다일 추세는 비목적(§5).

### 3-5. 신뢰도 = 읽기 시 계산 (저장 안 함·표본 명시 의무)

누적 `strategy_daily_performance` 행에서 읽기 시 산출(저장 X):
- 표본 = 누적 round_trip_count 합 + 거래일수. 일별 return_pct 의 mean/std/Sharpe식(std=0 이면 null).
- 라벨: 누적 왕복<10 또는 거래일수<5 → **LOW** / <30 → **MEDIUM** / else **HIGH**.
- **표본 부족은 항상 N 표기 명시**(`feedback_no_mock_data`). mock·임의 합성 절대 금지 — 데이터 0건이면 "아직 청산된 거래 없음" 표시.

### 3-6. 모든 경로 읽기 전용 + 새 KIS 호출 0 (canary 무위험 LOCK)

집계(배치·백필)·조회(API)·메모(CRUD) **전 경로가 읽기 전용 소스**(`order_execution`·`market_index_snapshot` — 둘 다 이미 적재된 영속 행)만 읽는다. **신규 KIS 호출 0**. 백필은 1회성 멱등 잡(canary 시작 2026-06-11 ~ 어제). canary 운영(자본 경로·consumer·KIS rate limit 예산)에 **0 영향**.

---

## 4. 모듈 경계 (헥사고날 — domain 순수·어댑터 분리)

spec §8 빌드 분담을 모듈 경계로 LOCK한다. **마이그레이션 owner = backend-core 단일**(V85~V87 버전 충돌 회피).

| 영역 | 담당 모듈/에이전트 | 책임 | 순수성 |
|------|------|------|------|
| **왕복 집계 + 신뢰도 산식** | strategy-engine (domain) | (user,strategy,code)별 체결 replay → 왕복 손익·진입금액·승/패 순수 계산 + 신뢰도(mean/std/라벨) 순수 함수 | **순수**(Spring·JPA·KIS 의존 0 — `ScaleInExitCalculator` 선례) |
| **지수 일봉 롤업 + 레짐 분류** | market-data | `market_index_snapshot` → `market_index_daily` OHLC 집계 + 룰 기반 레짐 분류(AI 0) — 지수 데이터 owner | 롤업·레짐은 순수 계산, 영속은 어댑터 |
| **배치 확장 + 백필 + 영속(Flyway V85~V87)** | backend-core (batch) | `DailyPerformanceJob` strategy_id 그룹핑 확장 + 1회성 백필 잡 + V85~V87 마이그레이션 단일 owner | 어댑터 레이어 |
| **조회 endpoint + 메모 CRUD** | api (backend-core/strategy) | REST 4종(history·summary·note GET·note PUT) + Swagger 4단 어노테이션 | 어댑터 레이어 |
| **화면(모달·페이지·훅)** | frontend | StrategyCard "📊 성과·메모" 액션 + StrategyPerformanceModal(탭2) + `/strategy/:id/performance` 라우트 | — |

- **포트/어댑터 분리**: 왕복 집계·신뢰도는 domain 순수 함수(strategy-engine)로 두어 live·백필이 단일 계산기를 공유한다(이중 구현 금지). 영속·KIS·배치 스케줄은 어댑터(batch/api).
- **개발 룰 3 (한 PR 한 영역·라운드 분할)**: **R1** 도메인 집계·신뢰도 순수 함수 + Flyway V85~V87 → **R2** 배치 확장·영속 어댑터·REST/메모 API → **R3** UI(모달·페이지) + 백필 잡 실행 + 일괄 검증.
- qa Visual: 전략 목록은 핵심 화면(헌법 §9-2) → 성과·메모 모달 시각 회귀는 R3 후속 dispatch.

---

## 5. 비목적 (v1 제외 — 일자행에 원천 데이터 보존 → 후속 무손실 추가)

다음은 v1 범위에서 제외한다. 단 **모두 `strategy_daily_performance` 일자행에 원천 데이터가 남아** 후속에서 영속 변경 없이(또는 읽기 시 계산으로) 추가할 수 있다:

- **미실현 손익**(보유 중 평가손익) 일자 추적 — 왕복(완전 청산) 단위만 v1.
- **다일(multi-day) 추세 기반 레짐** — v1은 단일일 지수 기준. 누적 `market_index_daily` 행에서 후속 산출 가능.
- **MDD / 연속손실일** — 누적 일자행 return_pct 시퀀스에서 읽기 시 후속 산출 가능.
- **실시간(장중) 증분 누적** — 접근법 B 보류(§2).

---

## 6. 롱 온리 정합 (헌법 §13)

magicJar는 **공매도(숏) 없음** — 매도는 항상 보유분 청산이다(ADR 0110 lot ledger·ADR 0114 청산 보장·헌법 §13 long-only). 따라서 왕복 집계(§3-2·§3-3)의 전제 "매도 체결 = 보유분 청산"은 항상 성립하며, 음수 포지션(숏 진입)으로 인한 왕복 정의 깨짐은 발생하지 않는다. 검증 시 음수 포지션 데이터(공매도) 부재를 확인한다(spec §9).

---

## 7. 결과 (운영 ADR 양식 3 슬롯 — 헌법 §9-5)

### 7-1. SLO 영향
정상 운영(canary) SLO(ADR 0095 §9-1)에 **영향 없음** — 집계는 야간 T-1 배치(00:30 KST, 장 마감 후·매매 비활성 시간대)·전 경로 읽기 전용·신규 KIS 호출 0. 자본 경로(시그널→주문 S-1·KIS WS S-2·rate limit S-4)와 **물리 분리**. 조회 endpoint는 영속행 단순 SELECT로 부하 미미.

### 7-2. rollback plan
- 신규 테이블 3종은 add-only(기존 스키마 무변경) → Flyway revert(V85~V87 down) + 배치 확장 코드 PR revert로 원자 복구. `pnl_daily`(기존) 집계는 무영향.
- 백필 잡은 1회성·멱등 → 재실행/중단 안전. 부분 백필 상태로 롤백해도 기존 자본 경로 무손상.
- UI/API는 add-only → revert 시 전략 목록 기존 동작 복귀.

### 7-3. 관측 매트릭스
- 배치 확장 후 `DailyPerformanceJob` 00:30 실행 로그 에러 0 + `strategy_daily_performance`·`market_index_daily` row 채워짐 DB 실증(할루시네이션 금지 — spec §9 백필 검증: 6/11~어제 후성 왕복이 실제 행으로).
- **교차검증**: 전략별 왕복 realized_pnl 합 ↔ user `pnl_daily` 합(전략 미귀속분 = strategy_id 0/MANUAL 분리 확인) ↔ `strategy_position.realized_pnl`(ADR 0110).
- **멱등성**: 배치·백필 재실행 시 UPSERT 중복 0 확인.
- frontend 전략 목록 성과·메모 모달 시각 회귀(qa Visual).

### 7-4. 자원 audit (ADR 0102)
- **Flyway 신규 V85·V86·V87**(다음 가용 = V85, main V84까지 — owner backend-core 단일). **Kafka 신규 0** / **STOMP 신규 0** / **REST 신규 4**(`/api/strategy/{id}/performance/history`·`/performance/summary`·`/note` GET·`/note/{date}` PUT) / **ENV 신규 0**(레짐 임계치는 상수/yml 기본값) / **Redis 신규 0**. KIS 호출 증가 **0** / AI 호출 **0** / mock 합성 **0**.
- 본 ADR 자체는 **코드 0 touch**(md only — ADR·README·spec status). 구현 = R1~R3 sub-agent 병렬/순차 PR.

---

## 8. 대안 (rejected) 요약

| 대안 | rejected 사유 |
|---|---|
| **B. 실시간 증분 누적** | canary 자본 경로(consumer)에 집계 부하 결합 → 운영 안정성 직접 위협. mutable 증분 원장은 ADR 0115가 진단한 "미귀속 영구화" risk 반복. 멱등·교차검증 어려움. 폐기 아님 — 장중 실시간 대시보드 수요 명확화 시 A+B-lite 하이브리드 후보 |
| **C. 읽기 시 계산(조회마다 replay)** | 조회마다 전체 체결 O(N) replay + 과거 지수 snapshot 재조회 → 누적 길어질수록 부하·지연 비현실적. 일자행 freeze(시장 맥락·α·레짐) 불가 |
| **거래 단위 = 개별 매도 체결** | 부분 청산·분할 익절(ADR 0111) 시 "성과 한 건"이 흩어져 승률·수익률 해석 왜곡. 왕복(완전 청산 0복귀)이 사용자 직관("사고팔면 1번")과 정합 — 사용자 결재 |
| **레짐 분류 AI 사용** | AI는 사용자 트리거 옵션 분석 보조·매매/집계 결정권 0(ADR 0001·0023·0026). 레짐은 결정론적 룰(지수 등락률·변동성)로 충분 + 멱등·재현·백필 일관 |
| **MDD·다일 레짐·미실현손익 v1 포함** | v1 범위 과다. 일자행 원천 데이터 보존 → 후속 무손실 추가 가능(§5) — MVP 최우선 원칙 |

---

## 9. 참고

- spec(설계 SoT): `_workspace/specs/2026-06-16-strategy-daily-performance.md` (컬럼·UQ·INDEX·산식·UI·검증 단일 권위)
- ADR 0072 — T-J + T-PERF 묶음 (`pnl_daily` V30 + `batch DailyPerformanceJob` — 본 ADR이 strategy_id 그룹핑 확장 + 멱등 UPSERT 패턴 계승)
- ADR 0110 — `strategy_position` 전략별 lot ledger·realized_pnl (전략 귀속 권위·교차검증)
- ADR 0114·0115 — 주문 집행·청산 사슬 (`order_execution` 불변 체결 로그 = 왕복 집계 source·증분 mutable 원장 risk 선례)
- ADR 0100 — market-trend-engine (`market_index_snapshot` V47 = 지수 일봉 롤업 source)
- ADR 0023·0026·0001 — Signal/AI 룰 본체 + AI 옵션 (레짐·집계 AI 0 정합)
- ADR 0102 — spec/plan 발행 시 main 영속 자원 audit (V85~V87·REST 4 audit)
- 헌법 §3(KIS-first raw)·§13(롱 온리)·§9-2(canary 변경 관리)·§9-5(운영 ADR 양식)

## 10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-16 | ADR 0126 본문 발행 (Accepted) — architect opus 4.8. 전략별 일자별 성과 누적 원장 + 시장 레짐/α + 일자별 메모. **6건 LOCK**: ①신규 영속 3종(`strategy_daily_performance`·`market_index_daily`·`strategy_daily_note` Flyway V85~V87 — 전략×일자 불변 누적 원장·멱등 UPSERT) ②거래 단위=왕복(진입→완전청산 0복귀)·청산일 귀속·수익률=청산손익/진입금액 ③집계=기존 `DailyPerformanceJob`(00:30 KST T-1) strategy_id 그룹핑 확장(접근법 A — 실시간 증분 B·읽기 시 계산 C 기각) ④α=전략수익률−벤치마크 등락률·레짐=룰 기반 4분류(HIGH_VOL/UPTREND/DOWNTREND/SIDEWAYS·AI 0) ⑤신뢰도=읽기 시 표본+일관성 산출(저장 X·표본 N 명시·mock 금지) ⑥전 경로 읽기 전용·새 KIS 호출 0(canary 운영 영향 0). 모듈 경계: domain 순수 집계·신뢰도(strategy-engine) / 지수 롤업·레짐(market-data) / 배치·영속·백필·Flyway 단일 owner(backend-core batch) / REST·메모(api) / UI(frontend). 비목적(미실현손익·다일 레짐·MDD — 일자행 원천 보존 후속 무손실 추가). 롱 온리 정합(헌법 §13 매도=청산). 자원 audit(ADR 0102): Flyway V85~V87·REST 4 신규·Kafka/STOMP/ENV/Redis 0·KIS·AI·mock 0. 구현=R1(도메인+마이그레이션)→R2(배치+영속+API)→R3(UI+백필+검증) sub-agent. md only(코드 0 touch). spec `2026-06-16-strategy-daily-performance.md` SoT. 자본 결정권 0. |
