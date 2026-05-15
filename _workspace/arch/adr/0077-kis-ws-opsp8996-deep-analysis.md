# ADR 0077 — KIS WS OPSP8996 잔존 결함 deep 분석 + revert 의제 매트릭스

- 상태: **Proposed** (2026-05-15 architect 자율 발의, ADR 0076 Phase 1·2·3 머지 후에도 결함 잔존 → root cause 재추적)
- 작성일: 2026-05-15
- 결정자: 사용자(최고 관리자) — §5 권장 fix 매트릭스 채택 후 sub-agent 디스패치 (architect 자율 위임 가능)
- 관계: **ADR 0076** (KIS WS NXT/시간외 표준 정합) supersede 대상 후보 — ADR 0076 §3 가설 매트릭스 H 추가 + §7 권장안 부분 revert 의제. ADR 0048 (WS 41 slot) · ADR 0063 (NXT 08:00~20:00) · ADR 0074 (SettingsModal reconnectGracefully) · 누적 hotfix PR #443·#444·#445 · ADR 0076 Phase fix PR #448·#449·#450

> 본 ADR은 **plan/analysis 단계 (Proposed)** — ADR 0076 권장안(A-1·B-2·D-1·E-1·F-1) Phase 1·2·3 PR 모두 머지·재배포 완료에도 OPSP8996 "ALREADY IN USE appkey" 103건 + 종목 005930 15:14 KST tick stop이 사용자 dogfooding(2026-05-15 19:21 KST)으로 재확인. ADR 0076 §3 root cause 가설 재평가 + 누적 PR 코드 영향 매트릭스 추적 + revert 의제 발의. **코드 변경 0 LOCK** (분석 사이클).

> **사용자 통찰 인용 (2026-05-15 19:21 KST)**: "Phase 1·2·3 머지·재배포 후에도 OPSP8996 ALREADY IN USE 103건 다시 발생 + candle 005930 = 15:14 stop" — 본 ADR 발의 trigger.

## 1. 잔존 결함 매트릭스 (Phase 1·2·3 머지 후 evidence)

### 1-1. 사용자 증상 (2026-05-15 19:21 KST)

| 항목 | 증상 | 발생 시각 | 영향 |
|---|---|---|---|
| OPSP8996 ALREADY IN USE | 103건 재발생 | dogfooding 시간대 누적 | F-1 self-heal cap(1회) 초과 → 다음 watchdog cycle 대기 → 무한 루프 잠재 |
| candle 005930 stop | 15:14 KST 이후 tick 0 | 정규장 종료(15:30) 16분 전 | 자본 보호 critical — 종목 가격 변동 인지 0 |

### 1-2. ADR 0076 권장안 채택 매트릭스 (Phase 1·2·3 적용 후 상태)

| 의제 | architect 권장 | 채택 PR | 적용 코드 위치 | 잔존 결함 영향 |
|---|---|---|---|---|
| A-1 invalidate revert + 24h cache reuse | ✅ 채택 | PR #448 | `KisWebSocketBridge.doConnect()` L327~341 — invalidate 호출 0 | OPSP8996 발생 빈도는 줄었으나 **103건 잔존** → ADR 0076 §3-A 가설은 root cause 일부 — but 단독 원인 아님 |
| F-1 OPSP8996 self-heal retry | ✅ 채택 | PR #448 | `KisWebSocketBridge.handleOpsp8996()` L811~847 — 1회 retry cap | 1회 retry cap → 2회 이상 발생 시 ERROR skip → 다음 watchdog cycle 대기 → cumulative 결함 잔존 |
| D-1 heartbeat 30s/60s | ✅ 채택 | PR #449 | `KisWebSocketBridge.currentHeartbeatTimeoutSeconds()` L702~711 | 정규장 30s timeout = false-positive 재연결은 감소, but 15:14 KST(정규장 종료 16분 전) tick stop 시점에서 30s 무신호 trigger → 재연결 + OPSP8996 발생 sequence 재현 가능 |
| E-1 unsubscribe ACK 2s | ✅ 채택 | PR #449 | `KisWebSocketBridge.destroy()` L863~922 | shutdown 시점만 적용 — 통상 runtime 재연결 시 미적용 → graceful close 미보장 잔존 |
| B-2 H0STOUP0 dynamic 16:00~18:00 | ✅ 채택 | PR #450 | `OvertimeSinglePriceSubscriber.kt` + `KisWebSocketBridge.subscribeOvertimeSinglePrice()` | H0STOUP0는 16:00 시작 — 15:14 stop 시점은 H0STOUP0 미관여. 잔존 결함과 무관 |

### 1-3. fitness gap (`feedback_v3_skepticism_no_regression.md` lens)

