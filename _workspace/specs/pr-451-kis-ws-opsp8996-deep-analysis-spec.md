# PR #451 — KIS WS OPSP8996 잔존 deep 분석 (ADR 0077) — 누적 PR 영향 추적 + revert 의제

> **유형**: analysis spec/plan (코드 변경 0 LOCK) — ADR 0076 Phase 1·2·3 후 잔존 결함 추적 + ADR 0077 §5 권장안 architect 자율 발의
> **PR base**: main · branch `agent/architect/kis-ws-opsp8996-deep-analysis`
> **타입**: arch 신규 (ADR 0077 + 본 spec)
> **작업 author**: bot+architect@magicjar.local
> **사용자 자율 위임**: architect 풀 권장안 H-1-A + H-2-A + H-3-A + H-6 자율 LOCK (사용자 자율 위임 lens — `feedback_autonomy.md`)

---

## §1. 본 사이클 산출물 매트릭스

| 파일 | 변경 종류 | 라인 수 |
|------|-----------|---------|
| `_workspace/arch/adr/0077-kis-ws-opsp8996-deep-analysis.md` | 신규 | ~390 |
| `_workspace/specs/pr-451-kis-ws-opsp8996-deep-analysis-spec.md` | 신규 (본 문서) | ~150 |
| `_workspace/arch/adr/README.md` | §3 헤더 + §5 row 추가 (0076 status 정정 + 0077 신규) + §11 후속 0078 시프트 + §12 row 추가 | ~10 line |
| `_workspace/backlog.md` | §55 신규 (ADR 0077 §8 비범위 + §10 Phase 매트릭스 잔류 10 의제) | ~80 line |
| `CHANGELOG.md` | architect single row prepend (anchor `pr-451-slot-architect`) | 1 row |

**LOCK**: 코드 변경 0 (consumer/api/domain/frontend 무영향). 분석 ADR + spec only.

ADR 0076 본문 갱신 의무는 H-6 의제 — 본 ADR 0077 머지 직후 별도 architect docs PR (Phase 2 §6).

---

## §2. 사용자 자율 위임 권장 의제 (ADR 0077 §5·§9)

| ID | 영역 | 옵션 | architect 권장 | architect 자율 LOCK |
|---|---|---|---|---|
| H-1 | runtime 재연결 graceful close 정합 | H-1-A / H-1-B | **H-1-A** unsubscribe + 1s 대기 + dispose 패턴 watchdog + reconnectGracefully 경로 확장 | ✓ |
| H-2 | 재연결 1차 0ms → 1s | H-2-A / H-2-B | **H-2-A** reconnectDelaysMs[0] = 1000L (KIS 표준 raw 정합) | ✓ |
| H-3 | F-1 self-heal grace period | H-3-A / H-3-B / H-3-C | **H-3-A** onConnected 직후 1초 grace period — race window 0 | ✓ |
| H-4 | H0NXCNT0 다중 tr_id 검증 (실험적) | H-4-A / H-4-B | H-1·H-2·H-3 채택 후 잔존 시 fallback | dogfooding trigger |
| H-5 | 누적 PR 단계별 revert (최후 수단) | H-5-A / H-5-B | H-5-B 우선 (add-only H-1·H-2·H-3) | dogfooding trigger |
| H-6 | ADR 0076 §3 supersede 갱신 | architect 자율 LOCK | 본 ADR 머지 직후 별도 PR | ✓ |

→ **architect 풀 권장안 = H-1-A + H-2-A + H-3-A + H-6** (자율 LOCK). H-4·H-5는 dogfooding evidence trigger 후 발화. 사용자 메모리 `feedback_autonomy.md`·`feedback_3tier_hierarchy.md` 정합.

---

## §3. KIS-first raw fetch 검증 매트릭스 (ADR 0077 §3 + §11)

| 영역 | URL | 검증 결과 |
|---|---|---|
| OPSP8996 코드 명시 | `gh api search/code?q=OPSP8996+repo:koreainvestment/open-trading-api` | 0건 (KIS server-side error code) |
| OPSP8996 이슈 명시 | `gh api search/issues?q=OPSP8996+repo:koreainvestment/open-trading-api` | 0건 |
| ALREADY IN USE 이슈 | `search/issues?q=ALREADY+IN+USE+appkey+repo:koreainvestment/open-trading-api` | 0건 |
| approval_key 24h reuse | `examples_user/kis_auth.py` reAuth_ws() | **86400초 reuse 명시** |
| 재연결 1초 sleep | `examples_user/kis_auth.py` __runner() except 분기 | **`await asyncio.sleep(1)` 명시** |
| max_retries | `examples_user/kis_auth.py` self.max_retries | **3회 default** |
| 다중 tr_id 동시 sample | `examples_user/domestic_stock/domestic_stock_examples_ws.py` | 단일 tr_id sample만 chained — H0STCNT0 + H0NXCNT0 동시 sample 미발견 |
| subscribe dedupe | KISWebSocket.subscribe() add_open_map | **dedupe 0** — 단순 append |
| OPSP8996 retry 로직 | KISWebSocket subscribe 응답 처리 | **0건** — ws 단위 reconnect만 처리 |

