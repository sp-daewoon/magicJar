# ADR 0077 — OPSP8996 ERROR 재발 deep dive 분석 (v3.6.11 hotfix — ADR 0076 6 가설 미발동 시나리오 신규 발굴)

- 상태: **Proposed** (2026-05-17 architect 자율 분석 발의, 사용자 결정 의제 매트릭스 §10 위임)
- 작성일: 2026-05-17 (KST)
- 결정자: 사용자(최고 관리자) — 본 ADR의 의제 §10 풀세트 결정 후 후속 사이클 sub-agent 디스패치
- 관계: **ADR 0076** (KIS WS NXT/시간외 시세 sync 표준 정합 — 6 가설 A·B·C·D·E·F 모두 자격증명 유효 가정) · ADR 0046 (Onboarding & Credential Registration — REAL/MOCK 분리) · ADR 0048 (KIS WS 41 slot 한도) · ADR 0074 (SettingsModal — KIS 키 변경 + reconnectGracefully) · ADR 0038 (KIS OAuth 24h 토큰 + Redis 분산락) · v3.6.10 cut 직후 docker logs evidence
- 사용자 메모리 정합: [feedback_kis_key_realonly_for_data.md] · [project_kis_key_management.md] · [feedback_kis_first_policy.md] · [feedback_verification_strict.md] · [project_system_vision.md]

> 본 ADR은 **plan/analysis 단계 (Proposed)** — v3.6.10 cut(ADR 0076 Phase 1·2·3 머지 완료) 후에도 OPSP8996 ERROR 86건/1시간이 재발한 docker logs evidence를 기반으로, ADR 0076 6 가설(A·B·C·D·E·F)이 모두 자격증명 유효 가정에 한정된 점 + 사용자 메모리 [feedback_kis_key_realonly_for_data]와의 정합 결함 + retry counter logic 결함을 신규 가설(G·H·I·J)로 발굴. 코드 변경 0 LOCK (분석 사이클). 결정 채택 후 sub-agent 디스패치는 별도 사이클로 분리 (Phase 매트릭스 §11).

> **§12 reserved 슬롯 발동 사유**: ADR README §12 표 "0077 Reserved — ADR 0076에 분석 내용 흡수됨, 후속 OPSP8996 deep dive 필요 시 재사용" → 본 ADR이 정확히 그 시나리오 진입. README 갱신 의무 동반 (본 PR 동봉).

---

## 1. 컨텍스트 — v3.6.10 cut 직후 evidence

### 1-1. docker logs evidence (오케스트라 추출, 2026-05-17)

```
20:35:35.294 WARN  KisWebSocketBridge: KIS WS approval_key 발급 불가 — type=MOCK 자격증명 미등록 (SetupWizard에서 등록 후 재부팅 필요): userId=thde
20:35:35.312 ERROR KisWebSocketBridge: KIS WS OPSP8996 2회 fail — skip: attempt=69 ...
20:35:35.313 ERROR ... attempt=70~85 (1초 내 17건 연속)
```

**핵심 신호 3건**:

1. **`type=MOCK`** — 시세 수집 경로가 MOCK 자격증명으로 approval_key 발급을 시도 중. 사용자 메모리 [feedback_kis_key_realonly_for_data] "시세·캔들·호가 등 정보 수집은 모두 실전 키. 모의/실전 분기는 주문 액션에만" 정합 위반.
2. **`userId=thde`** — KIS user ID 접두 4자 (마스킹). 본 사용자는 MOCK 자격증명을 미등록 상태(SetupWizard에서 REAL만 등록 또는 MOCK 등록 누락).
3. **`attempt=69~85`** — `opsp8996RetryCount.incrementAndGet()` 누적값. ADR 0076 F-1 "1회 retry 후 ERROR + skip" 의도와 정면 충돌. 본 ADR §4 retry counter logic 결함 발굴.

### 1-2. ADR 0076 6 가설 미발동 매트릭스

| 가설 | ADR 0076 설명 | 현 시나리오(MOCK 자격증명 미등록) 발동 여부 |
|---|---|---|
| **A** approval_key 매 재발급 → KIS session 강제 invalidate | A-1 채택(invalidate 호출 0 + 24h reuse) | **미발동** — 자격증명 자체 미등록 → KIS 호출 자체 불가 |
| **B** 이전 process WS session 잔존 | E-1 채택(unsubscribe ACK 2s 대기) | **미발동** — 부팅 직후 새 process |
| **C** heartbeat 10s timeout false-positive | D-1 채택(30s/60s 동적) | **미발동** — 연결 자체가 안 되므로 heartbeat 무관 |
| **D** 15:30 KIS server stream 자동 종료 | (raw 명시 부재, backlog) | **부분 발동 가능성** — 20:35는 정규장 종료 후 + NXT 시간대 — but 본 결함 root cause 아님 |
| **E** KIS 키 변경 race | (가능성 낮음) | **미발동** — 키 변경 0건 시나리오 |
| **F** OPSP8996 self-heal retry | F-1 채택(1회 retry → 2회 skip) | **logic 결함으로 발동 — but skip 후 counter reset 0 → attempt 무한 누적** (본 ADR §4) |

