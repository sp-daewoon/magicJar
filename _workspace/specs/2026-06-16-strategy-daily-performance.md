# spec: 전략별 일자별 성과 히스토리 + 일자별 메모

- **작성일**: 2026-06-16
- **상태**: Draft (사용자 검토 대기)
- **트리거**: 최고 관리자 요청 — "전략이 효과가 있었는지 하루하루 누적, 그날 지수와 함께 기록, 전략별 일자 메모로 '이런 장에 이 전략이 좋았다'를 장기 축적"
- **접근법**: A (일배치 집계 + 영속 일자행 + 백필) — 사용자 결재
- **관련 자산(재사용)**: `pnl_daily`(V30) · `strategy_position.realized_pnl`(V64) · `order_request.strategy_id`(V43) · `order_execution`(V21/V31) · `market_index_snapshot`(V47) · `DailyPerformanceJob`(00:30 KST) · `trading_journal`/`journal_note`(V29, 패턴 참고)

---

## 1. 목적 / 비목적

### 목적
1. 전략별로 **매일 청산된 왕복 매매의 성과를 영속 누적**한다 (실현손익·승률·수익률).
2. 그날의 **시장 맥락**(코스피·코스닥 고/저/종가 + 자동 레짐 태그)을 함께 기록한다.
3. **초과수익 α**(전략 수익률 − 지수 등락률)를 매일 기록해 "장이 좋아서 번 건지, 전략이 좋아서 번 건지"를 분리한다.
4. 누적 데이터로 **신뢰도**(표본수 + 일관성)와 **레짐별 승률**을 산출해 "이런 장엔 켜는 게 좋았다"를 데이터로 보여준다.
5. **전략×일자 메모**(자유텍스트 + 태그)로 정성 관찰을 누적한다.

### 비목적 (v1 제외 — 모두 일자행에 원천 데이터가 남아 후속 무손실 추가 가능)
- 미실현 손익(보유 중 평가손익) 일자 추적
- 다일(multi-day) 추세 기반 레짐
- MDD / 연속손실일 (읽기 시 누적행에서 후속 산출 가능)
- 실시간(장중) 증분 누적 (접근법 B 보류)

---

## 2. 거래 단위 정의 (사용자 결재)

- **1 거래 = 1 왕복(round-trip)**: 진입(매수 체결) → 완전 청산(해당 전략·종목 포지션 수량 0 복귀).
  - 예) 오늘 후성 3왕복 = 3거래.
- **수익률(return_pct)** = 청산손익 / 진입금액(cost basis).
- **승/패**: 왕복 손익 > 0 = 승, < 0 = 패 (= 0 은 패에 포함하지 않고 별도 카운트 없이 win/loss 어디에도 안 넣음 — round_trip_count 에는 포함).
- **귀속 날짜**: 왕복이 **완전 청산된 날(close date)** 기준. 며칠 걸친 왕복(월 매수→수 매도)은 수요일에 귀속.

---

## 3. 데이터 모델 (Flyway V85 ~ V87)

> 다음 가용 버전 = **V85** (현재 V84까지 적용). 마이그레이션 1건당 1 관심사로 분리.

### V85 — `strategy_daily_performance` (전략×일자 성과 누적 원장)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| user_id | VARCHAR NOT NULL | |
| strategy_id | BIGINT NOT NULL | |
| trade_date | DATE NOT NULL | KST 영업일 |
| round_trip_count | INT NOT NULL DEFAULT 0 | 그날 청산된 왕복 수 |
| win_count | INT NOT NULL DEFAULT 0 | 왕복 손익 > 0 |
| loss_count | INT NOT NULL DEFAULT 0 | 왕복 손익 < 0 |
| realized_pnl | NUMERIC(20,4) NOT NULL DEFAULT 0 | 청산 왕복 손익 합 (원) |
| total_cost_basis | NUMERIC(20,4) NOT NULL DEFAULT 0 | 청산 왕복 진입금액 합 (원) — 수익률 분모 |
| return_pct | NUMERIC(10,4) | realized_pnl / total_cost_basis × 100 (cost_basis=0 이면 null) |
| benchmark_index_code | VARCHAR | 전략 universe 기준 지수 (0001 코스피 / 1001 코스닥) |
| benchmark_change_pct | NUMERIC(10,4) | 그날 벤치마크 지수 등락률 (snapshot 부재 시 null) |
| alpha_pct | NUMERIC(10,4) | return_pct − benchmark_change_pct (둘 중 null 이면 null) |
| market_regime | VARCHAR | UPTREND / DOWNTREND / SIDEWAYS / HIGH_VOL (지수 부재 시 null) |
| created_at, updated_at | TIMESTAMPTZ | |

- **UQ (user_id, strategy_id, trade_date)** — 배치/백필 멱등 UPSERT.
- INDEX (strategy_id, trade_date DESC) — 히스토리 조회.

