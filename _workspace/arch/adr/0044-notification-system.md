# ADR 0044 — Notification System (Slack webhook 1차 + Kafka 트리거 + outbox fallback)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · PR-3b 운영·UX fitness 갭 1번)
- 관계: ADR 0043 정합(KillSwitch 토글 시 통지) · ADR 0041 정합(position drift) · ADR 0042 정합(WS 재연결·OAuth 실패) · ADR 0024 정합(Risk Guard chain) · ADR 0038 정합(KIS OAuth 24h 토큰)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

자동매매는 **사용자 부재 중 사고 발생**이 본질적 위험이다. 다음 시나리오 모두 통지 없이는 사용자가 다음 날 아침에야 인지:

- KillSwitch 자동 발동 (drift / drawdown / OAuth 실패 누적) — 사용자 자고 있는 새벽
- KIS OAuth 토큰 24h 만료 후 갱신 실패 — 일주일째 주문 0건인데 모름 (ADR 0038 정합)
- order rejected 누적 5회/시간 — KIS API 장애·잔고 부족 의심
- Position drift 검출 (ADR 0041) — 우리 DB와 KIS 잔고 어긋남
- 시스템 startup/shutdown — 의도치 않은 재시작 인지 (ADR 0045 graceful 정합)

기존 ADR (0024 Risk Guard chain · 0041 Reconcile · 0042 Bootstrap · 0043 KillSwitch)은 모두 **STOMP push만 명시**. STOMP는 사용자가 브라우저를 켜고 있어야 받음. **자동매매 본체에서 통지는 post-MVP가 아니라 W3 동시 의무**.

**필요한 결정**:
- 1차 채널 선택 (Slack webhook 가성비 최적)
- 등록·저장 위치 (테이블 분리 — system_config 시스템 단일 vs user별)
- 트리거 이벤트 list와 메시지 포맷
- 미전송 fallback (Slack 5xx · 네트워크 단절)
- consumer 모듈 dispatcher 위치

## 2. 결정

### 2-1. 1차 채널 — Slack incoming webhook

| 항목 | 값 | 사유 |
|---|---|---|
| 채널 | **Slack incoming webhook** | 사용자 가입·서버 셋업·SDK·키 발급·인증 0건 — 가장 적은 운영 부담 |
| 등록 형태 | URL 1줄 (`https://hooks.slack.com/services/T.../B.../xxx`) | 사용자 본인 워크스페이스 webhook 생성 → 등록 화면에 붙여넣기 |
| 인증 | URL 자체가 secret | HTTPS POST + payload만 |
| 저장 위치 | **`system_config.notification_slack_webhook` (BYTEA AES 암호화)** | ADR 0046 정합 — DART 키와 동일 단일 row 시스템 설정 (사용자 = 관리자 동일) |
| 후속 채널 | 이메일·웹푸시·SMS — **post-MVP** | Slack 1차로 충분 — 사고 시 모바일 Slack push 즉시 |

**user별 webhook 분리 검토 → 폐기**: magicJar는 1차 single-tenant 운영 가정 (사용자 본인 1명 + 옵션 1~2명). user별 webhook은 trade-off 대비 복잡도 ↑. user 식별은 메시지 본문 `user_id(masked)` 필드로 충분. multi-user 본격화 시 ADR 후속(가칭 0070+)으로 user별 분리.

### 2-2. 트리거 이벤트 7종 — Kafka topic 주도

| # | Kafka topic | 트리거 ADR | 우선도 | Slack 전송 조건 |
|---|---|---|---|---|
| 1 | **`risk.killswitch.v1`** | 0043 | P0 | 모든 토글 (ON·OFF). manual·auto 구분 명시. |
| 2 | **`risk.violation.v1`** (신규) | 0024·0041 | P0 | DrawdownStop, PositionLimit, PriceSanity 위반 시 publish |
| 3 | **`kis.token.expired.v1`** (신규) | 0038 | P0 | 24h 토큰 갱신 실패 — 사용자별 5분 내 재시도 3회 모두 실패 시 |
| 4 | **`order.rejected.v1`** (현행) | 0015·0011 | P1 | 누적 카운터 — 사용자별 60분 슬라이딩 윈도 ≥ 5회 시 1회만 통지 (rate limit) |
| 5 | **`position.drift.v1`** (현행 ADR 0041) | 0041 | P0 | drift 검출 즉시 (KillSwitch auto-ON과 함께) |
| 6 | **`system.startup.v1`** (신규) | 0045 | P2 | 컨테이너 부팅 완료 (api·batch·consumer 각각) |
| 7 | **`system.shutdown.v1`** (신규) | 0045 | P2 | graceful shutdown 시작 시 (SIGTERM 수신) |

**우선도 정책**:
- **P0**: 즉시 단건 전송 (rate limit 없음)
- **P1**: 누적 후 윈도 단위 1회 (rate limit — 통지 폭주 방지)
- **P2**: dev profile은 skip 가능 (`MAGICJAR_NOTIFICATION_SYSTEM_EVENTS=false` toggle)