→ **결론**: ADR 0076 6 가설은 모두 "**자격증명 유효 + KIS 응답 OPSP8996 도착**" 시나리오 가정. 현 evidence는 "**자격증명 미등록 + getApprovalKey 자체 fail + ERROR_APPROVAL_KEY 문자열 fallback + 무한 retry**" 시나리오 → 신규 가설 G·H·I·J 의무.

### 1-3. fitness gap ([feedback_v3_skepticism_no_regression] lens)

| 질문 | 답변 |
|---|---|
| 자동매매에 필요한가? | **필요 ★★★**. 자격증명 미등록 시 무한 retry → docker logs ERROR 폭증 → 운영 노이즈 + 향후 진짜 결함 가시성 차단 + 디스크/모니터링 비용. 사용자 디버깅 신호 노이즈로 매몰. |
| v1/v2 잔재 아닌가? | **잔재 아님**. ADR 0046 Onboarding 도입(v3.x) + ADR 0076 F-1(v3.6.9)에서 도입된 코드 구간의 신규 결함. |
| AI 매매 결정 0건 위반 X? | 0건 (룰 5). 자격증명 + WS 인프라 영역 — LLM provider import 0건. |
| KIS-first 정합? | **검증 의무**. KIS 표준 패턴은 자격증명 미등록 시 어떻게 처리하는가? §3 raw 인용. |
| 사용자 메모리 정합? | **결함 ★** — [feedback_kis_key_realonly_for_data] "정보 수집=실전 키" 위반. MOCK type 시세 connect 자체가 정책 위반. §5 정합 결함. |

---

## 2. 자격증명 미등록 시 magicJar 현행 코드 path 추적 (root cause 정합)

### 2-1. 호출 chain (부팅 시점)

```
consumer process boot
  └─ WatchlistIngestionWorker.@PostConstruct onStartup()  [WatchlistIngestionWorker.kt:48]
      ├─ watchlistRepository.findAll()  → users={thde****} 1건
      ├─ kisWsPort.connect()  [KisWebSocketBridge.kt:312]
      │   └─ scheduleConnect(0) → doConnect()  [line 327]
      │       ├─ sink = Sinks.many().unicast()...  (sendSink 활성화)
      │       ├─ getApprovalKey()  [line 924]
      │       │   ├─ defaultUserId = "thde" (env MAGICJAR_KIS_WS_USER_ID)
      │       │   ├─ accountType = AccountType.MOCK  (magicjarMode != "REAL" 분기)
      │       │   ├─ approvalAdapter.getApprovalKey("thde", MOCK)
      │       │   │   └─ credentialRepository.findAllByAccountType("MOCK")  → 0건
      │       │   │   └─ creds.firstOrNull() ?: error("KIS 자격증명 없음 — userId=thde type=MOCK")
      │       │   └─ catch IllegalStateException → log.warn(...) + return "ERROR_APPROVAL_KEY"  [line 935~940]
      │       └─ wsClient.execute(URI).subscribe()  → WS 연결 시도 (approval_key="ERROR_APPROVAL_KEY")
      └─ subscriptionRouter.recompute("thde")
          └─ for each code in watchlist (16건 가정):
              └─ kisWsPort.subscribe(listOf(code))  [KisWebSocketBridge.kt:169]
                  ├─ approvalKey = getApprovalKey()  → "ERROR_APPROVAL_KEY" 또 호출 + WARN 로그
                  └─ sendSink?.tryEmitNext(buildSubscribeMessage(approvalKey="ERROR_APPROVAL_KEY", ...))
                      → KIS 서버에 invalid approval_key로 subscribe 메시지 발사 (32건 = 16종목 × H0STCNT0+H0NXCNT0)
```

### 2-2. KIS 응답 → 무한 loop 결함