### V86 — `market_index_daily` (지수×일자 OHLC 롤업, 전 전략 공유)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| index_code | VARCHAR NOT NULL | 0001(코스피)·1001(코스닥) (v1 2종, 확장 가능) |
| trade_date | DATE NOT NULL | |
| open | NUMERIC(14,2) | 그날 첫 snapshot 가격 |
| high | NUMERIC(14,2) | 그날 max |
| low | NUMERIC(14,2) | 그날 min |
| close | NUMERIC(14,2) | 그날 마지막 snapshot 가격 |
| change_pct | NUMERIC(10,4) | (close − prev_close) / prev_close × 100. 전일 없으면 (close−open)/open |
| range_pct | NUMERIC(10,4) | (high − low) / open × 100 — 변동성 |
| regime | VARCHAR | 본 지수 기준 레짐 (아래 §4) |
| created_at, updated_at | TIMESTAMPTZ | |

- **UQ (index_code, trade_date)** — 멱등.
- 출처: `market_index_snapshot`(V47, 1분) 을 그날치로 집계.

### V87 — `strategy_daily_note` (전략×일자 메모)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| user_id | VARCHAR NOT NULL | |
| strategy_id | BIGINT NOT NULL | |
| trade_date | DATE NOT NULL | |
| note_text | TEXT | 자유 메모 |
| tags | JSONB NOT NULL DEFAULT '[]' | 태그 배열 (추천 + 자유) |
| created_at, updated_at | TIMESTAMPTZ | |

- **UQ (user_id, strategy_id, trade_date)** — 단일 메모 UPSERT(수정 가능).

---

## 4. 왕복 집계 / 레짐 / α 산식

### 4-1. 왕복 집계 (도메인 순수 로직 — strategy-engine)
- 입력: `order_execution`(불변 체결 로그) + `order_request.strategy_id`(전략 귀속).
- (user_id, strategy_id, code)별 체결을 **시간순 replay** (기존 `DailyPerformanceJob`의 평균단가 replay 패턴 확장):
  - 매수 체결 → 누적 수량·평균단가 갱신.
  - 매도 체결 → 청산 수량만큼 (매도가 − 평균단가) × 수량 = 실현손익, 진입금액 = 평균단가 × 청산수량.
  - 포지션 수량이 **0으로 복귀하는 순간 = 왕복 1건 마감** → 그 왕복의 손익·진입금액을 close date 에 귀속.
- 같은 날 여러 왕복은 합산(round_trip_count += , win/loss, realized_pnl, total_cost_basis).
- **롱 온리 정합** (헌법 §13): 매도 = 보유분 청산. 공매도 없음 가정.

### 4-2. 벤치마크 선택
- 전략 `universe_market` (또는 codes 의 상장시장) 으로 결정:
  - 코스피 상장 위주 → `0001`, 코스닥 위주 → `1001`.
  - 혼합/불명 → 기본 `0001`(코스피). `market_index_daily` 에 두 지수 모두 저장되므로 후속 재계산 여지.
- benchmark_change_pct = 해당 지수의 `market_index_daily.change_pct`.

### 4-3. α (초과수익)
- `alpha_pct = return_pct − benchmark_change_pct`.
- 둘 중 하나라도 null(거래 없음 / 지수 snapshot 부재) 이면 null.

### 4-4. 레짐 분류 (룰 기반, AI 호출 0 — market-data)
지수 일봉(`market_index_daily`) 기준, **우선순위 적용**:
1. `range_pct ≥ 2.5%` → **HIGH_VOL** (방향 무관, 변동성 우선)
2. else `change_pct ≥ +0.6%` → **UPTREND**
3. else `change_pct ≤ −0.6%` → **DOWNTREND**
4. else → **SIDEWAYS**

> 임계치(2.5% / ±0.6%)는 `application.yml` 또는 상수로 노출해 추후 튜닝 가능하게 한다. v1 단일일 기준, 다일 추세는 후속.

### 4-5. 신뢰도 (읽기 시 계산 — 저장 안 함)
누적 `strategy_daily_performance` 행에서:
- 표본 = 누적 round_trip_count 합 (및 거래 발생 일수).
- 일별 return_pct 의 평균(mean), 표준편차(std), Sharpe식 = mean / std (std=0 이면 null).
- 라벨:
  - 누적 왕복 < 10 **또는** 거래일수 < 5 → **LOW** ("신뢰도 낮음 N건")
  - 누적 왕복 < 30 → **MEDIUM**
  - 그 외 → **HIGH**
- 표본 부족은 항상 명시(N 표기). mock·임의 합성 금지.

---

## 5. 배치 / 백필

### 5-1. 일배치 확장 — `DailyPerformanceJob` (매일 00:30 KST, T-1)
기존 user 단위 `pnl_daily` 집계에 더해:
1. **지수 일봉 롤업**: 대상일 `market_index_snapshot` → `market_index_daily` UPSERT (open/high/low/close/change_pct/range_pct/regime).
2. **전략별 왕복 집계**: order_execution replay 를 strategy_id 로도 그룹핑 → `strategy_daily_performance` UPSERT (benchmark·alpha·regime 채움).
- 순수 계산(AI/KIS 호출 0), 영속 데이터 reuse, 멱등 UPSERT (ADR 0072 §1-4 정합).