| 질문 | 답변 |
|---|---|
| 자동매매에 필요한가? | **필요 ★★★ critical**. 정규장 마지막 16분(15:14~15:30)은 종가 결정 + 자동매매 룰 매칭에서 자본 보호 가장 critical. tick stop = 룰 매칭 0 + 손절·익절 미발동 risk. |
| v1/v2 잔재 아닌가? | 잔재 아님. ADR 0076 권장안 채택 후 신규 결함 — Phase 1·2·3 사이드 이펙트 가능성. |
| AI 매매 결정 0건 위반 X? | 0건 (룰 5). 본 ADR은 시장 데이터 인프라 분석 — LLM provider import 0건. |
| KIS-first 정합? | 정합 — KIS open-trading-api raw fetch 인용 (§2-3·§3 매트릭스). 자체 추론 risk lens 동반. |

## 2. 누적 PR 코드 영향 매트릭스 (정밀 추적)

ADR 0076 §1-2 누적 hotfix 4건(#443·#444·#445) + Phase fix 3건(#448·#449·#450) = **총 6건 PR** 영향 추적.

### 2-1. PR 단위 변경 추적

| PR | 영역 | 적용 변경 | KisWebSocketBridge 영향 | KisApprovalAdapter 영향 | KisWsMessageParser 영향 |
|---|---|---|---|---|---|
| **#443** (2026-05-15) | market-data | H0NXCNT0 동시 구독 | `subscribe(codes)` H0NXCNT0 send 추가 + `nxtSubscribedCodes` Set + `handleNxtTickData()` | — | `parseH0Nxcnt0()` 추가 |
| **#444** (2026-05-15) — **A-1 revert 대상** | market-data | doConnect 시 invalidate | `doConnect()` 진입 시 `invalidate()` 호출 | `invalidate()` 메서드 추가 (Redis DEL) | — |
| **#445** (2026-05-15) | market-data | watchdog doFinally | `doConnect()` `doOnTerminate` → `doFinally` + `reconnecting` CAS guard | — | — |
| **#448** (2026-05-15) — **A-1 + F-1** | market-data | invalidate 호출 revert + OPSP8996 self-heal | doConnect 진입 시 invalidate 호출 0 (L327~341 주석 처리) + `handleOpsp8996()` 1회 retry cap 추가 + `opsp8996RetryCount` AtomicInteger | invalidate() 메서드는 코드에 잔존 — but doConnect에서 호출 0 (SystemKisKeyChangedListener.reconnectGracefully + Redis 직접 DEL 경로에는 잔존) | `isOpsp8996()` 메서드 추가 |
| **#449** (2026-05-15) — **D-1 + E-1** | market-data | heartbeat 30s/60s + destroy ACK 2s | `currentHeartbeatTimeoutSeconds()` 분기 (정규장 30s / 정규장 외 60s) + `destroy()`에 `pendingUnsubAckLatch` CountDownLatch + 2s await | — | `isUnsubscribeAck()` 메서드 추가 (OPSP0002 + UNSUB regex) |
| **#450** (2026-05-15) — **B-2** | market-data | H0STOUP0 16:00~18:00 dynamic subscribe | `subscribeOvertimeSinglePrice()` / `unsubscribeOvertimeSinglePrice()` 메서드 추가 + `overtimeSinglePriceCodes` Set + `handleOvertimeSinglePriceTickData()` + destroy 시 overtime 종목 unsubscribe 추가 | — | `parseH0Stoup0()` 메서드 추가 (live-overtime source) + columns 인덱스 H0STCNT0 동일 매핑 |

### 2-2. 상호 작용 충돌 매트릭스 (Phase 1·2·3 후 잔존)

| 상호 작용 | 잠재 결함 | 발생 조건 | architect 평가 |
|---|---|---|---|
| **재연결 시 subscribe 폭주** | doConnect onConnected 람다(L354~373)에서 H0STCNT0 + H0NXCNT0 동시 send. 재연결 N회 = `2N`건 subscribe 메시지 | watchdog dispose → doFinally → scheduleReconnect → doConnect 반복 | **★ 가능성 매우 높음** — 사용자 보고 OPSP8996 103건은 단일 재연결 사건 대비 너무 많음. 재연결 cycle N회 × 종목 K건 × 2(H0STCNT0+H0NXCNT0) = N×K×2 subscribe → KIS server 측 누적 active session 인식 |
| **F-1 self-heal과 onConnected 재구독 race** | OPSP8996 발생 시 F-1 1초 대기 후 재구독 + onConnected가 이미 재구독 메시지 전송 완료 → 동일 종목 중복 subscribe 메시지 | doConnect 직후 onConnected에서 H0STCNT0+H0NXCNT0 송신 → KIS 응답 OPSP8996 → 1초 후 F-1 재송신 (현재 활성 구독 목록 기준) | **★ 가능성 매우 높음** — `handleOpsp8996()` 내 L835~843 `session.subscribedCodes` 기반 재송신은 이미 onConnected에서 보낸 종목 그대로 재발사 → KIS 측 중복 인식 risk |
| **doConnect 다중 호출 가드 부재** | `connect()`에는 `session.connected || shuttingDown` guard 있으나(L313) `doConnect()`는 guard 0. scheduleReconnect + reconnectGracefully + connect() 동시 발생 시 doConnect 다중 호출 | (1) SystemKisKeyChangedListener.reconnectGracefully + (2) heartbeat watchdog dispose → doFinally → scheduleReconnect + (3) connect() 신규 호출 동시 발생 | **★ 가능성 중** — 사용자 dogfooding 환경에서 SettingsModal 키 변경 0이면 reconnectGracefully 미발생. but scheduleReconnect 자체로 doConnect 다중 호출 잠재 |
| **scheduleReconnect 1차 0ms 즉시** | reconnectDelaysMs[0] = 0L (L100) — 즉시 재연결. 이전 session의 graceful close ACK 미수신 시점에 새 connect 시작 | doFinally 진입 즉시 scheduleReconnect → 0ms 후 doConnect → 새 WS handshake + 새 approval_key reuse(24h cache) + 동일 종목 subscribe 송신 → KIS server 측 이전 session 정리 전 OPSP8996 반환 | **★ 가능성 매우 높음** — KIS 표준 raw 코드(kis_auth.py)는 재연결 시 1초 sleep 명시 (검증 §3-1) |
| **runtime 재연결 시 unsubscribe 0 + close** | E-1 unsubscribe ACK 2s 대기는 `destroy()` 한정. watchdog dispose 경로(`wsDisposable?.dispose()` L739) + graceful reconnect(L776)는 unsubscribe 메시지 0 발사 → KIS 측 ACK 0 → server side stale session 잔존 | watchdog timeout → reconnecting CAS 성공 → 즉시 dispose → graceful close 0 → 다음 doConnect에서 OPSP8996 (이전 session 잔존 인식) | **★ 가능성 매우 높음** — E-1은 shutdown 한정. 통상 runtime 재연결에서 graceful close 미적용은 ADR 0076 §3-B 가설 그대로 잔존 |
| **opsp8996RetryCount 초기화 시점 race** | onConnected 진입 시 `opsp8996RetryCount.set(0)` (L355) — but 새 doConnect 호출이 onConnected까지 도달 전에 이전 session의 OPSP8996 응답이 sendSink 통해 도착 가능 | KIS handshake 완료 전 sink 활성 — 응답 메시지가 새 session의 핸들러에 도착 시 카운터 reset 누락 risk | 가능성 중 — Reactor 체인 순서상 onConnected 람다 실행 시점이 sink 활성 직전이라 race window 좁음. but Reactor 비결정성 잔존 |

### 2-3. ADR 0076 §3 가설 매트릭스 재평가 (Phase 1·2·3 후)

| ADR 0076 가설 | 원래 평가 | Phase 후 재평가 | 확정 root cause 여부 |
|---|---|---|---|
| A (invalidate 빈도 과다) | ★ 가장 가능성 높음 | A-1 채택으로 차단 — but OPSP8996 잔존 → **단독 원인 아님** | 부분 원인 (해소) |
| B (consumer 재시작 시 graceful close ACK 미수신) | 가능 | **★ 가능성 매우 높음** — E-1은 destroy 한정, runtime 재연결 경로 미적용 | **★ root cause 후보 1** |
| C (heartbeat 10s false-positive) | 가능 | D-1 30s/60s 채택으로 차단 — but 정규장 30s = 15:14 stop 시점 trigger 가능 | 부분 원인 (잔존) |
| D (정규장 종료 후 KIS server side stream 자동 종료) | 가능 | 검증 미완 — 15:14는 정규장 종료 16분 전 → KIS server side disconnect 정책 불명 | 검증 의무 잔류 |
| E (reconnectGracefully race) | 가능성 낮음 | 사용자 SettingsModal 키 변경 0이면 미발생 — but Redis SystemKisKeyChangedListener 경로 잔존 | 가능성 낮음 (유지) |
| F (H0NXCNT0 다중 session 인식) | 가능성 낮음 | **★ 가능성 매우 높음** — KIS examples_user는 단일 tr_id sample만 제공. H0STCNT0 + H0NXCNT0 동시 subscribe sample raw 미발견. KIS server 측에서 동일 approval_key + 다른 tr_id를 별개 session으로 인식하면 동일 종목 N건 = 2N session 인식 → OPSP8996 다발 | **★ root cause 후보 2** |
| **H (신규 가설 — runtime 재연결 graceful close 0 + onConnected/F-1 race)** | — | **★ 가능성 매우 높음** — §2-2 매트릭스 다수 항목 종합. 재연결 cycle × 종목 K건 × 2(H0STCNT0+H0NXCNT0) × race factor = 103건 polynomial 누적 가능 | **★ root cause 후보 3 (가장 유력)** |

→ **확정 root cause 매트릭스 (가능성 ↓ 순)**:
1. **H (graceful close 0 + onConnected/F-1 race)** — 재연결 경로에 unsubscribe 미발사 + onConnected/F-1 중복 발사
2. **F (H0NXCNT0 다중 session 인식)** — KIS 측 동일 approval_key + 다중 tr_id 처리 정책 검증 의무
3. **B (graceful close ACK 미수신)** — E-1 destroy 한정 → runtime 미적용

## 3. KIS 측 상태 확인 가능 매트릭스 (raw fetch 검증)

### 3-1. KIS open-trading-api 레포 검증 (2026-05-15 raw fetch)

| 확인 영역 | 검증 출처 | 결과 |
|---|---|---|
| OPSP8996 코드 명시 | `gh api search/code?q=OPSP8996+repo:koreainvestment/open-trading-api` | **0건** — KIS server-side error code (공식 가이드 별도) |
| OPSP8996 이슈 명시 | `gh api search/issues?q=OPSP8996+repo:koreainvestment/open-trading-api` | **0건** |
| ALREADY IN USE 이슈 | `gh api search/issues?q=ALREADY+IN+USE+appkey+repo:koreainvestment/open-trading-api` | **0건** |
| approval_key 재발급 빈도 raw | `examples_user/kis_auth.py` reAuth_ws() | **86,400초(24h) reuse** — KIS 표준은 24h cache. 매 재연결 재발급 패턴 0 |
| 재연결 전 sleep raw | `examples_user/kis_auth.py` __runner() except 분기 | **`await asyncio.sleep(1)`** — 1초 대기 후 재연결 (재연결 0ms 0건) |
| 재연결 max retry | `examples_user/kis_auth.py` self.max_retries | **3회** default — magicJar는 무한 cycle (지수 백오프) |
| 다중 tr_id subscribe sample (H0STCNT0 + H0NXCNT0) | `examples_user/domestic_stock/domestic_stock_examples_ws.py` | **단일 tr_id sample만 chained subscribe** — H0STCNT0 + H0NXCNT0 동시 sample raw 미발견 |
| subscribe 중복 발사 방지 | KISWebSocket.subscribe() | **dedupe 0** — add_open_map() 단순 append. KIS 표준 client는 dedupe 의무 0 |
| OPSP8996 retry 로직 | KISWebSocket subscribe 응답 처리 | **rt_cd != "0" 시 retry 0** — KIS 표준 client는 ws 단위 reconnect만 처리. subscribe 단위 retry 0 |

### 3-2. KIS server side active session 조회·관리 endpoint

| 의제 | KIS REST API 가용성 |
|---|---|
| 현재 active WS session 조회 | **불명 — KIS 공식 API 가이드 검증 의무**. 공개 endpoint 없음 추정 |
| approval_key 발급 이력 조회 | **불명 — KIS 공식 API 가이드 검증 의무**. /oauth2/Approval response에 history 0 |
| 강제 disconnect 관리자 endpoint | **불명 — KIS 공식 API 가이드 검증 의무** |
| appkey 별 session 한도 정책 | **OPSP8996 "ALREADY IN USE appkey" 메시지로 추정 — appkey당 active session 1개**. 정확한 timeout·cleanup 주기 raw 명시 없음 |

→ **KIS 측 상태 확인 가능성 매우 낮음**. KIS 가이드 문의 의무 (backlog §54 의제 4 잔존).

### 3-3. KIS-first MCP 도구 활용 권장

KIS-code-assistant MCP 서버(`mcp__kis-code-assistant`)가 환경에 enabled. 본 ADR은 raw fetch 분석 사이클이지만, 후속 sub-agent 디스패치 시점에 KIS 측 admin endpoint·session policy 직접 질의 의무. CLAUDE.md MCP server instructions 정합.

## 4. 회복 절차 권장 매트릭스

### 4-1. 즉시 회복 (사용자 dogfooding 환경 — 2026-05-15 19:21 KST 시점)

| 옵션 | 동작 | 시간 | 권장 우선순위 |
|---|---|---|---|
| **R-1 (architect 권장)** consumer JVM 재시작 + 5분 대기 | consumer destroy → SIGTERM → E-1 unsubscribe ACK 2s → 5분 대기로 KIS server side session cleanup 보장 | 5~10분 | **★ 1순위** — 가장 안전. KIS server side cleanup 정책 불명이나 5분이면 통상 cleanup 충분 |
| R-2 Redis approval_key cache DEL + consumer 재시작 | `redis-cli DEL kis:approval:{userId}:REAL` + JVM restart | 즉시 | 2순위 — KIS server side 이전 session 인식 시 OPSP8996 잔존 가능 |
| R-3 KIS HTS·MTS 동시 로그인 해제 후 재시도 | 사용자가 다른 KIS 클라이언트(HTS/MTS/MCP) 로그아웃 → consumer 재시작 | 즉시 | 3순위 — 사용자 환경 검증 의무 |
| R-4 KIS 측 강제 disconnect 관리자 endpoint 호출 | (R-1 5분 대기로 미해소 시) KIS 공식 가이드 문의 | 미정 | 보조 — KIS 가이드 답변 대기 |

→ **권장 시퀀스**: R-1 → 미해소 시 R-2 → R-3 → R-4.

### 4-2. KIS HTS·MTS·MCP 동시 로그인 검증 의무

| 사용자 환경 점검 | 검증 방법 | OPSP8996 잠재 영향 |
|---|---|---|
| KIS HTS(영웅문) 로그인 상태 | 사용자가 HTS 종료 확인 | HTS WS session이 동일 appkey 사용 시 magicJar와 충돌 — but HTS는 별도 인증 체계로 분리됨 추정. 검증 의무 |
| KIS MTS(모바일) 로그인 | 사용자가 MTS 종료 확인 | 위와 동일 — 별도 인증 추정 |
| KIS MCP 다른 process | 사용자가 다른 magicJar process(별 worktree·docker) 동시 가동 검증 | **★ 가능성 높음** — 사용자가 docker compose down 미실행 + IDE bootRun 동시 가동 시 동일 appkey로 두 process가 WS 시도 |
| 다른 third-party 클라이언트 | 사용자 직접 인지 | 가능성 낮음 |

→ **검증 의무**: `lsof -i :21000 | grep magicJar` 또는 docker process 동시 가동 매트릭스 점검. 사용자 dogfooding 환경에서 직접 확인 권장.

### 4-3. KIS server side session timeout 추정 매트릭스 (raw 명시 0 — 추정)

| 검증 항목 | KIS 공식 가이드 | 추정 |
|---|---|---|
| 비정상 disconnect 후 session cleanup 시간 | **raw 명시 없음** | 1~5분 추정 (TCP keep-alive idle timeout 통상) |
| 정규장 종료(15:30) 후 KIS server side disconnect 정책 | **raw 명시 없음** | 즉시 disconnect 가능성 ★ — backlog §54 의제 4 잔존 |
| approval_key 24h 만료 후 KIS server side 자동 cleanup | **raw 명시 24h reuse 만 명시** | 만료 시점에 server side cleanup 추정 |

## 5. 권장 fix 매트릭스 (사용자 결정 의제 — architect 자율 위임 가능)

### 의제 H-1. runtime 재연결 graceful close 정합 (★ 권장 1순위)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **H-1-A (architect 권장)** watchdog dispose 경로 + reconnectGracefully 경로에 **unsubscribe + 1s 대기 + dispose** 패턴 적용 (E-1 패턴 destroy 한정 → 통상 재연결 경로 확장) | 모든 재연결 경로에서 KIS 측 graceful close 보장 — server side stale session 잔존 0 | OPSP8996 가설 B + H 차단 + KIS 표준 정합 (sleep 1s 명시 §3-1) | 재연결 시간 +1~3s — false-positive 재연결 빈도 감소(D-1 30s timeout)로 영향 미미 |
| H-1-B watchdog dispose 시 unsubscribe 0 유지 (현행) | 코드 변경 0 | OPSP8996 잔존 risk 잔류 |

architect 권장 = **H-1-A**.

### 의제 H-2. 재연결 1차 0ms → 1s 대기 (★ 권장 2순위)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **H-2-A (architect 권장)** `reconnectDelaysMs[0] = 0L` → `1000L` 변경 | KIS server side 이전 session cleanup 시간 1s 확보 — KIS 표준 raw 패턴 정합 (§3-1 인용) | OPSP8996 가설 H 차단 + 코드 변경 단 1줄 | 첫 재연결 1s 지연 — heartbeat 30s timeout 대비 영향 미미 |
| H-2-B 현행 즉시 재연결 유지 | 코드 변경 0 | OPSP8996 잔존 risk 잔류 |

architect 권장 = **H-2-A**.

### 의제 H-3. F-1 self-heal 비활성화 또는 onConnected 재구독과의 race 차단 (★ 권장 3순위)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **H-3-A (architect 권장)** F-1 self-heal handler에서 **onConnected 직후 1초 grace period** 추가 — onConnected의 재구독 메시지가 KIS 응답 받기 전 F-1 발화 차단 | onConnected/F-1 race 차단 + 중복 subscribe 발사 0 | §2-2 race 매트릭스 차단 + OPSP8996 빈도 추가 감소 | grace period 동안 진짜 OPSP8996도 retry 미발사 — but 다음 watchdog cycle로 fallback OK |
| H-3-B F-1 self-heal 완전 비활성화 — OPSP8996 ERROR log only | F-1 race 결함 완전 차단 | 일시적 OPSP8996 자동 회복 0 — 재연결 사이클 의존 |
| H-3-C F-1 유지 + onConnected 재구독 건너뛰기 (F-1 의존) | F-1 단독 책임 | 새 연결 후 첫 재구독 안정성 dogfooding 의무 |

architect 권장 = **H-3-A** (grace period 1s).

### 의제 H-4. H0NXCNT0 다중 tr_id 가설 검증 (★ 권장 4순위 — 실험적)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **H-4-A (architect 자율 — 실험적)** PR #443 부분 revert — H0NXCNT0 subscribe 일시 비활성화 + OPSP8996 발생 빈도 비교 | KIS server 측 다중 tr_id 인식 매트릭스 dogfooding evidence 수집 | 잔존 결함 root cause 확정 가능 | NXT 시간대(15:30~20:00) tick stop 잔존 — ADR 0063 NXT cover 일시 sunset risk |
| H-4-B H0NXCNT0 유지 + KIS 가이드 문의 (backlog §54 의제 4) | 데이터 잔존 + 추가 검증 의무 | 회복 시간 길어짐 |

architect 권장 = **H-4-A 실험적** — 단 24~48h dogfooding window 한정 + H0NXCNT0 즉시 복원 옵션. ADR 0063 부분 revert 의제.

### 의제 H-5. 누적 PR 부분 revert 매트릭스 (★ 최후 수단)

| 옵션 | 동작 | revert 대상 | Pro | Con |
|---|---|---|---|---|
| **H-5-A (architect 권장)** H-1·H-2·H-3 채택 후 dogfooding 검증 — OPSP8996 잔존 시 단계별 revert | (1) PR #450 H0STOUP0 → 영향 거의 없음 (16:00~18:00 한정) (2) PR #449 D-1 30s/60s → 10s 고정 복원 시 false-positive 다발 risk (3) PR #443 H0NXCNT0 → H-4-A로 검증 후 결정 (4) PR #448 F-1 → H-3-B로 비활성화 검토 | 결함 확정 시 마지막 안전 카드 | 회복 비용 큼 |
| H-5-B 누적 PR 전체 유지 + H-1·H-2·H-3만 추가 | 회복 비용 최소 + add-only | OPSP8996 잔존 시 추가 사이클 필요 |

architect 권장 = **H-5-B 우선** — H-1·H-2·H-3 추가 후 dogfooding evidence 수집. H-5-A는 H-1·H-2·H-3 채택 후에도 잔존 시 호출.

### 의제 H-6. ADR 0076 §3 가설 매트릭스 갱신 (cross-ref)

| 옵션 | 동작 |
|---|---|
| **H-6 (architect 자율 LOCK)** ADR 0076 §3 가설 매트릭스 본문에 **가설 H 추가** + ADR 0076 supersede 검토 | ADR 0076 §3은 본 ADR 0077 발의로 부분 supersede — ADR 0076 본문 (1) §3 가설 H 추가 row + (2) §7 권장안 후속 cycle 명시. 본 ADR 0077이 부분 supersede 명시. README ADR 인덱스 supersede 매트릭스 갱신. |

architect 자율 LOCK = H-6.

### 5-7. 통합 권장안 (architect 풀세트)

→ **architect 권장 = H-1-A + H-2-A + H-3-A + H-5-B** (H-4-A는 H-1·H-2·H-3 채택 후 잔존 시 fallback, H-6은 architect 자율 LOCK).

코드 변경 규모: KisWebSocketBridge.kt 단일 파일 ~30 라인 modify (3개 의제 묶음). 산출물 PR 1건 직렬.

## 6. ADR 0076 §3 가설 매트릭스 재평가 + 신규 가설 H

### 6-1. 가설 H 정의 (신규 — 본 ADR 발의)

**가설 H**: runtime 재연결 경로(watchdog dispose + reconnectGracefully)에 graceful close (unsubscribe + ACK 대기) 미적용 + scheduleReconnect 1차 0ms 즉시 재연결 + onConnected 재구독과 F-1 self-heal race.

3가지 결함 요소 조합:
1. **graceful close 0**: watchdog dispose 시 unsubscribe 메시지 0 발사 → KIS server side 이전 session "active" 상태로 인식
2. **0ms 재연결**: KIS server side cleanup 시간 0 → 즉시 새 connect → 동일 approval_key 사용 → KIS "ALREADY IN USE appkey" 반환
3. **onConnected + F-1 race**: 새 연결 onConnected 람다가 H0STCNT0+H0NXCNT0 즉시 발사 → KIS OPSP8996 응답 → 1초 후 F-1이 동일 종목 재발사 → 중복 subscribe 메시지 누적

→ 재연결 cycle N회 × 종목 K건 × 2(KRX+NXT) × ~2(F-1 race) = 4NK 폴리노미얼 누적. 사용자 보고 103건은 N≈5, K≈5~10 환경에서 정합.

### 6-2. ADR 0076 §3 supersede 매트릭스

| ADR 0076 §3 가설 | 본 ADR 0077 갱신 | supersede 여부 |
|---|---|---|
| A invalidate 빈도 과다 | A-1로 해소 | 부분 supersede (사후 검증) |
| B graceful close ACK 미수신 | **★ root cause 후보 1 — runtime 재연결에서 미적용 잔존 확정** | **확정 (E-1 destroy 한정 잔존)** |
| C heartbeat false-positive | D-1로 해소 | 부분 supersede |
| D 정규장 외 server side disconnect | 검증 미완 | 검증 의무 잔류 |
| E reconnectGracefully race | 가능성 낮음 | 유지 |
| F H0NXCNT0 다중 session 인식 | **★ root cause 후보 2 — H-4-A 실험적 검증 의무** | **재평가 필요** |
| **H (신규)** | **★ root cause 후보 3 (가장 유력) — H-1-A + H-2-A + H-3-A 채택안** | **★ 본 ADR 신규** |

### 6-3. 본 ADR 0077의 ADR 0076 부분 supersede 선언

ADR 0076는 **plan 단계 분석**으로 권장안 A-1·B-2·D-1·E-1·F-1·G-1 제시 + Phase 1·2·3 PR로 채택. but Phase 1·2·3 후에도 OPSP8996 잔존이 사용자 dogfooding 검증으로 확정 → ADR 0076 권장안은 **부분 효과만 입증**. 본 ADR 0077이 §3 가설 매트릭스를 **부분 supersede + 가설 H 신규 발의**.

ADR 0076 status는 **Accepted (Partial — supersede by ADR 0077 §6)**. ADR 0076 본문 갱신 의무는 H-6 의제로 architect 자율 LOCK.

## 7. Rollback 시나리오

본 ADR 0077 권장안 (H-1-A + H-2-A + H-3-A) 채택 후 결함 발생 시:

1. **OPSP8996 잔존 (H-1·H-2·H-3 채택 후 dogfooding)**: H-4-A 실험적 — PR #443 H0NXCNT0 일시 비활성화 + 24h evidence 수집. 결함 확정 시 ADR 0063 NXT cover 부분 sunset 의제 발의.
2. **재연결 시간 +1~3s 영향 critical**: H-1-A의 unsubscribe 대기 시간 cap 조정 (2s → 1s) 또는 ACK 대기 비활성화 + send only fire-and-forget로 fallback.
3. **F-1 grace period 동안 진짜 OPSP8996 미회복**: H-3-B F-1 완전 비활성화 + 재연결 cycle 의존 패턴 fallback.
4. **누적 PR 전체 revert 의무 확정 (최후 수단)**: H-5-A 단계별 revert — PR #450 → #449 → #448 → #443 순서 (영향도 낮은 순). 각 revert 후 24h dogfooding evidence 수집 의무.

revert 단위는 PR 단위 `git revert <commit>`. 본 ADR은 plan only — revert 대상 0건 (코드 변경 0 LOCK).

## 8. 비범위 (post-MVP / backlog §55 등재)

- KIS server side session timeout 정책 raw 가이드 문의 (KIS 측 답변 대기 — backlog §54 의제 4 기존 + §55 신규)
- KIS HTS·MTS·MCP 다른 클라이언트 동시 로그인 매트릭스 detect — 사용자 직접 점검 의무
- H-4-A H0NXCNT0 일시 비활성화 dogfooding 24h evidence 수집 — backlog §55 신규
- ADR 0076 §3 가설 D (정규장 외 server side disconnect 정책) 검증 — backlog §54 의제 4 잔존
- consumer 부팅 후 5분 stabilization window 강제 (재연결 cycle suppressed) — backlog §55 신규
- 시간외 단일가 시각대 backtest 룰 검증 — backlog §54 의제 6 (기존 잔류)

## 9. 권장 fix 매트릭스 요약 (architect 자율 위임)

| 의제 | architect 권장 | 채택 trigger | 후속 PR |
|---|---|---|---|
| H-1 runtime 재연결 graceful close 정합 | **H-1-A** | architect 자율 LOCK | market-data PR — KisWebSocketBridge watchdog dispose + reconnectGracefully unsubscribe+1s 추가 |
| H-2 재연결 1차 0ms → 1s | **H-2-A** | architect 자율 LOCK | market-data PR (H-1과 묶음 가능) — reconnectDelaysMs[0] = 1000L |
| H-3 F-1 grace period | **H-3-A** | architect 자율 LOCK | market-data PR (H-1과 묶음 가능) — handleOpsp8996 onConnected 후 1s grace period |
| H-4 H0NXCNT0 다중 tr_id 검증 | H-4-A 실험적 (H-1·H-2·H-3 채택 후 잔존 시) | dogfooding evidence trigger | market-data PR — PR #443 부분 revert |
| H-5 누적 PR revert (최후 수단) | H-5-B 우선 (add-only H-1·H-2·H-3) | H-1·H-2·H-3 채택 후 잔존 시 | 단계별 revert PR |
| H-6 ADR 0076 §3 갱신 (cross-ref) | architect 자율 LOCK | 본 ADR 머지 직후 | architect 단독 docs PR |

→ **architect 풀 권장안 = H-1-A + H-2-A + H-3-A + H-6** (H-4·H-5는 fallback). 채택 시 후속 sub-agent 디스패치 매트릭스는 §10 정밀 명세.

## 10. 사용자 채택 후 sub-agent 디스패치 매트릭스

| Phase | sub-agent | 영역 | 주요 산출물 | 영향 매트릭스 |
|---|---|---|---|---|
| **Phase 1** (architect 자율 직렬) | market-data | `KisWebSocketBridge.kt` watchdog dispose + reconnectGracefully + scheduleReconnect + handleOpsp8996 | H-1-A graceful close + H-2-A 1s 대기 + H-3-A F-1 grace period | KisApprovalAdapter 변경 0, OvertimeSinglePriceSubscriber 변경 0 |
| **Phase 2** (architect 단독 docs PR) | architect | `_workspace/arch/adr/0076-kis-ws-nxt-overtime-standard.md` + README | ADR 0076 status "Accepted (Partial — superseded by ADR 0077 §6)" 추가 + §3 가설 H row + README ADR 인덱스 supersede 매트릭스 갱신 | ADR 0076 본문 갱신만 |
| **Phase 3** (선택, dogfooding 잔존 시) | market-data | PR #443 부분 revert 또는 추가 fix | H-4-A 실험적 H0NXCNT0 비활성화 24h evidence 수집 | ADR 0063 부분 sunset 의제 |
| **Phase 4** (선택, 최후 수단) | market-data | 단계별 revert | H-5-A PR #450 → #449 → #448 → #443 순서 revert | 영향도 큰 순 |

Phase 1·2는 직렬 권장 (동일 worktree). Phase 3·4는 dogfooding evidence trigger 후.

## 11. KIS-first raw fetch 출처 (헌법 §3 5단계 절차 정합)

본 ADR 작성 시 raw 인용 출처 매트릭스:

| 영역 | URL | 인용 위치 |
|---|---|---|
| KIS 표준 approval_key 24h reuse | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` | §3-1 reAuth_ws() 86400s |
| KIS 표준 재연결 1s sleep | 동일 URL — __runner() except 분기 | §3-1 `await asyncio.sleep(1)` |
| KIS 표준 max retry 3회 | 동일 URL — self.max_retries default | §3-1 (magicJar 무한 cycle과 대비) |
| 다중 tr_id sample raw 검증 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_examples_ws.py` | §3-1 단일 tr_id sample만 chained subscribe (H0STCNT0 + H0NXCNT0 동시 sample 미발견) |
| subscribe dedupe 0 검증 | KISWebSocket.subscribe() | §3-1 add_open_map() 단순 append |
| OPSP8996 코드/이슈 검증 | `gh api search/code?q=OPSP8996+repo:koreainvestment/open-trading-api` + `search/issues` | §3-1 0건 (KIS server-side error code) |

raw fetch 검증 일자: **2026-05-15**. KIS 측 코드 갱신 시 본 ADR 인용 정밀도 재검증 의무 (헌법 §3).

## 12. 변경 이력

| 일자 | 내용 |
|------|------|
| 2026-05-15 | ADR 0077 신규 발행 — OPSP8996 잔존 결함 deep 분석 (ADR 0076 Phase 1·2·3 머지 후 사용자 dogfooding 재발 보고 19:21 KST trigger). 가설 H 신규 + H-1-A + H-2-A + H-3-A 권장안 architect 자율 발의. ADR 0076 §3 부분 supersede 선언 (가설 H 추가 + §7 권장안 추가 cycle 필요). |

## 13. 사용자 메모리 정합

- `feedback_kis_first_policy.md` — KIS open-trading-api raw fetch 100% 인용 (§3·§11 정합)
- `feedback_v3_skepticism_no_regression.md` — 자본 보호 critical (§1-3 fitness + 15:14 stop은 정규장 마지막 16분 critical risk)
- `feedback_3tier_hierarchy.md` — architect 분석 발의 → 사용자 자율 위임 → sub-agent 디스패치 (§9·§10 정합)
- `feedback_spec_plan_scope.md` — neutral 의제 분석 spec OK (코드 변경 0 LOCK §1-2 정합)
- `feedback_verification_strict.md` — Phase 1·2·3 머지 GREEN ≠ 결함 해소 검증 lens (§1-2 evidence 인용 의무)
- `feedback_no_mock_data.md` — 실 KIS WS dogfooding evidence 기반 (mock 합성 0)
- `feedback_autonomy.md` — architect 자율 발의 + 사용자 위임 (§9 architect 풀 권장안 자율 LOCK)
- `project_system_vision.md` — 룰 본체 + AI 0 (G-1 시간외 룰 매칭 동등 + AI 호출 0 정합)
- `feedback_pr_rebase_sync.md` — 본 ADR 후속 PR 머지 시 rebase 정책 유지
