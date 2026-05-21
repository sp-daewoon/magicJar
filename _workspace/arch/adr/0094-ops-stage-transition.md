# ADR 0094: 운영 단계 transition — 개발 단계 종착 + 운영·점진 고도화 단계 진입 (sub-agent 6 hierarchy + 운영 헌법 § 신설)

- 상태: **Proposed (2026-05-21 — 사용자 결재 후 Accepted 격상 예정)**
- 작성일: 2026-05-21
- 결정자: 사용자(최고 관리자) — architect sub-agent 수행
- 영역: 메타·하네스 (§9) + 비전 (§1) + 운영 헌법
- Supersedes (부분): ADR 0080 (sub-agent 8 → 6 reshuffle 본 ADR이 amend)
- Amends: ADR 0085 (chart-curator 페르소나·책임은 유지하되 frontend 흡수 평가) · ADR 0087 (페르소나 매트릭스 reshuffle 후 재배치) · 운영 헌법 (§9 운영 단계 룰 신설)
- 관련 ADR: 0001(비전) · 0030(SoT 단일 진실원) · 0036(Orchestrator Meta Rules) · 0044(Notification — Slack alert) · 0049(GitHub Actions release 한정) · 0080(전체 opus 통일) · 0084(P0 주문 path 안정성 baseline) · 0088(persistence-shared)

## 1. 맥락 (사용자 결재 evidence)

### 1-1. 사용자(최고 관리자) 2026-05-21 KST 명시 결재

> "전영역 코드베이스로 검증 절차에 돌입해야해. 이거 하고 나서 에이전트 전격적으로 해고 절차와 투입하는걸 다시 만들 거야. 이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해"

이 결재는 다음 3 변화를 트리거한다:

1. **전영역 코드베이스 검증** — fitness gap 기준을 "개발 단계" → "운영 단계"로 LOCK 전환
2. **sub-agent reshuffle** — 운영 단계 필요성 lens로 8 actor 재평가 + 해고/흡수/신규 결정
3. **mindset 전환** — "기능 추가·트랙 진입" → "안정 운영 + 점진 고도화"

### 1-2. 현 시점 객관 evidence (2026-05-21 main commit `28745ed3`)

| 영역 | 현황 | 운영 단계 fitness |
|------|------|------------------|
| JVM 모듈 | 7개 (domain·api·batch·consumer·persistence-shared·indicator-impl·strategy-engine) | ✅ 안정 (ADR 0083·0088·0092 모듈 분리 완료) |
| 코드 LOC | prod 65009 + test 26819 (Kotlin) + frontend 33489 (TS/TSX) | ✅ 충분 (운영 단계 dilution 없음) |
| Flyway migration | 43+ (API 모듈) | ✅ 자료 모델 안정 |
| Test class | 152 (api 77 + consumer 64 + batch 5 + domain 4 + persistence-shared 2) | ⚠️ batch·domain·persistence-shared 부족 (운영 단계 회귀 위험) |
| ADR | 93 Accepted/Proposed + README | ✅ 결정 traceability 풍부 |
| 운영 헌법 | 7 § + 정책 5건 (Living Policy) | ✅ 운영 룰 LOCK |
| Wave 진행 | W1·W2·W3 마감 + W4 진입 직전 (사용자 명시 W4 진입 0 LOCK) | — |
| Backlog open | 11+ §(P0-A persistence-shared·P1-4 Rate Limit·Lab 의제 등) | ⚠️ 운영 무관 후순위 trim 의제 |
| sub-agent 분포 (past 7 weeks) | architect 295 / backend-core 227 / orchestrator 173 / frontend 145 / market-data 129 / strategy 34 / chart-curator 32 / ai-engineer 19 / qa 1 | ⚠️ qa 거의 미가동 + ai-engineer W4 진입 0으로 거의 미가동 |

