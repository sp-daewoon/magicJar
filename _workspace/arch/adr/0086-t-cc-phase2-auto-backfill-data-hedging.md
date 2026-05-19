# ADR 0086: T-CC Phase 2 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 10건 (chart-curator 신입 보호)

- 상태: **Proposed**
- 작성일: 2026-05-19
- 결정자: architect (opus 4.7 1M) — 사용자 위임 명시 "신입한테 위 내용 잘 상기시켜서 진행해"
- 영역: 캔들 정합성 + 메타·운영·하네스 (chart-curator skill SoT 정합)
- 관련: ADR 0013(영업일 캘린더) · ADR 0019(캔들 저장 정책) · ADR 0028(Watchlist Ingestion) · ADR 0037(KIS API Rate Limit) · ADR 0043(KillSwitch 행동 정의) · ADR 0057(KIS-only) · ADR 0062(합산 가공 5종) · ADR 0063(NXT 매트릭스) · ADR 0085(chart-curator 영입)

---

## 1. Context

### 1-1. T-CC Phase 1 dogfooding evidence (선행 마감)

사용자 dogfooding(2026-05-19 KST) — **한국석유(004090)** 일봉 last=2025-12-24 ≈5개월 stale vs 1분봉 last=2026-05-18 15:19 정상. T-CC Phase 1(PR #514 머지) — `LastCandleAtMonitor` daily 18:00 KST cron + `LastCandleAtBootstrapRunner` 부팅 시 즉시 발화 + admin endpoint `POST /admin/monitor/last-candle-at` 신설 완료.

### 1-2. Phase 2 진입 사유

Phase 1은 **stale 검출 + 일괄 재수집 트리거**까지. 그러나:
- 갭 검출 알고리즘 0 — 중간 갭 누락은 last_ts 단순 비교로 검출 불가
- 자동 backfill = `ColdStartExecutor.submit()` 일괄 재수집 — KIS 호출 cost 비효율 + 기존 정상 데이터 덮어쓰기 risk
- 재시도 + KillSwitch 미연동
- 데이터 햇징 룰 0 — sanity check·dry-run·rollback·idempotency 부재

### 1-3. 신입 chart-curator 보호 필요성 (사용자 의제 baseline)

사용자(최고 관리자) 2026-05-19 KST 명시:

> "신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해. 아키텍처야 신입한테 위 내용 잘 상기시켜서 진행해"

chart-curator는 ADR 0085(2026-05-19, PR #511 머지) 영입 신입. **자동 backfill 워커가 잘못된 데이터를 적재할 risk가 운영 baseline의 잠재 위협** — architect는 사전에 **데이터 결함 햇징 룰 매트릭스**를 명시할 의무.

### 1-4. fitness gap (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

- 자동매매 필요성 — ✅ 일봉 갭 누락 → EMA 200 시그널 왜곡 → 자본 손실 risk
- v1/v2 잔재 — ❌ Phase 2는 chart-curator 신규 책임 (ADR 0085 영입 정합)
- MVP 적합 — ✅ watchlist 30종목 × 30 갭 = 900 호출/cycle (KIS rate limit 분산 충분)

---

## 2. Decision

### 2-1. 10 데이터 햇징 룰 LOCK

chart-curator가 캔들 데이터를 적재·변경·갱신·삭제할 때 **반드시** 다음 10 룰 적용 (spec §5 본문 정합).

| # | 룰 | 위반 시 risk |
|---|-----|-------------|
| 1 | **이중 검증 (Double Verification)** — KIS raw fetch 응답 적재 전 sanity check (가격 range·거래량 비음수·time monotonic·OHLC 정합성 `high≥max(open,close)·low≤min(open,close)`) | 잘못된 캔들 적재 → 백테스트 결과 왜곡 → 실거래 시그널 오작동 |
| 2 | **Dry-run 모드** — `magicjar.chart-curator.backfill.dry-run=true` toggle 시 KIS fetch만 + DB 적재 0 + log·response 출력 | 운영자 사전 검증 path 부재 → 잘못된 backfill 적재 |
| 3 | **Rollback 안전망** — backfill_batch_id로 변경 추적 + `audit_trail.CANDLE_BACKFILL_START` row + 결함 검출 시 `DELETE FROM candle WHERE backfill_batch_id=?` 가능 | 결함 검출 후 정정 path 부재 → 운영 부채 누적 |
| 4 | **Idempotency LOCK** — UPSERT `ON CONFLICT DO NOTHING` (historic 보존) | 기존 정상 historic candle을 KIS retry 응답으로 덮어써서 회귀 |
| 5 | **Source-of-truth 룰** — KIS raw 응답 권위 + 합성·계산·가공 적재 금지 | 백테스트 vs 실거래 시그널 분기 → 자본 손실 |
| 6 | **갭 검출 false positive 가드** — 거래정지·정리매매·휴장일·시간외 제외 (`KIS_COLD_START_SKIPPED` 패턴 + `BusinessDayCalendar.isBusinessDay()` 의무 호출) | 정상 휴장 갭을 결함으로 오인 → KIS 무의미 호출 + alert spam |
| 7 | **갭 검출 false negative 가드** — `last_candle_at` null 시 `Long.MAX_VALUE` (Phase 1 패턴 100% 재사용) + watchlist 신규 진입 종목 즉시 cold-start | 미검출 갭 누적 → 자동매매 시그널 무력화 |
| 8 | **재시도 backoff + KillSwitch** — 3회 (즉시 + 1s + 5s) 후 stop + Slack P0 alert + ADR 0043 정합 KillSwitch on | 무한 retry → KIS rate limit burn + 자동매매 무력화 |
| 9 | **audit_trail 의무** — 시작·성공·실패·skip·rollback 5 path 모두 row 1건 (`CANDLE_BACKFILL_START/SUCCESS/FAIL/SKIPPED/ROLLBACK`) | 운영 audit 불가 → 결함 root cause 추적 불가 |
| 10 | **운영자 우회 path** — admin endpoint `POST /admin/candle-curator/backfill?code=...&interval=...&dry-run=true` 신설 | 운영 자율성 부재 → 결함 발생 시 수동 정정 불가 |

**위반 시 risk 종합**: 잘못된 캔들 적재 → 백테스트 결과 왜곡 → 실거래 시그널 오작동 → **자본 손실**. chart-curator는 단일 owner이므로 본 룰 위반은 운영 부채 전체 책임 단일 귀속.

### 2-2. 신규 컴포넌트 LOCK

| 산출물 | 위치 | 책임 |
|--------|------|------|
| `CandleGapDetectorPort` | `domain/.../market/port/` | 도메인 port (영업일 캘린더 기반 갭 검출 인터페이스) |
| `CandleGapDetectorAdapter` | `consumer/.../aggregation/` | port 구현 (영업일 expected sequence + DB actual diff) |
| `CandleBackfillWorker` | `consumer/.../aggregation/` | 갭만 정밀 backfill + 재시도 3회 + KillSwitch + 햇징 룰 10건 본체 |
| `CandleBackfillAdminController` | `consumer/.../aggregation/` | admin endpoint (`POST /admin/candle-curator/backfill` — dry-run 옵션) |
| `CandleRepository.findAllTsByCodeAndIntervalBetween()` add-only | `domain/.../port/` + 어댑터 | 범위 내 모든 ts 조회 (chart-curator 자율 결정 — 기존 `findByCodeAndInterval()` filter도 가능) |
| Flyway V39 micro | `api/.../resources/db/migration/` | `candle.backfill_batch_id VARCHAR(96) NULL` add-only (인덱스 0) |
| `LastCandleAtMonitor.checkAllCodes()` 1 line edit | `consumer/.../monitoring/` | `coldStartExecutor.submit()` → `candleBackfillWorker.submit()` (chart-curator 자율 결정) |

### 2-3. 갭 검출 알고리즘 LOCK

```
windowDays = 30 (LOCK — KIS 호출 cost 보호)
expected = (now - windowDays..now).filter { calendar.isBusinessDay(it) }
actual = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
gaps = expected.filterNot { it in actualSet }
```

### 2-4. dispatch plan LOCK

- **Phase 2-A**: chart-curator 단일 PR (~835 LOC) — 도메인 port + 어댑터 + 워커 + admin endpoint + Flyway V39 + Phase 1 1 line edit + unit test (햇징 룰 10건 검증)
- **Phase 2-B**: chart-curator 자율 결정 (Phase 2-A에 모두 포함하면 분리 불필요) — Slack outbox · audit_trail integration 검증

### 2-5. release v3.6.18 patch 권장

- `feedback_release_policy_patch_first` 정합 — v3.7.0=W4 LOCK
- T-CC Phase 2(chart-curator) + ADR 0084 P1-3(backend-core/market-data) 영역 분리 → 병렬 dispatch 가능

---

## 3. Consequences

### 3-1. 긍정 결과

- chart-curator skill SoT § 신설 — 신입 에이전트 영구 보호 룰 LOCK
- 갭 검출 정밀화 → 자동 backfill 호출 cost 감소 (전체 재수집 대비 90%+ 효율)
- audit_trail 5 path → 운영 audit 가능 → 결함 root cause 추적 가능
- KillSwitch 자동 연동 → 자동매매 자본 보호

### 3-2. 부정 결과

- chart-curator 영역 책임 추가 (운영 부채 단일 귀속) — ADR 0085 영입 결정 정합 보전
- Flyway V39 micro 1건 — `candle.backfill_batch_id` column 추가 (인덱스 0 → 운영 query 영향 0)
- KillSwitch 발동 임계치 fine-tune 필요 (전체 vs 부분 비율 — chart-curator dogfooding 자율 결정)

### 3-3. 인접 트랙 영향 0 검증

- T-CC Phase 1 (`LastCandleAtMonitor`) — 1 line edit만 (chart-curator 자율 결정 — `coldStartExecutor.submit()` 그대로 두고 워커 호출 추가도 가능)
- T-O 자동 path — 0 touch (캔들 데이터 layer 변경만 — 주문 path 영향 0)
- T-ACCT(ADR 0081) · T-MAN(ADR 0082) — 0 touch
- ADR 0084 P0 baseline (주문 path 안정성) — 0 touch (영역 분리 병렬 가능)

---

## 4. Alternatives Considered

### A. Cron-only manual (거부)

운영자가 admin endpoint로 수동 backfill만. 자동화 0.

**거부 사유**: 사용자 dogfooding evidence(한국석유 5개월 stale)는 자동화 부재로 발생. Phase 1 마감 evidence(PR #514) 이후 stale 검출은 자동화됐지만 backfill 실행은 여전히 manual 1단계 필요(`coldStartExecutor.submit()`가 호출되어도 KIS 응답 결함 시 재시도 없음). 운영자 매번 개입 불가능 → MVP 부적합.

### B. Auto-backfill 채택 + 햇징 룰 (채택) ✅

신입 chart-curator가 자동 backfill 워커를 구현하되, architect가 사전에 햇징 룰 매트릭스를 LOCK으로 명시하여 신입 보호 + 데이터 결함 risk 사전 차단.

**채택 사유**:
- 사용자 의제(2026-05-19) "신입한테 위 내용 잘 상기시켜서 진행해" 정합
- chart-curator 영입 baseline(ADR 0085) 정합 — 단일 owner 책임
- 10 햇징 룰 매트릭스가 운영 부채 잠재 risk를 사전 LOCK으로 차단 → 자본 보호

### C. ADR 0085 본문에 흡수 (거부)

T-CC Phase 2 의제를 ADR 0085(chart-curator 영입)에 add-only § 추가로 흡수.

**거부 사유**: 
- ADR 0085는 메타·하네스 영입 ADR — Phase별 작업 의제 분리 의무 (ADR README §9 정합)
- `feedback_spec_plan_scope` 정합 — 신규 트랙·새 모듈·다중 산출물은 spec/plan + ADR 발행 의무
- 본 ADR 0086은 chart-curator skill 본문 보강 + Phase 2-A·B 명세 LOCK 책임 단일 귀속

---

## 5. 변경 이력

| 일자 | 상태 | 사유 |
|------|------|------|
| 2026-05-19 | Proposed | T-CC Phase 2 spec 정합 신규 발행 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 10건 LOCK + chart-curator 신입 보호. 사용자 명시 "신입한테 위 내용 잘 상기시켜서 진행해" 정합. spec 본문: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`. (architect, opus 4.7 1M) |