```
KIS 서버 응답 (subscribe 메시지 32건 각각):
  {"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey",...}}  OR
  {"body":{"rt_cd":"1","msg_cd":"OPSP????","msg1":"INVALID approval_key",...}}

KisWebSocketBridge.handleMessage(raw)  [line 403]
  └─ KisWsMessageParser.parse(raw) → SubscriptionResponse
      └─ !isSubscriptionSuccess && isOpsp8996  → handleOpsp8996(rawJson)  [line 811]
          ├─ attempt = opsp8996RetryCount.incrementAndGet()
          ├─ attempt > 1 → log.error("OPSP8996 2회 fail — skip: attempt=N") + return  [line 813-820]
          │   ★ counter는 reset 0 안 함 → 다음 OPSP8996 응답 시 attempt=N+1로 누적
          └─ (attempt == 1 한정) Thread.sleep(1000) + resubscribe 32건 재전송
              → KIS가 또 OPSP8996 응답 32건 → handleOpsp8996 32회 호출
              → attempt 2, 3, ..., 33 → 모두 ERROR skip
              → 이후에도 sendSink에 누적된 메시지가 KIS로 송신 + KIS 응답 도착 시마다 또 attempt++
```

### 2-3. 결함 명세 (코드 위치 인용)

| 결함 | 코드 위치 | 현재 동작 | 정합 결함 |
|---|---|---|---|
| **결함 #1: getApprovalKey 자격증명 미등록 시 silent fallback** | `KisWebSocketBridge.kt:924~944` | `IllegalStateException` catch → log.warn + return `"ERROR_APPROVAL_KEY"` 문자열 | "ERROR_APPROVAL_KEY"가 그대로 KIS subscribe 메시지에 포함되어 무한 OPSP???? 응답 유발. **예외를 던지지 않고 문자열 fallback이 자체 결함의 source.** |
| **결함 #2: OPSP8996 retry counter reset 0 누락** | `KisWebSocketBridge.kt:811~821` | `attempt > 1` 시 ERROR + skip만 — counter reset 0 호출 0 | "1회 retry → 2회 skip" 의도와 정면 충돌. attempt가 무한 누적. evidence attempt=69~85가 이 결함의 직접 증거. F-1 design intent 위배. |
| **결함 #3: connect()가 자격증명 사전 검증 0건** | `KisWebSocketBridge.kt:312~316` (`connect()`) + `WatchlistIngestionWorker.kt:59` | session/credential 검증 없이 connect 무조건 시도 | KIS WS handshake + subscribe 메시지 폐기 (ERROR_APPROVAL_KEY) → KIS 측 무의미 요청 누적 |
| **결함 #4: MOCK type 시세 connect 정합 위반** | `KisWebSocketBridge.kt:929` | `magicjarMode != "REAL" → AccountType.MOCK` 분기 → MOCK 자격증명으로 approval_key 발급 시도 | 사용자 메모리 [feedback_kis_key_realonly_for_data] "정보 수집=실전 키" 정합 위반. **MOCK은 주문 액션 한정이어야 함.** §5 KIS-first 검증 + 정합 결함 |
| **결함 #5: SubscribeMessage send 전 sendSink 유효성 검증 0** | `KisWebSocketBridge.kt:172~187` (`subscribe()`) | `sendSink?.tryEmitNext(msg)` — sink null 시 silent drop, sink 활성 시 invalid msg 그대로 송신 | WS 연결 fail 또는 approval_key invalid 시에도 메시지 sink에 적재 → 연결 회복 후 폭발 |

→ **root cause**: 결함 #1·#2·#4 결합. #1이 문자열 fallback이라는 silent path 만들고 → #4가 잘못된 AccountType으로 인한 자격증명 미등록 진입 가속 → #2가 OPSP8996 무한 누적 ERROR 노출.

---

## 3. KIS open-trading-api 표준 패턴 — 자격증명 미등록 시 처리 (raw fetch 인용)

### 3-1. raw URL + 검증 일자

- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` (검증 2026-05-17)
- 디렉토리 리스트: `https://api.github.com/repos/koreainvestment/open-trading-api/contents/examples_user` (검증 2026-05-17)

### 3-2. KIS 표준 `auth_ws()` 인용 (자격증명 미등록 처리)

raw 인용:

```python
def auth_ws():
    p["appkey"] = _cfg[ak1]
    p["secretkey"] = _cfg[ak2]
    # ... POST /oauth2/Approval
    if rescode == 200:
        approval_key = _getResultObject(res.json()).approval_key
    else:
        print("Get Approval token fail!\nYou have to restart your app!!!")
        return  # ← 명시적 None 반환 (예외 raise 0건)
```

**핵심 정합 의무 3건**:

1. **사전 검증 0건** — KIS 표준도 빈 값 그대로 POST 시도. 명시적 사전 fail 패턴 없음.
2. **응답 fail 시 print + None 반환** — magicJar의 "ERROR_APPROVAL_KEY" 문자열 fallback과 차이. **KIS 표준은 None → 호출자가 None 체크 후 connect 자체를 안 함이 표준**.
3. **사용자 안내 메시지** — `"You have to restart your app!!!"` — 자격증명 등록 + 재부팅이 KIS 표준 안내.