**핵심 evidence 인용**:
- `git log --since=2026-04-01` 작가 분포: qa 1건 / ai-engineer 19건 — 운영 단계 진입 후 가동 가능성 더 희박
- ADR 0080 "전체 opus 통일" 결정은 W3 종착 + W4 진입 직전 단계 — 본 ADR 시점에서 재평가 필요
- `Prometheus` import 0 / `actuator` 의존만 있고 application.yml `management.endpoints` 노출 0 / `logback*.xml` 미발견 — 운영 단계 관측성 P0 gap
- `docs/operations/` 2 파일만(`initial-setup.md`·`kis-api-reference.md`) — 운영 runbook gap

### 1-3. 운영 단계의 본질적 변화

| 항목 | 개발 단계 (현재 ~v3.7.0) | **운영 단계 (v3.8.0~)** |
|------|------------------------|------------------------|
| 1순위 목표 | 기능 완성 + 트랙 진입 | **안정 운영 + 점진 개선** |
| 변경 빈도 | 한 사이클 5~15 PR | **한 사이클 1~3 PR (hotfix 우선)** |
| 에이전트 가동 | spec/plan + 병렬 dispatch | **on-demand + 1~2 actor 직렬** |
| 시간 단위 | "사이클" (PR 묶음) | **"incident" + "주간 운영"** |
| 관측성 | dogfooding evidence | **SLA·SLO·alert 자동화** |
| 검증 lens | "동작하는가" | **"SLA 충족하는가" + "사고 대응 가능한가"** |
| Wave 진행 | W1·W2·W3·W4·W5 | **W3·W4·W5 보류 권한 (사용자 LOCK)** |
| 백로그 | 누적 OK | **운영 무관 후순위 trim 의무** |

---

## 2. 결정 (3 산출물 통합)

### 2-1. 전영역 코드베이스 audit 결과 (Fitness Gap P0/P1/P2 매트릭스)

본 ADR §3 참조. 직접 코드 grep·git log·SoT 인용 기반.

### 2-2. sub-agent reshuffle (8 → 6 hierarchy)

| 신 모델 | 책임 | 출신 | 분류 |
|---------|------|------|------|
| architect | 시스템 설계·ADR·운영 헌법 amend·SLO/SLA 정의 | 기존 | **유지 + 운영 ADR 양식 추가** |
| backend-core | Spring Boot 도메인·인프라·persistence-shared·KIS adapter | 기존 | **유지 + market-data 부분 흡수** |
| frontend | React/TS/LWC v4·STOMP UI·운영 dashboard (Symbol·Watchlist·SystemEvent 등) | 기존 | **유지 + chart-curator 흡수** |
| market-data | KIS API·시세·뉴스·공시 어댑터 + 캔들 SLA 정합성 (chart-curator 캔들 적재 책임 흡수) | 기존 | **유지 + chart-curator 일부 흡수** |
| strategy-engine | DSL·지표·백테스트·시그널·리스크·주문 path | 기존 | **유지** |
| **sre** (신규) | SLO/SLA · Prometheus/Grafana · runbook · incident response · alert routing · DR/backup · 변경 관리 (canary·rollback) | **신규 영입** | **신규 (운영 단계 핵심 actor)** |

#### 2-2-A. 해고 (2명)

| 에이전트 | 해고 사유 | 책임 인수 |
|---------|----------|----------|
| **qa** | past 7 weeks 1건 dispatch + "QA 게이트 배제" 룰(헌법 + `feedback_qa_on_demand`) 사용자 정책 정합 + 운영 단계는 incident response가 검증의 본질 | `sre` (incident·SLA 검증) + 각 sub-agent 자체 테스트 + cross-review 페르소나 매트릭스 (ADR 0087) |
| **ai-engineer** | past 7 weeks 19건 + W4(T-AI·T-MCP) 사용자 명시 진입 0 LOCK + 운영 단계에서 LLM 호출 비용 통제 + 미래 W4 진입 시 별도 ADR로 재영입 가능 (보류 카드) | 보류 — W4 진입 시 재영입 + 그 전에는 architect가 spec 단계 흡수 |

#### 2-2-B. 흡수 (1명)

