# T19·T20·T21 — 분봉 캐시 + 종목 검색·상세 + 대시보드 지수

> **Status**: SPEC v1 (다음 세션 디스패치 대기)
> **Author**: architect
> **사용자 결정 (2026-05-03)**:
> - 캔들 정책 — `docs/architecture/candle-data-policy.md` (PR #91 머지)
> - 대시보드는 대표종목 5개 → KOSPI·KOSDAQ 지수로 변경
> - 종목 검색 UI 부재 → 헤더 검색창 + 검색 결과 + 종목 상세(차트 + 메타 정보)

---

## T19 — 분봉 캐시 인프라 (market-data 영역)

### 목표
캔들 정책 v1 (`candle-data-policy.md`) 그대로 구현.

### 작업 분해 (~5~7일)
- **T19-T1** `MinuteCandleBackfillJob` — KIS `FHKST03010200` (분봉) interval 파라미터로 5분/30분/1분 분기
- **T19-T2** `instrument_cache_meta` 테이블 (Flyway V19) + LRU 정책 도메인
- **T19-T3** `CandleCacheService` (consumer 또는 api 모듈):
  - cache hit → 즉시 응답 + `last_accessed_at` 갱신
  - cache miss → 백그라운드 fetch trigger + D1 즉시 응답
  - 백그라운드 fetch는 30분봉 3년 1차 (~5초) → 5분봉 1년 2차 (~30초)
- **T19-T4** infinite scroll endpoint — `GET /api/instruments/{code}/candles?before=YYYY-MM-DD&limit=200`
- **T19-T5** frontend `candleAggregator` 확장 — M5 → M15/M30/H1 합성 추가
- **T19-T6** frontend `useCandles` hook 정정 — `onVisibleRangeChange` + scroll-end detector → 자동 추가 fetch
- **T19-T7** cron eviction job — 매 6시간 용량 체크·8 GB 초과 시 LRU DELETE
- **T19-T8** 운영 docs `_workspace/operations/candle-cache-guide.md`

### 디스패치 권장
- **market-data sub-agent (sonnet)**: T19-T1·T2·T3·T4·T7
- **frontend sub-agent (sonnet)**: T19-T5·T6
- **architect (메인)**: T19-T8 docs

병렬 가능 (영역 분리). **다음 세션 첫 디스패치**.

---

## T20 — 종목 검색 + 종목 상세 메타 (frontend + market-data 영역)

### 목표
헤더 검색창 → 종목명/코드 자동완성 → 클릭 시 종목 상세 페이지 (차트 + 회사 메타).

### 사용자 흐름
```
1. 헤더 검색 input 클릭/타이핑 → 자동완성 dropdown
2. 종목 선택 → /symbol/{code} 진입
3. 종목 상세 페이지 동시 표시:
   a. 종목 메타 (좌측 또는 상단)
      - 회사명·코드·시장(KOSPI/KOSDAQ)
      - 시가총액·발행주식수·외국인 비율·기관 비율
      - 매출액·영업이익·당기순이익 (최근 분기)
      - PER·PBR·EPS·BPS
      - 대주주 지분율·배당률
      - 업종·대표자
   b. 차트 (우측 또는 하단)
      - cache 확인 (T19 의존)
      - cache hit → 즉시 / miss → 30분봉 5초 백그라운드
4. 차트 끝 스크롤 → 자동 추가 fetch (T19 infinite scroll)
```

### 데이터 source
- **회사 기본 정보** (회사명·시장·업종·대표·발행주식수·시총):
  - KIS `/uapi/domestic-stock/v1/quotations/inquire-stock-info` 또는 종목 마스터파일 (T3 R1 이미 영속)
- **재무 정보** (매출액·영업이익·PER·PBR·EPS·대주주 지분율):
  - **DART OPEN API** (`/api/list.json`·`/api/multCmpnyAcntChg.json` 등)
  - 사용자 .env에 `OPENDART_API_KEY` 설정 필요 (1번 트랙에 있을 수도)
- **외국인·기관 비율**:
  - KIS `/uapi/domestic-stock/v1/quotations/inquire-foreign-disposition` 또는 유사

### 작업 분해 (~5일)
- **T20-T1** 회사 메타 도메인 (`InstrumentDetail` data class — code·name·market·sector·marketCap·sharesOutstanding·foreignRatio·institutionRatio·revenue·operatingIncome·netIncome·per·pbr·eps·bps·dividendYield·majorShareholderRatio·representativeName·industry)
- **T20-T2** KIS REST endpoint 호출 (`KisInstrumentDetailPort` + Real adapter)
- **T20-T3** DART REST endpoint 호출 (`DartFinancialPort` — 매출액·영업이익·EPS 등 재무 정보)
- **T20-T4** `InstrumentDetailService` (api) — KIS·DART 병합 + Redis 캐시 6시간 TTL (재무 정보는 자주 변경 X)
- **T20-T5** REST `GET /api/instruments/{code}/detail` + Swagger 4단
- **T20-T6** **frontend 헤더 검색창** (auto-complete) — `useInstrumentSearch` debounce 300ms·`/api/instruments/search?q=...&limit=10` 호출
- **T20-T7** frontend SymbolDetail 페이지 메타 panel 추가 — 좌측 sidebar 또는 상단 카드 (회사명·시총·재무 7~10 항목)
- **T20-T8** mobile 반응형 — 메타 panel collapse·차트 우선

### 디스패치 권장
- **market-data sub-agent (sonnet)**: T20-T1·T2·T3·T4·T5
- **frontend sub-agent (sonnet)**: T20-T6·T7·T8

병렬 가능. T19 머지 후 또는 동시.

---

## T21 — 대시보드 = KOSPI·KOSDAQ 지수 (frontend + market-data 영역)

### 목표
현재 Dashboard의 5종목 카드(005930·000660·035720·005380·051910) → **KOSPI·KOSDAQ 지수 차트·요약**으로 전면 교체.

### 사용자 흐름
```
1. localhost:3000/ 진입
2. Dashboard 표시:
   a. KOSPI 지수 카드 (현재값·전일대비·등락률·차트 mini)
   b. KOSDAQ 지수 카드 (동일)
   c. 시장 상태 (개장 전·장중·장마감) — MarketSessionBadge 활용
   d. 거래대금 상위 10종목 (선택)
   e. 등락률 상위/하위 5종목 (선택)
   f. 사용자 watch list 별도 페이지 또는 하단 (선택)
3. 종목 카드 클릭 → /symbol/{code} 진입 (T20 정합)
```

### 데이터 source
- **KOSPI 지수** (코드 `0001`, KIS API): `/uapi/domestic-stock/v1/quotations/inquire-index-price`
- **KOSDAQ 지수** (코드 `1001`): 동일 endpoint
- **거래대금 상위·등락률 상위**: `/uapi/domestic-stock/v1/quotations/volume-rank` 또는 유사
- 1분 cache (Redis)

### 작업 분해 (~3일)
- **T21-T1** 지수 도메인 (`MarketIndex` data class — code·name(KOSPI/KOSDAQ)·currentValue·prevClose·changePercent·volume·session)
- **T21-T2** KIS 지수 REST adapter (`KisMarketIndexPort` + Real)
- **T21-T3** REST `GET /api/market/index?codes=KOSPI,KOSDAQ` + Swagger
- **T21-T4** frontend Dashboard 전면 교체:
  - 5종목 카드 컴포넌트 제거 (또는 별도 watch list 페이지로 이전)
  - KOSPI·KOSDAQ 지수 카드 (Lightweight Charts 미니 차트 임베드)
  - MarketSessionBadge 페이지 상단
- **T21-T5** 거래대금/등락률 상위 종목 위젯 (선택, R2)
- **T21-T6** watch list 페이지 신설 — 기존 5종목 + 사용자 추가 종목 관리 (선택, R2)

### 디스패치 권장
- **market-data sub-agent (sonnet)**: T21-T1·T2·T3
- **frontend sub-agent (sonnet)**: T21-T4·T5·T6

T20과 영역 일부 겹침 (frontend) — 순차 또는 frontend sub-agent 한 명이 T20·T21 묶어서 처리.

---

## 다음 세션 디스패치 권장 순서

| 순서 | 트랙 | 에이전트 | 사이즈 |
|---|---|---|---|
| **1차** (병렬) | T19 (분봉 캐시) | market-data + frontend | ~5~7일 |
| **2차** (T19 머지 후) | T20 + T21 (검색·상세·대시보드) | market-data + frontend | ~5일 |

또는 토큰 여유 시 1차에 T19 + T21 병렬, 2차에 T20.

---

## 의존성 그래프

```
T19 (분봉 캐시) ─┬─→ T20 (종목 상세 — 차트는 T19 cache 의존)
                  └─→ T21 (대시보드 지수 — 지수 차트도 cache 정책 적용)
```

T19가 1차 priority. T20·T21은 T19의 cache 인프라 위에 동작.

---

## 비범위·후속 (post-MVP)

- 동시 호가창 (KIS WS hoga 토픽) — T22 후보
- 외국인·기관 매매 동향 차트 — T23 후보
- 종목 비교 차트 (multi-symbol overlay) — T24 후보
- TimescaleDB 도입 (1분봉 5년 전종목 영속) — 50 GB 초과 시 ADR 신규

---

## T22 — Devlog 메뉴 (frontend 통합)

### 목표
`_workspace/devlog/*.html` 일자별 개발일지를 magicJar frontend에서 직접 열람. 기존 하네스 메뉴를 **Devlog 메뉴**로 확장 + 탭 구조.

### 사용자 흐름
```
1. 헤더 메뉴에 "Devlog" 추가 (또는 기존 하네스 메뉴 rebrand)
2. /devlog 페이지 진입 시 탭:
   a. 일지 탭 (default) — 일자별 list (index.html 정합)
   b. 하네스 탭 — 에이전트 활동·worktree·task 파일 (기존 magicjar-harness-dashboard 통합)
   c. 빌드/CI 탭 (선택) — GitHub Actions 결과
3. 일지 탭에서 날짜 클릭 → iframe 또는 inline 렌더로 YYYY-MM-DD.html 표시
4. 검색·필터 (월·키워드)
```

### 작업 분해 (~3일)
- **T22-T1** backend `GET /api/devlog/list` — `_workspace/devlog/*.html` 파일 list (mtime·summary 추출)
- **T22-T2** backend `GET /api/devlog/{date}` — 해당 일자 HTML 반환 (또는 정적 파일 nginx serve)
- **T22-T3** frontend `/devlog` 페이지 + 탭 컴포넌트 (shadcn/ui Tabs)
- **T22-T4** 일지 탭 — 날짜 list + 클릭 시 iframe 또는 dangerouslySetInnerHTML 렌더
- **T22-T5** 하네스 탭 — 기존 magicjar-harness-dashboard 통합
- **T22-T6** 헤더 메뉴 정정 — Dashboard·Symbol·Strategy·Backtest·Orders·**Devlog**

### 디스패치 권장
- **backend-core (sonnet)**: T22-T1·T2
- **frontend (sonnet)**: T22-T3·T4·T5·T6

T19·T20·T21과 영역 일부 겹침 (frontend) — 4개 트랙 다음 세션 **순차** 권장:
1. T19 (분봉 cache, 가장 큰 가치)
2. T22 (Devlog 메뉴, 작음)
3. T20 (검색·상세)
4. T21 (대시보드 지수)