### 3-3. KIS 표준 `KISWebSocket` 클래스 retry cap 인용

raw 인용:

```python
class KISWebSocket:
    def __init__(self, api_url: str, max_retries: int = 3):
        self.api_url = api_url
        self.max_retries = max_retries
    # ...
    async def __runner(self):
        try:
            # connect + subscribe
        except Exception as e:
            print("Connection exception >> ", e)
            self.retry_count += 1
            await asyncio.sleep(1)
            if self.retry_count < self.max_retries:
                # retry
            else:
                return  # 종료
```

**핵심 정합 의무 2건**:

1. **`max_retries: int = 3` cap** — KIS 표준은 retry 3회 cap. magicJar 현행 retry cap = `opsp8996RetryCount`만 존재 + watchdog 재연결 cap 0 → 무한 retry 정합 위반.
2. **OPSP8996 분기 0건** — KIS 표준은 일반 `Exception`처럼 처리 + sleep 1s + retry. magicJar의 OPSP8996 특수 분기(`handleOpsp8996`)는 KIS 표준 superset.

### 3-4. magicJar 현행 vs KIS 표준 차이 매트릭스

| 항목 | KIS 표준 (raw) | magicJar 현행 | 정합 결함 |
|---|---|---|---|
| 자격증명 미등록 시 approval_key | None 반환 + 호출자가 None 체크 | "ERROR_APPROVAL_KEY" 문자열 fallback → connect 시도 강행 | ★ critical — 결함 #1 |
| approval_key fail 시 connect | 호출자가 None 체크 → connect 안 함 | 무조건 connect 시도 + invalid approval_key로 subscribe 발사 | ★ critical — 결함 #3 |
| retry cap | `max_retries: int = 3` (생성자 기본) | `opsp8996RetryCount` 무한 + watchdog 재연결 무한 | ★ critical — 결함 #2 |
| OPSP8996 분기 | 일반 Exception처럼 처리 | 특수 분기(`handleOpsp8996`) + 재구독 retry | superset OK — but counter logic 결함 (#2) |
| MOCK type 시세 connect | (raw 명시 부재 — KIS examples는 REAL 한정) | mode != REAL → MOCK 분기 | ★ — [feedback_kis_key_realonly_for_data] 정합 결함 #4 |

---

## 4. 신규 root cause 가설 매트릭스 (G·H·I·J — ADR 0076 6 가설 미커버 영역)

### 4-1. 가설 G — 자격증명 미등록 시 retry 무한 loop (★ 가장 가능성 높음, evidence 직접 증거)

| 항목 | 설명 |
|---|---|
| 가설 | MOCK type 자격증명 미등록 + getApprovalKey "ERROR_APPROVAL_KEY" 문자열 fallback + opsp8996RetryCount reset 0 누락 → KIS subscribe response OPSP8996/INVALID 무한 누적 |
| 증거 | docker logs evidence 직접 — `attempt=69~85 1초 내 17건` (1회 retry resubscribe 32건이 KIS에 도달 → OPSP8996 32건 응답 → handleOpsp8996 32회 호출). 본 ADR §2-2 코드 chain 추적 일치. |
| 검증 방법 | 1) SetupWizard에서 MOCK 자격증명 등록 후 재부팅 → ERROR 0건 검증 / 2) getApprovalKey에 예외 throw 변경 후 connect 사전 차단 검증 / 3) opsp8996RetryCount reset 0 추가 후 attempt 0~2 한도 검증 |
| architect 평가 | **★★★ 최우선 — root cause 확정** |

### 4-2. 가설 H — MOCK type 시세 connect 자체 정합 결함 (★ 사용자 메모리 정합)

| 항목 | 설명 |
|---|---|
| 가설 | 사용자 메모리 [feedback_kis_key_realonly_for_data] "시세·캔들·호가 등 정보 수집은 모두 실전 키" → MOCK type으로 시세 WS connect 자체가 정책 위반. 코드 logic은 magicjarMode == "REAL" 일치 안 하면 무조건 MOCK으로 분기 (line 929) — but 시세 수집은 mode 무관 REAL 고정이어야 함. |
| 증거 | KisWebSocketBridge.kt:929 `val accountType = if (magicjarMode == "REAL") AccountType.REAL else AccountType.MOCK` — 본 logic이 시세 WS에 잘못 적용. (주문 액션은 KisOrderHttpAdapter 별도 영역.) |
| 검증 방법 | 1) 시세 WS는 REAL 고정 (mode 무관) 변경 / 2) REAL 자격증명만 있고 MOCK 없는 사용자 시나리오 정상 작동 검증 |
| architect 평가 | **★★★ critical — 사용자 메모리 + KIS 표준(KIS examples는 REAL 한정 패턴) 정합** |

