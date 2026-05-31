# ADR 0105: 캔들 Reconciliation + 장기 1m 보관/복구 정책

- 상태: **Accepted (2026-05-31 사용자 결정)**
- 작성일: 2026-05-31
- 결정자: Codex (협의: 최고 관리자)
- 영역: 시장 데이터 · 캔들 정합성 · 자동매매 data readiness
- 관련: ADR 0013(시장 세션) · ADR 0019(캔들 저장 정책) · ADR 0028(Watchlist Ingestion) · ADR 0029(당일 진행 중 캔들 Redis) · ADR 0037(KIS Rate Limit) · ADR 0043(KillSwitch) · ADR 0057(KIS-only) · ADR 0062(합산 interval) · ADR 0063(NXT cover) · ADR 0086(데이터 햇징 룰) · ADR 0091(NXT resolver) · ADR 0093(차트 lazy backfill)

---

## §1. 맥락

2026-05-31 사용자(최고 관리자)가 다음 결함 가능성을 지적했다.

1. 종목 상세 화면에서 A 종목을 보다가 B 종목으로 이동한 뒤 A로 돌아오면, A의 WS 구독이 끊겨 있던 구간의 1분봉이 비어 있을 수 있다.
2. 시스템을 하루 이상 꺼두고 3일 뒤 재기동하면, 꺼져 있던 기간의 1m·합산봉·일봉/주봉/월봉/년봉이 누락될 수 있다.
3. 4h·12h 같은 전략 기준봉을 Strategy Builder와 Screener에서 의미 있게 쓰려면 30거래일보다 긴 1m 원천 데이터가 필요하다.
4. KIS `FHKST03010230`은 과거 1분봉 복구 source가 될 수 있으나, 1년치를 가져오면 종목당 약 10만~18만 row가 되어 TPS·큐잉·DB 용량 전략이 필요하다.

기존 정책은 KIS-only, DB 확정 캔들, Redis 진행 중 current candle, 장중 실시간 권위 KIS WS tick이라는 방향이 맞다. 그러나 구현과 문서에는 30거래일 hardcode가 남아 있어 4h·12h 전략과 장기 backtest에는 부족하다.

## §2. KIS 공식 제약과 magicJar 운영 기준

### §2-1. KIS 공식 분봉 API

KIS 공식 `open-trading-api` 샘플 기준:

