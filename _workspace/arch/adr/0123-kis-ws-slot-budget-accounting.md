# ADR 0123 — KIS WS 구독 슬롯 회계 + owner 우선순위 eviction + 채널 다이어트

- 상태: **Accepted (2026-07-03 감사 후 일괄 승격)** — 구현 머지 완료 evidence: `WsSlotBudgetLedger` 단일 회계기·OPSP0008 graceful path·H0UNCNT0 채널 다이어트(R3)가 라이브 운용 중이며 Accepted ADR 0124가 본 ADR amend로 발행·운용됨. (구 상태: Proposed 2026-06-11. 잔여분: owner priority 기반 eviction 선점 조항은 미구현 상태였음 — ADR 0138로 완성)
- 작성자: architect (opus 4.8, 1M)
- 관계: **ADR 0048 amend** (41건 → 40건 정정 + slot 산식 owner 모델 재정의 + OPSP0008 graceful path 신설), ADR 0063(NXT 통합 cover — WS H0UNCNT0 확장), ADR 0091(market-level UN 빈 응답 risk 인용), ADR 0114(HOLDING evict 금지 LOCK 계승), ADR 0095(alert routing), 헌법 §9-1(SLO)·§11-2(silent fallback 금지)
- spec: `_workspace/specs/2026-06-11-kis-ws-slot-budget.md` (본 ADR의 구현 분해 — R1/R2/R3/R4)
- 진입 트랙: 운영 고도화 (실시간 인입 회계 layer)

---

## 1. 컨텍스트

2026-06-11 장중, 후성(093370) 분봉이 정규장 92분 중 35개만 적재(차트 우측 공백). 원인 = KIS WS 구독 거부:

```
10:31:05 KIS WS 구독 응답: success=false {"tr_id":"H0STASP0","tr_key":"093370","msg1":"MAX SUBSCRIBE OVER"} (msg_cd OPSP0008)
```

40분간 35건 거부. 005930 호가 구독도 거부.

**수요 산식**: watchlist 14 + STRATEGY(페이퍼 82·84 후보) 11 + HOLDING 1 = 26종목 × 2채널(H0STCNT0 + H0NXCNT0) = 52 + 호가(H0STASP0) + 체결통보 1 = **≥ 53 slot**. KIS 한도를 13+ 초과.

**KIS-first 검증** (헌법 §3 — raw fetch, 2026-06-11):

| 항목 | 현행 | KIS 공식 출처 | 정정 |
|---|---|---|---|
| WS 구독 한도 | 41건 (ADR 0048 + external-api-policy) | **40건** — `examples_user/kis_auth.py` `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")` | **41 → 40** |
| 한도 단위 | "stream 수" | `open_map` key = (tr_id, tr_key) = **채널 단위** | 채널 회계 LOCK |
| 통합 체결가 | H0STCNT0+H0NXCNT0 이중 | **H0UNCNT0(통합)** 존재 — `ccnl_total/ccnl_total.py` (KRX+NXT 단일 cover) | R3 다이어트 레버 |

raw 출처: `kis_auth.py`(한도 40), `ccnl_total/ccnl_total.py`(H0UNCNT0), `domestic_stock_functions_ws.py`(H0STCNT0·H0NXCNT0·H0UNCNT0·H0STASP0·H0NXASP0·H0UNASP0·H0STCNI0). github.com/koreainvestment/open-trading-api main.

**현행 구조 갭** (코드 evidence):
- `KisWebSocketBridge.subscribe()` — 종목당 H0STCNT0+H0NXCNT0 무조건 이중 발사, slot 카운트·한도 인지·OPSP0008 처리 부재. 거부돼도 subscribedCodes 잔존·재시도 없음.
- `RealtimeSubscriptionRouter` — `PRICE_TICK_MAX`만 카운트, NXT 이중 발사 미회계 (실제 2배).
- `OrderbookSubscriptionRouter` — 자체 `maxSlots=10` LRU, 40 한도를 체결가 라우터와 공유한다는 인식 0.
- `KisWsMessageParser` — OPSP8996만 감지, **OPSP0008 미감지**.

**root cause**: 두 독립 라우터가 부분 한도를 각각 관리 + NXT 이중 발사 미회계 + 거부 응답 미감지 → 총 점유가 40을 넘어도 인지 못 한 채 계속 발사.

---