### 4-3. 가설 I — retry counter reset 0 누락 (F-1 design intent 위배)

| 항목 | 설명 |
|---|---|
| 가설 | `handleOpsp8996()` attempt > 1 시 ERROR + skip만 — counter reset 0 안 함. ADR 0076 F-1 "1회 retry → 2회 skip" 의도와 충돌. 다음 OPSP8996 응답 시 attempt가 무한 누적. |
| 증거 | KisWebSocketBridge.kt:813~820 — `incrementAndGet()` 후 `set(0)` 호출 0. 새 연결마다 reset(355번 line)은 있지만 본 결함의 진입은 새 연결 발생 안 함(자격증명 fail로 connect 자체가 안 되거나 연결 성공 후 응답만 fail) |
| 검증 방법 | 가설 G 검증 시 동시 해소 — 또는 단독 unit test: 동일 connection 내 OPSP8996 응답 5회 도달 시 attempt가 1·2·1·2·1·... reset되는지 검증 |
| architect 평가 | **★★ 가설 G와 결합 — H + I 동시 fix 의무** |

### 4-4. 가설 J — connect() 자격증명 사전 검증 0건 (KIS 표준 패턴 superset 권장)

| 항목 | 설명 |
|---|---|
| 가설 | KIS 표준은 명시적 사전 검증 0건이지만 magicJar는 onStartup 시점에 watchlist + credential 둘 다 확인 후 connect 권장. 자격증명 0건이면 connect 자체 skip → ERROR_APPROVAL_KEY 문자열 발사 차단 + 사용자에게 명확한 안내. |
| 증거 | WatchlistIngestionWorker.kt:48~78 — watchlist만 검증, credential 검증 0건. |
| 검증 방법 | OnboardingService 완료 신호(UserOnboardedEvent) 후에만 WS connect 활성화 + 자격증명 존재 검증 |
| architect 평가 | **★★ 결함 #1·#3와 결합 — guard 패턴 권장** |

### 4-5. 가설 D 재검증 — 15:30 KIS server stream 자동 종료

evidence 시각 20:35 = 정규장 종료 후 + NXT 시간대(15:30~20:00 종료 직전). 정규장 자동 disconnect로 인한 재연결 시도가 OPSP8996을 트리거할 가능성은 존재 — but **자격증명 미등록 상황에서는 재연결 자체가 ERROR_APPROVAL_KEY로 fail**되므로 가설 D는 본 root cause 아님 (자격증명 fix 후 별도 검증 의무).

---

## 5. 사용자 메모리 정합 검증

### 5-1. [feedback_kis_key_realonly_for_data] 정합 결함 ★

memory 본문: "시세·캔들·호가 등 정보 수집은 모두 실전 키. 모의/실전 분기는 주문 액션에만. '모의 키 사용자' 분기 ADR 도입 금지"

magicJar 현행 코드 (KisWebSocketBridge.kt:929):

```kotlin
val accountType = if (magicjarMode == "REAL") AccountType.REAL else AccountType.MOCK
return try {
    approvalAdapter.getApprovalKey(defaultUserId, accountType)
    ...
```

→ **정합 결함**: 시세 WS는 정보 수집 영역인데 magicjarMode 분기로 MOCK 자격증명 시도. 사용자 메모리 정면 위반.

**fix 권장 (가설 H 채택 시)**: `accountType = AccountType.REAL` 고정 (mode 무관). 결함 #4 해소.

### 5-2. [project_kis_key_management] SettingsModal 흐름 정합

memory 본문: "개발 중에는 ⚙️ SettingsModal 입력 + DB AES 암호화"

본 ADR fix는 SettingsModal에서 REAL 자격증명만 등록한 사용자 시나리오(MOCK 미등록)를 정상 진입 의무 — 가설 H 채택으로 MOCK 의존성 제거 시 정합 회복.

### 5-3. [feedback_verification_strict] 검증 의무

memory 본문: "PR 머지·gradle check GREEN만으로 '완료' 종결 금지. 로그·Redis·DB·STOMP·화면 실증 evidence 인용 의무"

본 ADR은 분석 only — 채택 후 sub-agent 디스패치 시 docker logs 시계열 정합 검증 의무 ($11 Phase 매트릭스 명시).

---

## 6. ADR 0076 미커버 영역 (보강 + 신규 가설 종합)

