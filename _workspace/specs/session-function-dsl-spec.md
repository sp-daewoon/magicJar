# spec — 세션함수 DSL 확장 (당일 VWAP · ORB · time-of-day RVOL) 계약 초안

- **상태**: Draft 계약 초안 (R-later — 본 wave 구현 아님)
- **작성**: architect · 2026-06-10
- **연관**: ADR 0121 §4(위임) · ADR 0117(단타 발굴) · `daytrade-preset-research.md` §2-3(일봉 표현 불가 목록) · ADR 0092(strategy-engine) · ADR 0083(indicator-impl)
- **권위**: 본 문서는 **식별자·시그니처·평가 컨텍스트·데이터 요구·계산 위치 계약 초안**이다. 구현 라운드·테스트는 **별도 wave**(이번 단타 사이징 wave 아님). 구현 시 strategy-engine + indicator-impl 협업으로 정밀화.

---

## §1. 배경 — 왜 일봉 DSL로 표현 불가한가

`daytrade-preset-research.md` §2-3은 3종을 "일봉 표현 불가"로 LOCK했다:

| 함수 | 불가 사유(원문) |
|---|---|
| VWAP(세션 누적) | 일봉 1봉에 세션 내 누적 정보 없음. `vwap` 식별자는 존재하나 일봉에서는 단일봉 근사라 세션 VWAP 의미 상실 |
| ORB(개장 N분 박스 돌파) | 분봉/세션 함수 필요. 일봉 표현 불가 |
| time-of-day RVOL(시간대별 누적 거래량) | 세션 내 시각 정보 필요. 일봉 불가 |

공통 근본 원인: 이 3종은 **"당일 장 시작부터 현재 분까지의 세션-누적/세션-구간"** 정보를 요구한다. 현 DSL 평가 컨텍스트(`IndicatorSource.calculate(spec, candles: List<Candle>)`)는 봉 시퀀스를 받지만 **세션 경계(개장/마감)·당일 리셋·시각(time-of-day)** 개념이 없다. 분봉 시퀀스를 넣어도 "오늘 09:00부터의 누적"을 구분할 메타가 없다.

---

## §2. 평가 컨텍스트 계약 (핵심 — 세션 인지 평가)

세션함수는 일반 indicator와 **다른 입력 계약**이 필요하다. 두 옵션:

- **옵션 A (채택 권장)**: `SessionContext`를 평가에 add-only 주입. 분봉 슬라이스 + 세션 경계 메타(`sessionOpen: LocalTime`, `sessionClose: LocalTime`, `currentBarTime: LocalDateTime`, `tradingDate: LocalDate`)를 담는다. indicator-impl 세션함수가 이를 읽어 "오늘 세션 시작봉 ~ 현재봉"을 슬라이스.
- **옵션 B (거부)**: 봉에 isSessionStart 플래그만 추가 — 누적 리셋 경계는 잡으나 time-of-day(특정 시각까지 누적) 표현 못 함.

**계약 (옵션 A — 초안)**:

```
SessionContext (신규 · domain/market/candle 또는 strategy DSL 평가 입력 add-only)
├─ tradingDate: LocalDate        // 당일(KST). 세션 누적 리셋 경계
├─ sessionOpen: LocalTime        // 정규장 개장(09:00 KST) — 시장 캘린더 정합(ADR 0104)
├─ sessionClose: LocalTime       // 정규장 마감(15:30 KST)
├─ currentBarTime: LocalDateTime // 평가 시점 봉의 KST 시각 — time-of-day 기준
└─ intradayBars: List<Candle>    // 당일 sessionOpen ~ currentBar 까지의 분봉(누적 구간)
```

- **당일 리셋 LOCK**: `intradayBars`는 매 거래일 sessionOpen에서 시작 — 전일 분봉 누적 금지(세션 VWAP·time-of-day RVOL의 핵심).
- **시장 캘린더 정합**: sessionOpen/Close·휴장은 ADR 0104(market-calendar) 권위 재사용 — 본 spec 신설 0.
- **분봉 인터벌**: 1m 권장(VWAP/ORB 정밀). 5m도 허용하되 ORB 박스 시작 N분 < 인터벌이면 정의 불가(가드).

---

## §3. 식별자 + 시그니처 계약 (DSL 화이트리스트 add-only)

| 식별자 | 시그니처 | 의미 | 평가식 예 |
|---|---|---|---|
| `session_vwap()` | 인자 0 | 당일 세션 거래량가중평균가 = Σ(typical_price × volume) / Σ(volume), typical=(H+L+C)/3, 당일 sessionOpen~현재봉 누적 | `close > session_vwap()` (VWAP 상회 진입) |
| `orb_high(n)` | n=시초 분 수(예 30) | 당일 sessionOpen부터 n분간 형성된 박스의 고가(opening range high) | `close > orb_high(30)` (시초 30분 박스 상단 돌파) |
| `orb_low(n)` | n=시초 분 수 | 당일 opening range 저가 | `close < orb_low(30)` (하단 이탈 — 숏 가설/필터) |
| `tod_rvol(n)` | n=비교 기준일 수(예 20) | time-of-day RVOL = 당일 sessionOpen~현재시각 누적거래량 / 과거 n일 동일시각까지 누적거래량 평균 | `tod_rvol(20) > 2.0` (해당 시각 평소 대비 2배 거래) |

