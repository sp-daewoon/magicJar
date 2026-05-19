---
name: magicjar-chart-curator
description: TradingView Lightweight Charts v4 차트 컴포넌트(series·overlay·marker·priceLine·multipane MTF·plugin) 깊이 작업 + 캔들 데이터 SLA·정합성(timeframe별 적재·갭 검출·자동 backfill·MTF 정합성 검증·corporate action adjustment) 작업. "차트 추가", "캔들 결함", "MTF 정합성", "LWC 확장", "한국석유처럼 일봉 stale" 같은 의제에 트리거. chart-curator 에이전트(ADR 0085)의 핵심 도메인 스킬.
---

# magicjar-chart-curator — 차트·캔들 큐레이션

> **★ 워크트리 진입 의무 (헌법 §1-A)**: 본 skill은 chart-curator 영역. worktree 진입 즉시 `git config user.email "bot+chart-curator@magicjar.local"` + `git config user.name "magicjar-chart-curator"`. task 파일 commit 의무는 헌법 §1-C 정합.
>
> **★ KIS-first raw fetch (헌법 §3 ★)**: 캔들 endpoint·tr_id·필드·시장구분 자체 결정 금지 — 공식 raw URL 인용 + MCP `mcp__kis-code-assistant__*` 검증 의무. 위반 시 PR-11·PR-21 baseline 정합 fitness gap 누적 risk.
>
> **★ CHANGELOG 슬롯 + rebase (헌법 §4·§5)**: PR 발행 전 main rebase 의무 + architect 발행 슬롯 row in-place edit (`(작성: chart-curator)` 마커).

chart-curator는 magicJar의 **캔들 데이터 layer + 차트 시각화 layer 단일 owner** (ADR 0085). 영입 baseline: 한국석유(004090) 일봉 5개월 stale dogfooding evidence + 3 영역(market-data·strategy-engine·frontend) 교차 점유로 인한 결함 책임 분기.

## 책임 영역 (LOCK)

| 영역 | 소유 | 비고 |
|------|------|------|
| TradingView LWC v4 차트 컴포넌트 (`frontend/src/components/chart/*`) | ✅ | frontend 이관 |
| 캔들 적재 워커 (KIS endpoint 호출 + 영속) | ✅ | market-data 이관 (어댑터는 market-data 잔류) |
| 갭 검출 + 자동 backfill 워커 | ✅ | 신규 책임 |
| MTF 정합성 검증 (1m → 5m·15m·30m·1h·일봉 집계 정합) | ✅ | 신규 책임 (ADR 0062 정합) |
| Corporate action candle adjustment | ✅ | market-data raw + chart-curator adjusted |
| 휴장일·시간외·NXT 매트릭스 (ADR 0063) | ✅ | market-data와 협력 |
| KIS REST/WS HTTP 인프라 | ❌ | market-data 잔류 |
| 뉴스·공시·시세 스냅샷·호가·잔고 | ❌ | market-data 잔류 |
| 지표·DSL·백테스트·주문 로직 | ❌ | strategy-engine 잔류 |
| Dashboard·Watchlist·매매일지·일반 UI | ❌ | frontend 잔류 |

## TradingView Lightweight Charts v4 도메인 깊이

### 핵심 API

| API | 용도 |
|-----|------|
| `createChart(container, options)` | 차트 인스턴스 생성 |
| `chart.addCandlestickSeries(options)` | 캔들스틱 series |
| `chart.addHistogramSeries(options)` | 거래량·히스토그램 |
| `chart.addLineSeries(options)` | 지표 라인 (이평선·RSI·MACD line 등) |
| `chart.addAreaSeries(options)` | 면적 차트 (포지션 영역 등) |
| `series.setData(data)` | 캔들/지표 데이터 일괄 셋 |
| `series.update(bar)` | 실시간 신규 bar 추가 (마지막 bar 갱신 또는 신규) |
| `series.setMarkers(markers)` | 시그널·주문 fill 마커 |
| `series.createPriceLine(options)` | 진입가·손절·익절 priceLine |
| `chart.timeScale().fitContent()` | 자동 zoom out |
| `chart.subscribeCrosshairMove(handler)` | crosshair 인터랙션 |
| `chart.subscribeClick(handler)` | click 인터랙션 |

### Multipane MTF (Multi-Time-Frame) 패턴

LWC v4는 단일 chart 인스턴스 multi-pane 미지원 — **방법 1: 여러 chart 인스턴스 + crosshair sync** 또는 **방법 2: 단일 chart + multiple series에 priceScaleId 분리** 사용.

magicJar 표준 (ADR 0085 §2-1):
- **방법 1 채택** — 1m / 5m / 15m / 1h / 일봉 각각 별도 chart 인스턴스, `subscribeCrosshairMove`로 sync
- volume은 동일 chart 인스턴스에 별도 priceScaleId="left" 분리