| 영역 | ADR 0076 cover | ADR 0077 보강 |
|---|---|---|
| 자격증명 유효 + KIS 응답 OPSP8996 | A·F (cover) | — |
| 자격증명 유효 + 이전 session 잔존 | B·E (cover) | — |
| 자격증명 유효 + heartbeat false-positive | C·D (cover) | — |
| **자격증명 미등록** | **미cover** | **가설 G·H·I·J (본 ADR §4)** |
| **MOCK type 시세 connect 정합** | **미cover** | **가설 H + 결함 #4 (본 ADR §5-1)** |
| **retry counter reset 0 logic 결함** | F-1 design intent | **가설 I + 결함 #2 (본 ADR §4-3)** |

---

## 7. KIS open-trading-api 표준 패턴 보강 raw 인용

본 ADR 신규 인용 출처 (헌법 §3 5단계 절차 정합):

| 영역 | URL | 인용 위치 |
|---|---|---|
| auth_ws() None 반환 패턴 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` | §3-2 |
| KISWebSocket max_retries=3 cap | 동일 URL | §3-3 |
| 디렉토리 리스트 | `https://api.github.com/repos/koreainvestment/open-trading-api/contents/examples_user` | §3-1 |
| (ADR 0076 인용 baseline reuse) | open-trading-api domestic_stock_functions_ws.py + overtime_ccnl_krx.py | ADR 0076 §12 (본 ADR 인용 baseline 동일) |

raw fetch 검증 일자: **2026-05-17 (KST)**. KIS 측 코드 갱신 시 본 ADR 인용 정밀도 재검증 의무 (헌법 §3).

---

## 8. Rollback 시나리오

본 ADR 채택 후 결함 발생 시:

1. **MOCK type 자격증명 등록한 사용자 정상 작동 회귀 결함**: 가설 H 채택(시세 REAL 고정)이 MOCK 자격증명 의존하던 시나리오 깨지면 → mode 분기 부활 + 가설 G·I만 단독 채택 (자격증명 미등록 시 connect 차단 + counter reset 0)
2. **getApprovalKey 예외 throw 변경 후 호출자 chain 결함 발생**: try-catch 추가 vs return null fallback 양자택일 — null 선택 시 connect chain에 null check 추가
3. **retry cap 추가 후 진짜 일시적 결함 self-heal 안 됨**: cap 3회로 조정 + watchdog 재연결과의 cap 분리 (별도 metric 추가)
4. **사전 자격증명 검증 추가 후 dev/test 시나리오 fail**: profile 분기 또는 fail-fast 옵션 추가

revert는 PR 단위 `git revert <commit>`. 본 ADR은 plan only — revert 대상 0건 (코드 변경 0 LOCK).

---

## 9. 비범위 (post-MVP / backlog)

- 가설 D 정규장 종료 후 KIS auto-disconnect 정책 raw 가이드 (KIS 측 답변 대기 backlog)
- 자격증명 미등록 시 사용자 알림 UI (Slack/email/UI banner) backlog
- watchdog reconnect cap (현재 무한 — KIS 표준 cap 3회와의 정합 backlog)
- KIS PINGPONG 주기 실측 backlog
- multi-tenant 진입 시 user-id별 credential 매핑 정밀화 (현재 firstOrNull fallback)

---

## 10. 사용자 결정 의제 매트릭스 (architect 권장 요약)

### 의제 G. 자격증명 미등록 시 connect 동작 (결함 #1·#3)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **G-1 (architect 권장)** getApprovalKey 자격증명 미등록 시 예외 throw + connect() 사전 검증 0건 fail-fast | doConnect 진입 시 자격증명 fail이면 즉시 return + log.warn 1회 + 다음 watchdog cycle도 차단 | KIS 표준 None 반환 정합 + ERROR_APPROVAL_KEY 문자열 fallback 차단 + 무한 retry 회피 | SetupWizard 완료 전 시도 시 WARN 로그 — but 사용자 안내 가능 |
| G-2 현행 "ERROR_APPROVAL_KEY" 문자열 fallback 유지 + retry cap만 추가 | 결함 #2만 fix — 결함 #1·#3 잔존 | 변경 최소 | KIS에 invalid msg 송신 + 노이즈 누적 — surface patch만 |
| G-3 fail-fast + onUserOnboardedEvent listener에서 retry 활성화 | 자격증명 등록 완료 시점에 자동 connect 시작 | 사용자 경험 최선 + 재부팅 불필요 | EventListener 신규 구현 + race 검증 의무 |

architect 권장 = **G-1** 즉시 + **G-3** 후속 backlog. G-2는 surface patch로 거부.