**신규 Kafka topic 4개** — `risk.violation.v1`·`kis.token.expired.v1`·`system.startup.v1`·`system.shutdown.v1` (master §6-4 등재 의무).

`notification.sent.v1`(audit topic) 추가 — 통지 발송 결과 audit 영속.

### 2-3. 메시지 포맷 (한국어 + 즉시 액션 가이드)

```
[magicJar 알림] (P0/P1/P2 prefix)
시각: 2026-05-07 03:14:22 KST
이벤트: KILLSWITCH_AUTO_ON
사용자: 7468**** (마스킹)
사유: Position drift 검출 — 종목 005930 KIS 100주 vs 내부 99주
즉시 액션:
  1. UI Banner에서 사유 확인
  2. KIS HTS에서 잔고 일치 여부 직접 점검
  3. 일치하면 OFF 토글 → 60초 cooldown 후 매매 재개

▶ 차트: https://magicjar.local/symbol/005930
▶ KillSwitch 패널: https://magicjar.local/risk
```

**필수 필드 5종**:
1. **시각** (KST, ISO 8601)
2. **이벤트 종류** (Kafka topic name 또는 `risk_event.type`)
3. **사용자 (마스킹)** — `substring(user_id, 1, 4) || '****'` (ADR 0046 정합)
4. **사유** (1~2 문장 한국어 — Kafka payload `reason` 필드)
5. **즉시 액션 가이드** (3~5 step — 사용자가 자고 있다 깨도 즉시 행동 가능)

**포함 금지**:
- KIS APP_KEY · SECRET_KEY 평문 (메모리 `feedback_secret_handling.md` 정합)
- 평문 계좌번호 (마스킹 의무)
- 평문 잔고 금액 (선택적 — `MAGICJAR_NOTIFICATION_SHOW_AMOUNT` toggle 후속)

### 2-4. consumer 모듈 dispatcher 구조

```
consumer/.../infrastructure/notification/
  ├─ NotificationDispatcher.kt          (도메인 port 구현 — Kafka @KafkaListener × 7 topic)
  ├─ SlackWebhookSender.kt              (HTTP client + retry)
  ├─ NotificationFormatter.kt           (Kafka payload → 한국어 메시지 포맷)
  ├─ NotificationRateLimiter.kt         (Redis sliding window — P1 누적 카운터)
  └─ NotificationOutboxRetryWorker.kt   (@Scheduled 5분 — outbox 재시도)

domain/.../notification/
  ├─ NotificationPort.kt                (interface — send(payload))
  ├─ NotificationPayload.kt             (data class — type·priority·userId·reason·meta)
  └─ NotificationPriority.kt            (enum P0/P1/P2)
```

**왜 consumer**: Kafka subscribe는 consumer 모듈 책임 (ADR 0005 정합). api는 STOMP fanout만, batch는 cron만. NotificationDispatcher는 7 topic listener 통합.

### 2-5. 미전송 fallback — outbox 패턴

| 단계 | 행동 |
|---|---|
| **정상** | Slack POST (3초 timeout) → 200 OK → `notification_outbox` row INSERT (status=SENT, sent_at=now) |
| **5xx 응답** | Resilience4j retry 3회 (1s·2s·4s exponential backoff) |
| **3회 실패** | `notification_outbox` row INSERT (status=PENDING, last_error=...) |
| **재시도** | `NotificationOutboxRetryWorker` (`@Scheduled 5분`) → status=PENDING 행 SELECT → Slack POST 재시도 → 성공 시 status=SENT |
| **24h 누적 미전송** | log ERROR + `system.notification.failed.v1` Kafka publish (감사용 — Slack 자체 장애 인지) |

**왜 outbox**: at-least-once 보장. Slack/Kafka/네트워크 어느 하나 끊겨도 재시도 가능. 사용자 부재 시간 동안 누적된 통지는 Slack 복구 후 일괄 전송.

**중복 방지**: outbox row UNIQUE index `(event_id, channel)` — Kafka payload `event_id` UUID로 dedup.

### 2-6. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `notification_outbox` (신규) | `id, event_id UUID, channel VARCHAR(16), payload JSONB, status VARCHAR(16), created_at, sent_at, retry_count, last_error TEXT` | **V24** (T-NOTIFY 진입 시) |
| `system_config` (신규 — ADR 0046 정합) | `notification_slack_webhook BYTEA` 컬럼 (AES-256-GCM) | **V25** (ADR 0046 통합) |

**Kafka topic 신규 5개**:

| topic | producer | consumer | retention |
|---|---|---|---|
| `risk.violation.v1` | consumer (Guard chain) | consumer(NotificationDispatcher), api(STOMP fanout) | 30d |
| `kis.token.expired.v1` | api·consumer (KisOAuthAdapter) | consumer(NotificationDispatcher) | 30d |
| `system.startup.v1` | api·batch·consumer (ApplicationReadyEvent) | consumer(NotificationDispatcher) | 7d |
| `system.shutdown.v1` | api·batch·consumer (PreDestroy) | consumer(NotificationDispatcher) | 7d |
| `notification.sent.v1` | consumer (NotificationDispatcher) | (audit only) | 30d |