### Plugin·Overlay (ADR 0025 정합)

- LWC v4 v4.1+ Plugin API (`chart.addCustomSeries(plugin, options)`) 활용
- 자체 도형(추세선·박스·피보나치)·주석·패턴(57 candle pattern ADR 0058) overlay
- TradingView Advanced 의존 0 (라이선스 회피 — ADR 0025)

### 한국 시장 특수 처리

- 시간 단위: `time` 필드는 UTC timestamp 또는 `{ year, month, day }` business day
- 한국 거래시간: 09:00~15:30 KST + 동시호가 + NXT/시간외 (ADR 0063 매트릭스)
- 휴장일: `time` 갭 발생 — `timeScale.applyOptions({ rightOffset, barSpacing })`로 시각적 보정

## 데이터 햇징 룰 매트릭스 (CRITICAL — 모든 캔들 작업 적용 · ADR 0086)

> **★ 신입 chart-curator 영구 보호 룰**: 본 § 10 룰은 캔들 데이터를 적재·변경·갱신·삭제할 때 **반드시** 적용. 위반 시 잘못된 캔들 적재 → 백테스트 결과 왜곡 → 실거래 시그널 오작동 → **자본 손실** risk + 단일 owner 책임 귀속(ADR 0085 정합).
>
> **권위**: ADR 0086 Proposed (2026-05-19, architect 자율 결정 · 사용자 명시 "신입한테 위 내용 잘 상기시켜서 진행해"). spec: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`.

| # | 룰 | 본문 |
|---|-----|------|
| 1 | **이중 검증 (Double Verification)** | KIS raw fetch 응답 적재 전 sanity check — 가격 range(`0 < open·high·low·close ≤ 1_000_000_000`) · 거래량 비음수(`volume >= 0`) · time monotonic (응답 list 내 ts 오름차순) · OHLC 정합성 (`high >= max(open, close)` ∧ `low <= min(open, close)` ∧ `low <= high`). 위반 시 적재 0 + warn log + audit. |
| 2 | **Dry-run 모드** | `magicjar.chart-curator.backfill.dry-run=true` toggle 시 KIS fetch만 + DB 적재 0 + log·response 출력. admin endpoint `?dry-run=true` 우선 적용 (property override). 운영자 사전 검증 path. |
| 3 | **Rollback 안전망** | backfill 시작 직전 `audit_trail.CANDLE_BACKFILL_START` row 발행 + `backfill_batch_id` (`"candle-backfill:${code}:${interval.code}:${epochMs}-${UUID}"` 형식) 추적 + 결함 검출 시 `DELETE FROM candle WHERE backfill_batch_id=?` 가능 (Flyway V39 micro `candle.backfill_batch_id VARCHAR(96) NULL` add-only). |
| 4 | **Idempotency LOCK** | UPSERT `ON CONFLICT DO NOTHING` (`CandleRepository.saveAll()` 기존 동작 100% 재사용 — UQ `candle.code+interval+ts` ADR 0019 정합). 기존 정상 historic candle을 KIS retry 응답으로 덮어쓰기 금지. |
| 5 | **Source-of-truth 룰** | KIS raw 응답이 권위 + 합성·계산·가공된 데이터 적재 금지. 5m·15m·30m·90m·1h 합산 가공은 `AggregateBackfillRunner` 위임(ADR 0062) — Phase 2 직접 touch 0. |
| 6 | **갭 검출 false positive 가드** | 거래정지·정리매매 종목 (`instrumentJpaRepository.findById(code)` skip — `KIS_COLD_START_SKIPPED` 패턴) + 휴장일 (`BusinessDayCalendar.isBusinessDay()` 의무 호출 — ADR 0013) + 시간외(NXT — ADR 0063 정합) + 신규 종목 상장일 이전 ts 제외. |
| 7 | **갭 검출 false negative 가드** | `last_candle_at` null 시 `Long.MAX_VALUE` (Phase 1 `LastCandleAtMonitor.detectStale()` 패턴 100% 재사용) + watchlist 신규 진입 종목 즉시 cold-start (`WatchlistChangedKafkaListener.onAdd()` 기존 동작). |
| 8 | **재시도 backoff + KillSwitch** | 1회(즉시) + 2회(1s backoff) + 3회(5s backoff). 3회 실패 시 KillSwitch on (ADR 0043 정합) + Slack P0 alert + `CANDLE_BACKFILL_FAIL` audit. 전체 backfill failure 시 자동매매 중지(자본 보호 우선) / 부분 failure 시 warn log + Slack notice(운영자 수동 확인 권고). 임계치 chart-curator dogfooding 자율 fine-tune. |
| 9 | **audit_trail 의무** | 5 path 모두 row 1건 — `CANDLE_BACKFILL_START` (시작 + `detailJson.backfillBatchId·gapCount·dryRun`) · `CANDLE_BACKFILL_SUCCESS` (완료 + `insertedCount·durationMs`) · `CANDLE_BACKFILL_FAIL` (3회 retry 실패 + `failedGaps·err`) · `CANDLE_BACKFILL_SKIPPED` (거래정지·휴장 + `reason`) · `CANDLE_BACKFILL_ROLLBACK` (운영자 수동 rollback + `deletedCount`). |
| 10 | **운영자 우회 path** | admin endpoint `POST /admin/candle-curator/backfill?code={code}&interval={1d|1m}&dry-run={true|false}` 신설 (`CandleBackfillAdminController`). 응답에 `gapCount·gapsSample·backfillBatchId` 노출 → 운영자 수동 검증 path + admin rollback (1차 MVP SQL 수동 가능). |

### 햇징 룰 적용 의무 영역

본 10 룰은 다음 작업에서 **반드시** 적용:

1. `CandleBackfillWorker` 본체 (T-CC Phase 2 — ADR 0086)
2. 향후 chart-curator가 발행하는 모든 캔들 적재·변경 코드
3. corp action retroactive 정정(Phase 4) — `Source-of-truth` + `Rollback 안전망` 룰 강조
4. MTF 정합성 검증(Phase 3) — `Idempotency LOCK` + `Source-of-truth` 룰 강조
5. LWC v4 확장(Phase 5) — `이중 검증` 룰 강조 (frontend client에서도 OHLC sanity check 권장)

### 함정 가드 cross-ref

본 § 룰은 본 SKILL 하단 §함정 가드의 운영 baseline:
- 룰 #1 ↔ 함정 #1 (time 타입) + #2 (series 정렬)
- 룰 #5 ↔ 함정 #6 (corp action retroactive)
- 룰 #6 ↔ 함정 #5 (MTF 합산 timezone)
- 룰 #8 ↔ 함정 #4 (KIS 페이지네이션)

---

## 캔들 데이터 SLA 매트릭스

### timeframe별 SLA 정책 (ADR 0085 §2-1 정합)

| timeframe | 적재 주기 | stale 임계치 | backfill 워커 | 비고 |
|----------|---------|------------|--------------|------|
| tick      | 실시간 WS | 5초 끊김    | WS 재연결    | KIS 동시 구독 한도 41 (ADR 0048) |
| 1m        | 실시간 + 1분 마감 | 2분          | KIS endpoint 호출 | 합산 가공 원천 (ADR 0062) |
| 5m·15m·30m·1h | 1m 합산 가공 | MTF 정합성 잡 | 1m 재집계 + 직접 조회 비교 | ADR 0062 |
| 일봉(D)   | EOD 일 1회 + 장중 진행 | **3 영업일** ★ | KIS daily endpoint 호출 | 한국석유 evidence — 5개월 stale 발생 시 즉시 alert |
| 주봉(W)·월봉(M) | EOD 주/월 마감 | 1~2주 | KIS endpoint period 분기 | |

### 갭 검출 워커 (신규 — chart-curator 영역 1순위)

- daily watchdog 잡 — 모든 watchlist 종목 `last_candle_at` 검증
- stale 임계치 위반 시 → KIS endpoint 재호출 + 갭 메우기 + 사용자 보고
- 한국석유(004090) dogfooding cycle — 본 워커 부재로 5개월 누적 결함

### MTF 정합성 검증 (신규)

- 1m raw → 5m·15m·30m·1h 합산 결과가 KIS 직접 조회와 일치하는지 daily 검증
- 불일치 시 → 자동 backfill + 사용자 보고 + ADR 0062 정합 재검토
- 검증 매트릭스: `_workspace/chart-curator/mtf-integrity.md`

### Corporate Action Adjustment (ADR 0018 cross-ref)

- 액면분할·배당락·합병 시 캔들 raw 데이터는 그대로 보존 (market-data)
- chart-curator는 **adjusted candle**(retroactive 정정 결과) 별도 영속 + UI 표시 토글 (raw/adjusted)
- KIS `inquire-elw-issuer-info` 등 corp action endpoint (raw 출처 인용 의무 — 헌법 §3)

## KIS 캔들 endpoint 매트릭스 (KIS-first raw fetch 의무)

> 본 매트릭스는 **architect spec 인용 + KIS 공식 raw URL 인용 의무 baseline**. 자체 결정 금지.

| endpoint | tr_id | timeframe | 비고 |
|---------|-------|----------|------|
| `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | FHKST03010100 | 일/주/월 (`FID_PERIOD_DIV_CODE` 분기) | 100건 페이지네이션 |
| `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | FHKST03010200 | 분봉 (1·3·5·10·30분 — `FID_INPUT_HOUR_1` 분기) | |
| `/uapi/domestic-stock/v1/quotations/inquire-time-conclusion` | tick | 체결 단위 | |
| `/uapi/domestic-stock/v1/quotations/inquire-daily-overtimeprice` | FHPST02320000 | 시간외 (ADR 0076 cross-ref) | NXT/통합거래 ADR 0063 |

**raw 출처**:
- KIS 공식 GitHub: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/`
- MCP: `mcp__kis-code-assistant__search` 호출로 endpoint 검증
- 매 코드 발행 시 inline 주석에 raw URL 명시