### 의제 H. MOCK type 시세 connect 정합 (결함 #4)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **H-1 (architect 권장)** 시세 WS는 AccountType.REAL 고정 (mode 무관) | KisWebSocketBridge.kt:929 분기 제거. 시세 수집은 모두 REAL 자격증명. | [feedback_kis_key_realonly_for_data] 정합 + KIS 표준 패턴 + MOCK 미등록 사용자도 정상 작동 | REAL 키 없는 환경(테스트?) 시나리오 — but [feedback_no_mock_data] 정합 |
| H-2 현행 mode 분기 유지 + MOCK 미등록 시 REAL fallback | 분기는 유지 + MOCK 없으면 REAL 시도 | 점진 마이그레이션 | 분기 복잡도 증가 + 사용자 메모리 정합 흐림 |
| H-3 mode == "REAL" 시 REAL 고정, 그 외 시세 비활성화 | dev 환경에서 시세 0건 | KIS 호출 0건 | dev 사용성 큰 손실 + 사용자 dogfooding 차단 |

architect 권장 = **H-1**. 사용자 메모리 + KIS 표준 + dogfooding 모두 정합.

### 의제 I. OPSP8996 retry counter logic 정합 (결함 #2)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **I-1 (architect 권장)** handleOpsp8996 attempt > 1 분기에서 counter reset 0 + cap 3회 도달 시 connection dispose | F-1 design intent 일치 + KIS 표준 max_retries=3 cap 정합 | retry 누적 차단 + 진짜 결함 시 connection 재시작으로 self-heal | 별 부담 없음 — bug fix 범주 |
| I-2 reset 0만 추가 + cap 0건 유지 | counter는 안 누적 — but retry는 무한 | 변경 최소 | 진짜 결함 시 무한 retry — KIS 표준 위반 |
| I-3 OPSP8996 분기 자체 제거 (일반 Exception처럼 처리) | KIS 표준 최정합 — sleep 1s + retry | 단순 + KIS 표준 일치 | self-heal 의도 손실 — backlog `feedback_kis_first_policy` 정합 의무 |

architect 권장 = **I-1**. I-3는 backlog (안정화 후 KIS 표준 최정합 검토).

### 의제 J. WatchlistIngestionWorker 사전 검증 강화 (결함 #5)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **J-1 (architect 권장)** onStartup에서 자격증명 존재 검증 후 connect — 미등록 시 skip + log.warn 1회 | G-1과 보조 — connect 자체 안 함 | 진입점 차단 + log noise 최소 | 약간의 코드 추가 |
| J-2 G-1만 채택 (Bridge 내부 fail-fast로 충분) | 단순 | G-1만으로도 충분 — but onStartup 호출 흐름에서 ERROR 로그 남음 |
| J-3 별도 KisCredentialValidator + Spring bean으로 분리 | 재사용성 + 다른 호출자(키 변경 등)에 적용 가능 | 영역 분리 — but 본 ADR 범위 초과 |

architect 권장 = **J-1**. J-3는 별도 트랙 backlog (T-CRED Validator 추출).

### 의제 K. 사용자 알림 UI (자격증명 미등록 시)

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **K-1 (architect 권장)** 본 사이클 범위 X — backlog 항목으로 분리 (UI banner + SetupWizard 재진입 trigger) | v3.6.11 hotfix는 backend 정합만 | hotfix 사이클 LOCK + 단일 책임 | UI 결함 잔존 — but 자동매매 critical 아님 |
| K-2 frontend UI banner 동시 작업 | 사용자 경험 최선 | 다 sub-agent 디스패치 + 사이클 범위 확장 |

architect 권장 = **K-1** (backlog 등록). hotfix 범위 lock.

### 의제 L. v3.6.11 사이클 흐름

| 옵션 | 동작 | Pro | Con |
|---|---|---|---|
| **L-1 (architect 권장)** v3.6.11 단일 PR (Phase 1·2·3 직렬 묶음) | 결함 #1·#2·#4·#5 한 번에 fix (G-1 + H-1 + I-1 + J-1) | 사용자 단일 검증 + smoke test 일괄 | PR diff 크기 ~150 라인 — 영역 단일(consumer/ws) |
| L-2 v3.6.11(가설 G·I) + v3.6.12(가설 H·J) 직렬 | 단계별 검증 + 회귀 risk 최소 | 사이클 2회 — 사용자 진도 손실 |
| L-3 묶음 spec/plan + 별 sub-agent | architect spec 발행 후 분리 디스패치 | architect 추가 사이클 + spec 분량 — `feedback_spec_plan_scope` 위반 risk (hotfix 사이클) |

