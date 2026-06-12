# PR #447 — KIS WS NXT/시간외 시세 sync KIS 표준 패턴 분석 (ADR 0076) — 누적 hotfix root cause 정합

> **유형**: analysis spec/plan (코드 변경 0 LOCK) — ADR 0076 의제 7건 사용자 결정 후 별도 사이클 sub-agent 디스패치
> **PR base**: main · branch `agent/architect/kis-ws-nxt-standard`
> **타입**: arch 신규 (ADR 0076 + spec 본 문서)
> **작업 author**: bot+architect@magicjar.local
> **사용자 결정 위임**: 7 의제 풀세트 — architect 권장 = A-1 + B-2 + C-1 + D-1 + E-1 + F-1 + G-1
>
> **현행 정합 note (2026-06-13)**: 본 분석 spec 본문의 H0STCNT0(KRX)+H0NXCNT0(NXT) 분리 이중 구독 서술은 당시 결함 진단·표준 분석 evidence로 **역사 보존**한다. 체결가 채널은 **2026-06-13 ADR 0124로 H0UNCNT0 단일 채널(종목당 1채널)로 전환** + 이중 발사 경로·flag 완전 제거됐다.

---

## §1. 본 사이클 산출물 매트릭스

| 파일 | 변경 종류 | 라인 수 |
|------|-----------|---------|
| `_workspace/arch/adr/0076-kis-ws-nxt-overtime-standard.md` | 신규 | ~330 |
| `_workspace/specs/pr-447-kis-ws-nxt-standard-spec.md` | 신규 (본 문서) | ~280 |
| `_workspace/arch/adr/README.md` | 인덱스 §5 + §10 row 추가 + §11 후속 ADR 0077 시프트 | ~5 line |
| `docs/architecture/master-architecture.md` | §10 ADR 0076 row 추가 + §13 변경 이력 row | ~3 line |
| `_workspace/backlog.md` | §54 신규 (의제 §7-C·§9 비범위 흡수) | ~30 line |
| `CHANGELOG.md` | architect single row prepend (anchor `pr-447-slot-architect`) | 1 row |

**LOCK**: 코드 변경 0 (consumer/api/domain/frontend 무영향). 분석 ADR + spec only.

---

## §2. 사용자 결정 위임 의제 (ADR 0076 §7·§10)

| ID | 영역 | 옵션 | architect 권장 | 결정자 |
|---|---|---|---|---|
| A | approval_key 재발급 | A-1 / A-2 / A-3 | **A-1** PR #444 invalidate revert + 24h cache reuse | 사용자 |
| B | H0STOUP0 시간외 단일가 | B-1 / B-2 / B-3 | **B-2** 16:00~18:00 dynamic subscribe | 사용자 |
| C | 호가 NXT/시간외 cover | C-1 / C-2 / C-3 | **C-1** H0STASP0만 유지 (NXT 호가는 backlog) | 사용자 |
| D | heartbeat watchdog timeout | D-1 / D-2 / D-3 | **D-1** 30s + 정규장 종료 후 60s | 사용자 |
| E | destroy() unsubscribe ACK | E-1 / E-2 | **E-1** ACK 2s 대기 (graceful close) | 사용자 |
| F | OPSP8996 self-heal | F-1 / F-2 | **F-1** SubscriptionResponse OPSP8996 감지 + 1회 retry | 사용자 |
| G | 시간외 룰엔진 처리 | G-1 / G-2 / G-3 | **G-1** 룰 매칭 동등 + 주문 시간 RiskGuard 게이트 | 사용자 |

---

## §3. 후속 사이클 sub-agent 디스패치 매트릭스 (사용자 채택 후)

### Phase 1 — market-data: A-1 + F-1 (approval_key 표준 reuse + OPSP8996 self-heal)

**branch**: `agent/market-data/kis-ws-approval-reuse-self-heal`
**author**: `bot+market-data@magicjar.local`

