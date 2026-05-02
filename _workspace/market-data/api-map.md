# market-data API Map — T3 트랙 (종목 마스터)

## KIS 종목 마스터 정적 파일 서버

| 항목 | 값 |
|------|-----|
| 방식 | HTTP 파일 다운로드 (REST API 아님, trId 없음) |
| Base URL | `https://new.real.download.dws.co.kr/common/master` |
| 인코딩 | CP949 (줄 읽기는 ISO_8859_1 + 필드별 CP949 디코딩) |
| 레이트리밋 | 없음 (정적 파일 서버) — 1일 1회 cron 실행 |
| 인증 | 없음 |

### 파일 목록

| 시장 | 파일명 | Part2 크기 |
|------|--------|-----------|
| KOSPI | `kospi_code.mst.zip` → `kospi_code.mst` | 228 bytes |
| KOSDAQ | `kosdaq_code.mst.zip` → `kosdaq_code.mst` | 222 bytes |
| KONEX | `konex_code.mst.zip` → `konex_code.mst` | 222 bytes |
| ETF | KOSPI/KOSDAQ 파일에 포함 (별도 파일 없음) | — |
| ETN | KOSPI/KOSDAQ 파일에 포함 (별도 파일 없음) | — |

### MST 파일 레이아웃

```
[Part1]
  Byte  0 -  8 : 단축코드 (9 bytes, CP949, 6자리 앞 패딩)
  Byte  9 - 20 : ISIN (12 bytes, CP949)
  Byte 21 - (N-Part2Size-1) : 한글 종목명 (CP949, variable)

[Part2] 공통 offset (KOSPI 228 / KOSDAQ·KONEX 222 bytes)
  offset  0 -  1 : 패딩 (2 bytes)
  offset  2 -  5 : 업종코드 (4 bytes)
  offset 44      : 거래정지 여부 ('Y'/'N', 1 byte)
  offset 45 - 49 : 거래 수량 단위 (5 bytes, right-padded)
  offset 47 - 58 : 액면가 (12 bytes, right-padded)  ← offset 47 기준
  offset 79 - 86 : 상장일 (8 bytes, yyyyMMdd)
```

## 저장 구조

### PostgreSQL (Flyway V12)

| 테이블 | 용도 |
|--------|------|
| `instrument` | 종목 마스터 (ALTER 추가: isin, name_en, type, sector, listed_at, delisted_at, par_value, trading_unit, active) |
| `instrument_alias` | 종목코드 별칭 (예: 005930 ↔ 삼성전자) |
| `instrument_sync_log` | 마켓별 동기화 이력 |

인덱스:
- `idx_instrument_market` — market 컬럼
- `idx_instrument_status` — active + delisted_at
- `idx_instrument_name` — name GIN trgm (향후 pg_trgm 활성화 시)

### Redis (캐시)

| 키 | 타입 | 용도 |
|----|------|------|
| `instrument:idx:code` | ZSET | 코드 prefix 검색 (ZRANGEBYLEX) — score=0, member=`{CODE}\|{code}` |
| `instrument:idx:name` | ZSET | 종목명 substring 검색 — score=0, member=`{name}\|{code}` |
| `instrument:data:{code}` | HASH | 종목 상세 캐시 (TTL 1일) |
| `instrument:list:{MARKET}` | ZSET | 시장별 universe — score=0, member=`{code}` (TTL 1일) |

## REST API (api 모듈)

| 메서드 | 경로 | 설명 | 파라미터 |
|--------|------|------|----------|
| GET | `/api/instruments/search` | 종목 검색 | `q` (query), `limit` (max 100, default 20) |
| GET | `/api/instruments/{code}` | 종목 상세 조회 | `code` (6자리) |
| GET | `/api/instruments/{code}/candles` | 캔들 조회 | `interval`, `from`, `to` |

## 스케줄러 (batch 모듈)

| Job | Cron | Profile | 설명 |
|-----|------|---------|------|
| `InstrumentMasterSyncJob` | `0 0 6 * * *` (KST 06:00) | dev, prd | KOSPI→KOSDAQ→KONEX 순차 upsert + 상장폐지 처리 |
