# spec — KIS WS 구독 슬롯 회계 + owner 우선순위 eviction + 채널 다이어트

- 작성: architect (opus 4.8, 1M)
- 작성일: 2026-06-11 (장중 — 구현은 장마감 후 디스패치)
- 상태: **결재 완료 (최고 관리자) — 구현 후속 디스패치**
- 권위 ADR: **ADR 0123 (Proposed)** → **ADR 0124 (Accepted, 2026-06-13)로 R3 채택 확정** — 본 spec이 ADR 0123의 구현 분해
- amend 관계: **ADR 0048 amend** (41건 → 40건 정정 + slot 산식 owner 모델 재정의 + OPSP0008 graceful path 신설), **ADR 0063 정합** (NXT WS 통합 H0UNCNT0 검토), **candle-data-policy-v2 §6 정합**
- 진입 트랙: 운영 고도화 (T-MARKET 정합 — 실시간 인입 회계 layer)

> **현행 정합 note (2026-06-13)**: 본 spec 본문의 "종목당 H0STCNT0+H0NXCNT0 이중 발사" 서술은 당시(2026-06-11) 결함 진단·R3 검토 의제 evidence로 **역사 보존**한다. R3 채널 다이어트는 **ADR 0124로 H0UNCNT0 단일 채널 채택 확정**(종목당 1채널·라이브 31→16 실측·이중 발사 경로·flag 완전 제거 2026-06-13). owner priority도 ADR 0124에서 STRATEGY(10)>DETAIL(20) 재정렬 + watchlist owner 폐지(WS→polling)로 확정됐다. 본 spec의 slot 산식(이중 채널 전제)·owner priority 일부는 ADR 0124가 권위다.

---

## 0. 한 줄 요약

> **KIS WS 구독을 "코드 × tr_id" 채널 단위로 단일 회계기에 등록·한도 인지·OPSP0008 거부 감지 + owner 우선순위 eviction + 채널 다이어트(NXT 선택화·통합 체결가 검토)로 40 한도 안에 수렴**시켜, 장중 분봉 연속성을 보장한다.

---

## 1. 컨텍스트 — 결함 진단 (실측 evidence)

### 1-1. 현상 (2026-06-11 장중, orchestrator + qa 실측)

후성(093370) 분봉이 정규장 92분 중 **35개만 적재**(차트 우측 공백). 원인 = KIS WS 구독 거부:

```
10:31:05 KIS WS 구독 응답: success=false
  {"tr_id":"H0STASP0","tr_key":"093370","msg1":"MAX SUBSCRIBE OVER"} (msg_cd OPSP0008)
```

40분간 35건 거부. 삼성전자(005930) 호가 구독도 거부.

### 1-2. 수요 산식 (실측)

| 수요 owner | 종목 수 | 채널/종목 | slot |
|---|---|---|---|
| watchlist | 14 | H0STCNT0 + H0NXCNT0 (2) | 28 |
| 페이퍼 전략 82·84 후보 (STRATEGY) | 11 | H0STCNT0 + H0NXCNT0 (2) | 22 |
| 보유 (HOLDING) | 1 | H0STCNT0 + H0NXCNT0 (2) | 2 |
| 상세 진입 호가 (DETAIL orderbook) | 가변 | H0STASP0 (1) | 1+ |
| 체결통보 (reserve) | 1 | H0STCNI0 (1) | 1 |
| **합계** | 26 종목 | — | **≥ 53 slot** |

**KIS 한도 40을 13+ slot 초과.** OPSP0008 거부 불가피.

### 1-3. KIS-first 검증 결과 (헌법 §3 — 자체 추론 0)

