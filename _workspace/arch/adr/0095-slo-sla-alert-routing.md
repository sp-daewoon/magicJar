# ADR 0095: P0-OPS-3 — SLO/SLA 정의 + alert routing matrix (v3.8.0 운영 baseline P0)

- 상태: **Accepted (2026-05-21 — architect 단독 결재, 사용자 의제는 ADR 0094 D-5 운영 단계 v3.8.0 cut 결재에 통합 완료)**
  - **Amended (2026-06-01)**: TRADE 즉시 Slack, SystemEvent Slack bridge, Dozzle WARN/ERROR 보조 alert 추가.
  - **Amended (2026-06-10)**: §3-2 **S-4(KIS rate limit) windowCount 차등** — 윈도(5분) **1~4건 = INFO**(알림센터만, Slack 0) / **≥5건 = WARN**(현행 frontend toast 경로). 자동 backoff(1s)로 회복되는 산발 EGW00201(12:15~12:18 7→11 실측)이 사용자 알람화되는 노이즈 제거 (최고 관리자 승인 완료 · backend-core 병렬 구현 `agent/backend-core/kis-ratelimit-alarm-tiering`). spec `_workspace/specs/2026-06-10-kis-ratelimit-queue-redesign-spec.md` §5(EGW00201 회복/실패 2-시그널)와 상호 보완.
- 작성일: 2026-05-21
- 결정자: architect (ADR 0094 §3-2 P0-OPS-3 task 본문화 의무 정합)
- 영역: 운영 헌법 §9 (SLA/SLO) + observability + alerting
- Amends: 운영 헌법 §9-1 (SLO 5건 측정 매트릭스 + alert routing 본문화 amend)
- 관련 ADR: 0094 (운영 단계 transition — 본 ADR이 §3-2 P0-OPS-3 task 본문화) · 0084 (SystemEvent framework — P-4 alert 발행 path) · 0083 (KIS rate limit Bucket4j — S-4 측정 source) · 0044 (Notification — Slack alert reuse) · 0043 (KillSwitch behavior — S-5 측정 source) · 0070 (T-RECON notify bundled — KillSwitch 토픽 권위)

## 1. 배경

### 1-1. ADR 0094 §9 (운영 헌법 §9 신설) SLA/SLO 본문화 의무

ADR 0094 §4-2가 운영 헌법 §9-1을 다음과 같이 신설:

> 자동매매 시그널 emit ~ 주문 발사 지연 **P95 < 5초 (P99 < 10초)** · KIS WS 연결률 (장중 09:00~15:30) **> 99%** · account API **p95 < 500ms** · Outbox drain lag **P95 < 30초** · Redis hit ratio **> 95%** · DB connection pool 활용률 **< 80%**

본문이 SLO 수치만 LOCK했고 **측정 source · alert routing · 위반 시 처리 절차**는 미정. ADR 0094 §3-2 P0-OPS-3가 이를 별도 ADR로 본문화 의무 명시:

> "Slack alert는 ADR 0044 + NotificationDispatcher 구축됨 BUT 임계치 정의(SLO) 0 + alert routing matrix 0 → SLO/SLA 정의 + alert routing matrix (P1·P2 트리거·channel·on-call) ADR 별도 발행 — **architect spec + backend-core 1차 dispatch**"

본 ADR이 그 본문화. 동시에 ADR 0094 §3-2 P0-OPS-1·P0-OPS-2(logback JSON + Actuator/Prometheus + Grafana) 1차 dispatch는 P0-OPS-3 직전 사이클에 머지 완료(P0-OPS-1·2 baseline) → 측정 인프라(JSON 로그 + Prometheus exporter)는 본 ADR이 의존 가능.

### 1-2. v3.8.0 운영 baseline 직전 — 측정·routing 정합 의무

ADR 0094 §4-3 release 정책 정합:

> v3.8.0 운영 단계 1차 baseline — 7 hierarchy 정착 + qa Visual 격상 + **P0 OPS 6건 해소** + observability stack + runbook

P0 OPS 6건 중 본 ADR(P0-OPS-3)이 3번째. 후속 P0-OPS-4(DR/backup)·P0-OPS-5(runbook 5 시나리오)·P0-OPS-6(persistence-shared monitoring)이 본 ADR §2 SLO 매트릭스를 권위 source로 참조하여 발행됨 → 본 ADR이 운영 단계 측정·routing 일관성의 single source of truth.

### 1-3. 측정 source 가용성 evidence (2026-05-21 main commit baseline)