## 2. 결정

### 2-1. 단일 슬롯 회계기 (WsSlotBudgetLedger)

체결가·NXT·호가·체결통보·시간외를 **단일 회계기**가 "채널 = (code, trId)" 단위로 추적한다. 두 독립 라우터의 카운트를 폐기하고 단일 진실원으로 통합.

- 한도: `MAGICJAR_KIS_WS_MAX_SLOTS` ENV (default 40 — **상수 아님**, KIS 한도 변경·실측 보정 대비). reserve(체결통보 1) 제외 실효 = 39.
- subscribe 발사 **직전** `available = maxSlots - reserved - occupied` 검사 → 부족 시 발사 전 eviction/큐잉 (OPSP0008 선제 예방).
- OPSP0008(MAX SUBSCRIBE OVER) 파서 추가 (OPSP8996 1:1 패턴) → 거부 감지 시 ledger 회계 보정 + graceful path.

### 2-2. owner 우선순위 eviction

priority(낮을수록 상위): **HOLDING(0) > PENDING_BUY(5) > DETAIL(10) > WATCHLIST(20, 신규) > STRATEGY(40, 상한 15)**.

- **HOLDING을 최상위로 결정** — 디스패치 input 제안(DETAIL > HOLDING)을 뒤집음. 사유: "보는 중"보다 "돈이 들어가 있는 것"의 시세 끊김이 더 위험. ADR 0114 evict 절대 금지 LOCK 정합.
- HOLDING·PENDING_BUY 채널은 **eviction 절대 금지** (ADR 0114 계승).
- STRATEGY는 가변 수 owner — `MAGICJAR_KIS_WS_STRATEGY_MAX_CODES`(default 15) 상한, discovery score 상위 우선 (ADR 0117).
- 한도 초과 시 저priority victim unsubscribe(trType=2) → ledger 보정 → polling/gap-fill fallback.

### 2-3. 채널 다이어트 (slot 절반화 — 최대 레버)