| 에이전트 | 흡수 사유 | 흡수 대상 |
|---------|----------|----------|
| **chart-curator** | TradingView LWC v4는 frontend 모듈 1 컴포넌트 + 캔들 SLA 정합성은 market-data adapter 책임 + 페르소나 "안정빌드"(보수)는 frontend/market-data 페르소나로 흡수 가능 + 8 hierarchy 운영 부담 ↓ | TradingView LWC v4 컴포넌트 → **frontend** / 캔들 적재 워커 + SLA 정합성 → **market-data** / 페르소나 "안정빌드"는 backend-core "리팩토링맨"(진보)와 짝 유지 — frontend·market-data 1차 보수 lens 흡수 (ADR 0085 페르소나 보존 + 책임 이관) |

#### 2-2-C. 신규 영입 (1명)

| 에이전트 | 영입 사유 | 페르소나 |
|---------|----------|---------|
| **sre** (Site Reliability Engineer) | 운영 단계 핵심 — 관측성(Prometheus·Grafana)·SLO/SLA 정의·incident response runbook·alert routing·DR/backup·변경 관리(canary·rollback·blue/green)·캐패시티 플래닝 단일 owner. 현 시점 0건 → 운영 단계 진입 시 1순위 actor | "운영자" (operator) — **방어적·관측 1순위·alert 1차 차단 lens**. 10년차 SRE/DevOps 경험. 좌우명 "측정할 수 없는 것은 개선할 수 없다." 코드 리뷰 lens: 관측성·복원력·롤백 가능성·SLO 영향·alert noise·runbook 갱신. opus 4.7. |

#### 2-2-D. 페르소나 매트릭스 재배치 (운영 단계)

| 영역 | 페르소나 |
|------|---------|
| backend-core | **진보 — "리팩토링맨"** (ADR 0087 보존) |
| frontend | **보수 — "안정빌드"** (chart-curator 페르소나 흡수 — LWC v4·차트 컴포넌트 자본 path lens 보존) |
| market-data | **보수 — "안정빌드"** (chart-curator 캔들 정합성 lens 흡수 — KIS API·자본 path 직결) |
| strategy-engine | **보수 — "안정빌드"** (DSL·시그널·주문 path 자본 흐름 직결) |
| **sre** (신규) | **방어 — "운영자"** (관측·alert·롤백) |
| architect | **균형 — 결정자** (보수+진보+방어 cross-lens) |

cross-review 효과:
- frontend·market-data PR 1건 → backend-core 진보 lens 자동 검토 (리팩토링 제안) + sre 방어 lens 자동 검토 (관측·SLO 영향)
- strategy-engine PR (자본 path) → sre 방어 lens 의무 검토 + backend-core 진보 lens 옵션
- backend-core 진보 PR → market-data 보수 lens + sre 방어 lens 의무 cross-review

### 2-3. 운영 단계 transition plan (7 단계 + 헌법 §9 신설)

본 ADR §4 참조. mindset 전환 + 신규 룰 + 항목별 결정 매트릭스 + 점진 고도화 우선순위 + 운영 ADR 양식.

---

## 3. 전영역 코드베이스 Fitness Gap audit (P0/P1/P2 매트릭스)

운영 단계 기준 — **"개발 단계는 동작했으나 운영 단계에서 위험인가"** lens 의무.

### 3-1. 영역별 평가 매트릭스

