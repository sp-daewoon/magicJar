# market-data 수집→가공→저장→공급 흐름 (T3 트랙)

## 종목 마스터 동기화 흐름

```
[KIS 정적 파일 서버]
  https://new.real.download.dws.co.kr/common/master/{market}_code.mst.zip
        |
        | (1) HTTP ZIP 다운로드 (06:00 KST, batch 모듈)
        v
[RealKisInstrumentMasterAdapter]    <-- @Profile("dev|prd")
  (StubKisInstrumentMasterAdapter)  <-- @Profile("mock")  fixture 8건
        |
        | (2) ZIP 해제 + MST 파일 파싱
        |   - ISO_8859_1로 줄 읽기 (CP949 raw bytes 보존)
        |   - Part1(코드·ISIN·한글명) + Part2(섹터·거래정지·단위·액면가·상장일)
        v
[InstrumentRaw 리스트]
        |
        | (3) 도메인 변환 InstrumentRaw.toDomain()
        v
[Instrument 도메인 모델]
  code, isin, name, nameEn, market, type, sector,
  listedAt, delistedAt, parValue, tradingUnit, active
        |
        | (4) InstrumentCatalogAdapter.upsertAll()
        v
[PostgreSQL — instrument 테이블]
  ON CONFLICT (code) DO UPDATE
  상장폐지: markDelistedIfNotIn(activeCodes) — delistedAt 설정, 행 삭제 X (ADR 0037)
        |
        | (5) InstrumentMasterSyncJob 완료 후 rebuild 트리거
        v
[RedisInstrumentSearchAdapter.rebuild()]
  - DELETE instrument:idx:code
  - DELETE instrument:idx:name
  - ZADD instrument:idx:code  — {CODE}|{code} score=0
  - ZADD instrument:idx:name  — {name}|{code} score=0
  - HSET instrument:data:{code}  — 종목 상세 HASH (TTL 1일)
  - ZADD instrument:list:{MARKET} — universe (TTL 1일)
```

## 종목 검색 흐름 (실시간)

```
[사용자 → GET /api/instruments/search?q=삼성&limit=20]
        |
        v
[InstrumentController]
        |
        v
[InstrumentService.search(q, limit)]
        |
        v
[RedisInstrumentSearchAdapter.search()]
  Step1: ZRANGEBYLEX instrument:idx:code ["삼성", "삼성￿"] + Limit.unlimited()
         → 코드 prefix 매칭 결과
  Step2: ZRANGE instrument:idx:name 0 -1
         → 이름 substring 포함 여부 필터링
  Step3: 각 code → HGETALL instrument:data:{code} → Instrument 역직렬화
        |
        v
[InstrumentSearchResponse 리스트]
  code, name, nameEn, market, type, sector, active
        |
        v
[HTTP 200 JSON 응답]
```

## 컴포넌트 의존 관계

```
domain/
  market/instrument/
    Instrument               (도메인 모델)
    InstrumentType           (STOCK, ETF, ETN, PREFERRED, WARRANT, REIT)
    InstrumentRaw            (KIS 파싱 원시 데이터)
    port/
      KisInstrumentMasterPort  (인터페이스)
      InstrumentRepository     (인터페이스)
      InstrumentSearchPort     (인터페이스)
      KisInstrumentMasterException

batch/
  infrastructure/
    kis/master/
      RealKisInstrumentMasterAdapter  (@Profile dev|prd)
      StubKisInstrumentMasterAdapter  (@Profile mock)
    persistence/market/
      InstrumentJpa + InstrumentJpaRepository
      InstrumentCatalogAdapter        (InstrumentRepository 구현)
  job/instrument/
    InstrumentMasterSyncJob   (@Scheduled cron)

consumer/
  infrastructure/
    cache/
      RedisInstrumentSearchAdapter    (InstrumentSearchPort 구현)
    persistence/market/
      InstrumentJpa + InstrumentJpaRepository
      InstrumentCatalogAdapter

api/
  infrastructure/
    persistence/market/
      InstrumentJpa + InstrumentJpaRepository
      InstrumentCatalogAdapter
  application/instrument/
    InstrumentService
  controller/
    InstrumentController     (GET /search, GET /{code}, GET /{code}/candles)
    dto/
      InstrumentSearchResponse
```

## 데이터 흐름 타임라인

```
T+00:00  KST 06:00  InstrumentMasterSyncJob 트리거
T+00:01             KOSPI MST 다운로드 + 파싱 (~2500건)
T+00:02             KOSPI upsertAll + markDelisted
T+00:03             KOSDAQ MST 다운로드 + 파싱 (~1400건)
T+00:04             KOSDAQ upsertAll + markDelisted
T+00:05             KONEX MST 다운로드 + 파싱 (~수십건)
T+00:06             KONEX upsertAll + markDelisted
T+00:06             RedisInstrumentSearchAdapter.rebuild() — 전체 재빌드
T+00:07             Redis 인덱스 최신화 완료 → 검색 API 정확도 보장
```