raw fetch 검증 일자: **2026-05-15**.

KIS-code-assistant MCP 도구 (`mcp__kis-code-assistant`) 활용 권장 — 후속 sub-agent 디스패치 시점에 KIS 측 admin endpoint·session policy 직접 질의 의무. CLAUDE.md MCP server instructions 정합.

---

## §4. 누적 PR 영향 매트릭스 (ADR 0077 §2 + §6)

| PR | 적용 변경 | KisWebSocketBridge | KisApprovalAdapter | KisWsMessageParser |
|---|---|---|---|---|
| #443 | H0NXCNT0 동시 구독 | subscribe()+handleNxtTickData()+nxtSubscribedCodes Set | — | parseH0Nxcnt0() |
| #444 (revert 대상) | doConnect 시 invalidate | doConnect() invalidate 호출 | invalidate() 메서드 추가 | — |
| #445 | watchdog doFinally | doConnect() doOnTerminate → doFinally + reconnecting CAS guard | — | — |
| #448 (A-1 + F-1) | invalidate 호출 revert + OPSP8996 self-heal | doConnect 진입 시 invalidate 호출 0 + handleOpsp8996() 1회 retry cap + opsp8996RetryCount AtomicInteger | invalidate() 메서드 잔존 (호출 0) | isOpsp8996() 추가 |
| #449 (D-1 + E-1) | heartbeat 30s/60s + destroy ACK 2s | currentHeartbeatTimeoutSeconds() 분기 + destroy() pendingUnsubAckLatch | — | isUnsubscribeAck() 추가 |
| #450 (B-2) | H0STOUP0 dynamic | subscribeOvertimeSinglePrice() / unsubscribeOvertimeSinglePrice() + overtimeSinglePriceCodes Set + handleOvertimeSinglePriceTickData() | — | parseH0Stoup0() (live-overtime) |

**상호 작용 충돌 6 row** — ADR 0077 §2-2 매트릭스 LOCK.

**가설 H 신규** — ADR 0077 §6-1:
1. graceful close 0 (watchdog dispose + reconnectGracefully unsubscribe 미발사)
2. 0ms 재연결 (reconnectDelaysMs[0] = 0L 즉시)
3. onConnected + F-1 race (재구독 + self-heal 동시 발사 중복)

→ **재연결 cycle N × 종목 K × 2(KRX+NXT) × ~2(F-1 race) = 4NK 폴리노미얼 누적**. 사용자 103건은 N≈5·K≈5~10 환경 정합.

---

## §5. 회복 절차 매트릭스 (ADR 0077 §4)

| 옵션 | 동작 | 시간 | 우선순위 |
|---|---|---|---|
| **R-1 (architect 권장)** | consumer JVM 재시작 + 5분 대기 (E-1 unsubscribe ACK + KIS server side cleanup) | 5~10분 | ★ 1순위 |
| R-2 | Redis `kis:approval:{userId}:REAL` DEL + consumer 재시작 | 즉시 | 2순위 |
| R-3 | KIS HTS·MTS·MCP 다른 클라이언트 로그아웃 확인 + consumer 재시작 | 즉시 | 3순위 |
| R-4 | KIS 측 강제 disconnect 관리자 endpoint 호출 (R-1~3 미해소 시) | 미정 | 보조 |

→ 권장 시퀀스: R-1 → R-2 → R-3 → R-4.

---

## §6. ADR 0076 부분 supersede 선언 (ADR 0077 §6-3)

ADR 0076 status: **Accepted (Partial — superseded by ADR 0077 §6)** — Phase 1·2·3 권장안 부분 효과만 입증. ADR 0076 §3 가설 매트릭스는 ADR 0077 §3·§6에서 부분 supersede + 가설 H 신규.

ADR 0076 본문 갱신은 H-6 의제 (Phase 2 PR — architect 단독 docs PR로 별도 사이클).

---

## §7. Phase 디스패치 매트릭스 (ADR 0077 §10)