| 영역 | 코드 품질 | 아키텍처 정합 | **운영 적합성** | 기술 부채 | 종합 |
|------|----------|--------------|----------------|----------|------|
| domain (199 prod / 4 test) | A | A (ADR 0004 순수성) | **B** (test 부족) | LOW | **B+** |
| api (251 prod / 77 test) | A | A | **C** (actuator config 0 + alert 0) | MEDIUM | **B-** |
| batch (41 prod / 5 test) | B | A | **C** (test 5건 only + scheduler 모니터링 0) | MEDIUM | **C+** |
| consumer (149 prod / 64 test) | A | A | **C** (Kafka lag 모니터링 0 + KIS WS 끊김 가시화 부족 — recent hotfix 6건 #631~#644 evidence) | MEDIUM | **C+** |
| persistence-shared (13 prod / 2 test) | B | A (ADR 0088 신모듈) | **D** (test 2건 only + outbox 운영 모니터링 0) | LOW | **C** |
| indicator-impl (집계 X) | A | A (ADR 0083) | B | LOW | **B+** |
| strategy-engine (집계 X) | A | A (ADR 0092) | B | LOW | **B+** |
| frontend (246 file / 33489 LOC) | A | A | **C** (관측 UI 부족 + SystemEvent 외 dashboard 0) | MEDIUM | **B-** |
| docker compose (인프라 3 + 앱 4 + 디버깅 2) | A | A (ADR 0008) | **C** (Prometheus 0 / Grafana 0 / Loki 0) | LOW | **B-** |
| logging | C | — | **D** (logback.xml 미발견 + JSON 로깅 0 + 중앙 집계 0) | HIGH | **D** |

**종합**: 코드 품질 + 아키텍처는 A·B+ 수준. **운영 적합성은 C·D 다발 — P0 갭 다수.**

### 3-2. P0 (즉시 처리 — 운영 단계 진입 차단)

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| **P0-OPS-1** | logging | logback.xml 미발견 + JSON 구조 로깅 0 + correlation id 0 | logback-spring.xml 발행 (JSON encoder + level threshold + KST timezone + traceId/spanId/userId/orderId MDC) — sre 1차 dispatch |
| **P0-OPS-2** | observability | Spring Actuator 의존만 추가 + `management.endpoints.web.exposure.include` 0 + Prometheus 0 | application.yml `management.endpoints.web.exposure.include: health,info,metrics,prometheus` + `io.micrometer:micrometer-registry-prometheus` 의존 + docker compose Prometheus + Grafana 추가 — sre 1차 dispatch |
| **P0-OPS-3** | alerting | Slack alert는 ADR 0044 + NotificationDispatcher 구축됨 BUT 임계치 정의(SLO) 0 + alert routing matrix 0 | SLO/SLA 정의 + alert routing matrix (P1·P2 트리거·channel·on-call) ADR 별도 발행 — sre 1차 dispatch |
| **P0-OPS-4** | DR/backup | postgres pg_dump 자동화 0 + Kafka offset 백업 0 + Redis snapshot 정책 0 | postgres pg_dump cron + Kafka topic backup + Redis snapshot 정책 ADR 별도 발행 — sre 1차 dispatch |
| **P0-OPS-5** | runbook | `docs/operations/` 2 파일만 (initial-setup + kis-api-reference) — incident response·KIS API 차단 대응·KillSwitch 토글 절차 등 0 | `docs/operations/runbook/` 디렉토리 신설 + 5 시나리오 1차 (incident response · KIS 차단 · KillSwitch 토글 · DB 복구 · 재배포) — sre 1차 dispatch |
| **P0-OPS-6** | persistence-shared 운영 정합 | Outbox Repository wiring 완료(ADR 0088) BUT 운영 monitoring 0 (drain lag·notification retry queue depth 0) | actuator metrics 노출 + Grafana panel + Slack alert (drain lag > 60s) — sre + backend-core 합작 |

### 3-3. P1 (1~2 사이클 내 처리)

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| P1-OPS-1 | batch test | test class 5건만 (DailyCandleBackfillJob·PerformanceJob 등 cron 자체 회귀 부족) | batch 모듈 통합 테스트 보강 — strategy-engine + sre 합작 |
| P1-OPS-2 | domain test | test class 4건만 (도메인 invariant unit test 부족) | port·entity·rule 단위 테스트 — backend-core 1차 |
| P1-OPS-3 | persistence-shared test | test class 2건 (SystemEventLoggerAdapterTest·SystemEventKafkaProducerTest) | Outbox·notification adapter 통합 테스트 — backend-core 1차 |
| P1-OPS-4 | KIS Rate Limit Bucket4j Phase 2 | ADR 0037 amend + spec 발행 완료 (backlog §0-A) + Phase 1 backend-core 합류 + Phase 2 ~20 adapter 합류 대기 | market-data Phase 2 dispatch — backlog §0-A 처리 |
| P1-OPS-5 | KillSwitch + reconcile gap detection | KillSwitch (ADR 0043) + reconcile (ADR 0041) 구축됨 BUT 발사 빈도 dashboard 0 | sre dashboard + 발사 빈도 → reconcile 정밀도 개선 점진 사이클 — sre 1차 |
| P1-OPS-6 | frontend 운영 dashboard | SystemEvent UI 외 운영 dashboard 0 (Kafka lag·Redis hit ratio·KIS rate limit budget·outbox drain lag) | frontend `/ops` 페이지 신설 — frontend + sre 합작 |

### 3-4. P2 (3+ 사이클 후 처리 / 점진 고도화)

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| P2-OPS-1 | backlog trim | open 11+ §(P1-4·P0-A 외) 일부는 운영 단계 무관 — Lab·미래 트랙 의제 | backlog §별 fitness 재평가 + 후순위/폐기 결정 — architect 단독 사이클 |
| P2-OPS-2 | Wave 진행 결정 | W4(AI·MCP) · W5(LWC·Learn) 진입 vs 보류 | 사용자 결재 의무 항목 — 본 ADR §6-1 참조 |
| P2-OPS-3 | ADR open Proposed 9건 정리 | 0076·0077·0078·0082·0087·0088·0089·0090·0093 Proposed | Phase 1 머지 후 Accepted 격상 — architect 사이클 |
| P2-OPS-4 | docs/operations runbook 확장 | P0-OPS-5 5 시나리오 후 — 분기별 갱신 의제 | sre 분기 사이클 |
| P2-OPS-5 | multi-tenant 인증 | OAuth2/JWT post-MVP 슬롯 (master §2 부수 영역) | 실서비스 진입 시 — 별도 ADR |
| P2-OPS-6 | TimescaleDB 전환 | 일봉 ≥1억 행 도달 시 (master §2 부수 영역) | 데이터 임계 도달 시 — 별도 ADR |

---

## 4. 운영 단계 transition plan (7 단계)

### 4-1. mindset 전환 LOCK (사용자 명시)

> "이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해"

| 항목 | 개발 단계 mindset | 운영 단계 mindset |
|------|------------------|------------------|
| PR 1건의 가치 | "트랙 진입·기능 완성" | **"안정성 유지 또는 개선"** |
| 사이클 단위 | "PR 묶음" | **"incident + 주간 운영"** |
| 첫 질문 | "동작하는가" | **"SLA 충족하는가 + 롤백 가능한가"** |
| sub-agent 가동 | spec/plan 후 병렬 | **on-demand + sre 1순위** |
| dogfooding evidence | "기능 발견" | **"SLO 위반 발견 + runbook 갱신"** |

### 4-2. 운영 헌법 §9 신설 (architect 발행 의무 — 본 ADR Accepted 후 별도 PR)

본 ADR Accepted 격상 후 `_workspace/arch/agent-charter.md` §9 신설:

```
## §9. 운영 단계 룰 (2026-05-21 ADR 0094 신설)

### 9-1. SLO/SLA 의무
- 자동매매 시그널 emit ~ 주문 발사 지연 P95 < 5초 (P99 < 10초)
- KIS WS 연결률 (장중 09:00~15:30) > 99%
- Outbox drain lag P95 < 30초
- Redis hit ratio > 95%
- DB connection pool 활용률 < 80%
- 위반 시 SystemEvent P1 자동 발사 + Slack alert (ADR 0044 reuse)

### 9-2. 변경 관리 의무
- 모든 PR diff > 50 LOC 또는 자본 path 직결 시 rollback plan 본문 명시 의무
- canary 배포: dev → staging → REAL (자동매매 진입 전 30분 staging dwell)
- 사용자 결재 가시화: PR description "운영 영향 매트릭스" 섹션 의무 (SLO 영향·예상 alert·롤백 단계 3건)

### 9-3. on-call rotation
- 1차: 사용자(최고 관리자) — KillSwitch 토글 권한 보유
- 2차: sre sub-agent — incident classification + runbook 적용 + escalation
- 3차: architect + 영역 sub-agent — root cause + ADR 발행

### 9-4. 정기 backup·DR
- postgres pg_dump cron 매일 03:00 KST + 7일 retention
- Redis snapshot 매일 03:30 KST + 3일 retention
- 분기별 DR drill (사용자 결재 의무)

### 9-5. 신규 ADR 양식 (운영 단계)
모든 신규 ADR §결과 직전에 다음 3 슬롯 의무 추가:
1. **SLO 영향** — 본 결정이 §9-1 SLO에 미치는 영향 (개선/유지/저하 + 수치 변동)
2. **rollback plan** — 본 결정이 운영에 부적합 발견 시 즉시 되돌리는 절차 (코드 revert · DB 마이그레이션 down · 설정 toggle 등)
3. **관측 매트릭스** — 본 결정 결과를 운영 단계에서 측정하는 metric 또는 alert (Prometheus query · Grafana panel · Slack alert)
```

### 4-3. release 정책 갱신 (사용자 메모리 `feedback_release_policy_patch_first` 정합)

| 버전 | 의미 |
|------|------|
| v3.7.0 | W3 종착 + chart-curator 영입 + 안정성 누적 (현재 baseline) |
| v3.8.0 | **운영 단계 1차 baseline — sre 영입 + observability stack + runbook** |
| v3.8.x patch | 운영 hotfix + SLO 위반 대응 + P0/P1 gap 해소 |
| v3.9.0 | W4 진입 결재 시 — AI·MCP 옵션 트랙 마감 |
| v4.0.0 | 실서비스 cut (`MAGICJAR_MODE=REAL` 정식 진입 + multi-tenant 인증) |

### 4-4. 항목별 결정 매트릭스 (W4·W5·backlog)

| 항목 | 본 ADR 이후 | 사유 |
|------|------------|------|
| W4 T-AI (AI 옵션 보조) | **보류 — 사용자 결재 시 진입** | LLM 비용 + 운영 단계 1순위는 안정성 |
| W4 T-MCP (MCP 서버 노출) | **보류 — 사용자 결재 시 진입** | 외부 노출 → 인증·rate limit 필요 → 운영 안정 baseline 후 |
| W5 T-LWC (LWC 확장) | **진행 OK** (chart-curator 흡수 후 frontend 단일 dispatch) | 차트 점진 개선은 운영 단계와 양립 |
| W5 T-LEARN (학습/검증) | **부분 진행** (운영 단계 사용자 학습 자료 우선) | mindset 전환과 정합 |
| backlog §0-A persistence-shared Phase 2~3 | **즉시 처리** | P0 자본 흐름 차단 해소 |
| backlog §0 P1-4 Rate Limit Phase 2 | **즉시 처리** | P0 KIS 차단 risk 해소 |
| backlog §1·§5·§7·§8 etc | **architect 사이클 trim** | 운영 무관 평가 + 후순위/폐기 |

### 4-5. 점진 고도화 우선순위 (운영 데이터 기반)

운영 단계 진입 후 의사결정 lens — **"발사 빈도·alert 횟수·SLO 위반 빈도"** 기반:

1. KillSwitch 발사 빈도 ≥ 주 1회 → reconcile 정밀도 개선 trigger
2. KIS rate limit 위반 시도 ≥ 주 1회 → Bucket4j Phase 3 (Circuit Breaker) trigger
3. Outbox drain lag P95 > 30초 → consumer 모듈 캐패시티 plan
4. STOMP backfill 호출 빈도 → 사용자 UX 개선
5. SystemEvent ERROR 발사 패턴 → root cause ADR 발행

### 4-6. sub-agent reshuffle 적용 절차 (본 ADR Accepted 후)

1. **architect 단독 PR (본 PR)** — ADR 0094 Proposed + 본 master plan 발행
2. **사용자 결재** — §6-1 결재 의무 항목 6건 응답 의무
3. **architect 후속 PR** (Accepted 격상 후) — 다음 정합 동시:
   - `.claude/agents/qa.md` 삭제 (또는 archive)
   - `.claude/agents/ai-engineer.md` archive (재영입 path 보존 — W4 진입 시 복구 가능)
   - `.claude/agents/chart-curator.md` archive (페르소나·책임 이관 정합 본 PR에 흡수)
   - `.claude/agents/sre.md` 신규 발행 (페르소나 본문 + 책임 + 트리거 + 페르소나 매트릭스)
   - `.claude/agents/frontend.md` 페르소나 amend (보수 "안정빌드" 흡수 — chart-curator 페르소나 보존)
   - `.claude/agents/market-data.md` 페르소나 amend (보수 "안정빌드" 흡수 + 캔들 SLA 정합성 책임 흡수)
   - `_workspace/arch/agent-charter.md` §9 신설 (본 ADR §4-2)
   - `.claude/skills/magicjar-orchestrator/SKILL.md` 6 actor 갱신
   - `docs/architecture/master-architecture.md` §10 SoT 정합
   - ADR 0080 status → "Amended by ADR 0094" (8 → 6 reshuffle 정합)
   - ADR 0085 status → "Responsibilities migrated to frontend·market-data per ADR 0094 (페르소나 보존)"
   - `CLAUDE.md` 운영 단계 진입 본문 amend
   - `CHANGELOG.md` row 추가

### 4-7. orchestrator dispatch 매트릭스 갱신 (운영 단계)

| 트리거 | 1순위 actor | 2순위 actor |
|--------|------------|------------|
| 사고 발생 (SystemEvent ERROR + Slack alert) | **sre** | architect (root cause ADR) |
| SLO 위반 (1주 누적) | **sre** | strategy-engine (자본 path) 또는 market-data (KIS API) |
| dogfooding 결함 (사용자 보고) | 영역 sub-agent | sre (운영 영향 평가) |
| 신규 트랙 진입 (W4·W5) | architect (spec) | 영역 sub-agent + sre (관측 plan) |
| 정기 운영 점검 (주간) | sre | architect (헌법 amend) |
| 백로그 trim | architect | — |

---

## 5. 트레이드오프

### 5-1. 채택 (선택지)

- **sub-agent 6 hierarchy** — 8 → 6 (qa·ai-engineer 해고 + chart-curator 흡수 + sre 신규)
- **운영 헌법 §9 신설** — 5 룰 (SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식)
- **release 정책 v3.8.0 운영 단계 baseline** — 사용자 메모리 정합
- **W4·W5 부분 보류** — 사용자 결재 의무
- **backlog trim 의제 사이클** — architect 단독

### 5-2. 거부 옵션 (왜 안 했는가)

| 옵션 | 거부 사유 |
|------|----------|
| 8 hierarchy 유지 + sre 추가만 (9 actor) | 운영 부담 ↑ + 8 hierarchy LOCK 이미 운영 부담 한계(ADR 0085 §1-2 evidence) + qa·ai-engineer 가동률 (1·19건) 너무 낮음 |
| qa 보존 + 정책만 갱신 | past 7 weeks 1건 dispatch — 정책 갱신해도 가동 불가능 + "QA 게이트 배제" 룰과 모순 |
| ai-engineer 해고 (재영입 path 0) | W4 진입 시 즉시 필요 + 본 ADR §2-2-A "보류 카드" 명시로 path 보존 |
| chart-curator 페르소나 폐기 (책임만 이관) | 보수적 "안정빌드" 페르소나는 frontend·market-data 자본 path lens로 의의 보존 가치 — ADR 0085 본문 자산 |
| sre 신규 영입 X (sre 책임을 architect가 흡수) | architect 의제 폭증(현재 295건 1순위) + 운영 단계 1순위 actor 부재 → 사고 대응 즉시성 부족 |

---

## 6. 사용자 결재 의무 항목 (최고 관리자 응답 필요)

본 ADR Accepted 격상 + 후속 적용 PR 발행 전 사용자(최고 관리자) 결재 의무 항목 6건:

### 6-1. 결재 카드 (Yes/No 또는 옵션 선택)

| # | 항목 | 옵션 | 권장 |
|---|------|------|------|
| **D-1** | sub-agent 8 → 6 reshuffle 채택 | (a) 채택 / (b) 부분 채택(어떤 변경 거부) / (c) 거부 | **(a) 채택** |
| **D-2** | qa 해고 + ai-engineer 보류(archive — 재영입 path 보존) | (a) 채택 / (b) qa만 / (c) 둘 다 보존 | **(a) 채택** |
| **D-3** | chart-curator 흡수 (책임 이관 + 페르소나 보존) | (a) 채택(frontend·market-data 흡수) / (b) 거부(보존) | **(a) 채택** |
| **D-4** | sre 신규 영입 + 페르소나 "운영자" | (a) 채택 / (b) sre 책임 architect 흡수 / (c) 보류 | **(a) 채택** |
| **D-5** | 운영 단계 진입 시점 — v3.8.0 minor cut 채택 (사용자 메모리 `feedback_release_policy_patch_first` 정합) | (a) 채택 / (b) v3.7.x patch 누적 후 v3.8.0 / (c) 즉시 v3.8.0 | **(b) 권장** — 본 ADR Accepted + P0 OPS gap 1차 해소 후 v3.8.0 cut |
| **D-6** | W4 진입 시점 — 운영 단계 baseline 후 결재 의제 | (a) 운영 baseline 후 다음 사이클 / (b) v3.9.x 슬롯 / (c) 무기한 보류 | **(b) 권장** — v3.8.x 운영 안정 누적 후 v3.9.0 minor |

### 6-2. 추가 검토 항목 (선택)

| # | 항목 | 비고 |
|---|------|------|
| O-1 | logback-spring.xml 발행 시 JSON encoder 선택 (logstash-logback-encoder vs spring-boot 기본) | sre dispatch 시 결정 — architect spec |
| O-2 | Prometheus + Grafana 배포 방식 (docker compose 통합 vs 별도 cluster) | docker compose 통합 권장 (운영 부담 ↓) |
| O-3 | DR drill 분기 주기 | 분기 1회 권장 |
| O-4 | sre 페르소나 "운영자" 본문 amend 의제 (본 ADR §2-2-C 외 추가 본문) | 사용자 명시 시 ADR 0087 패턴 — 페르소나 본문 LOCK |

---

## 7. 결과

### 7-1. 긍정

- **운영 단계 mindset LOCK** — sub-agent·헌법·release 정책 일관 정합
- **8 → 6 hierarchy** — 운영 부담 ↓ + qa·ai-engineer 미가동 정합 + sre 신규로 운영 1순위 actor 확보
- **운영 헌법 §9 신설** — SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식 단일 출처
- **P0 OPS 6건 ID 부여** — sre 1차 dispatch 가능
- **release 정책 v3.8.0 운영 baseline** — 사용자 메모리 정합 + 점진 고도화 경로 명확

### 7-2. 부정 (대응 plan)

- **sre 신규 영입 학습 cost** — architect spec 1회 + 첫 3 사이클 architect cross-review 의무
- **chart-curator 흡수 시 책임 분기 risk** (캔들 SLA가 frontend·market-data 둘에 산재) — ADR 0085 책임 매트릭스 본 ADR §2-2-B 명시 분리 + cross-review 페르소나 매트릭스 LOCK
- **W4 보류 → ai-engineer 재영입 시점 모호** — 본 ADR D-6 응답 후 v3.9.0 슬롯 명시 + ai-engineer archive 본문 보존

### 7-3. 측정 가능 결과 (운영 헌법 §9-5 적용 — 본 ADR이 첫 적용 사례)

- **SLO 영향**: 본 ADR 자체는 메타·하네스 변경 — §9-1 SLO 직접 영향 0. BUT P0 OPS 1·2·3 처리 후 SLO 측정 가능성 0 → 100% 회복
- **rollback plan**: 본 ADR Proposed 단계 → 사용자 결재 거부 시 ADR Withdrawn + agent 정의·헌법 갱신 PR 0건. Accepted 후 적용 PR도 단일 revert 가능(에이전트 정의 + 헌법 본문 추가만)
- **관측 매트릭스**: 본 ADR 적용 후 분기 sre 사이클 사용자 보고 — SLO 위반 빈도·alert noise·incident MTTR·runbook 갱신 횟수

---

## 8. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-21 | ADR 0094 Proposed — 운영 단계 transition master plan 발행 (audit + agent reshuffle + transition + 헌법 §9 신설 plan) | architect (사용자 결재 대기) |