| 용도 | endpoint | tr_id | 공식 한도/특성 |
|---|---|---|---|
| 당일 분봉 | `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | `FHKST03010200` | 실전/모의 모두 1회 최대 30건. **당일 분봉만 제공, 전일자 분봉 미제공** |
| 과거 일별 분봉 | `/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice` | `FHKST03010230` | 실전계좌 1회 최대 120건. `FID_INPUT_DATE_1` + `FID_INPUT_HOUR_1`로 과거일자 조회. **KIS 서버 보관분 최대 1년** |

raw 출처:
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_itemchartprice/inquire_time_itemchartprice.py`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`

### §2-2. magicJar rate limit 기준

magicJar는 ADR 0037과 `docs/architecture/external-api-policy.md` 기준으로 KIS REST를 **10 TPS 토큰 버킷**으로 제한한다. 장기 1m backfill은 사용자 path를 압박하지 않도록 BACKFILL/INGESTION_LOW priority로 큐잉한다.

## §3. 결정

### §3-1. Source-of-truth 계층

| 데이터 | 권위 |
|---|---|
| 현재가 tick | KIS WS `H0STCNT0` + `H0NXCNT0` 또는 통합 WS |
| 호가 | KIS WS 호가 stream |
| 진행 중 1m·3m·4h·12h·1d current candle | Redis `candle:current:{code}:{interval}` |
| 확정 candle | PostgreSQL `candle` table |
| 지나간 1m gap 복구 | KIS REST `FHKST03010230` |
| 당일 짧은 replay/검증 | KIS REST `FHKST03010200` 또는 `FHKST03010230` 날짜 지정 |
| 1d·1wk·1mo·1y 확정/검증 | KIS REST `FHKST03010100` |

장중에는 기존 방향 그대로 WS tick이 Redis current candle의 close/high/low/volume을 갱신한다. REST는 실시간 권위가 아니라 과거/누락 복구와 마감 검증이다.

### §3-2. 1m 보관 목표

| 레벨 | 범위 | 용도 | 정책 |
|---|---:|---|---|
| Operational default | **최근 6개월 거래일** | 4h·12h 전략, 스크리너, dogfooding chart | 자동 reconciliation 목표 |
| Manual/admin full | **KIS 보관 최대 1년** | 장기 backtest, 데이터 재수집, 장애 후 복구 | 사용자/관리자 명시 또는 저부하 백그라운드 |
| KIS 한도 초과 | 1년 초과 1m | KIS 표준 API로 복구 불가 | DB 백업/장기 보관만 권위 |

기존 30거래일은 더 이상 목표 정책이 아니다. 구현 phase 동안 legacy fallback으로만 남고, Phase 1에서 property 기반 6개월/1년 lookback으로 교체한다.

### §3-3. Row·call 추정

| 기준 | 6개월(약 126 거래일) | 1년(약 252 거래일) |
|---|---:|---:|
| KRX 정규장 390 bar/day | 약 49,140 row | 약 98,280 row |
| NXT 통합 720 bar/day | 약 90,720 row | 약 181,440 row |
| KRX calls/day (`ceil(390/120)`) | 4 call/day | 4 call/day |
| NXT calls/day (`ceil(720/120)`) | 6 call/day | 6 call/day |
| 안전 maxPages/day | 8 call/day | 8 call/day |
| NXT 1년 최악 calls | 약 1,512 call | 약 2,016 call with safety cap |

10 TPS를 모두 쓰면 종목당 1년 NXT backfill은 약 2.5~3.4분 수준이다. 운영에서는 사용자 path를 위해 backfill budget을 2~5 TPS로 제한하므로 종목당 약 7~17분까지 걸릴 수 있다. 따라서 전체 universe 1년 backfill은 부팅 path가 아니라 background queue로 분산한다.

### §3-4. Reconciliation 트리거

| 트리거 | 대상 | 동작 |
|---|---|---|
| Consumer boot | managed universe | last candle ts 확인 → gap fill queue 등록 |
| SymbolDetail ENTER | 해당 종목 | last 1m 이후부터 현재 직전분까지 빠른 gap fill + WS subscribe |
| SymbolDetail EXIT | 해당 종목 | DETAIL owner를 즉시 삭제하지 않고 grace TTL로 전환 |
| 정기 점검 | active universe | 장중 15분마다 짧은 gap 검사, 20:10 KST 마감 검증 |
| Daily/weekly maintenance | managed universe | 1d·1wk·1mo·1y 갱신 + 1m 6개월 target 유지 |
| Manual recollect | 사용자 선택 종목/interval | 해당 interval 삭제 후 KIS 원천 재수집, 합산봉 재생성 |

### §3-5. Managed universe

Reconciliation 대상은 watchlist 단일 출처가 아니다.

1. 보유 종목과 미체결 주문 종목
2. ACTIVE 전략이 참조하는 종목/유니버스
3. watchlist
4. 최근 SymbolDetail 진입 종목
5. Screener/Strategy 후보 TTL 종목

priority는 자본 path 기준으로 `holdings/open orders > active strategies > detail > watchlist > screener candidates > manual deep backfill` 순서다.

### §3-6. Data readiness gate

자동매매는 candle reconciliation 상태를 확인해야 한다.

| 상태 | 의미 | 자동매매 |
|---|---|---|
| `READY` | 필요한 interval의 최신 확정 candle + current candle merge 가능 | 전략 평가 허용 |
| `RECONCILING` | gap fill 진행 중 | 전략 평가 skip, 주문 발행 금지 |
| `STALE` | 마지막 candle이 세션 기준 stale | 전략 평가 skip, 필요 시 KillSwitch 검토 |
| `FAILED` | KIS fetch 반복 실패 또는 sanity check 실패 | 전략 평가 skip + audit/alert |

전략 평가는 `SIGNAL_SKIPPED_DATA_NOT_READY` audit를 남기고 skip한다. 기존 주문·리스크 gate를 우회하지 않는다.

### §3-7. 합산 interval 재생성

1m gap fill 성공 후에는 3m·5m·15m·30m·90m·1h·4h·12h를 `CandleAggregator`로 재생성한다. 4h·12h 전략을 지원하기 위해 최소 6개월 1m 원천을 유지한다.

합산봉은 KIS 직접 source가 아니다. 1m 원천이 결손이면 합산봉도 `READY`가 될 수 없다.

### §3-8. 장중 current candle

장중에는 현재 구현 방향을 유지한다.

```
KIS WS tick
  -> quote:last:{code}
  -> candle:current:{code}:1m
  -> candle:current:{code}:{3m|5m|...|12h|1d|1wk|1mo|1y}
  -> market.tick.v1