### 2-7. 환경변수 (대원칙: 환경변수 X — 단, dev toggle 예외)

ADR 0046 정합 — 사용자 secret(Slack URL)은 **환경변수 X · DB AES 저장**. 단 다음 toggle은 환경변수 OK (시크릿 아님):

| 변수 | default | 용도 |
|---|---|---|
| `MAGICJAR_NOTIFICATION_ENABLED` | `true` | 글로벌 toggle (dev 디버깅 시 false) |
| `MAGICJAR_NOTIFICATION_SYSTEM_EVENTS` | `true` | startup/shutdown(P2) 통지 toggle |
| `MAGICJAR_NOTIFICATION_RATE_LIMIT_WINDOW_MIN` | `60` | P1 슬라이딩 윈도 분 |
| `MAGICJAR_NOTIFICATION_RATE_LIMIT_THRESHOLD` | `5` | P1 누적 임계 |

`.env.example`에 위 4건 추가. Slack URL은 절대 .env에 두지 않는다.

## 3. 결과·영향

### 3-1. 트랙 영향

- **신규 트랙 후보 — T-NOTIFY (W3)** — `NotificationDispatcher` + `notification_outbox` + `SlackWebhookSender`. 사이즈 M.
- **T-RISK (W3)** — Guard chain의 risk_event 발생 시 `risk.violation.v1` publish 의무 추가.
- **T-O (W3)** — order.rejected.v1는 현행 유지 (NotificationDispatcher가 sub).
- **T-RECON (W3)** — position.drift.v1 → NotificationDispatcher sub (자동).
- **T-W1·T-W2 (W1)** — startup/shutdown 통지 publish 코드 합류 (각 컨테이너 ApplicationReadyEvent · PreDestroy).

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | `system.startup.v1`·`system.shutdown.v1` publish + `kis.token.expired.v1` publish (KisOAuthAdapter 호출 실패 시) |
| `consumer` | **NotificationDispatcher 신규 (7 topic listener)** + SlackWebhookSender + outbox retry worker |
| `batch` | `system.startup.v1`·`system.shutdown.v1` publish |
| `domain` | `NotificationPort` + `NotificationPayload` + `NotificationPriority` enum |
| `frontend` | (없음 — STOMP는 별도 채널, 알림 설정 화면은 ADR 0046 system_config UI에 합류) |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| `notification_outbox` PENDING 누적 | 30분 동안 ≥ 10건 → Slack 자체 장애 의심, log ERROR |
| 통지 빈도 | 사용자별 1일 ≥ 50건 → 시스템 점검 (P1 rate limit 부족 의심) |
| `system.startup.v1` 빈도 | 1시간 동안 ≥ 3회 → 컨테이너 비정상 재시작 (OOM·crash) 의심 |

### 3-4. 보안 정책 (메모리 `feedback_secret_handling.md` 정합)

- Slack webhook URL은 입력 화면(HTTPS·CSRF 토큰)에서 받아 **즉시 AES-256-GCM 암호화 → DB 저장**
- 마스터 키는 `MAGICJAR_SECRET_MASTER_KEY` 환경변수 (ADR 0046 §2-5 정합 — 마스터 키 단일 예외)
- 메시지 본문에 KIS 키·계좌번호 평문 포함 금지 (마스킹 의무)
- 로그에 webhook URL 평문 출력 금지 (`[REDACTED]` 표기)

### 3-5. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **STOMP push만 (현재)** | 폐기 | 브라우저 켜져 있어야 받음 — 자동매매 사고 통지에 부적합 |
| (b) **이메일 1차** | 폐기 | SMTP 셋업·DKIM·SPF·발신 도메인 인증 = 운영 부담 ↑ |
| (c) **Slack webhook 1차 + outbox** (채택) | ✅ | 셋업 0 (URL 1줄) + 모바일 push 즉시 + outbox로 신뢰성 |
| (d) Push notification (FCM·APNs) | 폐기 | iOS·Android 앱 SDK 빌드 필요 — frontend SPA 가정에 부적합 |
| (e) Discord webhook | 후속 | Slack과 동일 패턴, 후속 ADR로 추가 가능. 1차는 Slack |

## 4. 후속 ADR 후보

- **0070+ (가칭)** — user별 webhook 분리 + 멀티 채널 라우팅 (multi-user 본격화 시)
- **0071+ (가칭)** — 통지 정책 사용자 설정화 (eventType별 ON/OFF 토글, quiet hour)
- **0072+ (가칭)** — 이메일·SMS·푸시 등 보조 채널 (Slack 보완)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0044 신규 발행 — PR-3b 운영·UX fitness 갭 1번. Slack webhook 1차 + Kafka 7 topic 트리거 + outbox fallback. (architect) |
