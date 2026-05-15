# ADR 0076 — KIS WS NXT/시간외 시세 sync 표준 정합 (누적 hotfix #443·#444·#445 root cause 분석)

- 상태: **Proposed** (2026-05-15 architect 자율 분석 발의, 사용자 결정 의제 매트릭스 §7 위임)
- 작성일: 2026-05-15
- 결정자: 사용자(최고 관리자) — 본 ADR의 의제 §7 풀세트 결정 후 후속 사이클 sub-agent 디스패치
- 관계: ADR 0048 (KIS WS 41 slot 한도) · ADR 0063 (NXT/통합거래 08:00~20:00 cover, Accepted 2026-05-11) · ADR 0057 (KIS-only 데이터 출처) · ADR 0037 (KIS Rate Limit) · ADR 0074 (SettingsModal KIS 키 변경 + WS reconnectGracefully) · 누적 hotfix PR #443·#444·#445

> 본 ADR은 **plan/analysis 단계 (Proposed)** — 직전 4 hotfix 누적이 surface patch만 처리한 root cause를 KIS open-trading-api 표준 패턴(raw fetch 인용)과 정합 검증하여 의제 7건을 사용자에게 제시. 코드 변경 0 LOCK (분석 사이클). 결정 채택 후 sub-agent 디스패치는 별도 사이클로 분리(Phase 매트릭스 §7 정밀 명세).

> **사용자 통찰 인용 (2026-05-15)**: "복잡하고 어떻게 처리할지 정리를 못하고 있어. 일반적으로 거래소에서 하고있는 정책을 따라가. 거기선 잘되는게 우린 왜 안되겠니?" — 본 ADR의 출발점.

## 1. 컨텍스트 — 결함 매트릭스 (누적 hotfix 후 잔존)

### 1-1. 사용자 증상 인식