| 파일 | 변경 | 검증 |
|---|---|---|
| `consumer/.../infrastructure/kis/KisApprovalAdapter.kt` | `invalidate()` 메서드 유지 (ADR 0074 키 변경 경로에서 사용) — but `doConnect()` 자동 호출은 제거 | 단위 테스트 — `connect()` 호출 시 `invalidate()` 호출 0건 검증 |
| `consumer/.../ws/KisWebSocketBridge.kt` | `doConnect()` 진입 시 `approvalAdapter.invalidate()` 호출 **제거** (PR #444 revert) | 단위 테스트 — `doConnect()` 호출 시 `approvalAdapter.invalidate()` 호출 0건 검증 |
| `consumer/.../ws/KisWsMessageParser.kt` | `SubscriptionResponse` OPSP8996 감지 메서드 추가 — `isOpsp8996(rawJson: String): Boolean` 신규 | 단위 테스트 — OPSP8996 응답 raw fixture 3건 (PASS·기타 OPSP·정상 SUCCESS) |
| `consumer/.../ws/KisWebSocketBridge.kt` | `handleMessage` `SubscriptionResponse` 분기에서 OPSP8996 감지 시 → `approvalAdapter.invalidate()` 1회 호출 + Reactor retry SUBSCRIBE 메시지 재발송 (지수 백오프 1회 cap 2s) | 단위 테스트 — OPSP8996 발생 시 invalidate 1회 + retry 1회 검증 |
| `consumer/.../ws/KisWebSocketBridge.kt` | retry cap counter — 5분 슬라이딩 윈도 안 OPSP8996 5회 이상 발생 시 retry stop + log WARN + KillSwitch 트리거 검토 (별도 사이클 backlog) | 단위 테스트 — retry cap 동작 |
| `CHANGELOG.md` | slot row prepend (anchor `pr-447-slot-md-phase1`) | — |

**검증 시나리오**:
1. `./gradlew :consumer:check` BUILD SUCCESSFUL
2. ktlint PASS
3. AI provider import 0건 grep PASS
4. consumer 재시작 시 `KIS approval_key cache 무효화` 로그 0건 (재기동 시점)
5. dogfooding — KIS 정규장 + 시간외 + NXT 시간대 전 구간 OPSP8996 발생 0건 logs evidence

### Phase 2 — market-data: D-1 + E-1 (heartbeat + destroy graceful close)

**branch**: `agent/market-data/kis-ws-heartbeat-graceful-close`
**author**: `bot+market-data@magicjar.local`

| 파일 | 변경 | 검증 |
|---|---|---|
| `consumer/.../ws/KisWebSocketBridge.kt` | `heartbeatTimeoutSeconds` 10L → 30L 변경 | 단위 테스트 — heartbeat 30s 후 false-positive 0건 |
| `consumer/.../ws/KisWebSocketBridge.kt` | `startHeartbeatWatchdog()` 내 정규장 시간 인식 분기 — `KisMarketSessionFsm.isInRegularSession()` (ADR 0013 신규 또는 BoundaryAggregator 시각 검사 reuse) 후 외부 시간이면 timeout 60s 적용 | 단위 테스트 — 정규장 종료 후 60s timeout 동작 |
| `consumer/.../ws/KisWebSocketBridge.kt` | `destroy()` 내 unsubscribe send 후 `sendSink.tryEmitComplete()` 전 KIS ACK 응답 대기 2s — `CountDownLatch` + `handleMessage` SubscriptionResponse 매칭 | 단위 테스트 — graceful destroy 후 KIS ACK 수신 검증 |
| `consumer/.../ws/KisWebSocketBridge.kt` | ACK 미수신 시 timeout 2s 후 강제 dispose (graceful 5s ADR 0045 한도 안) | 단위 테스트 — ACK timeout 분기 검증 |
| `CHANGELOG.md` | slot row prepend (anchor `pr-447-slot-md-phase2`) | — |

**검증 시나리오**:
1. `./gradlew :consumer:check` BUILD SUCCESSFUL
2. consumer 재시작 시 직전 process의 unsubscribe ACK 로그 evidence (`KIS WS unsubscribe ACK 수신`)
3. dogfooding — false-positive 재연결 발생 0건 (정규장·정규장 종료 후 전 구간)

### Phase 3 — market-data: B-2 (H0STOUP0 시간외 단일가 dynamic subscribe)

**branch**: `agent/market-data/kis-ws-overtime-single-price`
**author**: `bot+market-data@magicjar.local`

| 파일 | 변경 | 검증 |
|---|---|---|
| `consumer/.../ingestion/OvertimeSinglePriceSubscriber.kt` | 신규 — `@Scheduled(cron = "0 0 16 * * *", zone="Asia/Seoul")` 16:00 KST H0STOUP0 subscribe + `@Scheduled(cron = "0 0 18 * * *")` 18:00 unsubscribe | 단위 테스트 — schedule trigger 시점 검증 + KisWebSocketBridge.subscribeOvertimeSinglePrice 호출 검증 |
| `consumer/.../ws/KisWebSocketBridge.kt` | `subscribeOvertimeSinglePrice(codes)` + `unsubscribeOvertimeSinglePrice(codes)` 신규 — H0STOUP0 build message 전송 + `overtimeSubscribedCodes` Set 신규 + `handleOvertimeTickData()` 신규 (H0STCNT0와 동일 흐름 + source="live-overtime") | 단위 테스트 — H0STOUP0 subscribe message 검증 |
| `consumer/.../ws/KisWsMessageParser.kt` | `parseH0Stoup0()` 신규 — H0STOUP0 raw 인용: `examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py` cols 배열 정합 (42 필드) | 단위 테스트 — fixture 3건 (정상 tick · 필드 수 부족 · 다른 code) |
| `consumer/.../ws/KisWebSocketBridge.kt` | `handleMessage` `H0STOUP0` 분기 추가 | 단위 테스트 — TR_ID 분기 |
| `consumer/.../ingestion/SubscriptionPolicy.kt` | `OVERTIME_SINGLE_PRICE_MAX=20` 상수 + 41 slot 한도 재계산 KDoc — H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) + H0STOUP0(20) = 61 slot. 16:00~18:00 시간대만 활성이므로 dynamic 관리 LOCK | 단위 테스트 — slot 한도 검증 |
| `consumer/.../ws/KisWebSocketBridge.kt destroy()` | shutdown 시 H0STOUP0 unsubscribe 추가 | 단위 테스트 — graceful close H0STOUP0 unsubscribe 검증 |
| `domain/.../KisWsPort.kt` | port 인터페이스 `subscribeOvertimeSinglePrice(codes)` + `unsubscribeOvertimeSinglePrice(codes)` 추가 (ADR 0004 hexagonal port 정합) | 단위 테스트 — port 인터페이스 변경 |
| `CHANGELOG.md` | slot row prepend (anchor `pr-447-slot-md-phase3`) | — |