- **반환 타입**: 모두 `BigDecimal`(가격·비율) — 기존 indicator 반환 계약 정합.
- **결손 처리 (graceful LOCK)**: 분봉 미적재·세션 미시작·박스 형성 전(`currentBarTime < sessionOpen + n분`) → 함수는 **null 반환 → 비교식 false**(throw 0 · ADR 0117 graceful 정합). 백테스트·실시간 동일.
- **화이트리스트 등록**: `entry_dsl` 화이트리스트(ADR 0117 §entry_dsl)에 4 식별자 add-only.

---

## §4. 데이터 요구

| 함수 | 필요 데이터 | 현 적재 상태(2026-06-10) |
|---|---|---|
| `session_vwap()` | 당일 1m 분봉(OHLCV) sessionOpen~현재 | 분봉 전수 미적재 — 일봉만 완비(ADR 0117 §결정1·0118·0119) |
| `orb_high/low(n)` | 당일 1m 분봉 sessionOpen~+n분 | 동일 |
| `tod_rvol(n)` | 당일 + 과거 n일 1m 분봉(동일 시각 누적) | 동일 — 과거 n일치 분봉 보존 필요(분봉 retention — ADR 0105·0113 tiering 정합 검토) |

**핵심 제약 (LOCK)**: 3종 전부 **분봉 적재 의존**. 현 시스템은 일봉 전수 + 분봉 best-effort(ADR 0117). 따라서:
- **실시간(consumer)**: KIS WS tick → 1m 캔들 집계 경로에서 `intradayBars` 구성 가능(장중 권위 = WS tick). 당일 세션 누적은 실시간 경로의 자연스러운 대상.
- **백테스트**: 과거 분봉 적재 필요 → **전 종목 분봉 backfill 선행 의무**. 일봉 단일진입 근사(V77 방식)로는 세션함수 검증 불가. `daytrade-preset-research.md` §6 "분봉 재검증" 후보와 동일 선결 조건. → **세션함수 백테스트는 분봉 적재 완료 후 별도 wave**.

---

## §5. 계산 위치 (모듈 경계)

| 함수 | 계산 위치 | 사유 |
|---|---|---|
| `session_vwap` · `orb_high/low` · `tod_rvol` | **indicator-impl**(ADR 0083) | 기존 indicator(sma·rsi·atr·donchian)와 동일 — `IndicatorSource.calculate` 패턴. 단 `SessionContext` 입력 확장 필요(§2) |
| `SessionContext` 구성 | **호출 어댑터**(실시간=consumer WS 집계 · 백테스트=api 엔진) | 세션 경계·당일 슬라이스 조립은 데이터 소스를 아는 어댑터 책임. indicator-impl은 순수 계산만 |

**모듈 경계 정합 (ADR 0083·0092)**: indicator-impl은 순수 계산 lib, strategy-engine은 DSL parser/evaluator, domain은 `SessionContext` 타입(Spring/JPA import 0). 세션함수 도입은 `IndicatorSource` 인터페이스에 `SessionContext?` 옵셔널 파라미터 add-only(기존 indicator 무영향 — null이면 세션 무관 함수)로 평가 — 구현 wave에서 인터페이스 변경 영향도 정밀 재검토 의무.

---

## §6. 구현 wave 선결 조건 (LOCK — 본 wave 아님)

1. **전 종목 분봉(1m) backfill** — 세 함수 모두 분봉 의존. 일봉만으로 검증·실시간 불가.
2. **분봉 retention 정책** — `tod_rvol(n)`은 과거 n일 분봉 보존 필요. ADR 0105·0113 tiering과 정합 검토.
3. **SessionContext 평가 컨텍스트** — `IndicatorSource` 입력 확장 + 실시간/백테스트 어댑터 양쪽 조립. 인터페이스 변경 영향도(ADR 0083 indicator-impl 공유 — api·consumer 양쪽) 재검토.
4. **백테스트 세션 시뮬** — 일봉 단일진입 근사 → 분봉 intrabar 진입/청산으로 엔진 평가 슬라이스 변경(ADR 0056 코어 영향 재검토 — 별도 ADR 가능성).

→ 위 선결이 갖춰진 별도 wave에서 본 spec을 구현 spec으로 승격. **단타 사이징 정합 wave(ADR 0121)와 독립**.