### 5-2. 백필 (1회성, 멱등)
- 범위: **canary 시작(2026-06-11) ~ 어제**.
- 날짜 루프로 5-1 과 동일 로직 재실행. `market_index_snapshot` 보존분이 있는 날만 지수/α/regime 채움(없는 날은 null).
- 운영 안전: 읽기 전용 소스(order_execution·snapshot) → 신규 KIS 호출 0.

---

## 6. 조회 / 메모 API (읽기 전용, 새 KIS 호출 0)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/strategy/{id}/performance/history?from=&to=` | 일자별 성과 행 + 그날 지수 맥락(고/저/종가/regime) |
| GET | `/api/strategy/{id}/performance/summary` | 누적 평균·승률·신뢰도(라벨+표본수) + **레짐별 승률 브레이크다운** + 평균 α |
| GET | `/api/strategy/{id}/note?from=&to=` | 전 기록(일자별 메모 타임라인) |
| PUT | `/api/strategy/{id}/note/{date}` | 당일치 메모·태그 UPSERT (수정 가능) |

- summary 의 레짐별 승률: `GROUP BY market_regime` 로 "횡보장 70% / 추세장 30%" 산출.
- Swagger 4단 어노테이션(컨트롤러/메서드/파라미터/DTO) + 한국어 + example.

---

## 7. UI (frontend) — 전략 목록에서 직접 진입

진입점은 **기존 전략 목록**(`StrategyListPage` → `StrategyList` → `StrategyCard`). 카드 클릭 상세는 현재 없음.

### 7-1. StrategyCard 액션 추가
- 기존 액션 영역(빌더 수정·자동매매 토글·삭제·AI) 옆에 **"📊 성과·메모"** 버튼 1개.
- 클릭 → `StrategyPerformanceModal` (기존 `useModalBehavior` + `BacktestPage`식 탭 패턴 재사용).

### 7-2. `StrategyPerformanceModal` — 탭 2개
- **탭1 "성과 히스토리"** (보는 거)
  - 상단 요약: 누적 평균 수익률 · 승률 · 신뢰도 배지(부족 시 "신뢰도 낮음 N건") · 레짐별 승률.
  - 아래 일자별 목록: 날짜 · 왕복수 · 손익 · 수익률 · α · 그날 지수(코스피·코스닥 고/저/종가) · 레짐 태그.
- **탭2 "메모"** (당일치 입력 + 전 기록)
  - 상단: 당일치 입력칸(자유텍스트 + 태그 선택).
  - 아래: 과거 일자별 메모 타임라인(읽기).

### 7-3. 별도 페이지 (동일 컴포넌트 재사용)
- 라우트 `/strategy/:id/performance` 추가(App.tsx) → 모달 내부와 **동일 컴포넌트를 풀스크린 렌더**. 카드에 "전체화면" 링크. 추가 비용 최소.

### 7-4. 메모 추천 태그 시드 (자유 입력 허용)
`거래량부족` · `변동성과다` · `뉴스이벤트` · `손절잘됨` · `익절놓침` · `추세양호` · `슬리피지큼` · `갭상승` · `갭하락`

### 7-5. 데이터 없음 처리 (feedback_no_mock_data)
- 성과 행 0건 → "아직 청산된 거래가 없습니다" 표시(mock 0). 지수 null 인 날 → "지수 데이터 없음".

---

## 8. 빌드 분담 (orchestrator 경로) + ADR

신규 테이블 3 · 새 배치 책임 · 새 도메인 개념(전략 성과 원장) → **ADR 발행 대상**.

| 영역 | 담당 | 산출 |
|------|------|------|
| ADR + 모듈 경계 | architect | ADR(전략 성과 원장·배치 책임·테이블) |
| 왕복 집계 도메인 로직 + 신뢰도 산식 | strategy-engine | 순수 계산기(domain) |
| 지수 일봉 롤업 + 레짐 분류 | market-data | 지수 데이터 owner |
| 배치 확장 + 백필 잡 | backend-core(batch) | DailyPerformanceJob 확장·1회성 백필 |
| 조회 endpoint + 메모 CRUD | api(backend-core/strategy) | REST + Swagger |
| 화면(모달·페이지·훅) | frontend | StrategyCard 액션·Modal·route |

- 개발 룰 3(한 PR 한 영역·라운드 분할) 준수: R1 도메인+마이그레이션 → R2 배치+영속+API → R3 UI+백필+일괄 검증.
- qa Visual: 전략 목록은 핵심 화면 → 성과·메모 모달 시각 회귀 후속 dispatch.

---

## 9. 리스크 / 검증

- **데이터 정합**: 전략별 왕복 합 ↔ 기존 user `pnl_daily` 합 교차검증(전략 귀속 누락분 = MANUAL strategy_id 0 분리).
- **멱등성**: 배치·백필 재실행 시 UPSERT 로 중복 0.
- **운영 안전**: 모든 경로 읽기 전용·새 KIS 호출 0 (canary 운영 영향 0).
- **백필 검증**: 6/11~어제 실행 후 후성 왕복이 실제 행으로 채워졌는지 DB 실증(할루시네이션 금지).
- **롱 온리**: 매도=청산 가정이 깨지는 데이터(공매도) 없음 확인.
