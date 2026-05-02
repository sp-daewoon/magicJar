# ADR 0037: Instrument Master 1급 시민 (KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.1
- 트랙: T3 (W1)
- 선행: ADR 0031

---

## 1. 맥락

magicJar는 현재 일부 종목(005930·000660·035720·005380·051910)을 시드로 운영 중이다. 사용자 비전 정정(2026-05-02): "기본 오프라인으로 마스터 데이터만으로도 종목이 추출 가능한 시스템이 기본 목적". universe가 5종목이면 screener·signal 흐름의 가치 0.

종목 마스터는 **1급 시민** — 모든 트랙(T4 corp action·T5 indicator·T7 DSL·T10 screener·T11 signal)의 전제다.

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
| `instrument` | 위 fields + `created_at`·`updated_at` | V12 |
| `instrument_alias` | code·alias_code·alias_source ("KRX"·"NAVER"·"FNGUIDE") | V12 |

상장폐지 종목 보존 의무 — **생존자 편향 차단** (master §3-5).

### 2-3. Sync 흐름

`InstrumentSyncJob` (cron 17:00):

1. KIS REST `domestic-stock/v1/quotations/inquire-symbol` (또는 KRX 공식 zip 다운로드 — KIS는 endpoint 한정적이라 KRX 보조)
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
| mock | 정적 시드 (mock seed — 5~50종목) |
| dev | KIS sync (모의 endpoint) + 실패 시 정적 시드 fallback |
| prd | KIS + KRX 동시 sync, 충돌 시 KRX 우선 |

## 3. 결과·영향

- T4 (corp action), T5 (indicator), T7 (DSL), T10 (screener), T11 (signal) 모두 본 ADR의 instrument 테이블을 직접 의존.
- **상장폐지 종목 보존**으로 백테스트 시점 universe 정확 — 룩어헤드 차단 (ADR 0038과 결합).
- 일별 sync 실패 시 마지막 성공 데이터 유지 + 알림 (post-MVP).

## 4. 검토 대안

- **(a) 종목 마스터 외부 의존** (KRX 매번 호출) — 부하·세션 비용. 거부.
- **(b) 정적 CSV 시드 영속만** — universe 변경 추적 불가. 거부.
- **(c) 일별 sync + Redis 캐시 + 정적 시드 fallback (채택)** — 오프라인 동작 + 갱신 정합.

## 5. References

- master-architecture.md §A.1, §6-2
- ADR 0031 (오프라인 1순위)
- ADR 0038 (Corp Action — instrument 의존)
- KRX 종목 발행 zip: https://kind.krx.co.kr/
- KIS open-trading-api `genfile_*.py` (참조용)
