# ADR 0017 — Instrument Master 1급 시민 (KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주)

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0001(비전), ADR 0018(Corp Action), ADR 0039(종목 마스터 갱신 cron)

## 1. 맥락

magicJar의 모든 트랙(corp action·indicator·DSL·screener·signal·backtest)은 종목 마스터에 의존. 종목 마스터는 **1급 시민** — 사용자 검색·자동완성·universe 정의의 base.

오프라인 동작 1순위 정책(ADR 0001) 정합 — 마스터 데이터만으로도 종목 추출이 가능해야 한다.

## 2. 결정

### 2-1. 도메인 모델

```kotlin
// domain/.../instrument/Instrument.kt
data class Instrument(
    val code: String,            // KIS code (6자리)
    val isin: String?,           // 국제 표준 ID
    val name: String,            // 한글명
    val nameEn: String?,         // 영문명
    val market: InstrumentMarket,// KOSPI · KOSDAQ · KONEX
    val type: InstrumentType,    // STOCK · ETF · ETN · PREFERRED · WARRANT
    val sector: String?,         // 업종 (KRX 분류)
    val listedAt: LocalDate,
    val delistedAt: LocalDate?,  // null = 상장 중
    val parValue: BigDecimal?,
    val faceValue: BigDecimal?,
    val active: Boolean,         // delisted 또는 거래정지 시 false
)

enum class InstrumentMarket { KOSPI, KOSDAQ, KONEX }
enum class InstrumentType { STOCK, ETF, ETN, PREFERRED, WARRANT, REIT }
```

### 2-2. 영속

| 테이블 | 컬럼 | Flyway |
|---|---|---|
| `instrument` | 위 fields + `created_at`·`updated_at` | V1 베이스라인 |
| `instrument_alias` | code·alias_code·alias_source ("KRX"·"NAVER"·"FNGUIDE") | V1 |

**상장폐지 종목 보존 의무** — 생존자 편향 차단 (백테스트 정확성).

### 2-3. Sync 메커니즘

KIS 종목 마스터 갱신 시각(13시각 +5분)에 `InstrumentMasterSyncScheduler`가 sync. 상세 — **ADR 0039 (종목 마스터 갱신 Cron)**.

흐름 요약:
1. KIS REST 종목 마스터 endpoint 호출 (Rate Limiter 통과, ADR 0037)
2. 응답 parse → `instrument` upsert (delistedAt 변경 감지)
3. Kafka `instrument.updated.v1` publish (변경된 것만)
4. consumer가 Redis 검색 인덱스 invalidate

### 2-4. 검색 인덱스

Redis:
- `instrument:search:{prefix}` — 한글 prefix tree (set + sorted set)
- `instrument:list:{market}` — universe 캐시 (TTL 1일)
- `instrument:by-code:{code}` — 단일 조회 캐시 (TTL 1일)

frontend `InstrumentSearch.tsx` 자동완성에서 사용.

### 2-5. Profile 분기

| profile | source |
|---|---|
| mock | 정적 시드 (5~50종목) |
| dev | KIS sync (모의 endpoint) + 실패 시 정적 시드 fallback |
| prd | KIS sync + KRX 보조 sync, 충돌 시 KRX 우선 |

## 3. 결과·영향

- corp action(ADR 0018) · indicator(ADR 0021) · DSL(ADR 0022) · screener · signal · backtest 모두 본 ADR의 instrument 테이블 직접 의존
- **상장폐지 종목 보존**으로 백테스트 시점 universe 정확 — 룩어헤드 차단 (ADR 0018과 결합)
- 일별 sync 실패 시 마지막 성공 데이터 유지 + 알림 (post-MVP)

## 4. 참고

- ADR 0001 — 시스템 비전 (오프라인 1순위)
- ADR 0018 — Corp Action raw + adjusted (instrument 의존)
- ADR 0039 — 종목 마스터 갱신 Cron (KIS 13시각 +5분)
- ADR 0037 — KIS Rate Limit (sync 호출 시)
- KRX 종목 발행 zip: https://kind.krx.co.kr/