**검증 시나리오**:
1. `./gradlew :consumer:check :domain:check` BUILD SUCCESSFUL
2. AI provider import 0건 grep PASS
3. dogfooding — 16:00 KST H0STOUP0 subscribe 로그 + redis quote:last 16:xx ts 갱신 evidence
4. dogfooding — 18:00 KST H0STOUP0 unsubscribe 로그 + 19:00 이후 H0STOUP0 tick 0건 evidence

### Phase 4 (선택 — 별도 사이클) — strategy-engine: G-1 (시간외 룰 매칭 + 주문 시간 게이트)

**branch**: `agent/strategy-engine/risk-time-gate`
**author**: `bot+strategy@magicjar.local`

| 파일 | 변경 | 검증 |
|---|---|---|
| `consumer/.../strategy/risk/RegularHourGuard.kt` | 신규 — RiskGuard 게이트 6단(ADR 0024)에 시간 게이트 추가. 주문 의도 발생 시 현재 시각이 KIS 주문 가능 시간(정규장 09:00~15:30 + REAL 모드)인지 검증. 시간외/NXT 시각에는 주문 BLOCKED + log INFO. | 단위 테스트 — 시간별 분기 |
| `consumer/.../strategy/signal/SignalEngine.kt` | 시간외 tick도 정규장과 동등 룰 매칭 처리 — 변경 0 (시그널 엔진은 이미 시간 무관). 본 Phase는 명시적 검증 + KDoc 보강 | 단위 테스트 — 시간외 tick 시그널 정상 emit 검증 |
| `CHANGELOG.md` | slot row prepend (anchor `pr-447-slot-strategy-phase4`) | — |

**검증 시나리오**:
1. `./gradlew :consumer:check` BUILD SUCCESSFUL
2. dogfooding — 16:00 시간외 단일가 시각대 시그널 emit 정상 + 주문 BLOCKED 로그 evidence

---

## §4. 검증 시나리오 — 본 ADR 분석 사이클 (코드 변경 0)

본 PR #447은 분석 spec/plan only — 다음 사항만 검증:

1. **ADR 0076 본문 검증** — §1~§14 전 구간 + raw fetch 출처 매트릭스 정합
2. **ADR README 인덱스 갱신** — §5·§10-1·§10-2·§11·§12 row 추가
3. **master-architecture §10 ADR 인덱스 0076 row + §13 변경 이력 row prepend**
4. **backlog §54 신규 — 의제 §7-C(C-2·C-3 호가 NXT/시간외) + §9 비범위 의제 5건 등록**
5. **CHANGELOG slot single row prepend** (architect anchor `pr-447-slot-architect`)
6. **PR body** — ADR 0076 핵심 요약 + 7 의제 매트릭스 + Phase 디스패치 매트릭스 + 사용자 결정 위임 명시