```

REST backfill은 닫힌 과거 candle만 채우며, 현재 진행 중 bar는 Redis current가 우선한다.

### §3-9. 실전 운용 시나리오 acceptance matrix

2026-06-01 사용자 지시에 따라 다음 5개 시나리오는 ADR 0105 구현의 acceptance 기준으로 LOCK한다. `DETAIL` TTL은 gap 가능성을 줄이는 완충 장치일 뿐, 캔들 정합성 보장의 본체가 아니다. 정합성은 재진입 gap fill, boot reconciliation, scheduled reconciliation, data readiness gate가 함께 책임진다.

| 상황 | 현재 대비 | 실전 기준 필요한 보강 |
|---|---|---|
| 종목 A 보다가 B 갔다가 A 재진입 | `DETAIL` TTL/grace가 있어 짧은 이탈은 완충하지만, EXIT 이후 WS가 끊긴 구간의 닫힌 1m candle은 비어 있을 수 있다 | `SymbolDetail ENTER`마다 해당 종목의 마지막 DB 1m 이후부터 현재 직전분까지 REST gap fill을 queue에 넣고, 동시에 KIS WS 현재가·호가를 구독한다 |
| 시스템 꺼짐 후 3일 뒤 부팅 | watchlist는 `CandleBootstrapRunner`가 stale 감지 가능하지만, watchlist 외 상세 진입 종목·전략 후보·자동매매 대상 universe는 누락될 수 있다 | boot 시 managed universe를 재산정한다. 최소 대상은 보유/미체결, ACTIVE 전략 universe, 최근 상세 진입 종목, watchlist, screener/strategy 후보 TTL snapshot이다 |
| 3일간 빠진 1분봉 | KIS `FHKST03010230`로 KIS 보관 1년 안의 지난 1m을 복구할 수 있다 | boot/scheduled reconciliation이 1m gap을 자동 채우고, 성공 후 3m·5m·15m·30m·90m·1h·4h·12h 합산봉을 재생성한다 |
| 1년 넘게 꺼짐 | KIS 표준 API의 1m 보관 한도를 넘어선 구간은 복구할 수 없다 | DB 백업/장기 보관/외부 유료 데이터가 없으면 1m 구멍을 인정한다. UI와 readiness 상태는 `UNRECOVERABLE` 또는 `FAILED`로 표시하고, 필요한 전략 window 안에 있으면 자동매매를 허용하지 않는다 |
| 자동매매 | stale candle 상태에서 전략 평가와 주문 발행이 가능하면 자본 손실 위험이 있다 | data readiness가 `READY`가 아니면 전략 평가는 `SIGNAL_SKIPPED_DATA_NOT_READY`로 skip하고 주문 발행은 fail-closed로 차단한다 |

### §3-10. Progress streaming 원칙

장기 1m backfill/recollection은 candle row 자체를 STOMP로 대량 전송하지 않는다. 6개월~1년 1m은 종목당 수만~십수만 row가 될 수 있으므로, STOMP는 진행 상태만 전송한다.

| 경로 | 정책 |
|---|---|
| 캔들 데이터 본문 | DB 확정 candle + REST pagination (`GET /api/symbols/{code}/candles`) |
| 진행 중 현재봉 | KIS WS tick → Redis `candle:current:{code}:{interval}` → API merge |
| backfill/recollect 진행률 | Kafka/STOMP progress event (`jobId`, `code`, `interval`, `status`, `targetFrom`, `targetTo`, `processedDays`, `insertedCount`, `failedCount`, `lastCompletedAt`) |
| UI 반응 | 기존 캔들을 유지한 채 `데이터 보정 중` 상태를 표시하고, chunk 완료 또는 DONE 때 REST query를 invalidate/re-fetch |

## §4. 구현 계획

### §4-1. Phase 1: 1m lookback 확장

- `KisRestCandleAdapter`의 30거래일 hardcode를 property로 교체
  - `magicjar.candle.one-minute.default-lookback-trading-days=126`
  - `magicjar.candle.one-minute.max-lookback-trading-days=252`
  - `magicjar.candle.one-minute.max-pages-per-day=8`
- `FHKST03010230` 날짜+시간 cursor pagination을 helper로 분리한다.
- 기존 `insertIgnore` 멱등성을 유지한다.

### §4-2. Phase 2: Boot/Scheduled Reconciliation

신규 `CandleReconciliationWorker`를 추가한다.

| 컴포넌트 | 책임 |
|---|---|
| `CandleReconciliationWorker` | managed universe 산정, gap 검사, backfill queue 등록 |
| `CandleReconciliationState` | code·interval·targetFrom·targetTo·status·gapCount·lastCheckedAt·batchId 기록 |
| `SymbolDetailMinuteGapFillService` | SymbolDetail ENTER 시 마지막 DB 1m 이후 현재 직전분까지 빠른 보정 큐잉 |
| `DataReadinessService` | 전략 평가 전 `READY` 여부 확인 |

Phase 2는 다음 순서로 구현한다.

| Phase | 범위 | 완료 조건 |
|---|---|---|
| 2-A | async reconciliation job + progress event | 수동 재수집/장기 1m backfill은 HTTP 동기 대기 대신 `jobId`를 반환하고, STOMP는 progress만 push한다 |
| 2-B | SymbolDetail ENTER gap fill | A→B→A 재진입 시 마지막 DB 1m 이후부터 현재 직전분까지 gap fill이 자동 queue 된다 |
| 2-C | boot managed universe reconciliation | 재기동 시 watchlist 외 보유/미체결·ACTIVE 전략·최근 상세·후보 TTL snapshot까지 reconciliation 대상에 포함한다 |
| 2-D | 1m gap fill 후 합산봉 재생성 | 1m 보정 성공 구간의 3m~12h 합산봉이 재생성된다 |

2026-06-01 1차 구현은 위 Phase 2-B/2-C/2-D와 Phase 3의 핵심 gate를 코드에 연결했다. `SymbolDetailMinuteGapFillService`는 Kafka `symbol.detail.command.v1`의 `ENTER`에서 비동기 gap fill을 큐잉하고, `ManagedCandleUniverseProvider`는 보유/미체결·ACTIVE 명시 전략·현재 realtime 수요·최근 상세·watchlist·strategy 후보 TTL snapshot을 boot reconciliation 대상으로 합친다. 1m gap fill 성공 시 `AggregateBackfillRunner`가 3m~12h를 upsert로 재생성하고, `RealtimeSignalGenerator`는 `DataReadinessService`가 `READY`가 아닐 때 `SIGNAL_SKIPPED_DATA_NOT_READY` audit 후 signal 생성을 중단한다.

2026-06-01 보강 구현은 SymbolDetail gap fill을 장기 lookback worker에서 분리했다. `KisOneMinuteCandleRangePort`가 `FHKST03010230` 날짜+시간 cursor를 `from..to` 범위로 제한하고, 상세 진입 path는 `lastDb1m + 1분`부터 현재 직전분까지만 조회한다. `lastDb1m`이 없으면 당일 NXT 통합 세션 시작(08:00 KST)부터만 빠르게 복구하고, 장기 cold-start는 boot/managed universe reconciliation이 책임진다.

### §4-3. Phase 3: Strategy gate

`RealtimeSignalGenerator`와 Screener/Backtest path는 필요한 interval의 data readiness를 확인한다. `READY`가 아니면 HOLD가 아니라 skip이다. HOLD는 정상 평가 결과이고, 데이터 미준비는 운영 상태다.

### §4-4. Phase 4: UI/운영 표시

- Symbol Chart: `데이터 보정 중`, `실시간 연결됨`, `마지막 확정 candle`, `현재봉 갱신 중` 상태를 분리 표시
- Strategy Builder/Screener: 기준봉 데이터 부족 시 실행 버튼 disable 또는 queued 상태 표시
- Admin: reconciliation run/dry-run/retry endpoint 제공

## §5. 운영 스케줄 LOCK

| 시각(KST) | 동작 |
|---|---|
| boot 즉시 | managed universe reconciliation queue 등록 |
| 장중 15분 주기 | active universe 1m gap 검사 |
| 20:10 | NXT 포함 daily close 검증 + 1d 확정 + 합산봉 재생성 |
| 03:20 | 저부하 deep backfill queue 진행 |
| 주 1회 | 6개월 target cover 감사 + 1년 full 후보 보고 |

20:10은 NXT 20:00 마감 이후 10분 여유다. KRX-only 종목의 16:00 finalizer는 유지 가능하지만, NXT 후보는 20:10 기준으로 최종 검증해야 한다.

## §6. 리스크와 방어

| 리스크 | 방어 |
|---|---|
| KIS 10 TPS 초과 | ADR 0037 RateLimiter 필수, long backfill은 BACKFILL/INGESTION_LOW priority |
| 부팅 시간이 길어짐 | boot path는 queue 등록까지만, full backfill은 background |
| 자동매매 stale data | data readiness gate fail-closed |
| 1년 초과 1m 복구 불가 | DB 장기 보관/backup만 권위, KIS로는 복구 불가 표시 |
| 잘못된 KIS 응답/허봉 | ADR 0086 sanity check + fake tick 포함 여부 명시 |
| 합산봉과 1m 불일치 | 1m gap fill 후 합산봉 재생성, 합산봉 직접 수정 금지 |

## §7. 거부한 대안

| 대안 | 거부 사유 |
|---|---|
| WS만으로 모든 당일 gap 복구 | WS는 켜진 이후 tick만 준다. 꺼진 동안/화면 이탈 구간 복구 불가 |
| 모든 종목 1년 1m을 boot에서 동기 수집 | 부팅 지연과 KIS TPS 점유가 크다 |
| 4h·12h를 일봉에서 근사 합성 | intraday 전략 기준봉 왜곡. mock/근사 데이터 금지 |
| 30거래일 유지 | 4h·12h 전략과 6개월 최소 요건 불충족 |

## §8. 변경 이력

| 일자 | 변경 |
|---|---|
| 2026-06-01 | 사용자 실전 운용 시나리오 5건을 acceptance matrix로 추가. A→B→A 재진입 gap fill, 3일 off 후 boot managed universe reconciliation, 3일 1m gap 자동 복구+합산봉 재생성, 1년 초과 1m 복구 불가 명시, 자동매매 data readiness fail-closed를 LOCK. 장기 backfill/recollect는 candle row streaming이 아니라 progress event streaming으로 처리하는 원칙을 추가. |
| 2026-06-01 | ADR 0105 1차 구현 연결. SymbolDetail ENTER 1m gap fill queue, boot managed universe provider, 1m gap fill 후 aggregate upsert 재생성, realtime signal data readiness gate를 반영. |
| 2026-06-01 | SymbolDetail 1m gap fill 보강. 장기 lookback worker 호출 대신 `KisOneMinuteCandleRangePort`로 `lastDb+1m..previousMinute`만 조회하는 range path를 추가하고, source `KIS-RANGE-GAPFILL` 및 삽입 시 aggregate rebuild 정책을 반영. |
| 2026-05-31 | ADR 0105 신설. KIS `FHKST03010230` 최대 1년 보관분을 활용한 1m 장기 복구, 기본 6개월 1m retention, boot/re-entry/scheduled reconciliation, data readiness gate, 4h·12h 전략용 합산봉 재생성 정책을 Accepted로 LOCK. |