architect 권장 = **L-1** 단일 PR. hotfix 사이클 + 단일 영역(consumer/ws/KisWebSocketBridge.kt) + 사용자 단일 검증 cycle. spec/plan 0건 ([feedback_spec_plan_scope] 정합).

---

## 11. 사용자 채택 후 sub-agent 디스패치 매트릭스 (Phase 분리)

architect 권장안 (G-1 + H-1 + I-1 + J-1 + K-1 backlog + L-1 단일 PR) 채택 시:

| Phase | sub-agent | 영역 | 주요 산출물 |
|---|---|---|---|
| **Phase 1 (단일 PR)** | market-data | `consumer/ws/KisWebSocketBridge.kt` + `consumer/ingestion/WatchlistIngestionWorker.kt` | G-1 fail-fast + H-1 REAL 고정 + I-1 counter reset + cap 3 + J-1 사전 검증 + unit test 4건 (자격증명 0건 시 connect skip / MOCK 미등록 시 REAL 사용 / OPSP8996 cap 3 dispose / retry counter reset) |

Phase 단일 — 동일 파일 modify-shared이므로 직렬 의무. 다른 sub-agent 영역 침범 0건.

---

## 12. 권장 사이클 흐름

```
v3.6.11 hotfix (단일 PR, market-data 디스패치, ~150 LOC diff)
  ├─ Phase 1: G-1 + H-1 + I-1 + J-1 동시 fix
  ├─ unit test 4건 (위 §11)
  ├─ docker build-and-deploy 재배포 + docker logs 검증 (10 분 이상 ERROR 0건 확인)
  ├─ smoke (KIS 자격증명 미등록 시나리오 + REAL 자격증명 등록 시나리오 양쪽)
  └─ release v3.6.11 cut

v3.6.12+ backlog 후속 (별 사이클)
  ├─ K-1 UI banner (frontend)
  ├─ J-3 KisCredentialValidator 추출 (T-CRED backlog)
  └─ I-3 OPSP8996 분기 자체 제거 검토 (안정화 후)
```

---

## 13. KIS-first raw fetch 출처 (헌법 §3 5단계 절차 정합)

본 ADR 작성 시 raw 인용 출처:

| 영역 | URL | 인용 위치 | 검증 일자 |
|---|---|---|---|
| auth_ws None 반환 + KISWebSocket max_retries=3 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` | §3-2 · §3-3 | 2026-05-17 |
| examples_user 디렉토리 리스트 | `https://api.github.com/repos/koreainvestment/open-trading-api/contents/examples_user` | §3-1 | 2026-05-17 |
| (ADR 0076 인용 baseline reuse) | open-trading-api domestic_stock_functions_ws.py + overtime_ccnl_krx.py | ADR 0076 §12 | 2026-05-15 (baseline) |

자체 추론 0건 — 모든 결정 근거는 raw 인용 또는 magicJar 자체 코드 추적(§2)에 한정.

---

## 14. 변경 이력

| 일자 | 내용 |
|------|------|
| 2026-05-17 | ADR 0077 신규 발행 — v3.6.10 cut 후 OPSP8996 ERROR 재발 86건/1시간 deep dive. ADR 0076 6 가설(A·B·C·D·E·F) 미발동 시나리오 발굴 — 자격증명 미등록 + MOCK type 시세 connect 정합 결함 + retry counter logic 결함. 신규 가설 G·H·I·J 의제 매트릭스 6건(G·H·I·J·K·L) architect 자율 발의. 사용자 결정 위임 + 채택 후 market-data 단일 디스패치(v3.6.11 단일 PR 권장). |

---

## 15. 사용자 메모리 정합 종합

| 메모리 | 본 ADR 정합 위치 |
|---|---|
| [feedback_kis_first_policy] | §3 KIS raw fetch 100% 인용 + §13 출처 매트릭스 |
| [feedback_kis_key_realonly_for_data] | §5-1 시세 REAL 고정 정합 결함 + 의제 H 권장 |
| [project_kis_key_management] | §5-2 SettingsModal REAL-only 사용자 시나리오 정합 |
| [feedback_verification_strict] | §11 Phase 1 docker logs 검증 의무 명시 |
| [feedback_v3_skepticism_no_regression] | §1-3 fitness gap 4 질문 답변 |
| [project_system_vision] | 룰 본체 + AI 0 (LLM provider import 0건) |
| [feedback_spec_plan_scope] | 의제 L-1 hotfix 단일 PR 정합 (spec/plan 0건) |
| [feedback_no_mock_data] | §5-1 시세 REAL 고정 + MOCK 자격증명 의존 폐기 |
| [feedback_3tier_hierarchy] | architect 분석 발의 → 사용자 결정 → sub-agent 디스패치 (§10·§11 정합) |