---

## §5. KIS-first raw fetch 출처 (헌법 §3 5단계 정합)

본 spec 작성 시 raw fetch 검증:

| 영역 | URL | 검증 일자 |
|---|---|---|
| WS 함수 매트릭스 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py` | 2026-05-15 |
| 다중 tr_id subscribe | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_examples_ws.py` | 2026-05-15 |
| approval_key auth | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py` | 2026-05-15 |
| NXT 체결가 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_nxt/ccnl_nxt.py` | 2026-05-15 |
| 시간외 단일가 체결가 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py` | 2026-05-15 |
| 디렉토리 트리 | `https://api.github.com/repos/koreainvestment/open-trading-api/git/trees/main?recursive=1` | 2026-05-15 |

raw 인용 결과:
- H0STOUP0 = 시간외 단일가 16:00~18:00 (raw 명시 docstring)
- approval_key = `auth_ws()` 1회 발급 + 24h cache reuse 표준
- 다중 tr_id = 단일 WS 객체 안에서 `kws.subscribe(request=foo)` 반복 호출 표준
- PINGPONG = 수신 raw 그대로 echo 의무

---

## §6. ADR 헌법 §3·§4 정합 검증

- §3 KIS-first raw fetch: 본 ADR §2·§4·§5·§12 + spec §5 매트릭스 — KIS open-trading-api 레포 raw URL 인용 100%. 자체 추론 lens 동반 (§3 매트릭스 + §7 의제 architect 권장 + 가설 매트릭스 §3 6건).
- §4 CHANGELOG slot 사전 발행: 본 사이클은 architect single row (`pr-447-slot-architect`) — 분석 spec only. 후속 Phase 1·2·3·4 sub-agent 디스패치는 별도 사이클로 분리되며 그 시점에 새 PR + 새 slot anchor 4건 발행.

---

## §7. 사용자 메모리 정합

| 메모리 | 정합 위치 |
|---|---|
| `feedback_kis_first_policy.md` | ADR §2·§4·§12 + spec §5 (raw URL 인용 100%) |
| `feedback_v3_skepticism_no_regression.md` | ADR §1-4 + §7-G G-1 (자본 보호 critical lens) |
| `feedback_3tier_hierarchy.md` | ADR §10·§11 (architect 권장 → 사용자 결정 → sub-agent 디스패치) |
| `feedback_spec_plan_scope.md` | 본 spec — 분석 spec/plan only (코드 변경 0 LOCK) |
| `feedback_verification_strict.md` | ADR §1-2 + §3 (누적 hotfix 비판 + root cause 가설 매트릭스) |
| `feedback_no_mock_data.md` | ADR §7-G·§11 Phase 4 (시간외 tick 실제 KIS 정합) |
| `feedback_release_policy_patch_first.md` | 본 ADR Phase 1·2·3 채택 시 v3.6.9 patch 또는 v3.6.10 patch 사이클 후보 (v3.7.0 = W4 LOCK 유지) |
| `project_system_vision.md` | AI 호출 0 + 룰 본체 (G-1 시간외 룰 매칭 + AI 0) |

---

## §8. Rollback 시나리오 — 본 분석 사이클

본 PR #447은 분석 spec/plan only — revert 대상 0건. 사용자 의제 결정 변경 시:

1. **ADR 0076 본문 갱신** — architect 단독 PR (의제 결정안 반영 + Status Accepted 갱신)
2. **본 spec 본문 갱신** — sub-agent 디스패치 매트릭스 조정

만약 사용자가 채택 의제 (A-1 + B-2 + ... + G-1) 외 다른 조합 채택 시 — Phase 1·2·3·4 산출물 매트릭스 재조정 + spec §3 본문 갱신 + ADR 0076 §10 Status Accepted + 채택안 명시.

---

## §9. 변경 이력

| 일자 | 내용 |
|------|------|
| 2026-05-15 | spec PR #447 신규 — ADR 0076 분석 + 사용자 결정 의제 7건 + Phase 분리 sub-agent 디스패치 매트릭스 (architect 자율 발의, 사용자 결정 위임). 코드 변경 0 LOCK. |