| Phase | sub-agent | 영역 | 산출물 | trigger |
|---|---|---|---|---|
| **Phase 1** | market-data | KisWebSocketBridge.kt watchdog + reconnectGracefully + scheduleReconnect + handleOpsp8996 | H-1-A + H-2-A + H-3-A 묶음 (~30 라인 modify) | 본 ADR 머지 직후 |
| **Phase 2** | architect | `_workspace/arch/adr/0076-kis-ws-nxt-overtime-standard.md` + README | ADR 0076 §3 가설 H row + status "Accepted (Partial — superseded by ADR 0077 §6)" | 본 ADR 머지 직후 또는 Phase 1과 병렬 |
| **Phase 3** (선택) | market-data | PR #443 부분 revert | H-4-A 실험적 24h evidence | Phase 1 dogfooding 잔존 trigger |
| **Phase 4** (최후) | market-data | 단계별 PR revert | H-5-A PR #450 → #449 → #448 → #443 | Phase 1·3 잔존 trigger |
| **Phase 5** (별도) | market-data | KisWebSocketBridge.kt 또는 신규 StabilizationWindowGuard | 부팅 후 5분 stabilization (backlog §55 의제 8) | 별도 사이클 — Phase 1 안정화 후 |

---

## §8. 검증 시나리오 (코드 변경 0 — 분석 사이클 N/A)

본 PR은 analysis only — 검증 의무 0. ADR 0077 §1-3 fitness gap 충족 + KIS-first §3 raw fetch 검증 일자 명시(2026-05-15) + 누적 PR 영향 매트릭스 §2 정밀 추적 = 본 사이클 산출물 무결성 검증.

후속 Phase 1 PR(H-1-A + H-2-A + H-3-A 묶음) 머지 시 dogfooding evidence 의무 매트릭스:

| 검증 항목 | evidence 수집 방법 | trigger |
|---|---|---|
| OPSP8996 발생 빈도 변화 | consumer 로그 `grep OPSP8996 | wc -l` 24h 비교 | Phase 1 머지 24h 후 |
| 재연결 cycle 변화 | `grep "KIS WS 재연결 스케줄" | wc -l` | Phase 1 머지 24h 후 |
| tick 빈도 변화 | Redis `quote:last:*` 갱신 빈도 측정 | Phase 1 머지 24h 후 |
| 005930 15:14 stop 재현 | 15:00~15:30 KST window 로그 분석 | Phase 1 머지 다음 거래일 |

→ evidence 잔존 시 H-4·H-5 fallback trigger.

---

## §9. Rollback 시나리오 (ADR 0077 §7)

| 시나리오 | Rollback 동작 |
|---|---|
| OPSP8996 잔존 (H-1·H-2·H-3 후) | H-4-A 실험적 — PR #443 부분 revert + 24h evidence. ADR 0063 NXT cover 부분 sunset 의제 발의 가능 |
| 재연결 +1~3s critical | H-1-A unsubscribe 대기 시간 cap 조정 (2s → 1s) 또는 ACK 대기 비활성화 |
| F-1 grace 동안 진짜 OPSP8996 미회복 | H-3-B F-1 완전 비활성화 + 재연결 cycle 의존 |
| 누적 PR 전체 revert 의무 (최후) | H-5-A 단계별 — PR #450 → #449 → #448 → #443 순서 |

본 ADR 0077 자체 revert 단위는 PR 단위 `git revert <commit>`. 분석 사이클 — revert 대상 0건 (코드 변경 0 LOCK).

---

## §10. 사용자 메모리 정합 (ADR 0077 §13)

- `feedback_kis_first_policy.md` — KIS open-trading-api raw fetch 100% 인용 (§3·§11)
- `feedback_v3_skepticism_no_regression.md` — 자본 보호 critical (§1-3 fitness + 15:14 stop critical)
- `feedback_3tier_hierarchy.md` — architect 분석 발의 → 사용자 자율 위임 → sub-agent 디스패치
- `feedback_spec_plan_scope.md` — neutral 의제 분석 spec OK (코드 변경 0 LOCK)
- `feedback_verification_strict.md` — Phase 1·2·3 머지 GREEN ≠ 결함 해소 (evidence 인용 의무)
- `feedback_no_mock_data.md` — 실 KIS WS dogfooding evidence 기반 (mock 합성 0)
- `feedback_autonomy.md` — architect 자율 LOCK + 사용자 자율 위임
- `project_system_vision.md` — 룰 본체 + AI 0 (AI 호출 0 정합)
- `feedback_pr_rebase_sync.md` — 후속 PR rebase 정책 유지

---

## §11. CHANGELOG slot anchor

`pr-451-slot-architect` — single row prepend. ADR 0077 발행 + ADR 0076 부분 supersede 선언 + backlog §55 등재.
