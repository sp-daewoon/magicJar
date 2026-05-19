# ADR 0090: `instrument_status_history` 테이블 신설 — chart-curator §12 4축 active gap detection 정밀도 향상

- 상태: **Proposed (2026-05-19, Phase 1 market-data + Phase 2 chart-curator 합작 PR 후 Accepted 격상 예정)**
- 작성일: 2026-05-19
- 결정자: architect (사용자 위임 — chart-curator PR #554 P2-1·P2-2 evidence 정합)
- 영역: 시장 데이터·외부 API + 캔들 정합성 (§5 + §B)
- 관련: ADR 0013(Market Session FSM) · ADR 0017(Instrument Master 1급 시민) · ADR 0019(캔들 저장 정책) · ADR 0028(Watchlist Ingestion Worker) · ADR 0085(chart-curator 영입) · ADR 0086(T-CC Phase 2 데이터 햇징 13 룰)

## 1. 맥락

### 1-1. chart-curator PR #554 P2-1 evidence — `instrument_status_history` 부재

chart-curator sub-agent system audit(PR #554) 2026-05-19 발굴 — ADR 0086 §12 active gap detection 4축 교차 검증에서 axis 2(거래정지)·axis 3(정리매매)이 **시점 부재 한계** 보유:

**§12 4축 매트릭스**:
| axis | 권위 source | 부재 시 결함 |
|------|-------------|--------------|
| 1. `market_calendar.is_business_day=TRUE` | `business_day_calendar` (ADR 0013) | — (정합) |
| 2. `instrument.trading_halt=FALSE` 또는 history 부재 | `instrument` 단일 row(현재 상태) **★ 시점 부재** | 과거 시점 거래정지 history 없음 → 갭이 stale data인지 정상 휴장인지 분기 0 |
| 3. `instrument.liquidation=FALSE` | `instrument` 단일 row(현재 상태) **★ 시점 부재** | 동일 |
| 4. `instrument.listed_at <= ts` | `instrument.listed_at` (상장일 권위) | — (정합) |

axis 2·3은 **현재 상태 단일 row**만 보유 → 과거 시점 status 변화 추적 불가 → §12 active gap detection이 "이전 5개월 동안 거래정지였을 수도 있다" lens 부재 → false positive(정상 휴장을 결함으로 오인) 또는 false negative(실 결함을 거래정지로 오인) risk.

### 1-2. PR #535 한국석유(004090) dogfooding cycle evidence cross-ref

PR #535·#537·#540 한국석유 closure 사이클에서 — 일봉 2025-12-24 ~ 2026-05-18 ≈27 영업일 갭 검출 시 chart-curator가 §12 4축 평가 시도:
- axis 1 (market_calendar): 27 영업일 모두 TRUE
- axis 2 (`instrument.trading_halt=FALSE` 현재 상태): TRUE
- axis 3 (`instrument.liquidation=FALSE` 현재 상태): TRUE
- axis 4 (`instrument.listed_at <= 2025-12-24`): TRUE

→ 4축 모두 TRUE → "명확한 결함" 판정 GREEN. 그러나 만약 2025-12-25 ~ 2026-01-15 동안 KIS 측에서 거래정지가 있었다면(가정), axis 2 현재 상태 FALSE는 정합하지만 과거 정지 구간은 false positive 결함으로 오인. → 실제 한국석유는 정지 이력 없는 종목이라 GREEN 결과 정합이었으나, **다른 종목에 본 룰 발동 시 false positive risk 잠재**.

### 1-3. fitness gap 검증

`feedback_v3_skepticism_no_regression` lens — 자동매매 본체 필수 lens:
- v1/v2 잔재 아님 — v3.0.0 베이스라인 컷 후 신규 발굴 결함 (chart-curator 영입 ADR 0085 시점에 §12 4축 발굴됨)
- 자동매매 필수성 — 캔들 갭 검출 정밀도가 자동매매 룰 본체 직결(거래정지 종목 매수 시그널 발사 시 자본 손실 risk)
- MVP 적합성 — 본 ADR 채택 시 일회성 backfill(history 부재 시 default NORMAL 추정) + 신규 KIS endpoint 호출 0(이미 instrument master 갱신 cron이 KIS `inquire-status` 응답 raw fetch 중)

## 2. 결정

### 2-1. 신규 테이블 `instrument_status_history` LOCK

Flyway V42 또는 다음 시퀀스(market-data Phase 1 PR 시점 결정):

```sql
CREATE TABLE instrument_status_history (
    id                BIGSERIAL PRIMARY KEY,
    instrument_code   VARCHAR(12) NOT NULL,
    status            VARCHAR(32) NOT NULL,    -- 'NORMAL' | 'TRADING_HALT' | 'LIQUIDATION' | 'DELISTED'
    effective_from    TIMESTAMPTZ NOT NULL,    -- 본 status가 시작된 시점 (KIS instrument master 갱신 시점)
    effective_until   TIMESTAMPTZ NULL,        -- 본 status가 종료된 시점 (다음 status row 발행 시점에 backfill)
    reason            TEXT NULL,               -- KIS 응답 또는 운영자 메모(향후)
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_instrument_status_history_status
      CHECK (status IN ('NORMAL', 'TRADING_HALT', 'LIQUIDATION', 'DELISTED'))
);

CREATE INDEX idx_instrument_status_history_code_from
  ON instrument_status_history (instrument_code, effective_from DESC);

CREATE INDEX idx_instrument_status_history_active
  ON instrument_status_history (instrument_code) WHERE effective_until IS NULL;
```

설계 원칙:
- **`effective_from`은 KIS instrument master 갱신 시점**(ADR 0039 cron 09:00 + 18:00 KST 2회) — status 변화 detection 시점이 시작 시점
- **`effective_until=NULL` = 현재 활성** — 다음 status 변화 detection 시 `UPDATE ... SET effective_until=NOW()` + 신규 row `INSERT`
- **추가만 LOCK** — `effective_until` 갱신 외 row 수정 금지 (history immutability)
- **`status`는 enum like CHECK constraint** — 도메인 enum `InstrumentStatus` 정합
- **`reason` nullable** — 향후 KIS 응답 raw 보존 또는 운영자 수동 메모 path

### 2-2. 운영 path — market-data Phase 1

market-data sub-agent (Phase 1 PR):
1. **신규 port** `domain/instrument/port/InstrumentStatusHistoryPort` — `findActiveStatus(code: String, at: Instant): InstrumentStatus?`
2. **신규 adapter** `consumer/.../persistence/instrument/InstrumentStatusHistoryRepositoryAdapter` — JPA
3. **신규 JPA entity** `InstrumentStatusHistoryJpaEntity` (persistence-shared 모듈 후보 — ADR 0088 정합 검토)
4. **신규 JPA repository** `InstrumentStatusHistoryJpaRepository` — abstract fun only (헌법 §6 default fun 함정 가드)
5. **`InstrumentMasterRefreshJob` 보강** (ADR 0039 cron) — KIS `inquire-status` 응답 비교:
   - 현재 active row(`effective_until IS NULL`) 조회
   - KIS 응답 status와 비교 → 차이 있으면 active row `effective_until=NOW()` + 신규 row INSERT
6. **Backfill 1회** — Phase 1 PR 시점 모든 watchlist 종목 `instrument` 단일 row를 `instrument_status_history` `NORMAL`/`TRADING_HALT`/... 매트릭스에 default INSERT (`effective_from = instrument.listed_at` or NOW(), `effective_until=NULL`)
7. **신규 audit_trail path** `INSTRUMENT_STATUS_CHANGED` — status 변화 시 audit row INSERT

KIS endpoint 호출 신규 0 LOCK — `InstrumentMasterRefreshJob`이 이미 KIS `inquire-status` 호출 중 (ADR 0039 cron 2회/일). 본 ADR은 응답 raw 비교 lens만 추가.

### 2-3. 운영 path — chart-curator Phase 2

chart-curator sub-agent (Phase 2 PR — ADR 0086 §12 정밀도 향상):

`CandleGapDetectorAdapter.passesActiveGapAxes()` 메서드에 history 조회 lens 통합:

```kotlin
fun passesActiveGapAxes(code: String, ts: Instant): Boolean {
    // axis 1: market_calendar.is_business_day
    if (!businessDayCalendar.isBusinessDay(ts)) return false
    // axis 2 (★ history snapshot): instrument_status_history at ts
    val statusAt = instrumentStatusHistoryPort.findActiveStatus(code, ts)
    if (statusAt == InstrumentStatus.TRADING_HALT) return false
    if (statusAt == InstrumentStatus.LIQUIDATION) return false
    if (statusAt == InstrumentStatus.DELISTED) return false
    // axis 3 (★ 동일 history snapshot — axis 2와 통합 — LIQUIDATION/DELISTED 분리는 status enum 분기로 충분)
    // axis 4: instrument.listed_at <= ts
    val listedAt = instrumentRepository.findListedAt(code) ?: return false
    if (listedAt > ts) return false
    return true
}
```

→ 4축 모두 시점별(`at ts`) 정밀도 향상 → §12 active gap detection false positive·false negative 영구 차단.

### 2-4. Phase 분리 LOCK

| Phase | sub-agent | 산출물 | LOC | dispatch 순서 |
|-------|-----------|--------|-----|---------------|
| 1 | market-data | Flyway V42 + port/adapter/entity/repo + `InstrumentMasterRefreshJob` 보강 + backfill 1회 + audit_trail path | ~250 LOC | **직렬 1순위** |
| 2 | chart-curator | `CandleGapDetectorAdapter.passesActiveGapAxes()` history lens 통합 + unit test 5건 | ~80 LOC | **직렬 2순위** (Phase 1 머지 후) |
| 3 (옵션) | qa | dogfooding case — 거래정지 종목 강제 INSERT → §12 갭 검출 LOCK 검증 | report only | 별도 사이클 |

### 2-5. KIS endpoint·AI·mock 합성 검증

- **KIS endpoint 신규 호출 0 LOCK** — `InstrumentMasterRefreshJob` cron 응답 raw 활용 (ADR 0039 정합)
- **AI 호출 0 LOCK** — 룰 5 (W1·W2·W3 LLM provider import 자제)
- **mock 합성 0 LOCK** — backfill default `NORMAL` 추정은 mock 합성 아닌 baseline initialization
- **Flyway V42** add-only — `instrument_status_history` 신규 + index 2 (인덱스만)

### 2-6. cross-validation 검증 절차

1. Phase 1 머지 후: `psql -c "SELECT COUNT(*) FROM instrument_status_history WHERE effective_until IS NULL"` = watchlist 종목 수 LOCK
2. KIS instrument master cron 1회 trigger → status 변화 없으면 INSERT 0건 / 차이 있으면 변화한 종목 row 1건 INSERT 검증
3. Phase 2 머지 후: 거래정지 종목 강제 INSERT(`status='TRADING_HALT'` `effective_from='2026-05-01'` `effective_until='2026-05-10'`) → 해당 기간 갭 `passesActiveGapAxes()` FALSE 반환 LOCK
4. audit_trail `INSTRUMENT_STATUS_CHANGED` row evidence 인용 의무

## 3. 결과

### 3-1. 긍정

- ADR 0086 §12 active gap detection axis 2·3 시점 정밀도 향상 → false positive·false negative 영구 차단
- 거래정지·정리매매·상장폐지 history 추적 가능 → 자동매매 룰 본체가 "정지 종목 매수 시그널" 발사 차단 (자본 보호)
- ADR 0027 MCP future spec 시점 history 노출 path 확보 (Claude/Cursor가 "이 종목 거래정지 이력은?" 호출 가능)
- audit_trail path 추가 → 운영 audit 깊이 향상
- ADR 0017 instrument 1급 시민 + ADR 0019 캔들 저장 정책 시점 정합 보강

### 3-2. 부정

- Flyway V42 신규 + JPA entity·repository·port·adapter 5+ 파일 발행 (~250 LOC)
- `InstrumentMasterRefreshJob` cron 부담 미세 증가 (status 비교 + diff INSERT — 종목 수 ~2000 → diff 평균 <10건/일)
- `instrument_status_history` row 누적 — 종목 ~2000 × status 변화 빈도(~1회/년 평균) = 연간 ~2000 row → 5년 누적 ~10000 row → 인덱스 fitness 무한 안전권
- persistence-shared 모듈 합류 의제 별도 결정 (ADR 0088 정합 — 옵션 B 검토)

### 3-3. 후속

본 ADR 머지 후:
1. **Phase 1 market-data 단일 PR** — Flyway V42 + port/adapter/entity/repo + cron 보강 + backfill (~250 LOC)
2. **Phase 2 chart-curator 단일 PR** — `passesActiveGapAxes()` history lens + unit test 5건 (~80 LOC)
3. **Phase 3 (옵션) qa report** — dogfooding case 거래정지 종목 강제 INSERT 검증 (별도 사이클)
4. 본 ADR Proposed → Accepted 격상(Phase 1·2 머지 evidence)
5. ADR 0086 §12 본문 amend — 4축 권위 source 갱신(axis 2·3 → `instrument_status_history` history at ts)

## 4. 대안 검토

### 4-1. 대안 A: 현행 유지(`instrument` 단일 row만)

§12 axis 2·3 현재 상태만 사용 — 그대로.

**거부 사유**:
- false positive·false negative risk 잔류 → 자동매매 룰 본체 자본 손실 risk
- 다른 종목 dogfooding 시 동일 결함 재발 risk

### 4-2. 대안 B: `instrument` 테이블에 `status_changed_at` 단일 컬럼 추가

`instrument` row에 `status_changed_at` TIMESTAMPTZ 추가 — 마지막 변화 시점만 보존.

**거부 사유**:
- 과거 status 매트릭스 보존 불가 → 5개월 전 거래정지 → 1개월 전 NORMAL 복귀 같은 multi-transition 추적 0
- `effective_from`·`effective_until` 명시 history 표준 미달

### 4-3. 대안 C: 별도 schema `instrument_audit` 발행

별도 schema에 audit-only 발행 — 운영 audit만.

**거부 사유**:
- chart-curator §12 active gap detection은 audit이 아닌 **자본 path 룰** — schema 분리 시 join 부담 ↑
- ADR 0006 schema 분리 default 정책에 어긋남 (감사용 audit_trail은 별도 영역, status history는 도메인 영역)

### 4-4. 대안 D: persistence-shared 모듈 합류 (ADR 0088)

`InstrumentStatusHistoryRepositoryAdapter`를 persistence-shared 모듈에 발행 — api/consumer 양쪽 공유.

**채택 후보** (Phase 1 market-data 결정 위임):
- chart-curator `CandleGapDetectorAdapter`(consumer)·market-data `InstrumentMasterRefreshJob`(consumer) 양쪽 모두 consumer 모듈 → persistence-shared 합류 비필수
- 단, future T-RECON·T-NOTIFY·api 측 운영 endpoint 합류 시 persistence-shared 패턴 1:1 정합 prereq → Phase 1 market-data 결정 위임

## 5. 사용자 명시 인용

> "chart-curator PR #554 P2-1·P2-2 evidence — `instrument_status_history` 미존재 — 종목 거래정지·정리매매 시간별 history 추적 불가 → ADR 신설 의제. ADR 0085 §책임영역 (chart-curator vs frontend) 명확화 amend 동반." (2026-05-19 KST orchestrator 사용자 위임)

## 6. 관련 결정

- ADR 0013 — Market Session FSM (`business_day_calendar` axis 1 권위)
- ADR 0017 — Instrument Master 1급 시민 (`instrument` 테이블 + KIS instrument master 갱신 path)
- ADR 0019 — 캔들 저장 정책 (캔들 row 시점 정합)
- ADR 0028 — Watchlist Ingestion Worker (watchlist 종목 status history 적재 path)
- ADR 0039 — 종목 마스터 갱신 Cron (KIS instrument master 2회/일 cron 활용)
- ADR 0085 — chart-curator 영입 (§12 active gap detection 본인 영역)
- ADR 0086 — T-CC Phase 2 데이터 햇징 13 룰 (§12 4축 → 본 ADR로 정밀도 향상)
- ADR 0088 — persistence-shared 모듈 (대안 D 합류 검토)

## 7. 변경 이력

| 일자 | 변경 | 사유 |
|------|------|------|
| 2026-05-19 | ADR 0090 신설 — Proposed | chart-curator PR #554 P2-1·P2-2 evidence (`instrument_status_history` 미존재 → §12 4축 active gap detection axis 2·3 시점 부재 한계) + 한국석유 dogfooding cycle cross-ref 잠재 false positive risk → 신규 테이블 의제. Flyway V42 + market-data Phase 1 cron 보강 + chart-curator Phase 2 lens 통합 + Phase 3 qa dogfooding 검증. KIS endpoint 신규 호출 0 LOCK + AI 호출 0 + mock 합성 0. (architect, opus 4.7 1M) |
