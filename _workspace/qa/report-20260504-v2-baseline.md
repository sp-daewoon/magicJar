# QA Report — v2.0.0 베이스라인 (2026-05-04)

## 메타

| 항목 | 값 |
|---|---|
| 일시 | 2026-05-04 KST |
| 트리거 | ADR 0016 #6 (Phase 통합·실배포 직전 — v2.0.0 베이스라인 컷) |
| 검증 범위 | 분봉 아키 v2 + KIS·DART 통합 + 종목 메타 + 기본시세 3종 + 개발일지 + 하네스 |
| 결과 | **APPROVED** |

## 1. 컨테이너 상태 — 9/9 healthy

```
magicjar-api          Up 2m   (healthy)
magicjar-batch        Up 3h   (healthy)
magicjar-consumer     Up 13h  (healthy)
magicjar-frontend     Up 3h   (healthy)
magicjar-kafka        Up 14h  (healthy)
magicjar-minio        Up 14h  (healthy)
magicjar-postgres     Up 14h  (healthy)
magicjar-pyworker     Up 5h   (healthy)
magicjar-redis        Up 14h  (healthy)
```

## 2. 데이터베이스 — Flyway V0~V20 적용

| 버전 | 설명 | 상태 |
|---|---|---|
| V20 | candle 1m only (M5/M15/M30/H1 row DELETE + CHECK constraint) | ✅ |
| V19 | instrument_cache_meta (LRU eviction) | ✅ |
| V18 | mcp_call_log | ✅ |
| V17 | ai_analysis_log | ✅ |
| V0~V16 | 기존 베이스라인 | ✅ |

## 3. REST API — 11 endpoint × HTTP 200 ✅

| endpoint | HTTP | 검증 |
|---|---|---|
| `GET /api/instruments/{code}/detail` | 200 | name·marketCap·sharesOutstanding·foreignRatio·revenue·operatingIncome·netIncome·per·pbr·eps·bps·industry 모두 응답 |
| `GET /api/instruments/{code}/quote` | 200 | LAST_DAY_CLOSE 220500 정상 |
| `GET /api/instruments/{code}/candles?interval=D1` | 200 | 일봉 응답 |
| `GET /api/instruments/{code}/candles?interval=M5&limit=10` | 200 | M1 → M5 합성 + `X-Has-More: true` `X-Total-Count: 10` |
| `GET /api/instruments/{code}/orderbook` | 200 | 호가창 schema |
| `GET /api/instruments/{code}/trades?limit=5` | 200 | 체결 list (장 마감 시 빈 array 정상) |
| `GET /api/instruments/{code}/investors?days=5` | 200 | 외국인·기관 동향 |
| `GET /api/market/index?codes=KOSPI,KOSDAQ` | 200 | KOSPI 6598.87 / KOSDAQ 1192.35 + prevClose·changePercent 정상 |
| `GET /api/instruments/search?q=...` | 200 | ETF/공모주 필터 정합 |
| `GET /api/devlog/list` | 200 | 일자별 HTML 목록 |
| `GET /api/harness/dashboard` | 200 | dashboard.html raw |

## 4. 단위·통합 테스트

| 모듈 | 결과 |
|---|---|
| `:domain:test` | ✅ PASS |
| `:api:test` | ✅ PASS |
| `:batch:test` | ✅ PASS |
| `:consumer:test` | ✅ PASS |
| `:api:ktlintMainSourceSetCheck` | ✅ PASS |
| `:api:ktlintTestSourceSetCheck` | ✅ PASS |
| `:batch:ktlintMainSourceSetCheck` | ✅ PASS |
| `:consumer:ktlintMainSourceSetCheck` | ✅ PASS |
| `:domain:ktlintMainSourceSetCheck` | ✅ PASS |

## 5. 종목 상세 메타 (samsung 005930) 정합 ✅

```json
{
  "code": "005930", "name": "삼성전자", "market": "KOSPI",
  "marketCap": 1289104400000000,    // 1,289조
  "sharesOutstanding": 5846278608,
  "foreignRatio": 49.24,
  "revenue": 333605938000000,       // DART
  "operatingIncome": 43601051000000,
  "netIncome": 44260956000000,
  "per": 33.59, "pbr": 3.45,
  "eps": 6564.00, "bps": 63997.00,
  "industry": "전기·전자"
}
```

KIS `search-stock-info`(CTPF1002R) + `inquire-price`(FHKST01010100) + DART `fnlttSinglAcntAll` 3-way 병합 정상.

## 6. 분봉 아키 v2 (ADR 0048) 정합

- candle 테이블: D1·M1 only (V20 CHECK constraint)
- M5·M15·M30·H1 = 백엔드 `CandleAggregator` 합성
- W·MO·Y = D1 합성
- yfinance cold-start backfill (신규 종목 자동)
- KIS WS tick → 1분봉 자체 누적 (`TickToMinuteAggregator`)

## 7. 사용자 정책 정합 검증

| 정책 | 상태 |
|---|---|
| 데이터 조회 = 항상 실전 키 | ✅ `application.yml` `magicjar.kis.app-key: ${KIS_APP_KEY}` |
| 주문 = 모의/실전 분리 | ✅ `KisCredentialProvider` trading_mode 분기 |
| `MAGICJAR_SECRET_MASTER_KEY` = .env 단일 source | ✅ |
| Mock 데이터 미사용 | ✅ frontend mock 폐기 (PR #115) |
| 로그 INFO 레벨 + Kafka WARN | ✅ logback-spring.xml + application.yml |
| 검색 결과 ETF·공모주·상장폐지 필터 | ✅ `searchByCodeOrName` native query 8단 필터 |

## 8. ADR·spec 정합

- ADR 0048 (분봉 아키 v2): 1분봉 단일 영속 + 백엔드 합성 + Yahoo 하이브리드 — 적용 완료
- spec `2026-05-04-candle-architecture-v2-1m-only-and-yahoo.md` — A·B·C·D 4영역 머지 완료
- `docs/operations/initial-setup.md` — 초기 설치 가이드
- `docs/operations/kis-api-reference.md` — KIS endpoint 정리

## 9. 알려진 미완·후속 (post-v2.0.0)

| 항목 | 사유 | 후속 |
|---|---|---|
| frontend warnings UI 통합 | PR #127 backend 응답 schema 정합 — frontend 표시 코드는 들어감, 실제 fail 케이스 거의 없어 사용자 시각 검증 필요 | 다음 사이클 |
| 호가창·체결·외국인 frontend UI | PR #131 backend endpoint만 제공. frontend 통합 미수행 | 다음 사이클 |
| FHKST03010230 PoC | 사용자 결정 — 별도 plan 후 진행 | 사용자 일정 |
| KIS 마스터 sync `type` 세분화 (ETF·ETN·REIT) | 키워드 필터 임시 사용 중 | 다음 사이클 |

## 10. 결론

**APPROVED — v2.0.0 베이스라인 컷 진행 가능.**

- 사용자 검증 완료: 검색 기록·Enter·종목 변경 가격 reset·Dashboard 카드·DART 재무 응답
- 핵심 흐름 200 응답: 종목 상세·차트·시세·지수·검색·devlog·harness
- 모든 단위 테스트 + ktlint PASS
- 9 컨테이너 healthy
- 사용자 정책(키 source·로그·mock 폐기) 정합

## 서명

`magicjar-architect` (메인 셀프 QA) — ADR 0030 정합