## 운영 체크리스트

### 작업 시작 전

1. worktree git config 확인 — `bot+chart-curator@magicjar.local`
2. `task.mjs start --agent chart-curator --goal "..." --deliverables "..."`
3. `git add _workspace/tasks/T-*.md` 즉시
4. KIS 캔들 endpoint 변경 시 — `mcp__kis-code-assistant__*` 검증

### 코드 발행 시

1. `frontend/src/components/chart/*` 변경 시 — `cd frontend && pnpm typecheck && pnpm build` GREEN
2. 캔들 적재 워커 변경 시 — `./gradlew :api:test` 또는 `:consumer:test` 모듈 단위 검증
3. KIS endpoint 호출 코드 inline 주석에 raw URL 명시
4. Repository default fun 함정(헌법 §6·ADR 0051) 검증

### PR 발행 직전

1. main rebase 의무 (헌법 §5) — `git fetch origin main && git rebase origin/main`
2. CHANGELOG 슬롯 row 또는 새 row prepend
3. `_workspace/chart-curator/<topic>.md` 갱신 (candle-sla-policy·mtf-integrity·lwc-component-map)
4. dogfooding cycle 진입 시 — 한국석유 등 stale evidence 인용

## 협업 패턴

### market-data와 협력 (어댑터 인터페이스)

