# ADR 0063 — NXT/통합거래 매트릭스 cover (분봉·일봉 시장 분류 코드 결정)

- 상태: **Proposed** (사용자 결정 대기 — 채택 후 Accepted 갱신)
- 작성일: 2026-05-11
- 결정자: architect (사용자 dogfooding 의제 발의 — 2026-05-11)
- 관계: ADR 0019 (캔들 저장 정책) · ADR 0028 (Watchlist Ingestion) · ADR 0029 (당일 진행 중 캔들 Redis) · ADR 0037 (KIS Rate Limit) · ADR 0057 (KIS-only 데이터 출처) · backlog §32 (본 트랙 후속)

> 본 ADR은 **plan 단계 (Proposed)** — 결정 의제 3건을 사용자에게 제시하고 채택안 확정 후 후속 사이클에서 sub-agent 디스패치. **add-only 신규 파일**로 발행하며, ADR 0019 §2 매트릭스 본문 갱신은 채택 후 별도 사이클로 분리 (PR #298 / ADR 0062 합산 트랙과의 modify-shared 충돌 회피).

## 1. 컨텍스트

### 1-1. NXT (Next-Trading Korea Exchange) 도입

NXT는 한국거래소 KRX의 **대체 거래소** (2025년 출범). KOSPI 200 등 KRX 정규 상장 대형주를 NXT에서도 **연장된 시간대**로 거래 가능:

- **NXT pre-hour**: 08:00 ~ 09:00 KST (KRX 정규장 시작 전)
- **NXT main-hour**: 09:00 ~ 15:30 KST (KRX 정규장과 동일)
- **NXT after-hour**: 15:30 ~ 20:00 KST (KRX 정규장 마감 후)

대표 종목: 005930(삼성전자) · 000660(SK하이닉스) · LG에너지솔루션 등 KOSPI 200 대형주 일반.

### 1-2. 사용자 의제 발의 (2026-05-11)

사용자(최고 관리자) dogfooding 중 명시:

> "삼성전자나 sk하이닉스 같은경우는 정상장 시간보다 빨리시작하고 늦게까지 장이 열리잖아? NXT 8시작 하고 밤에 끝나는거 그런건 감안 안되있는거같은데?"

→ 현재 magicJar 캔들 수집 매트릭스가 **KRX 정규장 시간(09:00~15:30)만 cover** — NXT pre-hour + after-hour 거래 데이터 누락. 자동매매 본체의 시그널·룰엔진·백테스트가 NXT 시간대 가격 변동을 인지 못함.

### 1-3. 결함 evidence (orchestrator 사전 검증)

**(a) 코드 검증** — `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt`:

| 위치 | 현재 값 | 결함 |
|------|--------|------|
| L170 `fetchOneMinCandles` | `fid_cond_mrkt_div_code="J"` (KRX 고정) | NXT 시간대 응답 X |
| L380 `fetchDailyCandles` | `fid_cond_mrkt_div_code="J"` (KRX 고정) | 일봉도 KRX만 |
| L152 페이지네이션 시작 | `currentHour="153000"` (KRX 장마감) | NXT after-hour(15:30~20:00) 미cover |
| L243 일자 종료 조건 | `< 09:00 KST` | NXT pre-hour(08:00~09:00) 미cover |
| `CandleBootstrapRunner` L46 | `MIN_1M_CANDLE_THRESHOLD=350` | KRX 정규장 ~390건 기준. NXT cover 시 ~720건 필요 |

**(b) DB 실측** — 000660·005930 (KOSPI 200, NXT 거래 대상):

```
code   | 1m min_kst | 1m max_kst | 당일 count
-------+-----------+-----------+-----------
000660 | 09:00     | 15:30     | 381
005930 | 09:00     | 15:30     | 381
```

→ KRX 정규장만 적재. NXT pre-hour + after-hour 거래 누락 확정.

### 1-4. KIS API spec (헌법 §3 raw fetch)

raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`

```python
fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:KRX, NX:NXT, UN:통합)
```

동일 endpoint family의 일봉 `inquire_daily_itemchartprice.py`도 J/NX/UN 3코드 옵션 보유. KIS는 시장 분류 코드 분기로 동일 종목 코드를 KRX 단독·NXT 단독·통합 응답 3가지로 분리 제공.

### 1-5. magicJar 운영 자료 점검

- ADR 0019·0028·0029·0057 — **NXT 인용 0건**
- `docs/architecture/external-api-policy.md` — NXT 인용 0건
- `docs/architecture/candle-data-policy-v2.md` — NXT 인용 0건
- `instrument` 테이블 — NXT 거래 가능 종목 식별 컬럼 부재 (간접 추정: `is_kospi200=t` 등)

본 ADR은 magicJar에서 NXT를 **명시적으로 인식**하는 첫 결정.

## 2. 결정 의제 (사용자 결정 3건)

### 의제 A. 시장 분류 코드 cover

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **A. UN 통합** (architect 권장) | `fid_cond_mrkt_div_code="UN"` 단일 호출 → KIS가 KRX+NXT 통합 응답 반환 | 종목별 시장 분기 불요 · instrument 컬럼 신설 회피 · 코드 변경 최소(쿼리파라미터 + 시간 상수만) · NXT 미거래 종목은 KRX 단독 응답으로 자동 호환 | UN 응답 sample 검증 부족 (NXT 미거래 종목 시 응답 형식 변동 가능성) · KIS 부하 영향 측정 미흡 |
| B. NX 분기 + instrument 컬럼 신설 | 종목별 `nxt_tradable` 컬럼 + 분기 호출 (NX 종목 = NX 호출, 그 외 = J 호출) | NXT 비대상 종목의 KIS 호출 최적화 · 명시적 데이터 분리 | instrument 마이그레이션 추가 · 종목 마스터 sync에서 NXT 거래 가능 정보 별도 수집 의무 · KIS 종목 마스터에 NXT 정보 포함 여부 확인 필요 |
| C. KRX 단일 유지 (현행) | `fid_cond_mrkt_div_code="J"` 그대로 | 변경 0 | 사용자 의제 미해결 — NXT pre/after-hour 누락 지속 |

**architect 권장 = 옵션 A (UN 통합)**. 사유:
- 자동매매 본체 fitness 정합 — NXT 시간대 가격 변동이 시그널·룰엔진·백테스트에 필수
- 단순함 — 추가 마이그레이션·sync 부담 0
- KIS-first 정합 — KIS가 통합 모드 제공 → magicJar는 그대로 활용
- 옵션 B는 NXT 비대상 종목 비율 측정 후 KIS 호출량 부담 발견 시 후속 ADR로 separate (현재는 미측정)

### 의제 B. 시간 범위 cover

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **1. 08:00~20:00 명시** (architect 권장) | `fetchOneMinCandles` 시작 hour `200000`, 종료 조건 `< 08:00 KST` | 명시적 cover · 페이지 한도 계산 정밀(`maxPagesPerDay` 산정 정확) · NXT 운영시간 운영 가이드 명확 | NXT 운영시간 변경(예: 21:00로 연장) 시 코드 수정 의무 |
| 2. KIS 응답에 맡김 | 시작 hour `235959`, 종료 조건 = 응답 0건 또는 maxPages 초과 | 운영시간 변경 자동 호환 · NXT 외 향후 새 시장 도입 시도 호환 | 페이지 한도 산정 어려움 · 무의미 호출 페이지 1개 가능 · 디버깅 시 NXT 시간대 인지 어려움 |

**architect 권장 = 옵션 1 (08:00~20:00 명시)**. 사유:
- 현재 NXT 운영시간 공식 명시 = 08:00~20:00 — 가까운 미래에 변경 가능성 낮음
- 페이지 한도 산정 정밀 (12시간 × 60분 = 720건 / 120 = 6 페이지 + 마진 2 = 8 페이지)
- 디버깅 시 NXT 시간대 인지 가능 (`200000` 시작 hour 로그가 명확)

### 의제 C. threshold 갱신

| 옵션 | 동작 | 사유 |
|---|---|---|
| **350 → 700** (architect 권장) | `MIN_1M_CANDLE_THRESHOLD = 700L` | 12시간 × 60분 = 720건 cover. 700 = 720의 97% (페이지 누락 또는 거래 비활성 시간 대비 마진) |
| 350 유지 (현행) | `MIN_1M_CANDLE_THRESHOLD = 350L` | KRX 정규장 ~390건 기준. NXT cover 시 부팅 시 재트리거 안 됨 (1m 데이터가 700건 미만이어도 cold-start 안 함) |

**architect 권장 = 700 (옵션 1과 정합)**. 사유:
- 옵션 A·1 채택 시 1m count 자연 증가 → threshold 동반 상향 의무
- 700 < 720 마진 — pre-hour·after-hour 거래 비활성 시간(예: 19:30~20:00 거래량 0) 대비

## 3. fitness gap 분석 (`feedback_v3_skepticism_no_regression.md` lens)

| 질문 | 답변 |
|---|---|
| 자동매매에 필요한가? | **필요**. NXT 거래 종목(KOSPI 200 대형주)의 pre-hour 시초 + after-hour 시세는 룰 기반 시그널·백테스트·자동매매 본체에 필수. 사용자 의제 발의의 기반 |
| v1/v2 잔재 아닌가? | **잔재 아님**. NXT는 2025년 출범 — v1/v2 시점에 미존재. 본 ADR은 신규 시장 cover 결정 |
| AI 매매 결정 0건 강제 위반 X? | 0건 (룰 5). 본 ADR은 데이터 수집 매트릭스 결정 only — LLM provider import 0건 |
| KIS-first 정합? | 정합. `fid_cond_mrkt_div_code` 옵션은 KIS 공식 raw fetch 인용. 자체 추론 0건 |
| 정보 수집 prod credential 단일? | 정합 (`feedback_kis_key_realonly_for_data.md`). NXT 호출도 prod credential 단일 채널 사용 |

## 4. 운영 영향

### 4-1. KIS Rate Limit (ADR 0037)

| 항목 | 현재 (KRX) | 옵션 A·1 채택 후 (UN+08:00~20:00) | 변동 |
|---|---|---|---|
| 일자당 1m 페이지 호출 수 | 최대 6 pages | 최대 8 pages | +2 pages |
| 종목당 cold-start 호출 (30일) | ~180 calls | ~240 calls | +33% |
| 11종목 cold-start 총 호출 | ~1,980 calls | ~2,640 calls | +33% |
| ADR 0037 한도 (초당 10 TPS) | 안전 | 안전 (50ms 마진 유지) | 변동 없음 |

→ 한도 안. backlog §32 후속 sub-agent 작업 시 실측 검증 권장.

### 4-2. DB 영속 (ADR 0019 §2 / 0057 정합)

> 본 ADR은 ADR 0019 §2 매트릭스 본문 갱신 금지. 채택 후 별도 사이클에서 매트릭스 갱신 (modify-shared 충돌 회피).

현재 1m은 ADR 0057 §2-1 정합 **Redis only (당일 한정)** — DB 영속 X. NXT cover로 1m count 2배 증가하나 Redis만 영향:

| 항목 | 현재 (KRX) | 옵션 A·1 채택 후 (UN+08:00~20:00) | 변동 |
|---|---|---|---|
| Redis 1m 종목당 row | ~381/일 | ~720/일 | +89% |
| 풀 30종목 Redis 총 | ~11,430 rows | ~21,600 rows | +89% |
| Redis 메모리 (rough) | ~5 MB | ~10 MB | +5 MB |

→ Redis 한도(8GB host) 안전.

### 4-3. cold-start 시간

종목당 KIS 1m fetch (30 거래일 × 8 page × 50ms 마진) ≈ **12초** (현재 ~9초의 33% 증가).
풀 11종목 cold-start ≈ **2분 12초** (현재 ~1분 39초).

부팅 시 backlog 처리 시간 증가하나 운영 영향 미미 (백그라운드 작업).

### 4-4. 일봉 cover (옵션 A 채택 시)

`fetchDailyCandles`는 단일 호출로 100건 응답. `fid_cond_mrkt_div_code="UN"` 시 KIS 일봉 응답은 **단일 거래일 종가** (NXT/KRX 통합 종가 또는 KRX 종가 단독 — KIS 응답 sample 검증 필요).

→ backlog §32 sub-agent 작업 시 KIS MCP 또는 raw fetch로 일봉 UN 응답 sample 검증 의무. 본 ADR 시점에 추정 0건 — Proposed.

## 5. rollback 시나리오

채택 후 결함 발생 시:

1. **KIS Rate Limit 위반 (EGW00201/429 지속)**: `fid_cond_mrkt_div_code` 단일 line revert (`UN` → `J`) + threshold revert (700 → 350) + 시작 hour revert (`200000` → `153000`) + 종료 조건 revert (`< 08:00` → `< 09:00`)
2. **NXT UN 응답 sample 형식 불일치**: 동일 single-line revert + 옵션 B로 재진입 검토 (`nxt_tradable` 컬럼 신설 사이클)
3. **DB 또는 Redis 메모리 부족**: 풀 size 축소 또는 옵션 B 분기로 NXT 비대상 종목 KRX 단일 유지

revert는 모두 `git revert <commit>` single-line operation으로 가능 (add-only 컴포넌트 없음).

## 6. 영향 ADR/문서 매트릭스 (채택 후 별도 사이클 갱신)

본 plan PR에서는 **add-only**. 본문 갱신은 채택 후 분리 사이클:

| 문서 | 갱신 영역 | 사이클 |
|---|---|---|
| ADR 0019 §2-1·§2-2 매트릭스 | NXT 행 추가 / `fid_cond_mrkt_div_code` 옵션 명시 | 채택 후 architect 사이클 (PR #298 / ADR 0062 머지 후) |
| ADR 0028 §X cold-start 흐름 | NXT 시간 cover 반영 | 채택 후 architect 사이클 |
| ADR 0029 §2-4 당일 캔들 | 시간대 cover 확장 인용 | 채택 후 architect 사이클 (영향 작음) |
| ADR 0057 §2-5 | NXT cover 인용 추가 (영향 작음) | 채택 후 architect 사이클 |
| `docs/architecture/external-api-policy.md` §2-5 | NXT/통합 매트릭스 추가 | 채택 후 architect 사이클 |
| `docs/architecture/candle-data-policy-v2.md` §3·§5 | 시각 매트릭스 갱신 | 채택 후 architect 사이클 |
| `consumer/.../KisRestCandleAdapter.kt` L170·L380·L152·L243 | 시장 분류·시간 상수 변경 | 채택 후 market-data 사이클 |
| `consumer/.../CandleBootstrapRunner.kt` L46 | threshold 350 → 700 | 채택 후 market-data 사이클 |
| `instrument` 테이블 | (옵션 B 채택 시만) `nxt_tradable` 컬럼 신설 ADR 별도 분리 | 옵션 B 채택 시 |
| `frontend/.../IntervalToggle` | NXT 시각 표기 (현재 9:00~15:30 가정 UI) | 채택 후 frontend 사이클 (선택) |

## 7. 결과 (예상 — 옵션 A·1·700 채택 후)

- NXT 거래 종목(KOSPI 200 대형주)의 pre-hour + after-hour 시세가 시그널·룰엔진·백테스트에 진입
- 자동매매 본체가 NXT 시간대 가격 변동을 인지 → 룰 기반 트레이딩 fitness 보완
- KIS 호출량 종목당 +33% — ADR 0037 한도 안전
- DB 영속 영향 0 (1m은 Redis only) · Redis 메모리 +5 MB 수준

## 8. 절충

- KIS 호출량 +33% — 향후 풀 size 100+로 확장 시 Rate Limit 영향 측정 의무 (backlog §32 후속)
- NXT 미거래 종목(소형주 등)의 UN 호출이 KRX 단독 응답을 반환하는지 sample 검증 필요 (KIS MCP 추가 검증 권장)
- 사용자 차트 UI가 NXT 시간대 분봉을 표시 시 X축 범위 09:00~15:30 가정의 LWC 설정 점검 필요 (frontend 사이클 후속)

## 9. 결정 후 트리거

본 ADR이 사용자에 의해 Accepted로 갱신되면 backlog §32 트랙 진입:

1. **market-data sub-agent**: `KisRestCandleAdapter` 4 line revert + 재수집 + DB·Redis evidence 보고 + CHANGELOG slot
2. **architect sub-agent**: ADR 0019·0028 §2 매트릭스 NXT 행 보강 + 운영 정책 문서 갱신 + ADR 본 status `Accepted` 갱신
3. **frontend sub-agent** (선택): `IntervalToggle` NXT 시각 표기 + LWC X축 범위 점검
4. **qa sub-agent** (선택): NXT 시각 분봉 sample 검증 + 시그널 엔진 NXT 시간대 backtest 검증

## 10. 참고

- 사용자 의제 발의 (2026-05-11 dogfooding) — 인용 §1-2
- KIS raw fetch — §1-4 raw URL
- ADR 0057 — KIS-only 데이터 출처 (NXT 호출도 prod credential 단일 채널)
- ADR 0037 — KIS Rate Limit (NXT cover로 +33% 영향 측정)
- ADR 0019 — 캔들 저장 정책 (본 ADR 채택 후 §2 매트릭스 NXT 행 보강 의무)
- backlog §32 — 본 트랙 후속
- 사용자 메모리 `feedback_kis_first_policy.md`·`feedback_kis_key_realonly_for_data.md`·`feedback_v3_skepticism_no_regression.md`·`feedback_spec_plan_scope.md`·`feedback_3tier_hierarchy.md`