- **NXT(H0NXCNT0) owner별 선택화**: DETAIL·HOLDING·PENDING_BUY만 NXT 동시. WATCHLIST·STRATEGY는 KRX 단독. **HOLDING·PENDING_BUY는 NXT 유지** (#878 체결통보 회귀 fix 정합 — 자본 path 시간외 cover 의무).
- **H0UNCNT0 통합 체결가 검토(R3)**: H0STCNT0+H0NXCNT0(2) → H0UNCNT0(1)로 통합 시 KRX+NXT 단일 cover. **risk**: ADR 0091 REST UN 빈 응답 결함의 WS 재현 가능성 → R3 실측 검증 + fallback 가드(빈 tick 감지 시 이중 구독 복귀) + default false 게이트. **→ 2026-06-13 ADR 0124로 H0UNCNT0 단일 채널 전환 확정**(라이브 실측 31→16 + 최고 관리자 결재 — 이중 발사 경로·flag 완전 제거 + owner priority 재정렬 + watchlist owner 폐지. 본 §의 default false 게이트·이중 구독 경로는 0124에서 제거됨).

### 2-4. 거부 시 graceful path (헌법 §11-2 정합)

OPSP0008 감지 → ①ledger 회계 보정 ②대기열 등록(priority) ③`SymbolDetailMinuteGapFillService.submitIfNeeded` 분봉 REST 보완 ④`WatchlistPollingWorker.add` 5s polling ⑤Slack alert(임계). **silent 금지** — WARN 로그 + fallback 명시. 시세 누락은 자본 직결 아니므로(주문 발사 X) fail-fast 아닌 명시 degraded(polling) 진행.

---

## 3. 결과

### 3-1. 운영 양식 3 슬롯 (헌법 §9-5)

**SLO 영향**: KIS WS 연결률 SLO(>99%, §9-1)는 영향 없음 — 연결은 유지되고 구독만 거부되던 것을, 회계로 거부 0 수렴 → 분봉 연속성 개선. 신규 관측 "구독 충족률"(요청 대비 성공 %) backlog 측정.

**rollback plan**: 3 R 모두 ENV toggle 우선(코드 revert 불필요) —
- R1: `MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=false` → 현행 동작
- R2: `MAGICJAR_KIS_WS_EVICTION_ENABLED=false` → R1(거부 시 fallback only)
- R3: `MAGICJAR_KIS_WS_NXT_OWNERS=ALL` + `MAGICJAR_KIS_WS_UNIFIED_TICK_ENABLED=false` → 현행 이중 구독
**장중 배포 금지** — 장마감(15:30) 후 + staging dwell 30분(§9-2 canary).

**관측 매트릭스**: Prometheus 5종 — `magicjar_kis_ws_slots_occupied`(Gauge, tag trId)·`_slots_available`(Gauge)·`_subscribe_rejected_total`(Counter, tag msg_cd)·`_eviction_total`(Counter, tag owner)·`_demand_queued`(Gauge). OPSP0008 거부율 임계 → Slack P3 WARN(ADR 0095 routing). 로그 WARN 1회/주기 throttle. **Visual 회귀**: qa 후성 차트 우측 공백 0 스크린샷 회귀(§5-2).

### 3-2. 자원 영향

신규 Flyway 0 / Kafka 0 / STOMP 0 / REST 0. ENV 6종(`MAGICJAR_KIS_WS_*` prefix 확장) + Prometheus metric 5종(`magicjar_` 네이밍). Redis 키 default in-memory(multi-JVM 정합 필요 시 R2 검토). 자원 충돌 0.

### 3-3. 검증 (DoD)

1. 새벽 부팅 시 OPSP0008 0건 + `slots_occupied ≤ 40` 실증.
2. 장중 후성 분봉 92개 연속(또는 gap-fill 보완 완결 — 차트 공백 0).
3. eviction 정합 (STRATEGY 16번째 polling 전환 + HOLDING/DETAIL 미evict).
4. R3 후 동일 수요 `slots_occupied` 53 → 30 이하.
5. 각 R ENV toggle off 회귀 검증.
6. OPSP0008 분기 silent 0 (헌법 §11-2).

---

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **한도 41 유지** | KIS 공식 예제 코드가 40 enforce — KIS-first raw 출처 우선. 1 slot 보수가 거부 risk 회피에 우선 |
| **수요 거부(등록 차단)** | ADR 0048 §4 정합 — 사용자 의도 거부는 비전(ADR 0001) 위반. polling fallback이 정합 |
| **두 라우터 독립 카운트 유지 + 부분 한도 분할 고정** | 총합 통제 부재가 root cause — 고정 분할은 owner 수요 가변성(STRATEGY 후보)에 부적응. 단일 ledger 동적 배분이 본질 |
| **H0UNCNT0 즉시 전면 채택(R1)** | ADR 0091 빈 응답 risk 미검증 — 실측 GREEN 전 전면 채택은 tick 누락 risk. default false + 실측 게이트 |
| **DETAIL > HOLDING 우선순위(디스패치 input 제안)** | "보는 중"보다 "보유 자본"의 시세 끊김이 청산 재료 공백 = 자본 risk로 더 위험. ADR 0114 LOCK 정합 |

---

## 5. 참고

- ADR 0048 — KIS WS 41건 한도 + 우선순위 정책 (본 ADR이 amend)
- ADR 0063 — NXT/통합거래 매트릭스 (REST UN — 본 ADR이 WS H0UNCNT0 확장 검토)
- ADR 0091 — market-level UN 빈 응답 결함 (H0UNCNT0 risk 인용)
- ADR 0114 — HOLDING evict 금지 LOCK (계승)
- ADR 0117 — daytrade discovery (STRATEGY score 상위 우선)
- 헌법 §9-1·§9-5(운영 양식)·§11-2(silent fallback 금지)
- spec: `_workspace/specs/2026-06-11-kis-ws-slot-budget.md`
- KIS-first raw: `examples_user/kis_auth.py`(한도 40)·`ccnl_total/ccnl_total.py`(H0UNCNT0)·`domestic_stock_functions_ws.py`(tr_id 목록)

## 6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-11 | ADR 0123 본문 발행 (Proposed) — architect opus 4.8. KIS WS 슬롯 회계 단일 ledger + owner 우선순위 eviction(HOLDING 0 최상위) + 채널 다이어트(NXT 선택화·H0UNCNT0 검토) + OPSP0008 graceful path. ADR 0048 amend (41→40 정정, KIS-first raw `kis_auth.py` enforce 40 출처). 후속 R1/R2/R3/R4 디스패치(market-data 중심·backend-core 관측·qa 시나리오·strategy-engine NXT 협의). |