- chart-curator는 KIS REST/WS 어댑터 소비자, market-data는 어댑터 발행자
- 새 timeframe·새 필드 필요 시 → market-data에 어댑터 인터페이스 확장 요청
- 캔들 raw 응답 매핑은 market-data, 캔들 정합성·갭 검출은 chart-curator

### strategy-engine과 협력 (캔들 소비)

- strategy-engine은 캔들 소비 client만 유지 (지표 입력)
- 정합성 검증·MTF 합산은 chart-curator 단독 (strategy-engine은 결과만 받음)

### frontend와 협력 (UI 외 컴포넌트)

- 차트 컴포넌트 자체는 chart-curator 소유
- Dashboard·Watchlist·매매일지에서 차트 컴포넌트 import — frontend 책임
- 차트와 외부 UI(예: Watchlist 클릭 → 차트 종목 변경) 인터랙션은 합의 의무

### backend-core와 협력 (도메인 + Flyway)

- 캔들 도메인 port 정의 (`CandleQueryPort`·`CandleStoragePort`)
- Flyway 마이그레이션 (캔들 테이블·인덱스·파티션)
- 영속 인프라 (PostgreSQL·TimescaleDB 고려·Redis 캐시)

### architect 협업

- 캔들 도메인 계약·정합성 정책 ADR 발행 요청
- KIS endpoint 결정·MTF 정합성 매트릭스 ADR 발행 의무

## 함정 가드

1. **time 타입 함정** — LWC v4 `time` 필드 UTC vs business day vs string ISO 혼용 금지. 한국 시장은 `business_day = { year, month, day }` 권장 (timezone 회피)
2. **series 데이터 정렬 함정** — `setData([...])` 호출 시 `time` 오름차순 정렬 강제 (역순 시 chart 깨짐)
3. **multi-chart sync race** — 여러 chart 인스턴스 `subscribeCrosshairMove` sync 시 무한 루프 risk (sync 발신자는 자기 자신 skip)
4. **KIS 페이지네이션 함정** — 일봉 100건 한도, 5개월 stale → 1.5+ 요청 필요. backoff + 토큰 만료 처리
5. **MTF 합산 timezone 함정** — 1m → 5m 합산 시 한국 시장 9:00 KST 시작 기준 (UTC 0:00 기준 합산 금지)
6. **corporate action retroactive 함정** — adjusted 캔들 발행 시 raw 데이터 변형 금지 (별도 컬럼 또는 별도 테이블)

## 첫 발화 dogfooding (ADR 0085 머지 후 즉시)

**의제**: 한국석유(004090) 일봉 5개월 stale fix
1. KIS daily candle backfill — `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` 호출
2. 2025-12-25 ~ 2026-05-18 (≈5개월) 페이지네이션 backfill
3. `last_candle_at` 모니터링 워커 신설 — daily watchdog 잡
4. 모든 watchlist 종목 대상 stale 검증 + alert
5. T-CC 트랙 backlog 신설 — 캔들 정합성 트랙(갭 검출·자동 backfill·MTF 정합성·corp action adjustment)
