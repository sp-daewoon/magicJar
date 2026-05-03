# KIS OpenAPI 참조 — magicJar에서 사용하는 endpoint 정리

> 한국투자증권 OpenAPI(KIS) endpoint·TR_ID·모의투자 지원 여부 정리. 사용자 가이드(2026-05-04) + MCP 검증.
> **데이터 소스**: 사용자 직접 안내 + `mcp__kis-code-assistant__search_domestic_stock_api` MCP 검증.

## 1. 운영 정책 (사용자 결정)

- **데이터 조회 = 항상 실전 키** (`KIS_APP_KEY`·`KIS_APP_SECRET` + `https://openapi.koreainvestment.com:9443`)
- **주문(매매) = 모의/실전 분리** — `trading_mode` (MOCK_KIS|REAL_KIS) 따라 키와 baseUrl 분기
- **IP 화이트리스트 등록 X** — token 발급(`/oauth2/tokenP`)만으로 인증
- 모의투자(VTS) 일부 endpoint **미지원** — 표 우측 컬럼 확인

## 2. 종목정보 API ([국내주식] 종목정보)

| # | 명칭 | TR_ID | endpoint | 모의 | magicJar 사용 |
|---|---|---|---|---|---|
| 1 | 상품기본조회 | `CTPF1604R` | `/uapi/domestic-stock/v1/quotations/search-info` | ❌ 미지원 | (대안) |
| 2 | **주식기본조회** | **`CTPF1002R`** | **`/uapi/domestic-stock/v1/quotations/search-stock-info`** | ❌ 미지원 | ✅ **사용 중** (PR #126) — `KisInstrumentInfoAdapter.fetchInstrumentInfo` |
| 3 | 대차대조표 | `FHKST66430100` | `/uapi/domestic-stock/v1/finance/balance-sheet` | ❌ | (장차 — 자산·부채 표시 시) |
| 4 | 손익계산서 | `FHKST66430200` | `/uapi/domestic-stock/v1/finance/income-statement` | ❌ | (DART 대체 사용 중) |
| 5 | 재무비율 | `FHKST66430300` | `/uapi/domestic-stock/v1/finance/financial-ratio` | ❌ | (장차 — ROE·부채비율) |
| 6 | 수익성비율 | `FHKST66430400` | `/uapi/domestic-stock/v1/finance/profit-ratio` | ❌ | |
| 7 | 기타주요비율 | `FHKST66430500` | `/uapi/domestic-stock/v1/finance/other-major-ratios` | ❌ | |
| 8 | 안정성비율 | `FHKST66430600` | `/uapi/domestic-stock/v1/finance/stability-ratio` | ❌ | |
| 9 | 성장성비율 | `FHKST66430800` | `/uapi/domestic-stock/v1/finance/growth-ratio` | ❌ | |
| 10 | 신용가능종목 | `FHPST04770000` | `/uapi/domestic-stock/v1/quotations/credit-by-company` | ❌ | (신용 매매 시) |
| 11 | 배당일정 | `HHKDB669102C0` | `/uapi/domestic-stock/v1/ksdinfo/dividend` | ❌ | (장차 — 배당 알림) |
| 12~22 | 예탁원 정보 (주식매수청구·합병/분할·액면교체·자본감소·상장·공모주청약·실권주·의무예치·유상증자·무상증자·주주총회) | `HHKDB669xxxC0` | `/uapi/domestic-stock/v1/ksdinfo/...` | ❌ | (이벤트 알림 시) |
| 23 | 종목추정실적 | `HHKST668300C0` | `/uapi/domestic-stock/v1/quotations/estimate-perform` | ❌ | (애널리스트 컨센) |
| 24 | 대주가능 종목 | `CTSC2702R` | `/uapi/domestic-stock/v1/quotations/lendable-by-company` | ❌ | |
| 25 | 종목투자의견 | `FHKST663300C0` | `/uapi/domestic-stock/v1/quotations/invest-opinion` | ❌ | (애널리스트 의견) |
| 26 | 증권사별 투자의견 | `FHKST663400C0` | `/uapi/domestic-stock/v1/quotations/invest-opbysec` | ❌ | |

## 3. 기본시세 API ([국내주식] 기본시세)

| # | 명칭 | TR_ID | endpoint | 모의 | magicJar 사용 |
|---|---|---|---|---|---|
| 1 | **주식현재가 시세** | **`FHKST01010100`** | **`/uapi/domestic-stock/v1/quotations/inquire-price`** | ✅ 지원 | ✅ **사용 중** (PR #128) — `KisInstrumentInfoAdapter.fetchPriceMetrics` (PER·PBR·EPS·BPS·시총·업종한글명) |
| 2 | 주식현재가 시세2 | `FHPST01010000` | `/uapi/domestic-stock/v1/quotations/inquire-price-2` | ❌ | |
| 3 | 주식현재가 체결 | `FHKST01010300` | `/uapi/domestic-stock/v1/quotations/inquire-ccnl` | ✅ | (장차 — 실시간 체결 list) |
| 4 | 주식현재가 일자별 | `FHKST01010400` | `/uapi/domestic-stock/v1/quotations/inquire-daily-price` | ✅ | (D1 대체 가능) |
| 5 | 주식현재가 호가/예상체결 | `FHKST01010200` | `/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn` | ✅ | (장차 — 호가창 표시) |
| 6 | 주식현재가 투자자 | `FHKST01010900` | `/uapi/domestic-stock/v1/quotations/inquire-investor` | ✅ | (장차 — 외국인·기관 동향) |
| 7 | 주식현재가 회원사 | `FHKST01010600` | `/uapi/domestic-stock/v1/quotations/inquire-member` | ✅ | (장차 — 매수/매도 회원사) |
| 8 | **국내주식 기간별 시세 (일/주/월/년)** | **`FHKST03010100`** | **`/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice`** | ✅ | ✅ **사용 중** — `RealKisRestAdapter.fetchCandles` (D1) |
| 9 | 주식 당일 분봉 조회 | `FHKST03010200` | `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | ✅ | ⚠️ **당일 분봉 전용** (과거 backfill 불가) — yfinance 사용 (ADR 0048) |
| 10 | **주식 일별 분봉 조회 (과거)** | **`FHKST03010230`** | **`/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice`** | ❌ 미지원 | 🟡 **검토 대상** — 모의 미지원이라 우리는 yfinance 사용. 실전 키 사용 시 yfinance 대체 가능? |
| 11 | 당일 시간대별 체결 | `FHPST01060000` | `/uapi/domestic-stock/v1/quotations/inquire-time-itemconclusion` | ✅ | |
| 12~13 | 시간외 일자별·시간별 체결 | `FHPST02320000`·`FHPST02310000` | `inquire-daily-overtimeprice`·`inquire-time-overtimeconclusion` | ✅ | |
| 14~16 | 시간외 현재가·호가·예상체결 | `FHPST02300000` 등 | `inquire-overtime-*` | ❌ | |
| 17 | **ETF/ETN 현재가** | `FHPST02400000` | `/uapi/etfetn/v1/quotations/inquire-price` | ❌ | (장차 — ETF 시세 분리) |
| 18~21 | ETF 구성종목·NAV | 다양 | `/uapi/etfetn/v1/quotations/...` | ❌ | |

## 4. 사용 중인 endpoint 매핑 표 (magicJar 코드 → KIS endpoint)

| magicJar 코드 | KIS endpoint | TR_ID | 비고 |
|---|---|---|---|
| `KisInstrumentInfoAdapter.fetchInstrumentInfo` | `search-stock-info` | `CTPF1002R` | 종목명·자본금·상장주식수·industry 코드 |
| `KisInstrumentInfoAdapter.fetchPriceMetrics` | `inquire-price` | `FHKST01010100` | PER·PBR·EPS·BPS·시총(억원)·업종 한글명 |
| `KisMarketIndexAdapter.fetchMarketIndex` | `inquire-index-price` | `FHPUP02100000` | KOSPI/KOSDAQ 지수 |
| `RealKisRestAdapter.fetchCandles` (D1) | `inquire-daily-itemchartprice` | `FHKST03010100` | 일봉/주봉/월봉/년봉 |
| `RealKisRestAdapter.fetchQuote` | `inquire-price` | `FHKST01010100` | 현재가·시가·고가·저가·거래량 |
| `MinuteCandleBackfillJob` (deprecate 후보) | `inquire-time-itemchartprice` | `FHKST03010200` | 당일 분봉만 — yfinance 대체 (ADR 0048) |

## 5. 후속 검토 endpoint (다음 사이클 후보)

### A. 분봉 backfill 대안 — `FHKST03010230` (주식 일별 분봉 조회 / 과거)
- 사용자 안내 list `#10`. **모의투자 미지원이지만 실전 키 사용 시 가능**
- 우리는 yfinance(30일 한계) 대체 중. KIS 실전이 더 긴 기간 가능하면 전환 검토
- 단점: KIS rate limit 있음 (yfinance는 무제한)

### B. 호가창·체결 list — `inquire-asking-price-exp-ccn` + `inquire-ccnl`
- 종목 상세 화면 호가창·실시간 체결 list 표시 시
- WebSocket이 더 적합 (실시간 stream) — 다만 REST polling도 옵션

### C. 외국인·기관 동향 — `inquire-investor`
- 종목 상세 메타에 외국인·기관 매수·매도 추세 표시
- `InstrumentDetail.foreignRatio`·`institutionRatio` 정확화

### D. 종목 추정 실적 — `HHKST668300C0`
- 애널리스트 컨센서스 (매출·영업이익 추정)
- 실제 재무(DART) 대비 갭 표시

### E. 종목 투자의견 — `FHKST663300C0`·`FHKST663400C0`
- 애널리스트 의견·증권사별 의견 표시
- 종목 상세 페이지 사이드 패널 후보

## 6. KIS 종목 마스터파일 다운로드 (참고)

KIS 공식 GitHub repo 파이썬 스크립트:
- KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
- KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py

**구조**: HTTP에서 `kospi_code.mst.zip`·`kosdaq_code.mst.zip` 다운로드 → 고정 폭 텍스트 파일 파싱 → DataFrame.

**magicJar 사용 위치**: T3 R1(PR #71) `instrument` 테이블 적재. 우리 코드는 자체 다운로드·파싱 구현. 향후 정합 검증 시 KIS 공식 스크립트 참조.

**적재 빈도**: 마스터파일은 월 1회 정도 갱신. 사용자 SettingsModal "종목 마스터파일 수집" 버튼 또는 batch scheduled job.

## 7. MCP 검증 의무

새 KIS endpoint 사용 시 자체 추측 금지. 다음 도구로 검증:

```
mcp__kis-code-assistant__search_domestic_stock_api(query="...")
```

검증 항목:
- 정확한 endpoint path
- TR_ID
- query parameter / request body 필드
- response schema 필드명·단위(억원 vs 원, 백분율 vs 소수)
- 모의투자 지원 여부

**MCP 결과가 사용자 안내와 일치하는지 교차 검증**. 사용자 안내가 가장 정확한 source (사용자 KIS 공식 가이드 직접 참조).

## 변경 이력

| 날짜 | 변경 | PR |
|---|---|---|
| 2026-05-04 | 본 reference docs 신설 + 사용 endpoint 표·후속 검토 list | TBD |
| 2026-05-04 | `inquire-price` (FHKST01010100) 통합 — PER·PBR·EPS·BPS·시총·업종 한글명 | #128 |
| 2026-05-04 | `search-stock-info` endpoint 정정 (`inquire-stock-info` 잘못된 사용) | #126 |
