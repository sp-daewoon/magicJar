# ADR 0070 — T-RECON + T-NOTIFY 묶음 spec/plan (W3 자본 보호 prereq)

- 상태: Accepted (architect 자율 결정 — 사용자 위임 2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율 최종권한")
- 작성일: 2026-05-13
- 결정자: architect (사용자 위임 + orchestrator dispatch · W3 마무리 v3.6.2 patch 사이클)
- 관계: **ADR 0041 baseline 정밀화 (T-RECON)** · **ADR 0044 baseline 정밀화 (T-NOTIFY)** · ADR 0068 정합(T-O 마감 직후 안전망) · ADR 0024·0043 정합(KillSwitch) · ADR 0042 정합(Position Bootstrap) · ADR 0037 정합(KIS Rate Limit) · ADR 0038 정합(KIS OAuth 분산락) · ADR 0067 정합(Risk Guard chain) · ADR 0046 정합(system_config Slack webhook)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

### 1-1. 묶음 사유 — T-O 마감 직후 실거래 진입 전 안전망 단일 사이클

2026-05-13 v3.6.0 cut으로 **T-O (주문 실행) 종착 노드 마감 완료** (ADR 0068 A-1~H-1 채택 + 5 PR + 3중 게이트 자본 보호 LOCK). 다음 단계는 **자동매매 본체의 안전망 2건**:

| 트랙 | 자본 보호 역할 | T-O 의존 |
|---|---|---|
| **T-RECON** (ADR 0041) | KIS 잔고 ↔ 내부 position drift 검출 → KillSwitch auto-ON | T-O 체결 후 position 변동 검증 필수 |
| **T-NOTIFY** (ADR 0044) | 사용자 부재 중 사고 즉시 Slack push | T-O·T-RECON 이벤트 listen 필수 |

**왜 묶음 spec**: 두 트랙은 **자본 보호 prereq 단일 의제** — T-RECON drift 검출 시 KillSwitch auto-ON + Slack 알림 = 단일 자본 보호 흐름. 분리 spec/plan은 (a) PR 사이클 2회 분리 비용 + (b) Kafka 토픽 정합 일관성 분리 risk + (c) 사용자 의제 한 호흡(2026-05-13 "이제 이건 다 두고 W3 진행해서 마무리까지하자"). 따라서 단일 ADR 0070 묶음 spec + sub-agent 단계는 Phase 분리 LOCK으로 직렬 디스패치.

### 1-2. 사용자 비전 정합 fitness 검증

**필수성** (자동매매에 진짜 필요한가?):
- T-RECON: ★★★ **자본 사고 직결** — drift 발생 시 "이미 산 종목 또 사거나 이미 판 종목 또 파는 사고" (ADR 0041 §1). 매매=룰/시그널 본체 비전 정합.
- T-NOTIFY: ★★★ **사용자 부재 중 사고 인지 의무** — 자동매매는 사용자 자고 있는 새벽에도 동작. STOMP만으로는 브라우저 켜진 상태 가정 부적합 (ADR 0044 §1).

**v1/v2 잔재 검증**: v1·v2는 모의투자 단일 사이클 가정 + 단일 trader 가정. T-RECON·T-NOTIFY는 v3 (Spring Boot 3.3 + Kafka KRaft + multi-tenant) 본격 자동매매 진입의 신규 의제. 잔재 0.

**AI 호출 0 검증**: 본 ADR 트랙은 룰/cron 기반 — Spring AI·Anthropic·OpenAI import 0 의무 (개발 룰 5 정합). sub-agent 산출물에 grep 검증 의무.

### 1-3. baseline ADR 보유 + 본 ADR 0070 책임 분리

| ADR | 본문 분량 | 권위 |
|---|---|---|
| **ADR 0041** (T-RECON baseline) | §1~§5 (157 lines) | 결정 채택 권위 — cron 시각·KIS endpoint·KillSwitch toggle 정책·trade-off |
| **ADR 0044** (T-NOTIFY baseline) | §1~§5 (211 lines) | 결정 채택 권위 — Slack 채널·7 트리거·outbox 패턴·메시지 포맷 |
| **ADR 0070** (본 ADR — 묶음 spec) | §1~§15 (이 문서) | **sub-agent 디스패치 정밀 명세 권위** — Phase 분리·파일 매트릭스·DB DDL·Kafka 토픽·REST endpoint·UI·검증 시나리오 |

**0070 vs 0041·0044 책임 분리 LOCK**:
- 0041·0044 = "왜·무엇을" (결정 + trade-off)
- 0070 = "어떻게·누가·언제" (sub-agent 디스패치 정밀 명세 — Phase 1·2·3 LOCK)

ADR 0068 (T-O)이 §10~§17로 정밀 명세를 본문에 흡수한 패턴과 달리, 본 묶음은 2개 ADR baseline + 1개 묶음 spec ADR로 분리. 사유: ADR 0041·0044가 이미 Accepted 5개월 baseline (2026-05-07 발행) — supersede 없이 reuse + 정밀 보강이 정합 cost 최소.

## 2. 결정

### 2-1. Phase 분리 LOCK (직렬 디스패치 매트릭스)

**원칙**: T-RECON Phase 1·2 → T-NOTIFY Phase 1 직렬. T-NOTIFY는 T-RECON의 `position.drift.v1`·`killswitch.engaged.v1` 토픽 listen 의무 — 토픽 prerequisite 정합.

| Phase | sub-agent | 영역 | 산출물 핵심 | 의존 |
|---|---|---|---|---|
| **T-RECON Phase 1** | **strategy-engine** | consumer + domain | BalanceReconcileJob + BalanceReconciler + KisBalancePort + KillSwitchAutoToggler + 2 Kafka producer | ADR 0041 §2 |
| **T-RECON Phase 2** | **backend-core** | api + Flyway | reconcile REST 3 endpoint + Flyway V25(reconcile_run) + V26(risk_event.type=POSITION_DRIFT 보강) + JPA + StompReconcileNotifier | Phase 1 합류 후 |
| **T-NOTIFY Phase 1** | **backend-core** | consumer + api + Flyway | NotificationDispatcher 7 listener + SlackWebhookSender + Flyway V27(notification_outbox) + system_config.notification_slack_webhook 컬럼 + Retry Worker | T-RECON Phase 2 합류 후 |

**frontend 0건 LOCK**: KillSwitch 토글 UI는 SettingsModal에 이미 존재(ADR 0069 §3 RealTradingConsentSection 포함 — 사실 KillSwitch는 별도 UI Risk 페이지로 backlog §44 보강 의제). Slack webhook 입력 UI는 SystemConfigSection에 이미 mount (ADR 0069 §3 SystemConfigSection — useUpdateSystemConfig reuse). 본 사이클 frontend 변경 0.

### 2-2. KIS-first raw 인용 4건 (헌법 §3 의무)

T-RECON Phase 1 KisBalanceAdapter 구현 시 sub-agent KDoc inline 의무. 자체 추론 0건 LOCK.

| # | tr_id | URL endpoint | 출처 (raw URL · line 범위) | 용도 |
|---|---|---|---|---|
| 1 | `TTTC8434R` (실전 매수 가능) | `/uapi/domestic-stock/v1/trading/inquire-balance` | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py` L18·L97~100·L102~112·L125~134 | **메인 — 종목별 보유 잔고(output1) + 요약(output2)** + 페이징 CTX_AREA_FK100·NK100 |
| 2 | `VTTC8434R` (모의) | (동일 URL) | (동일 raw URL) | MOCK 환경 동일 endpoint, tr_id만 분기 |
| 3 | `CTRP6548R` (계좌 자산 현황) | `/uapi/domestic-stock/v1/trading/inquire-account-balance` | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_account_balance/inquire_account_balance.py` L14·L16·L42·L56~60 | **보조 — 평가금액·예수금 등 계좌 단위 요약** (drift 1차 fast check 용 — output2 단건 호출만 — Rate Limit 영향 최소화) |
| 4 | `H0STCNI0`/`H0STCNI9` (체결통보 — 이미 T-O 마감) | KIS WS | (ADR 0068 §14-6 매핑) | T-RECON과 직접 무관(이미 T-O에서 cover) — 본 ADR에서 cross-ref만 |

**raw KDoc 의무**: KisBalanceAdapter.fetchBalance(userId) 메서드 상단에 4건 raw URL inline LOCK. 헌법 §3 위반 시 자체 추론 0 fitness gap 누적 + 사용자 비판 risk.

**페이징 처리 의무** (실전 50건/모의 20건 한도):
```kotlin
// raw 정합: examples_llm/domestic_stock/inquire_balance/inquire_balance.py L125~134
// tr_cont 헤더 "M"=more / "F"=final loop
fun fetchAllBalances(userId: Long): List<KisHolding> {
    val accumulated = mutableListOf<KisHolding>()
    var fk100 = ""
    var nk100 = ""
    while (true) {
        val resp = fetchBalancePage(userId, fk100, nk100)
        accumulated.addAll(resp.output1)
        if (resp.trCont == "F" || resp.trCont == "D") break  // 마지막 페이지
        fk100 = resp.ctxAreaFk100
        nk100 = resp.ctxAreaNk100
    }
    return accumulated
}
```

### 2-3. DB Flyway V25·V26·V27 매트릭스

ADR 0041 §2-4·ADR 0044 §2-6 baseline 매트릭스를 본 ADR에서 sub-agent 디스패치용 정밀 DDL로 LOCK. **V20~V23은 T-O 사용(ADR 0068 §15-1)**, **V24는 T-O hotfix(position.created_at)** 이미 머지 — 본 사이클은 **V25·V26·V27**로 시프트.

| Flyway | 테이블/ALTER | 책임 sub-agent | 핵심 컬럼 |
|---|---|---|---|
| **V25** | `reconcile_run` 신규 | backend-core (T-RECON Phase 2) | `id BIGSERIAL PK · user_id BIGINT NOT NULL · started_at TIMESTAMPTZ NOT NULL · finished_at TIMESTAMPTZ · drift_count INT DEFAULT 0 · status VARCHAR(16) NOT NULL · last_error TEXT` + INDEX(user_id, started_at DESC) |
| **V26** | `risk_event` 보강 (`type` ENUM 확장) | backend-core (T-RECON Phase 2) | 기존 V18 활용 — DDL 변경 없음, 도메인 enum에 `POSITION_DRIFT` 추가만 (Kotlin sealed/enum class 영역) |
| **V27** | `notification_outbox` 신규 + `system_config` 컬럼 ALTER | backend-core (T-NOTIFY Phase 1) | **notification_outbox**: `id BIGSERIAL PK · event_id UUID UNIQUE NOT NULL · channel VARCHAR(16) NOT NULL · priority VARCHAR(4) NOT NULL · payload JSONB NOT NULL · status VARCHAR(16) NOT NULL · created_at TIMESTAMPTZ DEFAULT now() · sent_at TIMESTAMPTZ · retry_count INT DEFAULT 0 · last_error TEXT` + INDEX(status, created_at) + UNIQUE(event_id, channel). **system_config ALTER**: `ADD COLUMN IF NOT EXISTS notification_slack_webhook BYTEA` (AES-256-GCM 암호화, ADR 0046 §2-5 마스터 키 단일 예외 정합) |

**IF NOT EXISTS 강제 LOCK** (헌법 §6 + ADR 0068 §15-1 패턴): V20~V24 사이클에서 사용자 hotfix(V18 ADR 0042 충돌 패턴) 발생 — 본 사이클도 동일 LOCK. CREATE TABLE IF NOT EXISTS·ALTER TABLE ... ADD COLUMN IF NOT EXISTS·CREATE INDEX IF NOT EXISTS 의무.

**V26 DDL 변경 0 LOCK**: 도메인 enum 변경만 — Flyway 슬롯 V26은 reserved (충돌 회피용 비어있는 placeholder 권장 — backend-core 자율 결정). 또는 ALTER 0 SQL 파일 `V26__risk_event_position_drift_type.sql` (`-- DOMAIN ENUM ONLY, NO DDL CHANGE` 주석만 + dummy SELECT). **사용자 의제**: V26 슬롯 비우면 V27으로 ALTER 점프해도 무방 — sub-agent backend-core 자율.

### 2-4. Kafka 토픽 매트릭스 (신규 2 + listen 7)

ADR 0041 §2-4·ADR 0044 §2-6 baseline 토픽을 본 ADR sub-agent 디스패치용으로 LOCK.

**신규 publish 2 (본 사이클)**:

| topic | producer | consumer | retention | 책임 |
|---|---|---|---|---|
| **`reconcile.drift.v1`** (신규 — ADR 0041 `position.drift.v1` 대체 명칭 또는 동등 정합) | strategy-engine (BalanceReconcileJob) | strategy-engine (KillSwitchAutoToggler), api (StompReconcileNotifier), **backend-core (NotificationDispatcher)** | 30d | T-RECON Phase 1 |
| **`reconcile.run.v1`** (신규) | strategy-engine (BalanceReconcileJob) | api (STOMP audit fanout) | 7d | T-RECON Phase 1 |

**LOCK 명명**: ADR 0041 §2-4의 `position.drift.v1`은 본 ADR에서 **`reconcile.drift.v1`로 갱신** — semantic이 reconcile job 산출 결과이며 position 자체 변동(T-O `order.filled.v1`)과 구분 명확화. ADR 0041 본문은 정합 update 의무 (§5 변경 이력 row 추가).

**NotificationDispatcher listen 7 (T-NOTIFY Phase 1)**:

| # | topic | 트리거 ADR | 우선도 | rate limit |
|---|---|---|---|---|
| 1 | `order.filled.v1` (T-O 마감) | 0068 | P1 | 60분 슬라이딩 윈도 ≥ 10건 시 1회 |
| 2 | `order.rejected.v1` (T-O 마감) | 0068 | P1 | 60분 슬라이딩 윈도 ≥ 5건 시 1회 |
| 3 | `order.cancelled.v1` (T-O 마감) | 0068 | P2 | 60분 슬라이딩 윈도 ≥ 5건 시 1회 |
| 4 | `risk.violation.v1` (T-RISK 마감) | 0067 | **P0** | 즉시 (rate limit 0) |
| 5 | **`reconcile.drift.v1`** (T-RECON 신규) | 0041·0070 | **P0** | 즉시 |
| 6 | **`killswitch.engaged.v1`** (T-RECON 부수 publish) | 0043·0070 | **P0** | 즉시 |
| 7 | `kis.ws.disconnected.v1` (선택 — T-W2) | - | P1 | 30분 ≥ 3건 시 1회. **Phase 1 본 사이클은 선택** — 미합류 시 graceful skip (헌법 §6 ObjectProvider 패턴) |

**P0/P1/P2 우선도 룰** (ADR 0044 §2-2 정합):
- P0: 즉시 단건 전송 (rate limit 없음)
- P1: Redis sliding window 누적 후 윈도 단위 1회
- P2: dev profile은 skip 가능 (`MAGICJAR_NOTIFICATION_SYSTEM_EVENTS=false`)

**system.startup.v1·system.shutdown.v1·notification.sent.v1** (ADR 0044 §2-2 7 트리거 중 잔여 3): 본 사이클 **선택 publish** — 합류 시 NotificationDispatcher graceful sub. ADR 0045 (Graceful Shutdown) 트랙 진입 시 본격 publish. 본 사이클 backend-core 자율 결정.

**killswitch.engaged.v1 publish 책임**: ADR 0043 (KillSwitch 행동) baseline + 본 ADR T-RECON Phase 1에서 KillSwitchAutoToggler가 toggle 시 publish. T-O 마감 사이클에서 이미 KillSwitch state 테이블 + Service 존재 — toggle hook에 Kafka producer 합류만.

### 2-5. Redis 키 매트릭스

| Redis 키 | TTL | 용도 | sub-agent |
|---|---|---|---|
| `reconcile:lock:{userId}` | 5m | cron 동시 실행 방지 (분산락) | strategy-engine (T-RECON Phase 1) |
| `reconcile:lastrun:{userId}` | 5m | idempotency 캐시 (5분 내 재호출 시 직전 결과 반환) | strategy-engine (T-RECON Phase 1) |
| `notification:ratelimit:{userId}:{topicHash}` (ZSET) | 60m | P1 슬라이딩 윈도 누적 카운터 | backend-core (T-NOTIFY Phase 1) |
| `notification:dedup:{eventId}` | 24h | event_id 중복 dispatch 방지 | backend-core (T-NOTIFY Phase 1) |

**reconcile:lock 분산락 패턴** (ADR 0038 KIS OAuth 분산락 정합):
```kotlin
val locked = redis.opsForValue().setIfAbsent("reconcile:lock:$userId", "1", Duration.ofMinutes(5))
if (!locked) return  // 다른 인스턴스가 이미 처리 중
try {
    reconcileUser(userId)
} finally {
    redis.delete("reconcile:lock:$userId")  // 정상 종료 시 즉시 해제 + TTL fallback
}
```

### 2-6. REST endpoint 매트릭스 (RECON 3 + NOTIFY 0)

**T-RECON Phase 2 — REST 3 endpoint** (backend-core 책임):

| # | Method | URL | 책임 | 응답 |
|---|---|---|---|---|
| 1 | `POST` | `/api/risk/reconcile` (또는 `/api/risk/reconcile/{userId}`) | 사용자 수동 trigger (즉시 1회 실행) | `202 Accepted` + `{ reconcileRunId, startedAt }` |
| 2 | `GET` | `/api/risk/reconcile/runs?userId={id}&page={p}` | reconcile_run 시계열 조회 (페이징) | `200 OK` + `Page<ReconcileRunDto>` |
| 3 | `GET` | `/api/risk/reconcile/runs/{id}` | 단건 상세 (drift_count·last_error) | `200 OK` + `ReconcileRunDetailDto` |

**Swagger 4단 LOCK** (사용자 메모리 `feedback_swagger_pattern` 정합):
- 컨트롤러 `@Tag(name = "Reconcile", description = "KIS 잔고 정합 작업 (broker reconciliation)")`
- 메서드 `@Operation(summary = "수동 reconcile trigger", description = "사용자가 즉시 1회 KIS 잔고를 가져와 내부 position과 정합 비교. 5분 내 중복 호출은 캐시 반환")`
- 파라미터 `@Parameter(name = "userId", description = "대상 사용자 ID (multi-tenant 격리)", example = "12345678")`
- DTO `@Schema(description = "reconcile 실행 기록", example = "...")` + 한국어 설명 + example 값

**T-NOTIFY Phase 1 — REST 0 LOCK**: Slack은 outbound only — backend가 Slack에 push만 함. inbound webhook callback 없음. system_config.notification_slack_webhook 갱신은 **이미 존재하는 SettingsModal SystemConfigSection** (ADR 0069 §3) + 기존 `PUT /api/system-config` (ADR 0046 baseline)에서 cover — 본 사이클 신규 endpoint 0.

### 2-7. STOMP 토픽 매트릭스

| 토픽 | publish event | 책임 sub-agent | 트리거 |
|---|---|---|---|
| `/topic/recon.user.{userId}` (신규) | `reconcile.run.started` · `reconcile.run.completed` · `reconcile.drift.detected` · `killswitch.auto.engaged` | backend-core (T-RECON Phase 2 — StompReconcileNotifier) | Kafka `reconcile.run.v1`·`reconcile.drift.v1`·`killswitch.engaged.v1` listen → STOMP fanout |
| `/topic/order.user.{userId}` (T-O 기존) | (변경 없음) | - | - |
| `/topic/risk.user.{userId}` (T-RISK 기존) | (변경 없음) | - | - |

**단일 토픽 + event discriminator 패턴 LOCK** (ADR 0067·0068 정합): `/topic/recon.user.{userId}` 단일 + payload `eventType` 4 discriminator. 토픽 폭주 회피.

**frontend listen 후속**: 본 사이클 frontend 변경 0 — 다음 사이클 또는 backlog §44(KillSwitch 토글 UX 보강)에서 useStompReconcile hook + Risk 페이지 reconcile_run timeline 합류.

### 2-8. UI 변경 매트릭스 (frontend 0 LOCK)

| UI 영역 | 변경 | 사유 |
|---|---|---|
| SettingsModal SystemConfigSection | **변경 0** | Slack webhook 입력 이미 mount (ADR 0069 §3) — useUpdateSystemConfig reuse |
| KillSwitch 토글 | **변경 0** | KillSwitch state 갱신은 T-O 마감 baseline + KillSwitchAutoToggler가 backend에서 직접 toggle (사용자 수동 UI는 backlog §44 후속 의제) |
| Risk 페이지 reconcile timeline | **본 사이클 0 — 후속 backlog** | 후속 backlog §N 신규 등록 의제 (T-RECON Phase 2 마감 후 frontend sub-agent 단발 디스패치) |

**frontend 0 LOCK의 fitness 검증**: 사용자 관리자 비전 정합 — Slack push는 모바일 alert로 즉시 인지 + KillSwitch ON 시 다음 시그널·주문 차단(ADR 0043 baseline). UI 표시는 운영 편의 보강 의제로 backlog §N에 분리. 본 사이클 자본 보호 핵심 흐름(drift 검출 → KillSwitch ON → Slack push)은 backend 단독 완성.

### 2-9. cron 스케줄 매트릭스 (T-RECON Phase 1)

ADR 0041 §2-1 baseline 정합. strategy-engine Phase 1에서 `@Scheduled` + `JobLauncher` 사용 (Spring Batch 의존 X — 가벼운 단발 cron으로 충분, 헌법 §6 abstract port 패턴).

| 시점 | cron expression | 분산락 | 사유 |
|---|---|---|---|
| 시장 OPEN | `0 */5 9-15 * * MON-FRI` (KST) | `reconcile:lock:{userId}` 5m | 매매 활성 — drift 빠른 감지 |
| 시장 CLOSED | `0 0 * * * *` (1시간 주기) | (동일) | 야간·주말 catch-up |
| Manual | POST endpoint trigger | (동일) | drift 의심 시 즉시 |

**MarketSessionFsm (ADR 0013) 의존**: cron 시각은 `@Scheduled` cron expression만으로 1차 LOCK. MarketSession 조회는 향후 backlog §N 보강 의제 (시장 휴장 시 cron skip — 본 사이클 1차는 cron expression 단순 LOCK으로 충분).

### 2-10. drift 검출 임계치 (ADR 0041 §2-2 baseline LOCK)

| 조건 | 임계 | 처리 |
|---|---|---|
| 수량 diff | ≥ 1주 | **즉시 drift** — KillSwitch auto-ON + `reconcile.drift.v1` publish + Slack P0 |
| 평균단가 diff | ≥ 0.5% | **audit only** — `reconcile.run.v1` publish (status=DRIFT_PRICE), KillSwitch toggle X |
| 종목 자체 누락 | KIS 있는데 내부 0주 | **즉시 drift** (수량 diff ≥ 1주와 동일 처리) |
| 내부 있는데 KIS 0주 | (반대 케이스) | **즉시 drift** — 매도 미반영 의심 |

**audit only 사유** (평균단가 diff): 재계산 노이즈(소수점 처리·KIS round-down·CorpAction 정합) — 자본 사고 직결 아님. backlog §N에 사용자 설정화 의제 (ADR 0047 후보 — 임계치 사용자 토글).

### 2-11. KillSwitch auto-ON 흐름 (ADR 0043 baseline 정합)

```
[BalanceReconcileJob detects drift]
  ↓
[1] reconcile_run row INSERT (status=DRIFT, drift_count=N)
[2] DB position upsert (KIS 값 = 신뢰원)
[3] Redis position:current:{userId} SET (TTL 24h)
[4] Kafka publish: reconcile.drift.v1 (payload: userId, symbol, kisQty, internalQty, diff)
  ↓ (consumer KillSwitchAutoToggler listen)
[5] kill_switch_state.enabled = true (ADR 0043 baseline)
[6] Kafka publish: killswitch.engaged.v1 (payload: userId, reason=POSITION_DRIFT, triggeredBy=AUTO)
  ↓ (consumer NotificationDispatcher listen)
[7] Slack POST (P0 즉시)
  ↓ (api StompReconcileNotifier listen)
[8] STOMP /topic/recon.user.{userId} fanout (eventType=killswitch.auto.engaged)
```

**KillSwitch OFF 정책** (사용자 수동 only — ADR 0043 baseline): drift 후 KillSwitch auto-ON된 상태에서 사용자가 KIS HTS 직접 점검 후 일치 확인 → 수동 OFF 토글. 자동 OFF 0 (자본 보호 LOCK).

## 3. 결과·영향

### 3-1. 트랙 영향 매트릭스

| 트랙 | 본 사이클 영향 |
|---|---|
| **T-O (W3, 마감)** | order.filled.v1·order.rejected.v1·order.cancelled.v1 NotificationDispatcher가 listen — 토픽 정합 의무 (이미 ADR 0068 §15-3에서 publish baseline 완성, 본 ADR은 consumer 합류만) |
| **T-RISK (W3, 마감)** | risk.violation.v1 NotificationDispatcher listen — 토픽 정합 (이미 ADR 0067 baseline 완성) |
| **T-RECON (W3, 본 사이클)** | ADR 0041 baseline + 본 ADR 0070 정밀화 — Phase 1·2 직렬 디스패치 |
| **T-NOTIFY (W3, 본 사이클)** | ADR 0044 baseline + 본 ADR 0070 정밀화 — Phase 1 단독 |
| **T-PERF (W3, 후속)** | system_config.notification_slack_webhook 컬럼 reuse — backend-core가 일별 P&L 통계 Slack push 합류 가능 |
| **T-J (W3, 후속)** | 매매일지 자동 스냅샷 완료 시 Slack push 합류 가능 |
| **T-SC (W3, 후속)** | screener 결과 Slack push 합류 가능 |

### 3-2. 컨테이너 영향

| 컨테이너 | 변경 |
|---|---|
| `consumer` | BalanceReconcileJob(`@Scheduled`) + KisBalanceAdapter + BalanceReconciler + KillSwitchAutoToggler + NotificationDispatcher(7 @KafkaListener) + SlackWebhookSender + NotificationFormatter + NotificationRateLimiter + NotificationOutboxRetryWorker (`@Scheduled 5분`) |
| `api` | REST 3 endpoint(reconcile manual + runs list + run detail) + StompReconcileNotifier + JPA(ReconcileRun) + JPA(NotificationOutbox) + Flyway V25·V27 |
| `domain` | KisBalancePort + BalanceReconciler + ReconcileRun + NotificationPort + NotificationPayload + NotificationPriority enum + ReconcileEventType enum |
| `batch` | 변경 0 — cron은 consumer `@Scheduled`로 충분 (Spring Batch 의존 회피, 가벼운 단발 cron 패턴) |
| `frontend` | **변경 0 LOCK** |

### 3-3. 운영 영향 매트릭스

| 모니터링 | 임계 | 대응 |
|---|---|---|
| `reconcile_run` 5분 lag | 활성 user의 직전 `finished_at`이 7분 이상 | log ERROR + 후속 backlog §N alert |
| `drift_count` 누적 | 1시간에 user별 ≥ 3회 drift | KIS WS 구독 상태 점검 + 사용자 알림 |
| `notification_outbox` PENDING 누적 | 30분 동안 ≥ 10건 | Slack 자체 장애 의심 + log ERROR |
| `system.notification.failed.v1` Kafka publish | 24h 누적 ≥ 1건 | 운영자 인지 의무 |
| KIS API 401 누적 | 사용자별 5분에 3회 | KillSwitch auto-ON (ADR 0043 trigger 정합) |

### 3-4. KIS Rate Limit 영향 (ADR 0037 정합)

ADR 0037 `KisRateLimiterPort` baseline = 초당 10건 + batch share 3/10. 본 사이클 영향:

| 항목 | 산정 |
|---|---|
| 호출 빈도 | 활성 user N명 × 5분 1회 = 분당 N/5 호출 |
| N = 1 (1차 single-tenant) | 분당 0.2회 = 초당 0.003회 — 영향 0 |
| N = 10 (multi-user 본격화) | 분당 2회 — 영향 0 |
| KIS RateLimiter acquire | 의무 통과 + EGW00201 backoff 1초 + 1회 재시도 |
| 페이징 50건/요청 (실전) | 100주 보유 → 2 페이지 = 5분 cycle당 2 호출 — margin 충분 |

### 3-5. fitness 검증 (사용자 메모리 정합 — `feedback_v3_skepticism_no_regression`)

| 검증 항목 | 결과 |
|---|---|
| **자동매매에 진짜 필요한가?** | ★★★ T-RECON 자본 사고 직결 + T-NOTIFY 사용자 부재 중 인지 |
| **v1/v2 잔재 아닌가?** | v3 multi-tenant + Kafka KRaft 기반 신규 의제 — 잔재 0 |
| **AI 호출 0인가?** | 룰/cron 기반, sub-agent 산출물에 grep 검증 의무 (`grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" consumer/reconcile consumer/notification domain` 0 lines) |
| **KIS-first raw 인용 0 자체 추론?** | raw URL 4건 inline KDoc 의무 (§2-2) |
| **mock 합성 0인가?** | mock 데이터 합성 0 — KIS REST 실제 응답 또는 "잔고 0건" 명시 표시 (`feedback_no_mock_data` 정합) |

### 3-6. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **T-RECON·T-NOTIFY 분리 spec 2회 사이클** | 폐기 | PR 비용 + 토픽 정합 분리 risk + 사용자 의제 한 호흡 분리 부적합 |
| (b) **단일 통합 ADR (0041·0044 supersede + 0070 흡수)** | 폐기 | baseline 5개월 ADR 두 건 supersede cost ↑ + 결정 본문 + 정밀 명세 한 ADR 흡수 시 1500+ lines (ADR 0068 패턴) — 본 묶음은 결정 보존 + 정밀화 분리가 정합 |
| (c) **묶음 spec ADR 0070 + ADR 0041·0044 baseline reuse** (채택) | ✓ | 결정 권위 보존 + 정밀 명세 add-only + sub-agent 디스패치 LOCK 완비 |
| (d) frontend 합류 (Risk 페이지 reconcile timeline UI) | 본 사이클 보류 | 자본 보호 핵심 흐름은 backend 단독 완성 가능 (Slack push + KillSwitch ON). UI 표시는 운영 편의 보강 — backlog §N 후속 |
| (e) batch 모듈 Spring Batch JobOperator 사용 | 폐기 | 가벼운 단발 cron만 필요 — `@Scheduled` + JobLauncher 없이 단일 메서드로 충분. Spring Batch 의존 회피 cost ↓ |
| (f) Discord webhook 1차 (Slack 대체) | 폐기 | Slack baseline 보존 — Discord는 ADR 0044 §4 후속 ADR 0070+ 후보 (모바일 push 완성도 Slack 우위) |

## 4. 후속 ADR/backlog 후보

- **0071 (가칭)** — drift 임계치 사용자 설정화 (수량 1주·평균단가 0.5% 고정 → 사용자 토글). T-RECON 본 사이클 마감 후 사용자 운영 의제 발견 시.
- **0072 (가칭)** — user별 Slack webhook 분리 (multi-user 본격화 시). ADR 0044 §4 baseline 후속.
- **0073 (가칭)** — Discord·이메일·SMS 보조 채널 (Slack 보완).
- **backlog §N (신규)** — Risk 페이지 reconcile timeline UI + KillSwitch 토글 UX 보강 (frontend 단발 디스패치 — T-RECON Phase 2 마감 후).
- **backlog §N (신규)** — MarketSessionFsm cron skip 통합 (시장 휴장 시 reconcile cron skip).

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-13 | ADR 0070 신규 발행 — T-RECON + T-NOTIFY 묶음 spec/plan. ADR 0041·0044 baseline reuse + Phase 분리 LOCK (T-RECON Phase 1 strategy-engine → Phase 2 backend-core → T-NOTIFY Phase 1 backend-core 직렬 디스패치). KIS-first raw 인용 4건 (TTTC8434R·VTTC8434R·CTRP6548R + H0STCNI0 cross-ref). Flyway V25(reconcile_run) + V26(risk_event enum 보강) + V27(notification_outbox + system_config.notification_slack_webhook ALTER). Kafka 신규 2(reconcile.drift.v1·reconcile.run.v1) + listen 7 + killswitch.engaged.v1 publish. REST 3(reconcile manual + runs list + run detail) + Swagger 4단 LOCK. frontend 변경 0 LOCK(SettingsModal·KillSwitch 토글 baseline reuse, Risk 페이지 UI는 후속 backlog §N). 자본 보호 흐름 LOCK(drift → KillSwitch ON → Slack P0). v3.6.0 T-O 마감 후 자본 보호 prereq 단일 사이클 v3.6.2 patch. 사용자 자율 최종권한 위임 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). (architect) |