| 항목 | 현행 문서/코드 | KIS 공식 출처 (raw fetch) | 정정 |
|---|---|---|---|
| **WS 구독 한도** | `external-api-policy.md` §2-1 + ADR 0048 = **41건** | **40건** — `examples_user/kis_auth.py` `__runner()`: `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")` | **41 → 40 정정** (1건 과대) |
| 한도 단위 | "stream 수 단위 (sum across TR_IDs)" (ADR 0048 §1) | `open_map` key = (tr_id, tr_key) 조합 = **채널 단위**. 종목당 tr_id별 별도 slot 확정 | 정합 (코드×tr_id 채널 단위 회계 LOCK) |
| 통합 체결가 | 현행 WS = H0STCNT0 + H0NXCNT0 이중 발사 | **H0UNCNT0 "국내주식 실시간체결가(통합)"** 존재 — `examples_llm/domestic_stock/ccnl_total/ccnl_total.py`. KRX+NXT 단일 구독 cover | **R3 채널 다이어트 핵심 레버** (검토 의제) |
| 호가 통합 | H0STASP0(KRX) | H0NXASP0(NXT) + **H0UNASP0(통합)** 존재 — `domestic_stock_functions_ws.py` | R3 검토 |

**raw 출처 LOCK** (KIS open-trading-api main branch, 2026-06-11 fetch):
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` — 한도 40 enforce
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py` — H0UNCNT0 통합 체결가
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py` — H0STCNT0·H0NXCNT0·H0UNCNT0·H0STASP0·H0NXASP0·H0UNASP0·H0STCNI0

> **UNCERTAIN 태그**: KIS 공식 예제 코드는 40을 enforce하나, KIS 포털 문서가 명시 한도를 별도 공개하지 않는다. **40을 보수적 운영 상한**으로 채택(코드 enforce 값 = 실 거부 임계 추정)하되, **상수가 아닌 ENV 설정값**(`MAGICJAR_KIS_WS_MAX_SLOTS`, default 40)으로 두어 KIS 한도 변경·실측 보정 시 재배포 없이 조정 가능하게 한다. R1 실측에서 실제 거부 임계를 OPSP0008 발생 시점으로 역산해 default 검증.

---

## 2. 현행 구조 정합 (코드 읽고 확인 — evidence)

| 컴포넌트 | 현행 동작 | 갭 |
|---|---|---|
| `KisWebSocketBridge.subscribe()` (L396~435) | 종목당 H0STCNT0 + H0NXCNT0 **무조건 이중 발사** | slot 카운트·한도 인지·OPSP0008 거부 처리 **부재**. 거부돼도 `subscribedCodes`에 남아 재시도 없음 (확인 완료 — 거부 응답 핸들러 없음) |
| `RealtimeSubscriptionRouter.ensureSubscribedLocked()` (L216~243) | `SubscriptionPolicy.PRICE_TICK_MAX`(=30)만으로 H0STCNT0 종목 수 카운트 | **NXT(H0NXCNT0) 이중 발사를 회계하지 않음** → 실제 slot은 2배인데 router는 1배만 인식. 호가·체결통보 slot도 합산 안 함 |
| `OrderbookSubscriptionRouter` (L42~) | 자체 `maxSlots=10` LRU로 호가(H0STASP0) 관리 | **40 한도를 체결가 라우터와 공유한다는 인식 0** → 두 라우터 독립 카운트 → 총합 통제 부재 (OPSP0008 직접 원인) |
| OPSP0008 처리 | `KisWsMessageParser`는 **OPSP8996(ALREADY IN USE)만** 감지 | **OPSP0008(MAX SUBSCRIBE OVER) 감지·회계 보정 로직 전무** |
| `SubscriptionPolicy` | TOTAL_SLOTS=41, PRICE_TICK_MAX=30, NXT_TICK_MAX=20, ORDERBOOK_MAX=10 | 41 과대 + 라우터들이 이 상수를 단일 회계로 합산 사용 안 함 (주석상 "풀 > 20 시 backlog §53"로 미뤄짐) |
| `RealtimeDemandOwner` enum | HOLDING(0)·PENDING_BUY(5)·DETAIL(10)·STRATEGY(40) | **WATCHLIST owner 부재** (watchlist는 `WatchlistIngestionWorker` 별도 경로). owner priority가 **slot 배분에 실제 반영 안 됨** — eviction은 watchlist victim만 대상, owner 간 경합 미해결 |
| `SymbolDetailMinuteGapFillService` | `submitIfNeeded`·`runRangeFill` 존재 — WS 미수신 분봉 보완 | **OPSP0008 거부 종목 자동 gap-fill 트리거 미연결** — 확장 가능성 R1 |

**root cause 한 줄**: 두 개의 독립 라우터(체결가·호가)가 각각 부분 한도를 관리하고, NXT 이중 발사를 회계하지 않으며, 거부 응답을 감지하지 않아 — 총 점유가 40을 넘어도 시스템이 인지하지 못한 채 계속 발사한다.

---

## 3. 설계 결정 (6 의제)

### 3-1. 슬롯 budget 회계 (단일 회계기)

**핵심 결정**: 체결가·NXT·호가·체결통보·시간외를 **단일 회계기**(`WsSlotBudgetLedger`)가 "채널 = (code, trId)" 단위로 추적한다. 두 라우터의 독립 카운트를 폐기하고 단일 진실원으로 통합.

| 항목 | 결정 |
|---|---|
| 한도 인지 | `MAGICJAR_KIS_WS_MAX_SLOTS` ENV (default **40**, 상수 아님 — §1-3 UNCERTAIN 보정 여지). reserve(체결통보 1) 제외 실효 = 39 |
| 점유 추적 | 채널 단위 `Set<(code, trId)>`. subscribe/unsubscribe 발사 직후 ledger 갱신. KisWebSocketBridge의 `subscribedCodes`/`nxtSubscribedCodes`/`orderbookSubscribedCodes` 를 ledger가 단일 view로 집계 |
| 거부 응답 감지 | `KisWsMessageParser`에 **OPSP0008(MAX SUBSCRIBE OVER) 파서 추가** (OPSP8996 패턴 1:1). 감지 시 ledger에서 해당 (code, trId) 채널을 **회계 보정**(점유 취소 표시) + eviction/대기열 트리거 |
| 가용 slot 산식 | `available = maxSlots - reserved(1) - occupiedChannels.size`. subscribe 발사 **직전** ledger가 available 검사 → 부족 시 발사 전 eviction 또는 큐잉 (선제 차단 = OPSP0008 사전 예방) |

### 3-2. owner 우선순위 할당·eviction

**우선순위 순서 (재검토 후 결정)** — 낮은 숫자 = 상위:

| owner | priority | 근거 (재검토) |
|---|---|---|
| **HOLDING** | **0** | 보유 종목 — 자본 직결. 청산 재료 공백 = 자본 risk (ADR 0114 §4-3 evict 절대 금지 LOCK 정합). **DETAIL보다 상위로 정정** — 디스패치 input 제안(DETAIL > HOLDING)을 뒤집음. 사유: 사용자가 "보는 중"인 것보다 "돈이 들어가 있는 것"의 시세 끊김이 더 위험. 사용자가 상세를 보는 종목이 곧 보유 종목인 경우가 많아 실 충돌도 적음 |
| **PENDING_BUY** | 5 | 주문 제출~체결 사이 — 체결통보 수신 보장 필요 (ADR 0114 §4-3 정합) |
| **DETAIL** | 10 | 사용자가 현재 상세 화면에서 보는 중 — UX 직결이나 자본 미직결 |
| **WATCHLIST** | 20 | **신규 owner 도입** — 사용자 고정 관심목록. 현재 `WatchlistIngestionWorker` 별도 경로를 단일 ledger에 합류 (owner 명시) |
| **STRATEGY** | 40 | 페이퍼 전략 후보 — 수가 가변적·시그널 평가는 분/일봉 base라 5s polling fallback 영향 미미 (ADR 0048 §3-4 정합). **상한 도입**: `MAGICJAR_KIS_WS_STRATEGY_MAX_CODES`(default 15) — 가변 owner가 ledger를 독점하지 못하게 캡 |

**eviction 알고리즘** (한도 초과 수요 발생 시):
1. subscribe 발사 직전 ledger `available < 필요 slot` → eviction 검토
2. 현재 점유 중 **신규 priority보다 낮은(숫자 큰) owner** 채널을 victim 선정 (LRU tie-break)
3. victim **KIS unsubscribe(trType=2)** 발사 → ledger 회계 보정 → REST gap-fill/polling fallback 전환
4. HOLDING·PENDING_BUY owner 채널은 **eviction 절대 금지** (ADR 0114 LOCK 정합)
5. eviction해도 slot 부족 → 신규 수요를 **대기열** 등록 + polling fallback (§3-4)

**STRATEGY 상한**: 페이퍼 전략 후보가 11→가변 증가 시 상위 15종목만 WS, 나머지는 polling. 후보 score 상위 우선 (discovery score 정합 — ADR 0117).

### 3-3. 채널 다이어트 (slot 소비 절반화 — 최대 레버)

| 다이어트 항목 | 결정 | NXT 누락 영향 평가 (의무) |
|---|---|---|
| **NXT(H0NXCNT0) owner별 선택화** | DETAIL·HOLDING·PENDING_BUY만 NXT 동시 구독. WATCHLIST·STRATEGY는 **KRX(H0STCNT0) 단독** | STRATEGY/WATCHLIST는 시그널 평가가 분/일봉 base(정규장 데이터)라 NXT 시간외 tick 누락이 시그널 정확도에 미미. **단 HOLDING·PENDING_BUY는 NXT 유지** — 2026-06-10 WS 체결통보 회귀 fix(#878) 정합: 체결·청산 자본 path는 시간외 cover 의무 |
| **통합 체결가 H0UNCNT0 검토** | **R3 검토 의제** — H0STCNT0+H0NXCNT0(2 slot)을 H0UNCNT0(1 slot)로 통합 시 KRX+NXT 단일 cover. slot 절반 + NXT 누락 risk 0 동시 달성 가능 | **risk**: ADR 0091이 REST UN 일괄 적용 시 빈 응답 결함 보고 → WS에서도 동일 빈 응답·tick 누락 발생 여부 **R3 실측 검증 필수**. 정상 cover 확인 전까지 R3는 H0UNCNT0 fallback 가드(빈 tick 감지 → H0STCNT0+H0NXCNT0 복귀) 동반 |
| 호가(H0STASP0) DETAIL 한정 | 현행 정합 유지 — 호가는 DETAIL owner 진입 시만. LRU 10 → **owner 인지 LRU** (DETAIL 떠나면 즉시 unsub) | 변경 없음 (이미 DETAIL 한정) |

**slot 절감 추정** (현행 26종목 기준):
- 현행: 26×2(NXT) + 호가 + 1 = 53+
- R3 NXT 선택화 후: HOLDING(1)·DETAIL 일부만 NXT → watchlist 14 + STRATEGY 11 = KRX 단독 25 slot + NXT 소수 + 호가 + 1 ≈ **30~34 slot** (40 안 수렴)
- R3 H0UNCNT0 채택 시: 26 종목 × 1(통합) + 호가 + 1 ≈ **28 slot** (여유 확보)

### 3-4. 거부 시 graceful path (헌법 §11 silent fallback 금지 정합)

OPSP0008 (MAX SUBSCRIBE OVER) 감지 시:
1. **즉시 회계 보정** — ledger에서 해당 채널 점유 취소 표시
2. **대기열 등록** — 거부된 (code, owner) 를 pending queue에 등록 (priority 순)
3. **REST gap-fill 트리거** — `SymbolDetailMinuteGapFillService.submitIfNeeded(code)` 연결 → WS 미수신 구간 분봉 REST 보완 (분봉 연속성 보장 — 후성 결함 직접 해소)
4. **quote 폴링 fallback** — `WatchlistPollingWorker.add(code)` 연결 (5s 현재가 polling, ADR 0048 §2-4-C 정합)
5. **Slack 알람** — 거부율 임계 초과 시 (§3-5)
6. **헌법 §11-2 정합**: 거부 분기는 **silent 금지** — 최소 WARN 로그 + ledger 보정 + fallback 명시. REAL 모드라도 시세 데이터 누락은 자본 직결 아니므로(주문 발사 아님) fail-fast 아닌 **명시 degraded(polling)** 진행

### 3-5. 관측 (Prometheus — observability 스택 P0-OPS 정합)

| metric | 타입 | 용도 |
|---|---|---|
| `magicjar_kis_ws_slots_occupied` | Gauge (tag: trId) | 현재 점유 채널 수 (40 대비) |
| `magicjar_kis_ws_slots_available` | Gauge | 가용 slot |
| `magicjar_kis_ws_subscribe_rejected_total` | Counter (tag: msg_cd=OPSP0008/OPSP8996) | 거부 누적 |
| `magicjar_kis_ws_eviction_total` | Counter (tag: owner) | eviction 누적 |
| `magicjar_kis_ws_demand_queued` | Gauge | 대기열 크기 |

- **로그 레벨 정책**: OPSP0008 반복 거부는 **WARN 1회/주기**(throttle) — 매 거부마다 WARN 폭주 금지 (OPSP8996 grace window 패턴 정합)
- **Slack alert**: 거부율 임계(예: 1분 5건 초과) → WARN routing (ADR 0095 §3 severity matrix 정합, P3 WARN)
- **SLO 영향** (헌법 §9-1): KIS WS 연결률 SLO(>99%)는 영향 없음(연결은 유지, 구독만 거부). 신규 관측 항목 = "구독 충족률"(요청 대비 실제 구독 성공 %) backlog 측정

### 3-6. 마이그레이션·롤백 (장중 배포 금지·장마감 후 배포 전제)

| Round | 범위 | 독립 롤백 |
|---|---|---|
| **R1** | 슬롯 회계 ledger + OPSP0008 감지 + 관측 메트릭 + gap-fill/polling fallback 연결 (eviction 없이 — 거부 시 graceful만) | `MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=false` → 현행 동작 회귀 |
| **R2** | owner 우선순위 eviction + WATCHLIST owner 합류 + STRATEGY 상한 (선제 slot 배분) | `MAGICJAR_KIS_WS_EVICTION_ENABLED=false` → R1 동작(거부 시 fallback only) 회귀 |
| **R3** | 채널 다이어트 (NXT owner별 선택화 + H0UNCNT0 통합 검토) | `MAGICJAR_KIS_WS_NXT_OWNERS=ALL` (현행 전체 NXT) ↔ 선택 매트릭스 toggle. H0UNCNT0는 `MAGICJAR_KIS_WS_UNIFIED_TICK_ENABLED` default false (실측 GREEN 후 활성) |

각 R 독립 롤백 가능 + ENV toggle 우선(코드 revert 불필요). **장중 배포 금지** — 배포는 장마감(15:30) 후 + staging dwell 30분(헌법 §9-2 canary).

---

## 4. 구현 라운드 분해 + 담당 매핑

> 개발 룰 3 (development-rules §3) — 라운드 분할. 한 PR 4 layer 동시 변경 금지.

### R1 — 슬롯 회계 + 거부 감지 + 관측 (market-data 중심, backend-core 관측)

| 작업 | 모듈 | 담당 |
|---|---|---|
| `WsSlotBudgetLedger` 신규 — 채널 단위 단일 회계기 (pure + thread-safe) | consumer/ingestion | **market-data** |
| `SubscriptionPolicy` 정정 — TOTAL_SLOTS 41→40 + ledger 단일 산식 helper | domain/market/port | **market-data** |
| `KisWsMessageParser` OPSP0008 파서 추가 (OPSP8996 1:1 패턴) | consumer/ws | **market-data** |
| `KisWebSocketBridge` 거부 응답 → ledger 보정 + gap-fill/polling 콜백 hook | consumer/ws | **market-data** |
| gap-fill 자동 트리거 연결 (`SymbolDetailMinuteGapFillService.submitIfNeeded`) | consumer/reconciliation | **market-data** |
| Prometheus 메트릭 5종 + 로그 throttle + Slack alert rule | consumer + observability | **backend-core** |

### R2 — owner 우선순위 eviction (market-data 중심)

| 작업 | 모듈 | 담당 |
|---|---|---|
| `RealtimeDemandOwner`에 WATCHLIST 추가 + priority 매트릭스 정정 (HOLDING 0 최상위) | consumer/ingestion | **market-data** |
| `RealtimeSubscriptionRouter` + `OrderbookSubscriptionRouter` → 단일 ledger 합류 (독립 카운트 폐기) | consumer/ingestion | **market-data** |
| 선제 eviction 알고리즘 (발사 직전 available 검사 + victim 선정 + HOLDING/PENDING_BUY 제외 LOCK) | consumer/ingestion | **market-data** |
| `WatchlistIngestionWorker` → ledger owner=WATCHLIST 합류 | consumer/ingestion | **market-data** |
| STRATEGY 상한 ENV + discovery score 상위 우선 | consumer/ingestion | **market-data** |

### R3 — 채널 다이어트 (market-data, NXT 영향 평가는 strategy-engine 협의)

| 작업 | 모듈 | 담당 |
|---|---|---|
| NXT owner별 선택화 (DETAIL·HOLDING·PENDING_BUY만 NXT) | consumer/ws + ingestion | **market-data** |
| NXT 누락이 시그널·체결통보에 주는 영향 평가 (#878 정합) | — | **strategy-engine 협의** |
| H0UNCNT0 통합 체결가 실측 검증 (빈 tick risk — ADR 0091 정합) + fallback 가드 | consumer/ws | **market-data** |

### R4 — 데이터 흐름 시나리오 일괄 테스트 (개발 룰 4 — 테스트 마지막 일괄)

| 작업 | 담당 |
|---|---|
| ledger 회계 단위 테스트 (채널 점유·available·OPSP0008 보정) | **market-data** |
| eviction 알고리즘 테스트 (HOLDING 제외·priority victim·STRATEGY 상한) | **market-data** |
| 통합 시나리오: 53 수요 → 40 수렴 + gap-fill 연속성 (qa Visual 후성 차트 우측 공백 0 회귀) | **qa** |

---

## 5. 검증 시나리오 (DoD)

1. **내일 새벽 부팅 시 거부 0** — 부팅 후 watchlist 14 + STRATEGY 11 + HOLDING 1 구독 시 OPSP0008 로그 0건. ledger `slots_occupied ≤ 40` 실증 (Prometheus + 로그).
2. **장중 후성(093370) 분봉 연속성** — 정규장 92분 구간 분봉 92개 적재(또는 거부 발생 시 gap-fill로 보완 완결 — 차트 우측 공백 0). qa Visual 스크린샷 회귀.
3. **eviction 정합** — STRATEGY 후보 16번째 진입 시 상한(15) 초과분 polling 전환 + HOLDING/DETAIL 절대 미evict 로그 확인.
4. **채널 다이어트 효과** — R3 후 동일 26종목 수요에서 `slots_occupied` 현행 53 → 30 이하 실측.
5. **롤백 검증** — 각 R ENV toggle off 시 현행 동작 회귀 확인 (staging).
6. **헌법 §11 정합** — OPSP0008 분기 silent 0건 (WARN 로그 + fallback 명시 가시화).

---

## 6. 자원 audit (헌법 §3 spec 발행 전 의무 — main 점유 현황)

| 자원 | main 최신 점유 | 본 spec 신규 | 충돌 |
|---|---|---|---|
| **Flyway** | V82 (`ai_chart_prompt_context_enrichment`) | **신규 0** — 회계/관측은 in-memory ledger + Redis(선택), DB 테이블 불필요 | **0** |
| **Kafka 토픽** | `news.collected.v1` 등 기존 | **신규 0** — ledger는 in-process. (선택) 향후 `kis.ws.subscribe.rejected.v1` 검토는 R1 비범위 | **0** |
| **Redis 키** | `orderbook:top:{code}`·`quote:last:{code}` 등 | **신규 0~1** — ledger in-memory default. multi-JVM 정합 필요 시 `kis:ws:slots`(R2 검토, default in-memory) | **0** (in-memory 우선) |
| **STOMP 목적지** | `/topic/market.tick.{code}` 등 | **신규 0** | **0** |
| **REST 경로** | 기존 KIS adapter | **신규 0** — gap-fill·polling은 기존 어댑터 재사용 | **0** |
| **ENV** | `MAGICJAR_KIS_WS_URL_*`·`MAGICJAR_KIS_WS_USER_ID` | **신규 6** (아래 prefix `MAGICJAR_KIS_WS_*` 정합) | **0** (기존 prefix 확장) |
| **Prometheus metric** | `magicjar_*` 네이밍 | **신규 5** (`magicjar_kis_ws_slots_*` 등) | **0** |

**신규 ENV 6종**:
```
MAGICJAR_KIS_WS_MAX_SLOTS=40                  # R1 — 한도 (상수 아닌 설정, §1-3 UNCERTAIN 보정)
MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=true     # R1 — ledger 활성 (롤백 toggle)
MAGICJAR_KIS_WS_EVICTION_ENABLED=false       # R2 — eviction (R2 머지 후 true)
MAGICJAR_KIS_WS_STRATEGY_MAX_CODES=15        # R2 — STRATEGY owner 상한
MAGICJAR_KIS_WS_NXT_OWNERS=ALL               # R3 — NXT 적용 owner (ALL ↔ HOLDING,PENDING_BUY,DETAIL)
MAGICJAR_KIS_WS_UNIFIED_TICK_ENABLED=false   # R3 — H0UNCNT0 통합 체결가 (실측 GREEN 후 true)
```

**자원 충돌 0 명시** — 신규 Flyway/Kafka/STOMP/REST 0, ENV는 기존 `MAGICJAR_KIS_WS_*` prefix 확장, metric은 `magicjar_` 네이밍 정합.

---

## 7. 기존 ADR/정책 정합

| 문서 | 관계 | 갱신 |
|---|---|---|
| **ADR 0048** (41건 한도 + slot 1+30+10) | **amend** | 41→40 정정, slot 산식을 owner 모델 + 단일 ledger로 재정의, OPSP0008 graceful path 신설. (ADR 0048 본문은 ADR 0123 §변경이력에서 amend 마커) |
| **ADR 0063** (NXT 통합 cover) | **정합** | REST UN 통합은 0063 유지. WS H0UNCNT0 통합 체결가 검토는 ADR 0123 R3 신설 (0063 WS 확장) |
| **ADR 0091** (market-level UN 빈 응답 결함) | **정합 — risk 인용** | H0UNCNT0 WS 빈 tick risk R3 실측 검증 근거 |
| **ADR 0114** (HOLDING evict 금지 LOCK) | **정합 — LOCK 계승** | eviction에서 HOLDING/PENDING_BUY 제외 LOCK 그대로 계승 |
| **ADR 0095** (alert routing) | **정합** | OPSP0008 거부율 Slack alert = P3 WARN routing |
| **헌법 §9-1** (SLO) | **정합** | 구독 충족률 신규 관측 backlog |
| **헌법 §11-2** (silent fallback 금지) | **정합 — 적용** | OPSP0008 분기 silent 0 + WARN + fallback 명시 |
| **candle-data-policy-v2 §6** | **정합** | gap-fill 분봉 보완 흐름 cross-link (1줄 갱신 R1) |
| **external-api-policy §2-1** | **정정** | "41건" → "40건" (KIS-first raw 출처 인용) — R1 동반 |

---

## 8. trade-off

- **40 보수 상한 채택**: KIS 포털 명시 한도 미공개 → 공식 예제 코드 enforce 값(40) 채택. 실 임계가 41이면 1 slot 손해이나 거부 risk 0이 우선. ENV로 실측 보정.
- **단일 ledger 도입의 복잡도**: 두 독립 라우터를 단일 회계로 합류 → 리팩터 비용. 그러나 총합 통제 부재가 root cause이므로 불가피. R1에서 회계만(eviction 없이) → 점진 안전.
- **NXT 선택화의 시간외 cover 축소**: STRATEGY/WATCHLIST의 NXT 시간외 tick 누락. 시그널은 분/일봉 base라 영향 미미하나, 사용자가 시간외 가격을 watchlist에서 보는 UX는 polling fallback(5s)으로 보완.
- **H0UNCNT0 빈 tick risk**: ADR 0091 REST 결함의 WS 재현 가능성 → default false + 실측 GREEN 게이트 + fallback 가드로 보수적.