| 항목 | 가용 source | 비고 |
|------|------------|------|
| Micrometer timer 인프라 | ✅ (`io.micrometer:micrometer-registry-prometheus` 의존 — P0-OPS-2 도입) | `http.server.requests` 자동 + 커스텀 timer wiring 가능 |
| Prometheus query | ✅ (docker compose `docker/observability/prometheus/prometheus.yml` — P0-OPS-2 도입) | 15s scrape interval default |
| Grafana dashboard | ✅ (docker compose `docker/observability/grafana/` — P0-OPS-2 도입) | P0-OPS-6 monitoring panel 본문화 영역 |
| SystemEvent emit path | ✅ (ADR 0084 `persistence-shared/.../SystemEventLoggerAdapter.kt` — Outbox 직결) | severity P1~P4 매트릭스 LOCK |
| Slack alert 채널 | ✅ (ADR 0044 `NotificationDispatcher` + `MAGICJAR_SLACK_WEBHOOK_URL`) | severity 임계 routing 정의 필요 |
| KIS rate limit count | ✅ (PR #640 `KisRateLimitInterceptor` SystemEvent `KIS_RATELIMIT` emit) | S-4 측정 직결 |
| KillSwitch 토픽 발사 | ✅ (ADR 0070 `killswitch.engaged.v1` Kafka 토픽) | S-5 측정 직결 |
| WS 연결 상태 evidence | ✅ (PR #644 `WS_DISCONNECTED`/`WS_RECONNECTED` SystemEvent emit) | S-2 측정 직결 |

→ 본 ADR §2 SLO 5건 측정 source는 모두 baseline 가용. 신규 코드 wiring 0건으로 정의 가능.

---

## 2. SLO/SLA 5건 정의 매트릭스

ADR 0094 §9-1 SLO 6건 중 운영 단계 v3.8.0 1차 baseline에 필수 5건 LOCK. Redis hit ratio·DB pool 활용률 2건은 P1 OPS 사이클에서 본 ADR §6 amend로 추가 권장 (현재 baseline 측정 source는 가용하나 임계 정의 backlog).

### 2-1. SLO 5건 매트릭스

| # | 항목 | SLO (목표) | SLA (계약 minimum) | 측정 방법 | severity 임계 (warning / critical) |
|---|------|-----------|--------------------|----------|-----------------------------------|
| **S-1** | 자동매매 시그널 → 주문 발사 지연 | **P95 < 5s** (P99 < 10s) | < 10s | Micrometer timer `magicjar.signal.to.order.latency` (consumer/.../SignalToOrderDispatcher.kt wiring 예정 — backend-core dispatch) — `histogram_quantile(0.95, sum by (le) (rate(magicjar_signal_to_order_latency_seconds_bucket[5m])))` | warning ≥ 5s for 5m / critical ≥ 10s for 2m |
| **S-2** | KIS WS 연결률 (장중 09:00~15:30 KST) | **> 99%** (5분 window) | > 95% | SystemEvent `WS_DISCONNECTED` 빈도 (PR #644 emit path) — `sum(increase(magicjar_system_events_total{event_type="WS_DISCONNECTED"}[5m]))` 5분당 1건 이상 시 warning | warning ≥ 1건/5m / critical ≥ 3건/5m (장중 한정) |
| **S-3** | account API 응답 지연 | **p95 < 500ms** | < 2s | http.server.requests timer (endpoint=`/api/account/*`) — `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/account/.*"}[5m])))` | warning ≥ 500ms for 5m / critical ≥ 2s for 2m |
| **S-4** | KIS rate limit 초과 | **< 1건/5분** | < 5건/5분 | SystemEvent `KIS_RATELIMIT` 빈도 (PR #640 emit path) — `sum(increase(magicjar_system_events_total{event_type="KIS_RATELIMIT"}[5m]))` | warning ≥ 1건/5m / critical ≥ 5건/5m |
| **S-5** | KillSwitch 자동 ON 빈도 | **< 1건/일** | < 3건/일 | killswitch.engaged.v1 Kafka 토픽 빈도 — `sum(increase(magicjar_kafka_messages_total{topic="killswitch.engaged.v1"}[1d]))` 또는 SystemEvent `KILLSWITCH_AUTO_ON` 보조 | warning ≥ 1건/일 / critical ≥ 3건/일 |

### 2-2. 측정 source 분리 — Prometheus vs SystemEvent SQL

본 ADR은 **Prometheus query를 1순위 권위**로 LOCK. 사유:
1. P0-OPS-2 baseline에 Prometheus + Grafana 스택 도입 완료
2. SystemEvent는 영구 audit log → 분석/검증용 (사후 root cause)
3. Prometheus는 실시간 alert routing 직결 → SLA 측정 본질

| 측정 source | 1순위 사용 | 보조 사용 |
|------------|-----------|----------|
| Prometheus query | S-1·S-3 (Micrometer timer) + S-2·S-4 (SystemEvent metric exporter via `magicjar_system_events_total`) | — |
| Kafka 토픽 빈도 | S-5 (`killswitch.engaged.v1`) | S-2·S-4 cross-check |
| SystemEvent SQL (audit) | — | S-1~S-5 사후 root cause 분석 |

**신규 Micrometer wiring 요구**:
- S-1 — `magicjar.signal.to.order.latency` timer (consumer 모듈 `SignalToOrderDispatcher` wiring 영역, backend-core P0-OPS-3 후속 dispatch)
- S-2·S-4 — SystemEvent emit path가 Micrometer counter로도 동시 노출 의무 (`magicjar.system.events` counter — backend-core P0-OPS-3 후속 wiring)

본 ADR은 정의 권위 LOCK. wiring 코드 영역은 backend-core 후속 dispatch (본 ADR §6 권고).

### 2-3. SLO 측정 window·집계 룰

- **장중 한정 SLO**: S-2 KIS WS 연결률은 장중 KST 09:00~15:30 한정 측정 (장외 시간 KIS WS 비활성 정합 — ADR 0076)
- **24/7 측정 SLO**: S-1·S-3·S-4·S-5
- **집계 window**: P95/P99 기본 5분 sliding window. 단 S-5는 1일 누적 (자동매매 자본 보호 본질)
- **장외 시간 (15:30~09:00 KST)**: S-2 alert routing 비활성 LOCK. 그 외는 24/7 유지 (NXT 야간장 정합 — ADR 0076)

---

## 3. alert routing matrix

### 3-1. severity별 routing 4단

ADR 0084 SystemEvent severity P1~P4 매트릭스 정합. 본 ADR이 P1~P4 routing 본문화:

| severity | routing | 응답 시간 | 책임 | 대상 SLO |
|---------|---------|----------|------|---------|
| **P4 INFO** | SystemEvent emit만 (영구 audit log) | - | - | (각 SLO 위반 직전 trend evidence — 자동 archive) |
| **P3 WARN** | SystemEvent + frontend toast | 24h 내 사용자 인지 | 사용자 | S-1 warning · S-3 warning · S-4 warning |
| **P2 ERROR** | SystemEvent + frontend toast + 알람센터 강조(상단 sticky badge) | 1h 내 사용자 즉시 인지 | 사용자 + backend-core (runbook 적용) | S-2 warning · S-3 critical · S-4 critical |
| **P1 CRITICAL** | SystemEvent + toast + 알람센터 강조 + **★★ KillSwitch 자동 ON 검토** + Slack alert (ADR 0044 reuse) | **즉시** | 사용자 + backend-core + architect (root cause ADR) | S-1 critical · S-2 critical · **S-5 critical** |

### 3-2. SLO × severity routing 매트릭스 (2026-05-24 amend — W4 3 alert 신규 추가)

| SLO | warning routing | critical routing |
|-----|----------------|------------------|
| **S-1** 시그널→주문 지연 | P3 WARN (frontend toast `[지연] 시그널→주문 ${latency}s — 평균 5초 초과`) | P1 CRITICAL (Slack alert + KillSwitch 자동 ON 검토 — 자본 path 핵심) |
| **S-2** KIS WS 연결률 | P2 ERROR (frontend 알람센터 sticky badge `[KIS] WS 연결 불안정 — ${count}건/5분`) | P1 CRITICAL (Slack alert + 사용자 즉시 인지 — 시세 path 단절 risk) |
| **S-3** account API 지연 | P3 WARN (frontend toast `[성능] account API ${latency}ms — 평균 500ms 초과`) | P2 ERROR (frontend 알람센터 sticky badge — 사용자 UX 차단 risk) |
| **S-4** KIS rate limit (2026-06-10 amend — windowCount 차등) | **windowCount 1~4 = P4 INFO** (알림센터 audit만, frontend toast·Slack 0 — 자동 backoff 회복 산발 noise 제거) · **windowCount ≥5 = P3 WARN** (frontend toast `[KIS] rate limit ${count}건/5분`) | P2 ERROR (Slack alert 권장 — KIS 차단 risk 가속) |
| **S-5** KillSwitch 자동 ON | P3 WARN (frontend toast `[자본] KillSwitch 자동 ON 1건/일`) | **P1 CRITICAL** (Slack alert 필수 + 사용자 즉시 인지 — 자본 보호 본질) |
| **S-AI-1 (2026-05-24 amend) — AI 월 비용** | (P3 default disabled — $5/월 누적 시 frontend toast 권장 옵션) | **P1 CRITICAL** ($10/월 초과 시 Slack alert + AI KillSwitch 자동 검토 — `MAGICJAR_AI_KILLSWITCH` env toggle 권장 · 비용 폭주 차단 path) |
| **S-MCP-1 (2026-05-24 amend) — MCP 분당 호출** | **P3 WARN** (100/min 초과 → frontend toast `[MCP] 분당 호출 ${count}건 — 외부 client abuse risk`) | (P2 ERROR — 500/min 초과 시 Slack alert + ApiKeyAuthFilter rate limit 강제 검토) |
| **S-MARKET-1 (2026-05-24 amend) — 지수 fetch 실패** | (P3 default — 1건/5m frontend toast) | **P2 ERROR** (5건 연속 5m window → Slack ERROR · KIS overseas API 차단 risk 가속) |

### 3-3. routing channel 우선순위

ADR 0094 §9-3 on-call rotation 정합:

| 우선순위 | channel | 책임 actor | 트리거 |
|---------|---------|-----------|--------|
| 1차 | SystemEvent (P4 default 영구 audit) | - (auto) | 모든 SLO 측정 시점 (warning/critical 무관) |
| 2차 | frontend toast/알람센터 | 사용자 1차 인지 | P3 WARN ↑ |
| 3차 | Slack alert (ADR 0044 reuse) | 사용자 즉시 인지 + backend-core 운영 actor | P1 CRITICAL (의무) + P2 ERROR (권장 — S-4) |
| 4차 (자동 액션) | **KillSwitch 자동 ON 검토** (ADR 0043 정합) | strategy-engine + backend-core | S-1 critical · S-5 critical (자본 path 직결 SLO 한정) |

**★ Slack/email/SMS 외부 routing 확장**:
- Slack alert 기본 channel은 `MAGICJAR_SLACK_WEBHOOK_URL` 단일 (ADR 0044 정합)
- email/SMS/PagerDuty 등 외부 routing 확장은 **v3.8.x 이후 backlog** (외부 의존 + 사용자 단독 운영자 가정 정합 — ADR 0094 §9-3 1차 사용자 직접 인지 본질)

### 3-3-1. 2026-06-01 amend — 사용자 체감 알림 라우팅

사용자 운영 요구를 반영해 외부 메신저 정책을 다음 4분류로 고정한다.

| 분류 | routing | 포함 |
|------|---------|------|
| **CRITICAL/P0** | 즉시 Slack | killswitch, 주문 차단, 리스크 위반, DLT, 데이터 복구 실패, KIS WS 장시간 단절 |
| **ERROR** | 즉시 Slack 또는 짧은 묶음 | KIS upstream 실패, 주문 거절, gap fill 실패, 계좌/호가/현재가 stale |
| **WARN** | 묶음 또는 UI 알림 | KIS rate limit, 재연결 성공, 일부 캔들 지연, 일시적 API 실패 |
| **TRADE** | 즉시 Slack | REAL/MOCK/PAPER 매수체결, 매도체결, 부분체결, 주문거절 |

구현 원칙:

- `order.filled.v1`과 `order.rejected.v1`은 REAL/MOCK/PAPER 구분 없이 P0 즉시 Slack이다.
- `system.event.v1`은 consumer에서 외부 알림으로 bridge한다. `CRITICAL`/`ERROR`는 P0 즉시, `WARN`은 Redis window로 묶어 P2 outbox에 넣고, `INFO`는 Slack으로 보내지 않는다.
- Dozzle alert는 권위 source가 아니라 보조 안전망이다. 앱 로그의 WARN/ERROR를 cooldown으로 묶어 Slack에 보내되, 주문/자본/시장 데이터 사건은 Kafka/SystemEvent/outbox 경로를 우선한다.

### 3-4. 자동 액션 — KillSwitch 자동 ON 검토 (S-1·S-5 critical 한정)

자본 path 직결 critical SLO 위반 시 KillSwitch 자동 ON 검토 본문 (ADR 0043·0070 정합):

| 트리거 SLO | 자동 액션 |
|-----------|----------|
| S-1 critical (시그널→주문 ≥10s for 2m) | strategy-engine `KillSwitchAutoToggler` 진입 (ADR 0043 §2-11 가드 정합 — A. Pending Order Exclusion + B. Grace Window 5분 + C. N-Strike N=2) → 통과 시 KillSwitch ON |
| S-5 critical (KillSwitch 자동 ON ≥ 3건/일) | **KillSwitch 자동 ON 정책 자체 일시 비활성 검토** (자동 OFF 폭주 방지 — backend-core runbook 5 시나리오에 본문화 의무) |

본 자동 액션은 ADR 0043 §2-11 race-safe 가드 정합 LOCK. 직접 자동 ON 수동 토글은 사용자만.

---

## 4. Prometheus alert rule baseline (backend-core P0-OPS-3 후속 dispatch)

본 ADR §2·§3 정합 alert rule baseline 발행 권장 위치 LOCK. 본 ADR은 rule 본문화 영역이 아니므로 **권고 sketch만** 본 §에 명시. 실제 rule 파일 발행은 backend-core P0-OPS-3 후속 dispatch에서 처리.

### 4-1. rule 파일 권고 위치

- `docker/observability/prometheus/alerts/magicjar-slo.yml` (신규 — backend-core 발행 영역)
- `docker/observability/prometheus/prometheus.yml` rule_files include 추가

### 4-2. rule sketch (5 SLO × 2 임계 = 10 rule)

```yaml
groups:
  - name: magicjar.slo
    rules:
      # S-1 자동매매 시그널 → 주문 발사 지연
      - alert: SignalToOrderLatencyWarning
        expr: histogram_quantile(0.95, sum by (le) (rate(magicjar_signal_to_order_latency_seconds_bucket[5m]))) > 5
        for: 5m
        labels: { severity: warning, slo: S-1 }
        annotations: { summary: "시그널→주문 지연 P95 > 5s for 5m" }
      - alert: SignalToOrderLatencyCritical
        expr: histogram_quantile(0.95, sum by (le) (rate(magicjar_signal_to_order_latency_seconds_bucket[5m]))) > 10
        for: 2m
        labels: { severity: critical, slo: S-1, action: killswitch_review }
        annotations: { summary: "시그널→주문 지연 P95 > 10s for 2m — KillSwitch 자동 ON 검토 트리거" }

      # S-2 KIS WS 연결률 (장중 한정 — Prometheus query 시간 필터 권장)
      - alert: KisWsDisconnectWarning
        expr: sum(increase(magicjar_system_events_total{event_type="WS_DISCONNECTED"}[5m])) >= 1
        for: 0m
        labels: { severity: warning, slo: S-2 }
        annotations: { summary: "KIS WS 단절 1건/5분 — 장중 운영 risk" }
      - alert: KisWsDisconnectCritical
        expr: sum(increase(magicjar_system_events_total{event_type="WS_DISCONNECTED"}[5m])) >= 3
        for: 0m
        labels: { severity: critical, slo: S-2 }
        annotations: { summary: "KIS WS 단절 3건/5분 — 시세 path 단절 risk" }

      # S-3 account API
      - alert: AccountApiLatencyWarning
        expr: histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/account/.*"}[5m]))) > 0.5
        for: 5m
        labels: { severity: warning, slo: S-3 }
        annotations: { summary: "account API p95 > 500ms for 5m" }
      - alert: AccountApiLatencyCritical
        expr: histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/account/.*"}[5m]))) > 2
        for: 2m
        labels: { severity: critical, slo: S-3 }
        annotations: { summary: "account API p95 > 2s for 2m — 사용자 UX 차단 risk" }

      # S-4 KIS rate limit
      - alert: KisRatelimitWarning
        expr: sum(increase(magicjar_system_events_total{event_type="KIS_RATELIMIT"}[5m])) >= 1
        for: 0m
        labels: { severity: warning, slo: S-4 }
        annotations: { summary: "KIS rate limit 1건/5분" }
      - alert: KisRatelimitCritical
        expr: sum(increase(magicjar_system_events_total{event_type="KIS_RATELIMIT"}[5m])) >= 5
        for: 0m
        labels: { severity: critical, slo: S-4 }
        annotations: { summary: "KIS rate limit 5건/5분 — KIS 차단 risk 가속" }

      # S-5 KillSwitch 자동 ON
      - alert: KillSwitchAutoOnWarning
        expr: sum(increase(magicjar_kafka_messages_total{topic="killswitch.engaged.v1"}[1d])) >= 1
        for: 0m
        labels: { severity: warning, slo: S-5 }
        annotations: { summary: "KillSwitch 자동 ON 1건/일" }
      - alert: KillSwitchAutoOnCritical
        expr: sum(increase(magicjar_kafka_messages_total{topic="killswitch.engaged.v1"}[1d])) >= 3
        for: 0m
        labels: { severity: critical, slo: S-5, action: killswitch_policy_disable_review }
        annotations: { summary: "KillSwitch 자동 ON 3건/일 — 자동 ON 정책 일시 비활성 검토" }
```

### 4-3. Alertmanager routing (선택 — backend-core 발행 영역)

ADR 0044 reuse: Alertmanager → webhook → `NotificationDispatcher` Slack channel. 본 ADR은 routing 매트릭스 본문화만 LOCK. Alertmanager 본문화는 backend-core P0-OPS-3 후속 dispatch.

---

## 5. 운영 ADR 양식 3 슬롯 (운영 헌법 §9-5 정합 — 본 ADR이 첫 적용 사례)

### 5-1. SLO 영향

본 ADR이 §9-1 SLO **정의 source 자체**. 직접 SLO 수치 영향 0. BUT 본 ADR 적용 후 다음 회복:

| 항목 | before (현재) | after (본 ADR + backend-core 후속 wiring) |
|------|--------------|------------------------------------------|
| SLO 측정 가능성 | 0% (수치 LOCK만 + 측정 source 미정) | **100%** (Prometheus query 본 ADR §2 본문화) |
| alert routing 가능성 | 0% (Slack adapter만 + 임계 0) | **100%** (severity 4단 매트릭스 본 ADR §3 본문화) |
| 자동 액션 (KillSwitch 자동 ON 검토) 정합 | 부분 (ADR 0043 race-safe BUT SLO 트리거 0) | **완전** (S-1·S-5 critical → KillSwitch 자동 ON 검토 본 ADR §3-4 본문화) |

### 5-2. rollback plan

본 ADR은 정의·문서 권위 ADR — 운영 코드 0 touch. revert 절차:
1. 본 PR 단일 revert (ADR + README + master §10 + 헌법 §9-1 cross-link + CHANGELOG add-only)
2. backend-core 후속 dispatch (Micrometer wiring · Prometheus alert rule · Alertmanager routing) 별도 PR revert
3. ADR 0094 §3-2 P0-OPS-3 task 본문은 ADR 0094 §9-1 정합 LOCK 유지 (rollback에 영향 0)

운영 단계 부적합 발견 시 (예: 임계치 너무 민감 → alert noise) — 본 ADR §2-1 임계 amend로 처리 (사용자 결재 의무). 본 ADR 자체 revert는 운영 측정·routing 권위 공백 발생 → **NOT recommended**.

### 5-3. 관측 매트릭스

본 ADR 적용 후 1주일 dwell 후 (backend-core wiring 완료 가정):

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| alert 발사 빈도 (총) | < 20건/일 (noise 임계) | Prometheus `ALERTS{alertstate="firing"}` count |
| SLO critical alert 발사 빈도 | < 5건/일 | severity=critical 필터 |
| Slack alert 도달 성공률 | > 99% | NotificationDispatcher metric (ADR 0044 reuse) |
| KillSwitch 자동 ON 검토 trigger → 실제 ON 비율 | 사후 분석 evidence (ADR 0043 정책 정합성 검증) | KillSwitch 토픽 + SystemEvent cross-check |
| Visual 회귀 시나리오 (frontend 영향 시) | 본 ADR는 frontend 영역 0 — Visual 회귀 시나리오 N/A. P0-OPS-2(Grafana) + P0-OPS-6(persistence-shared monitoring) Visual 회귀 영역 | qa Visual dispatch (P0-OPS-6 후속 사이클) |

운영 1주일 후 발사 빈도 평가 → 임계 amend 사이클 (architect 단독 ADR 0095 amend PR 발행 권한).

---

## 6. ADR cross-link · 후속 정합

### 6-1. ADR cross-link 매트릭스

| 관련 ADR | 본 ADR과의 관계 |
|---------|---------------|
| **ADR 0094** | 본 ADR이 §3-2 P0-OPS-3 task 본문화 + 운영 헌법 §9-1 SLO 5건 측정 매트릭스 amend |
| **ADR 0084** | P-4 alert 발행 path — SystemEvent emit이 본 ADR §3 routing matrix의 P4 INFO ~ P1 CRITICAL 매트릭스 권위 source |
| **ADR 0083** | KIS rate limit Bucket4j — S-4 측정 source (`KisRateLimitInterceptor` SystemEvent emit) |
| **ADR 0044** | Slack alert 채널 reuse — 본 ADR §3-3 4차 routing channel |
| **ADR 0043** | KillSwitch behavior — 본 ADR §3-4 자동 액션의 race-safe 가드 정합 source |
| **ADR 0070** | T-RECON notify bundled — killswitch.engaged.v1 토픽 권위 (S-5 측정 source) |

### 6-2. 후속 ADR 권장 (v3.8.x amend 후보)

본 ADR baseline LOCK 후 운영 dwell 1주~1개월 후 amend 권장 매트릭스:

| 후속 amend ID | trigger | 권장 시점 |
|--------------|---------|----------|
| ADR 0095 amend §2 — Redis hit ratio SLO 신규 | P1 OPS 사이클에 Redis hit ratio 측정 source wiring | v3.8.x patch |
| ADR 0095 amend §2 — DB connection pool 활용률 SLO 신규 | P1 OPS 사이클에 HikariCP metric wiring | v3.8.x patch |
| ADR 0095 amend §2-1 임계 — S-1·S-3 P95 임계 사용자 결재 (운영 dwell evidence 기반) | 1주 dwell 후 alert noise 평가 | v3.8.x patch |
| 신규 ADR — Visual SLO (qa Visual 회귀 detect 빈도 SLO) | qa Visual 격상 후 첫 분기 dwell evidence | v3.9.0 amend |

### 6-3. backend-core P0-OPS-3 후속 dispatch 본문 (권고)

본 ADR Accepted 후 backend-core 1차 dispatch 영역:

1. **Micrometer wiring 5건** — S-1 timer (`magicjar.signal.to.order.latency`) + S-2·S-4 SystemEvent → Counter 노출 (`magicjar.system.events` counter) + S-5 Kafka 토픽 metric exporter
2. **Prometheus alert rule 파일 발행** — `docker/observability/prometheus/alerts/magicjar-slo.yml` (§4-2 sketch 정합)
3. **Alertmanager routing 발행** — `docker/observability/alertmanager/alertmanager.yml` + webhook → `NotificationDispatcher` Slack
4. **frontend toast/알람센터 routing 확장** — P3 WARN ~ P2 ERROR SystemEvent severity 분기 UI (qa Visual 시나리오 자동 dispatch 의무)
5. **runbook 본문화** — P0-OPS-5 5 시나리오 중 SLO 위반 대응 시나리오 1건 본 ADR §2·§3 정합 본문화

---

## 7. 트레이드오프

### 7-1. 채택 (선택지)

- **Prometheus query 1순위 권위** — 실시간 alert routing 본질 + P0-OPS-2 baseline 정합 (SystemEvent SQL은 사후 audit 보조)
- **SLO 5건 v3.8.0 baseline LOCK** — ADR 0094 §9-1 6건 중 Redis hit ratio·DB pool 활용률 2건은 P1 OPS amend (측정 source wiring 부담 ↓)
- **severity 4단 routing matrix** — ADR 0084 SystemEvent severity 매트릭스 직접 reuse (신규 분류 0)
- **KillSwitch 자동 ON 검토 자본 path 한정** — S-1·S-5 critical만 자동 액션 (오버 자동화 회피)
- **Slack 단일 channel** — ADR 0044 reuse + email/SMS/PagerDuty 외부 routing 확장은 backlog (운영 부담 ↓)
- **alert rule baseline sketch만** — backend-core 후속 dispatch에 wiring 영역 위임 (영역 책임 분리)

### 7-2. 거부 옵션 (왜 안 했는가)

| 옵션 | 거부 사유 |
|------|----------|
| SLO 6건 모두 v3.8.0 baseline 포함 | Redis hit ratio·DB pool 활용률 측정 source wiring 비용 ↑ + P0-OPS-1·2 baseline 직후 → 측정 인프라 안정 dwell 필요. P1 OPS amend로 분리 |
| SystemEvent SQL 1순위 권위 | 실시간 alert routing 본질 위배 (SQL polling은 30s+ lag) + Prometheus 인프라 가용 정합 위배 |
| 신규 severity 5단 도입 (P0 EMERGENCY 신규) | ADR 0084 4단 매트릭스 LOCK 정합 + 신규 severity는 incident response 시나리오 부담 ↑ + P1 CRITICAL이 본질 충분 |
| Slack/email/SMS 다채널 routing | 사용자 단독 운영자 가정 (ADR 0094 §9-3) + 외부 의존 ↑ + Slack 단일 channel이 본질 충분 |
| KillSwitch 자동 ON을 모든 critical SLO에 적용 | S-2·S-3·S-4 critical은 자본 path 직결 아님 — 자동 자본 차단은 본질 외 risk. S-1·S-5 한정이 ADR 0043 정합 |
| alert rule 파일 본 ADR에 본문화 | 영역 책임 분리 (architect = 정의 권위 + backend-core = wiring 권위). 본 ADR §4 sketch만 권고 → backend-core dispatch에 본문화 |

---

## 8. 결과

### 8-1. 긍정

- **운영 헌법 §9-1 SLO 5건 측정·routing 완성** — 정의만 있던 SLO가 alert 발사 가능 상태 회복
- **SLO × severity routing 매트릭스 single source** — backend-core·frontend·architect 후속 PR이 본 ADR §2·§3 직접 참조
- **운영 ADR 양식 첫 적용 사례** — 헌법 §9-5 SLO 영향·rollback plan·관측 매트릭스 3 슬롯 적용 본보기
- **KillSwitch 자동 ON 검토 trigger 본문화** — S-1·S-5 critical 자본 path 직결 자동 액션 정합
- **운영 코드 0 touch** — 정의 권위 ADR 본질 정합 + 후속 backend-core dispatch wiring 영역 분리
- **v3.8.0 운영 baseline P0 진척** — P0 OPS 6건 중 3번째 task 완료 (P0-OPS-1·2·3)

### 8-2. 부정 (대응 plan)

- **Micrometer wiring 0건 — 본 ADR 자체로는 측정 source 활성 0** — backend-core P0-OPS-3 후속 dispatch 의무 (본 ADR §6-3 권고)
- **alert noise risk** — 임계치 너무 민감 시 alert 폭주. 1주 dwell 후 §2-1 임계 amend 사이클 권장
- **Redis hit ratio·DB pool SLO 2건 v3.8.0 baseline 제외** — P1 OPS amend 사이클 의무 (본 ADR §6-2 권고)
- **Alertmanager 운영 본문 미정** — backend-core 후속 dispatch (본 ADR §4-3 권고)

### 8-3. SLO 영향

§5-1 본문화 — 본 ADR이 SLO 정의 source 자체. SLO 측정 가능성 0 → 100% 회복.

### 8-4. rollback plan

§5-2 본문화 — 본 PR 단일 revert 가능 (ADR + README + master §10 + 헌법 §9-1 cross-link + CHANGELOG add-only).

### 8-5. 관측 매트릭스

§5-3 본문화 — 1주일 dwell 후 alert 발사 빈도 평가 + Slack 도달 성공률 + KillSwitch 자동 ON trigger 비율.

---

## 9. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-21 | **ADR 0095 Accepted — P0-OPS-3 SLO/SLA + alert routing 본문화** (ADR 0094 §3-2 P0-OPS-3 task 본문화 + 운영 헌법 §9-1 측정 매트릭스 amend + 5 SLO × 2 임계 alert sketch + severity 4단 routing matrix + KillSwitch 자동 ON 검토 S-1·S-5 한정 + backend-core 후속 wiring 5건 권고) | architect (ADR 0094 §3-2 task 본문화 의무) |
| 2026-05-24 | **amend — §3-2 SLO × severity routing 매트릭스 3 신규 row (S-AI-1 AI 월 비용 $10 초과 Slack CRITICAL + AI KillSwitch 자동 검토 · S-MCP-1 MCP 분당 100/500 호출 routing · S-MARKET-1 지수 fetch 실패 5건 연속 Slack ERROR)** — W4 통합 사이클 (T-AI + T-MCP + T-MARKET) brainstorming 결재 cec6a4d6 정합. ADR 0026·0027·0094·0100·0101 amend 동반. orchestrator → architect R0 dispatch. backend-core P0-OPS-3 후속 wiring 추가 권고 3건 (Prometheus alert rule 3 신규 · Slack channel routing wiring · runbook 5 시나리오 amend `AI 비용 폭주`·`MCP abuse`·`지수 fetch 실패` 3 추가 시나리오). 운영 코드 0 touch. (architect, opus 4.7 1M) |
| 2026-06-10 | **amend — §3-2 S-4(KIS rate limit) windowCount 차등** (윈도 5분 1~4건=P4 INFO 알림센터 audit만/Slack·toast 0 · ≥5건=P3 WARN 현행 toast). 자동 backoff(1s) 회복되는 산발 EGW00201(12:15~12:18 7→11 실측)이 사용자 알람화되는 노이즈 제거 — 최고 관리자 승인 완료. backend-core 병렬 구현 `agent/backend-core/kis-ratelimit-alarm-tiering`. spec `2026-06-10-kis-ratelimit-queue-redesign-spec.md` §5(EGW00201 회복/실패 2-시그널 — recovered=INFO/failed=WARN) 상호 보완. 운영 코드 0 touch (architect 영역은 정의·routing amend만, wiring은 backend-core). (architect, opus 4.8 1M) |