| 시간대 | KRX HTS(증권사 일반) | magicJar 현행 (PR #443·#444·#445 후) |
|---|---|---|
| 정규장 09:00~15:30 | tick 실시간 표시 | 정상 sync (H0STCNT0) |
| **시간외 단일가 16:00~18:00** | tick 실시간 표시 (H0STOUP0 계열) | **표시 정지 — 구독 0건** |
| **NXT pre-market 08:00~09:00** | tick 실시간 표시 (H0NXCNT0) | PR #443 후 구독 — but OPSP8996로 실패 사례 잔존 |
| **NXT after-market 15:30~20:00** | tick 실시간 표시 (H0NXCNT0) | PR #443 후 구독 — but stop 증상 잔존 |

### 1-2. 직전 4 hotfix 누적 (surface patch — root cause 미해결)

| PR | 영역 | 적용 변경 | 잔존 결함 |
|---|---|---|---|
| **#443** (2026-05-15) | `KisWebSocketBridge.subscribe()` | H0NXCNT0 동시 구독 추가 (`nxtSubscribedCodes` Set + `handleNxtTickData()` + ADR 0063 정합) | 시간외 단일가(H0STOUP0) 미구독 + 첫 구독 후 OPSP8996 다발 발생 |
| **#444** (2026-05-15) | `KisWebSocketBridge.doConnect()` + `KisApprovalAdapter.invalidate()` | doConnect 진입 시 `approval_key` Redis cache DEL → 신규 발급 | KIS 표준 `auth_ws()`는 24h cache reuse 권장 — 매 재연결 시 `/oauth2/Approval` 호출 폭증 + OPSP8996 잔존 |
| **#445** (2026-05-15) | `KisWebSocketBridge.doConnect()` Reactor 체인 | `doOnTerminate` → `doFinally` 교체 + `reconnecting AtomicBoolean` CAS guard + `session.onConnected()` 람다 위치 이동 | watchdog 재연결 동작은 회복 — but OPSP8996 다발 발생 시 재연결도 거부됨 (subscribe 0건 무한 반복) |

### 1-3. 사용자 의제 발의

> "이게 정상장이 아닌건 시간외고 또 다른 거래 시장이 있는 NXT 시장도 있고 그런거 같은데 일반증권사 hts에선 호가가 잘들어와. 따라가야지 표준 정책을. 일반적으로 거래소에서 하고있는 정책을 따라가. 거기선 잘되는게 우린 왜 안되겠니?"

→ 본 ADR은 **자체 추론 0** + **KIS open-trading-api 레포 raw fetch 인용 100%** (헌법 §3 5단계 절차) 의무. 옛 결함 fix 누적 모두 폐기 후 KIS 표준 패턴으로 일괄 재정합.

### 1-4. fitness gap (`feedback_v3_skepticism_no_regression.md` lens)

| 질문 | 답변 |
|---|---|
| 자동매매에 필요한가? | **필요 ★★★**. 시간외·NXT 시간대 가격 변동을 시그널·룰엔진이 인지 못하면 자본 보호 critical risk (사용자 자본 손실 직결). 사용자 의제 발의 기반. |
| v1/v2 잔재 아닌가? | **잔재 아님**. KIS 표준 패턴 정합 신규 의제. v1/v2 시점에 NXT(2025년 출범) + 시간외 multi-tr_id 미존재. |
| AI 매매 결정 0건 위반 X? | 0건 (룰 5). 본 ADR은 시장 데이터 수집 인프라 — LLM provider import 0건. |
| KIS-first 정합? | **정합**. §3 매트릭스 전체가 KIS open-trading-api 레포 raw 인용. 자체 추론 risk lens 동반. |

## 2. KIS open-trading-api 표준 패턴 (raw fetch 인용)

### 2-1. tr_id 풀세트 매트릭스

raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`
보조 URL: `https://api.github.com/repos/koreainvestment/open-trading-api/git/trees/main?recursive=1`

| 영역 | tr_id | KIS 함수명 | 활성 시간 (raw 명시 또는 추정) | magicJar 현행 | 표준 정합 결함 |
|---|---|---|---|---|---|
| KRX 정규장 체결가 | `H0STCNT0` | `ccnl_krx()` | 09:00~15:30 KST | ✅ 구독 | — |
| **NXT 체결가** | `H0NXCNT0` | `ccnl_nxt()` | NXT 운영 08:00~20:00 (ADR 0063) | ✅ PR #443 구독 | OPSP8996 잔존 |
| KRX 호가 | `H0STASP0` | `asking_price_krx()` | 09:00~15:30 | ✅ orderbook 구독 (LRU 10) | — |
| **NXT 호가** | `H0NXASP0` | `asking_price_nxt()` | NXT 운영 08:00~20:00 | ❌ **미구독** | NXT 시간대 호가 sync 0 |
| **통합 호가** | `H0UNASP0` | `asking_price_total()` | KRX + NXT 통합 (시간 union) | ❌ 미구독 | (대안 — H0STASP0+H0NXASP0 별도 또는 H0UNASP0 단일 통합) |
| **시간외 단일가 호가** | `H0STOAA0` | `overtime_asking_price_krx()` | 16:00~18:00 (raw 명시) | ❌ **미구독** | 시간외 단일가 호가 sync 0 |
| **시간외 단일가 예상체결가** | `H0STOAC0` | `overtime_exp_ccnl_krx()` | 시간외 단일가 동시호가 시간대 | ❌ 미구독 | 16:00 동시호가 예상가 sync 0 |
| **시간외 단일가 체결가** | `H0STOUP0` | `overtime_ccnl_krx()` | 16:00~18:00 (raw 명시) | ❌ **미구독** | 시간외 단일가 체결가 sync 0 |
| 체결통보 | `H0STCNI0` | `ccnl_notice()` | 실시간 (계좌별) | reserve only | T-O 사이클 |

raw 인용 (`examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py` docstring):

> "국내주식 시간외 단일가(16:00~18:00) 시간대에 실시간체결가 데이터 확인 가능합니다"

### 2-2. 단일 WS session + 다중 tr_id 구독 표준 패턴

raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`
보조 URL: `examples_llm/domestic_stock/asking_price_krx/chk_asking_price_krx.py` 등 다중 tr_id 샘플 디렉토리.

**표준 패턴 (raw 인용)**:

```python
# 1회 인증 + 단일 WS 객체
ka.auth()
ka.auth_ws()
kws = ka.KISWebSocket(api_url="/tryitout")

# 다중 tr_id 반복 subscribe — 동일 WS 객체에 다른 request 객체 등록
kws.subscribe(request=asking_price_krx, data=["005930", "000660"])
kws.subscribe(request=asking_price_nxt, data=["005930", "000660"])
kws.subscribe(request=asking_price_total, data=["005930", "000660"])

# 단일 콜백 — 모든 tr_id 메시지 통합 수신
kws.start(on_result=on_result)
```

**핵심 정합 의무 3건** (raw 인용 종합):

1. **단일 WS session 단독** — `kws` 객체 1개 안에서 모든 tr_id subscribe.
2. **approval_key 재사용** — `auth_ws()` 인증 시 1회 발급 후 24h cache (`reAuth_ws()`만 86,400초 후 갱신). **매 subscribe·매 doConnect 시 신규 발급 아님.**
3. **subscribe()는 순차 호출 OK** — KIS 측 별도 rate limit이나 호출 간 sleep 의무는 raw 코드에 명시 없음.

### 2-3. magicJar 현행과 표준 패턴 차이 매트릭스

| 항목 | KIS 표준 (raw) | magicJar 현행 (PR #444 후) | 정합 결함 |
|---|---|---|---|
| approval_key 발급 빈도 | 인증 시 1회 + 24h cache reuse | **doConnect 진입 시마다 invalidate + 신규 발급** | 매 재연결 시 `/oauth2/Approval` 호출 — KIS 측에서 신규 session 인식 + 기존 session도 invalidate → 무한 OPSP8996 루프 risk |
| WS session 종료 시 | 자연 close (Python `with` block exit 또는 KeyboardInterrupt) | `dispose()` + 1s sleep + scheduler shutdown | unsubscribe ACK 대기 없음 — KIS 측 session 잔존 가능성 |
| 재연결 trigger | (raw 코드 명시 없음 — 표준 client 단순 disconnect 처리) | heartbeat 10s 무신호 → dispose → 재연결 (지수 백오프) | KIS 표준 PINGPONG 주기 명시 검증 필요 (10s가 정합인지 불명) |
| 다중 tr_id 구독 시점 | subscribe 반복 호출 (auth_ws 이후) | subscribe() loop 안에서 H0STCNT0 + H0NXCNT0 연속 send | 표준과 패턴 일치 ✓ — but doConnect 시 매번 approval_key 신규 발급으로 session 재시작 risk |
| 시간외 단일가 cover | H0STOUP0 별도 subscribe + 16:00~18:00 활성 시간 인식 | **미구독** | 시간외 단일가 시각대 magicJar 시세 stale |

## 3. OPSP8996 root cause 가설 매트릭스

PR #444 (approval_key invalidate)에도 불구하고 OPSP8996이 잔존하는 이유:

| 가설 | 설명 | 검증 방법 | architect 평가 |
|---|---|---|---|
| **A. 매 doConnect 시 approval_key 신규 발급 → KIS 측에서 새 session으로 인식하여 기존 session 강제 invalidate → 다음 subscribe가 (이제는 stale인) 기존 approval_key 또는 새 approval_key로 들어가서 충돌** | KIS는 동일 appkey에 active session 1개. magicJar는 doConnect 진입 시 invalidate + 즉시 새 발급 — but invalidate 시점과 신규 session 인식 시점 사이 race window 존재 | KIS 측 `/oauth2/Approval` 호출 빈도 + OPSP8996 발생 시점 로그 cross-check | **★ 가장 가능성 높음** — KIS 표준은 24h cache reuse, magicJar는 invalidate 빈도 과다 |
| B. consumer 재시작 시 이전 process의 WS session이 KIS 측에서 graceful close ACK 미수신 → 잔존 active로 인식 | `destroy()`가 unsubscribe만 보내고 1s sleep 후 dispose. KIS 측 ACK 대기 없음 | KIS 측 잔존 session timeout 정책 raw 검증 (raw 명시 없음 — KIS 가이드 문의 필요) | 가능 — but PR #444 invalidate가 회피했어야 함 |
| C. heartbeat 10s timeout이 KIS 표준 PINGPONG 주기보다 짧음 — 정상 PINGPONG 대기 중 false-positive 재연결 trigger | KIS 표준 PINGPONG 주기 raw 명시 없음. 일반적 KIS heartbeat 주기 30s 추정 | KIS 측 PINGPONG 실측 로그 + heartbeat timeout 30s+로 완화 후 OPSP8996 발생 빈도 비교 | 가능 — but 15:30 이후 정규장 종료 시 PINGPONG 자체가 끊길 가능성도 검증 필요 |
| D. 15:30 이후 KIS WS server side에서 H0STCNT0 stream 자동 종료 → magicJar는 모든 stream 동시 종료로 오인 → reconnect → OPSP8996 발생 | KIS WS 정규장 외 자동 disconnect 정책 raw 명시 없음 | 정규장 15:30 KIS 측 stream 종료 시점 vs OPSP8996 발생 시점 cross-check | 가능 — KIS 가이드 문의 필요 |
| E. SystemKisKeyChangedListener의 `reconnectGracefully` 경로가 정상 reconnect 경로와 race | ADR 0074 §2-2-3 graceful reconnect는 KIS 키 변경 시 한정. but Redis cache invalidate가 doConnect 진입과 race 가능 | KIS 키 변경 0건 시 OPSP8996 발생 → E는 배제 가능 | 가능성 낮음 — but 의제 §7-E에 명시 |
| F. NXT(H0NXCNT0) subscribe와 KRX(H0STCNT0) subscribe가 KIS 측에서 별개 session으로 인식되어 동일 approval_key 충돌 | KIS examples는 H0STCNT0 + H0NXCNT0 동시 subscribe 명시 sample 미발견 | KIS 측 단일 session multi-tr_id 한도 가이드 raw 검증 (§2-2 인용 OK) | 가능성 낮음 — but examples_llm은 각 tr_id 단일 sample만 제공 |

→ **가장 가능성 높은 가설 = A** (KIS 표준 reuse 패턴 위반 — invalidate 빈도 과다).

## 4. PINGPONG heartbeat 표준 정합

raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py`

raw 인용 (`auth_ws()` 인접 PINGPONG 처리):

```python
# kis_auth.py 내 KISWebSocket 클래스
async def _ws_handler(self, ws):
    async for raw in ws:
        # ...
        if "PINGPONG" in raw:
            await ws.pong(raw)  # 수신 raw 그대로 echo
            continue
```

→ **KIS 표준 = PINGPONG 수신 시 raw echo 의무**. PINGPONG 주기는 KIS server 측 정책 (raw에 명시 없음 — 30s 또는 60s 통상 추정).

**magicJar 현행**: `handleMessage` 내 `KisWsMessageParser.ParsedMessage.Pingpong` 분기에서 `sendSink?.tryEmitNext(parsed.rawMessage)` — 정합 ✓.

**잠재 결함**: heartbeat watchdog 10s timeout. KIS PINGPONG 주기가 30s+면 false-positive 가능. **표준은 PINGPONG 자체로 keep-alive 충분** — watchdog은 30s+ 또는 polling-fallback과 같은 보수적 threshold 권장.

## 5. 시간외 단일가 + NXT 활성 시간 매트릭스 (raw 인용)

| 시간대 (KST) | KRX 정규 H0STCNT0 | KRX 시간외 H0STOUP0 | NXT H0NXCNT0 | NXT after H0NXCNT0 |
|---|---|---|---|---|
| 08:00~08:30 | — | — | ✅ pre-market | — |
| 08:30~09:00 | — | — | ✅ pre-market | — |
| 09:00~15:30 | ✅ 정규장 | — | ✅ main (KRX와 병행) | — |
| 15:30~16:00 | — (정규장 종료) | — (시간외 시작 전) | — (NXT 일시 휴장) | — |
| 16:00~18:00 | — | **✅ 시간외 단일가** (raw 명시) | — | — |
| 18:00~20:00 | — | — | — | **✅ NXT after-market** |
| 20:00~ | — | — | — | — (NXT 종료) |

→ magicJar는 16:00~18:00 KRX 시간외 단일가 + 15:30~16:00 휴장 + 18:00~20:00 NXT after 시간대 표준 정합 의무.

**결함 확정**: PR #443에서 H0NXCNT0만 추가 — H0STOUP0(16:00~18:00 KRX 시간외 단일가) 누락. 사용자 "정규장 종료 후 magicJar stop" 증상의 일부는 이 시간대.

## 6. magicJar 현재 코드 구조 인용 (PR #443·#444·#445 통합 후)

```
KisWebSocketBridge.subscribe(codes)
  ├─ getApprovalKey() → KisApprovalAdapter (Redis cache 23h TTL)
  ├─ for code in codes:
  │   ├─ H0STCNT0 subscribe message send (KRX 정규장)
  │   └─ H0NXCNT0 subscribe message send (NXT 08:00~20:00)
  └─ (H0STOUP0 미포함 — 시간외 단일가 결함)

KisWebSocketBridge.doConnect()
  ├─ approvalAdapter.invalidate()  ← PR #444 신규 (KIS 표준 위반 risk)
  ├─ getApprovalKey() → KIS /oauth2/Approval 호출
  └─ wsClient.execute(URI) chain
      └─ doFinally(signal) → reconnecting.set(false) + scheduleReconnect()  ← PR #445

KisApprovalAdapter
  ├─ getApprovalKey() — in-process mutex + Redis SETNX 분산락 + cache 23h TTL
  └─ invalidate() — Redis DEL (PR #444 신규)

heartbeat watchdog (10s timeout) — startHeartbeatWatchdog()
  └─ elapsed > 10s → reconnecting CAS → wsDisposable.dispose() → doFinally → scheduleReconnect → doConnect → invalidate → 신규 approval_key → re-subscribe → OPSP8996 가능성 다발
```

→ **PR #444 invalidate가 핵심 결함 risk**. KIS 표준 = 24h cache reuse. magicJar = 매 재연결 시 신규 발급. OPSP8996 다발 → watchdog 재연결 trigger → 또 invalidate → 무한 루프.

## 7. 권유 fix 매트릭스 (사용자 결정 의제 7건)

### 의제 A. approval_key 재발급 정책 (PR #444 revert 검토)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **A-1 (architect 권장)** PR #444 invalidate 호출 **revert** + 24h cache reuse (KIS 표준 패턴) | doConnect 진입 시 invalidate 호출 0. Redis cache 23h TTL 자연 만료 시만 신규 발급 | KIS 표준 정합 + `/oauth2/Approval` 호출 빈도 1/24h로 감소 + OPSP8996 루프 차단 | 키 변경 시(ADR 0074) 명시적 invalidate 필요 — 이미 `SystemKisKeyChangedListener`에서 호출됨 ✓ |
| A-2 PR #444 그대로 유지 | 매 재연결 시 invalidate + 신규 발급 | OPSP8996 회피 의도는 명확 | KIS 표준 위반 — `/oauth2/Approval` 호출 폭증 + race window risk |
| A-3 invalidate를 specific 조건에만 (OPSP8996 응답 수신 시) | KIS subscribe response가 OPSP8996이면 invalidate + 1회 retry. 그 외에는 cache reuse | KIS 표준 reuse + 결함 시 self-heal | subscribe response 파싱 정밀화 필요 + 무한 retry 방지 cap 의무 |

architect 권장 = **A-1** (KIS 표준 정합 우선). A-3는 backlog 후보로 등록 — 안정화 후 OPSP8996 재발 시 검토.

### 의제 B. 시간외 단일가 cover (H0STOUP0 신규 구독)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **B-1 (architect 권장)** H0STOUP0 추가 구독 + 16:00~18:00 활성 시간 schedule 트리거 | subscribe 시 H0STCNT0 + H0NXCNT0 + H0STOUP0 3 tr_id 동시 등록. KIS-first sample 정합 | 시간외 단일가 sync 회복 — 사용자 자본 보호 critical | 41 slot 한도(ADR 0048 §2-1) — 추가 20 slot 소모 → 풀 ≤ 13 으로 축소 (현재 watchlist 20 가정 시 재계산 필요) |
| B-2 H0STOUP0만 16:00~18:00 시간대 동적 subscribe/unsubscribe | 16:00 도달 시 SubscriptionRouter가 H0STOUP0 신규 등록, 18:00에 해제 | 41 slot 효율 ↑ — 시간외 시간대만 차지 | 시간 trigger 로직 추가 + dynamic subscribe 안정성 dogfooding 의무 |
| B-3 H0STOUP0 cover 보류 (시간외 단일가 trading 미지원 명시) | 시간외 단일가 시각대는 룰 매칭 0 | 단순 — magicJar 현재 미지원 명시 | 사용자 dogfooding 결함 잔존 — 자본 보호 critical risk |

architect 권장 = **B-2** (41 slot 효율 + 시간 trigger LOCK). 의제 B-1은 풀 ≤ 13 강제 시 backlog. SubscriptionRouter 시간 trigger는 KisMarketSessionFsm(ADR 0013) 합류 가능.

### 의제 C. 호가 매트릭스 — H0NXASP0 / H0STOAA0 추가 검토

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **C-1 (architect 권장)** 현재 H0STASP0만 유지 (LRU 10 slot) | NXT/시간외 호가는 backlog | 41 slot 압박 회피 | NXT 시간대 + 시간외 호가 stale |
| C-2 H0STASP0 + H0NXASP0 + H0STOAA0 풀세트 구독 | 모든 시간대 호가 sync | 사용자 dogfooding 완전 정합 | 41 slot 한도 위반 — slot 재계산 + 풀 축소 의무 |
| C-3 H0UNASP0 통합 호가 단일 구독 (KIS examples_user 명시) | KRX+NXT 통합 응답 단일 stream | slot 절약 + 통합 sync | KIS H0UNASP0 응답 sample 검증 의무 — 시간외 단일가 cover 여부 raw 명시 부재 |

architect 권장 = **C-1** (호가는 1차 보류 — 시세 sync 회복이 우선). C-3는 raw 검증 후 backlog 후보.

### 의제 D. heartbeat watchdog timeout

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **D-1 (architect 권장)** 10s → 30s 완화 + 정규장 종료 후 60s로 추가 완화 | KIS 표준 PINGPONG 주기(30s 추정) 정합 + 정규장 종료 후 stream 빈도 저하 대응 | false-positive 재연결 차단 + OPSP8996 발생 빈도 감소 | KIS 실제 timeout 실측 검증 dogfooding 의무 |
| D-2 10s 유지 + polling fallback 강화 | 현행 그대로 + polling fallback 활성화로 stale 회피 | 코드 변경 0 | OPSP8996 다발 잔존 risk |
| D-3 heartbeat watchdog 완전 폐기 — KIS PINGPONG 자체로 keep-alive 신뢰 | KIS 표준 패턴 최정합 — PINGPONG echo만 처리 | 단순 + KIS 정합 | network 끊김 감지 0 — 진짜 stale 발생 시 회복 0 |

architect 권장 = **D-1** (30s + 정규장 종료 후 60s). 의제 D-3는 KIS 표준에 가장 가깝지만 network 끊김 risk 대응 별도 필요.

### 의제 E. destroy() unsubscribe ACK 대기

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **E-1 (architect 권장)** unsubscribe send 후 KIS 응답 OK ACK 대기 (≤ 2s timeout) + ACK 시점에 dispose | KIS 측 session graceful close 보장 — 다음 부팅 시 stale session 잔존 risk 차단 | OPSP8996 가설 B 차단 | shutdown 시간 +2s — ADR 0045 5s 한도 안 |
| E-2 현행 unsubscribe send + 1s sleep 후 dispose | 단순 + 빠른 shutdown | KIS ACK 미확인 — stale session risk 잔존 | OPSP8996 가설 B 잔존 |

architect 권장 = **E-1**. shutdown 시간 +2s 영향 미미 (graceful 5s 한도 내).

### 의제 F. SubscriptionResponse OPSP8996 감지 + 재발급 trigger

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **F-1 (architect 권장)** SubscriptionResponse parsing 시 OPSP8996 감지 → approval_key invalidate + 1회 retry (지수 백오프) | self-heal — A-1 + 결함 발생 시 회복 | KIS subscribe response 파싱 정밀화 + 재시도 cap 의무 | F-1 + A-1 결합 시 본 ADR 채택안 |
| F-2 OPSP8996 발생 시 log only + reconnect 트리거 의존 | 코드 변경 최소 | reconnect 시 또 OPSP8996 → 무한 루프 잠재 |

architect 권장 = **F-1** (A-1 + F-1 결합 — KIS 표준 reuse + 결함 감지 시 한정 재발급).

### 의제 G. 시간외 시각대 룰엔진 처리 정책

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **G-1 (architect 권장)** 시그널·룰엔진은 시간외 tick도 동등 처리 + 자동매매는 KIS 주문 시간 한도 정합 (ADR 0013 KisMarketSessionFsm 합류) | 자본 보호 critical — 시간외 가격 변동 인지 + 룰 매칭. 주문 시점은 정규장 한정 LOCK | 사용자 의제 발의 정합 + ADR 0067 RiskGuard에 시간 게이트 추가 | KisMarketSessionFsm 미존재 시 후속 구현 의무 |
| G-2 시간외 tick은 logging only — 룰 매칭 0 | 단순 | 시간외 가격 변동 룰 매칭 0 — 사용자 자본 보호 risk |
| G-3 시간외 tick 일괄 정규장 데이터로 fold (KIS UN 정합) | ADR 0063 UN 통합 정합 | 시간외 단일가는 1 거래일 동안 단일 가격 → 룰 매칭 의미 |

architect 권장 = **G-1** (자본 보호 critical). 주문 시간 한도는 ADR 0067 RiskGuard 시간 게이트로 분리.

## 8. Rollback 시나리오

본 ADR 채택안 (A-1 + B-2 + C-1 + D-1 + E-1 + F-1 + G-1) 적용 후 결함 발생 시:

1. **OPSP8996 다발 재발생** (A-1 채택 후): F-1 self-heal 동작 확인 → 안 되면 A-3 옵션으로 분기 (specific 조건 invalidate)
2. **시간외 단일가 시각대 41 slot 한도 위반** (B-2 채택 후): SubscriptionRouter priority 재조정 — H0NXCNT0를 16:00~18:00 동안 임시 unsubscribe 또는 풀 사이즈 축소
3. **heartbeat 30s 완화 후 진짜 stale 미감지** (D-1 채택 후): polling fallback 활성화 + 별도 stale 감지 메트릭 추가
4. **graceful destroy() ACK 미수신** (E-1 채택 후): timeout 2s 후 강제 dispose — 현재 1s sleep 동작과 동일 결과로 fallback

revert는 PR 단위 `git revert <commit>` operation. 본 ADR은 plan only — revert 대상 0건 (코드 변경 0 LOCK).

## 9. 비범위 (post-MVP / backlog)

- 의제 B-1 H0STOUP0 풀세트 41 slot 한도 위반 시 풀 축소 검토 → backlog
- 의제 C-2·C-3 호가 NXT/시간외 cover → backlog (시세 sync 회복 안정화 후 검토)
- 의제 A-3 specific 조건 invalidate (A-1 + F-1 채택 시 자동 fallback 가능)
- KIS WS server side disconnect 정책 raw 가이드 문의 (KIS 측 답변 대기 의제 backlog)
- KIS PINGPONG 주기 실측 + magicJar 측 30s/60s timeout fine-tune backlog
- 시간외 단일가 시각대 backtest 룰 검증 backlog (W4 사이클)
- ADR 0048 §2-1 slot 한도 41건 매트릭스 갱신 (B-2 채택 후 시간 trigger 반영)

## 10. 사용자 결정 의제 7건 (architect 권장 요약)

| 의제 | 옵션 | architect 권장 | 사유 |
|---|---|---|---|
| A. approval_key 재발급 | A-1 / A-2 / A-3 | **A-1** | KIS 표준 24h cache reuse 정합. PR #444 invalidate revert. OPSP8996 루프 차단. |
| B. H0STOUP0 시간외 단일가 | B-1 / B-2 / B-3 | **B-2** | 16:00~18:00 dynamic subscribe — 41 slot 효율. KisMarketSessionFsm 시간 trigger 합류. |
| C. 호가 NXT/시간외 cover | C-1 / C-2 / C-3 | **C-1** | 시세 sync 회복 우선. NXT 호가는 backlog. |
| D. heartbeat watchdog timeout | D-1 / D-2 / D-3 | **D-1** | 30s + 정규장 종료 후 60s. KIS PINGPONG 주기 정합. |
| E. destroy() unsubscribe ACK | E-1 / E-2 | **E-1** | graceful close 보장 — 다음 부팅 stale session risk 차단. |
| F. SubscriptionResponse OPSP8996 self-heal | F-1 / F-2 | **F-1** | A-1 + F-1 결합 — 표준 reuse + 결함 한정 invalidate. |
| G. 시간외 룰엔진 처리 | G-1 / G-2 / G-3 | **G-1** | 자본 보호 critical — 룰 매칭 동등 + 주문 시간 게이트 RiskGuard 합류. |

→ **architect 풀 권장안 = A-1 + B-2 + C-1 + D-1 + E-1 + F-1 + G-1**.

채택 시 후속 sub-agent 디스패치 매트릭스는 §11 정밀 명세.

## 11. 사용자 채택 후 sub-agent 디스패치 매트릭스 (Phase 분리 — 채택 후 확정)

| Phase | sub-agent | 영역 | 주요 산출물 |
|---|---|---|---|
| **Phase 1** | market-data | `consumer/infrastructure/kis/KisApprovalAdapter.kt` + `KisWebSocketBridge.kt` | A-1 invalidate 호출 0 + Redis 23h cache reuse + F-1 SubscriptionResponse OPSP8996 감지 + 1회 retry |
| **Phase 2** | market-data | `KisWebSocketBridge.kt` heartbeat + destroy + `SubscriptionPolicy.kt` | D-1 heartbeat 30s + 정규장 종료 후 60s + E-1 unsubscribe ACK 2s 대기 |
| **Phase 3** | market-data | `KisWebSocketBridge.kt` + new `OvertimeSinglePriceSubscriber.kt` (16:00~18:00 scheduler) | B-2 H0STOUP0 dynamic subscribe + KisMarketSessionFsm 합류 (ADR 0013) |
| **Phase 4** (선택) | strategy-engine | `RiskGuard.kt` 시간 게이트 + Signal·Order 흐름 | G-1 시간외 시각대 룰 매칭 동등 + 주문 시간 정규장 LOCK |

Phase 1·2는 직렬 권장 (동일 파일 modify-shared). Phase 3는 Phase 1·2 안정화 후 직렬. Phase 4는 별도 사이클로 분리.

## 12. KIS-first raw fetch 출처 (헌법 §3 5단계 절차 정합)

본 ADR 작성 시 raw 인용 출처 매트릭스:

| 영역 | URL | 인용 위치 |
|---|---|---|
| WS 함수 매트릭스 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py` | §2-1 tr_id 풀세트 + §2-2 subscribe 패턴 |
| WS 다중 tr_id 패턴 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_examples_ws.py` | §2-2 표준 패턴 |
| approval_key 발급 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` | §2-2 cache reuse + §4 PINGPONG echo |
| NXT 체결가 sample | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_nxt/ccnl_nxt.py` | §2-1 H0NXCNT0 |
| 시간외 단일가 체결가 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py` | §2-1 H0STOUP0 + 16:00~18:00 활성 시간 |
| 디렉토리 트리 | `https://api.github.com/repos/koreainvestment/open-trading-api/git/trees/main?recursive=1` | §2-1 매트릭스 발견 (시간외 + NXT 풀세트) |

raw fetch 검증 일자: **2026-05-15**. KIS 측 코드 갱신 시 본 ADR 인용 정밀도 재검증 의무 (헌법 §3).

## 13. 변경 이력

| 일자 | 내용 |
|------|------|
| 2026-05-15 | ADR 0076 신규 발행 — 누적 hotfix #443·#444·#445 root cause 분석 + KIS 표준 패턴 7 의제 architect 자율 발의. 사용자 결정 위임 + 채택 후 sub-agent 디스패치 별도 사이클. |

## 14. 사용자 메모리 정합

- `feedback_kis_first_policy.md` — KIS open-trading-api raw fetch 100% 인용 (§2·§12 정합)
- `feedback_v3_skepticism_no_regression.md` — 자본 보호 critical (§1-4 fitness + §7-G G-1 권장)
- `feedback_3tier_hierarchy.md` — architect 분석 발의 → 사용자 결정 → sub-agent 디스패치 (§7·§11 정합)
- `feedback_spec_plan_scope.md` — neutral 의제 분석 spec OK (코드 변경 0 LOCK §1-2 정합)
- `feedback_verification_strict.md` — 누적 hotfix surface patch 비판 lens 동반 (§1-2 매트릭스 + §3 root cause 가설 정밀화)
- `feedback_no_mock_data.md` — 시간외·NXT 시간대 실제 KIS tick 정합 (mock 합성 0)
- `project_system_vision.md` — 룰 본체 + AI 0 (G-1 시간외 룰 매칭 동등 + AI 호출 0)
