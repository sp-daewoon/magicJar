# ADR 0094: 운영 단계 transition — 개발 단계 종착 + 운영·점진 고도화 단계 진입 (sub-agent 7 hierarchy + qa Visual 격상 + 운영 헌법 §10 신설)

- 상태: **Accepted (2026-05-21 — 사용자 위임 결재 완료, orchestrator 자율 결정 매트릭스 반영 amend)**
- 작성일: 2026-05-21
- 결정자: 사용자(최고 관리자) — orchestrator 자율 위임 결재 + architect sub-agent 수행
- 영역: 메타·하네스 (§9) + 비전 (§1) + 운영 헌법 §10 신설
- Supersedes (부분): ADR 0080 (sub-agent 7명 → 7명 reshuffle + qa 페르소나 본 ADR amend) · ADR 0085 (chart-curator 해고 + 책임 이관 — 본 ADR이 superseder)
- Amends: ADR 0087 (페르소나 매트릭스 reshuffle 후 재배치 + qa Visual 페르소나 신규) · 운영 헌법 (§10 운영 단계 룰 신설 — 기존 §9 운영 메모는 보존)
- 관련 ADR: 0001(비전) · 0030(SoT 단일 진실원) · 0036(Orchestrator Meta Rules) · 0044(Notification — Slack alert) · 0049(GitHub Actions release 한정) · 0080(전체 opus 통일 — qa 포함) · 0084(P0 주문 path 안정성 baseline) · 0088(persistence-shared)

## 1. 맥락 (사용자 결재 evidence)

### 1-1. 사용자(최고 관리자) 2026-05-21 KST 명시 결재 (2 단계)

**1단계** (audit + 1차 reshuffle 제안):

> "전영역 코드베이스로 검증 절차에 돌입해야해. 이거 하고 나서 에이전트 전격적으로 해고 절차와 투입하는걸 다시 만들 거야. 이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해"

**2단계** (orchestrator 자율 위임 결재 — qa 격상 + sre 거절):

> "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든 스크린샷 등 하면서 테스트 시킬라했어. 오케스트라 믿고 맡길께. 최고 훌륭한 팀원으로 다시 구성해줘"

이 결재는 다음 4 변화를 트리거한다:

1. **전영역 코드베이스 검증** — fitness gap 기준을 "개발 단계" → "운영 단계"로 LOCK 전환
2. **sub-agent reshuffle (1단계 → 2단계 amend)** — 1단계 6 hierarchy 제안 → 2단계 사용자 명시 후 **7 hierarchy + qa Visual 격상**으로 정정
3. **qa의 본질 재정의** — "QA 게이트 배제" 룰은 보존하되 **"품질 헌터" 페르소나로 격상** (Playwright·스크린샷·시각 회귀·E2E·사용자 시나리오 자동 검증)
4. **mindset 전환** — "기능 추가·트랙 진입" → "안정 운영 + 점진 고도화"

### 1-2. orchestrator 자율 결정 매트릭스 (사용자 2단계 위임)

사용자 명시 "오케스트라 믿고 맡길께. 최고 훌륭한 팀원으로 다시 구성해줘" → orchestrator가 자율 결정한 매트릭스:

| 에이전트 | 1단계 제안 | **2단계 최종 결정** | 사유 |
|---------|-----------|-------------------|------|
| architect | 유지 | **유지** | - |
| backend-core | 유지 + market-data 부분 흡수 | **유지 + P0 OPS 6건 인수** | sre 영입 거절 → backend-core가 운영 인프라 흡수 (logback·Actuator·SLO·DR/backup·runbook·persistence-shared monitoring) |
| market-data | 유지 + chart-curator 일부 흡수 | **유지 + 캔들 SLA 인수** | chart-curator 캔들 적재 워커·SLA 정합성 책임 이관 |
| strategy-engine | 유지 | **유지** | - |
| frontend | 유지 + chart-curator 흡수 | **유지 + LWC 인수** | TradingView LWC v4 컴포넌트 책임 이관 |
| **qa** ★ | **해고** (past 7w 1건) | **격상 — Visual QA "품질 헌터"** | 사용자 명시 — Playwright·스크린샷·시각 회귀·E2E·사용자 시나리오 자동 재현 |
| ai-engineer | 보류 (archive) | **유지 (W4 진입 시 가동)** | 사용자 1단계 결재에 명시적 동의 없음 → 안전 default = 보존. W4 진입 시점에 가동 |
| **chart-curator** | 흡수 (페르소나 보존) | **해고** | 캔들 SLA → market-data + LWC → frontend 책임 이관. 페르소나 보존 의제는 적용 후 운영 단계 lens로 재평가 |
| **sre** (영입 제안) | 신규 영입 | **영입 거절** | 사용자 명시 "최고 훌륭한 팀원" → 기존 actor 강화 우선 + 운영 부담 ↑ 회피. P0 OPS 6건 책임은 backend-core 인수 |

**결과**: 8 → **7 hierarchy** (chart-curator 해고 1명 · qa 격상 1명 · ai-engineer 유지 · sre 영입 거절).

### 1-3. 현 시점 객관 evidence (2026-05-21 main commit `28745ed3`)

| 영역 | 현황 | 운영 단계 fitness |
|------|------|------------------|
| JVM 모듈 | 7개 (domain·api·batch·consumer·persistence-shared·indicator-impl·strategy-engine) | ✅ 안정 (ADR 0083·0088·0092 모듈 분리 완료) |
| 코드 LOC | prod 65009 + test 26819 (Kotlin) + frontend 33489 (TS/TSX) | ✅ 충분 (운영 단계 dilution 없음) |
| Flyway migration | 43+ (API 모듈) | ✅ 자료 모델 안정 |
| Test class | 152 (api 77 + consumer 64 + batch 5 + domain 4 + persistence-shared 2) | ⚠️ batch·domain·persistence-shared 부족 + **frontend E2E·시각 회귀 0건** (운영 단계 회귀 위험) |
| ADR | 93 Accepted/Proposed + README | ✅ 결정 traceability 풍부 |
| 운영 헌법 | 9 § (§9 운영 메모 포함) + 정책 5건 (Living Policy) | ✅ 운영 룰 LOCK — 본 ADR이 §10 신설 |
| Wave 진행 | W1·W2·W3 마감 + W4 진입 직전 (사용자 명시 W4 진입 0 LOCK) | — |
| Backlog open | 11+ §(P0-A persistence-shared·P1-4 Rate Limit·Lab 의제 등) | ⚠️ 운영 무관 후순위 trim 의제 |
| sub-agent 분포 (past 7 weeks) | architect 295 / backend-core 227 / orchestrator 173 / frontend 145 / market-data 129 / strategy 34 / chart-curator 32 / ai-engineer 19 / qa 1 | ⚠️ chart-curator·qa 단일 책임 owner 부재 + qa 격상 후 dispatch 빈도 회복 예상 |

**핵심 evidence 인용**:
- `git log --since=2026-04-01` 작가 분포: qa 1건 / ai-engineer 19건 → **qa 격상으로 dispatch 회복 + ai-engineer는 W4 진입 시 가동**
- ADR 0080 "전체 opus 통일" 결정은 W3 종착 + W4 진입 직전 단계 — 본 ADR 시점에서 7 actor 모두 opus 4.7 LOCK 유지
- `Prometheus` import 0 / `actuator` 의존만 있고 application.yml `management.endpoints` 노출 0 / `logback*.xml` 미발견 — **backend-core가 P0 OPS 1~6 인수**
- `docs/operations/` 2 파일만(`initial-setup.md`·`kis-api-reference.md`) — **backend-core가 runbook 발행 인수**
- **frontend Playwright·시각 회귀 0건** — qa Visual 격상 후 첫 P0 dispatch 대상

### 1-4. 운영 단계의 본질적 변화

| 항목 | 개발 단계 (현재 ~v3.7.0) | **운영 단계 (v3.8.0~)** |
|------|------------------------|------------------------|
| 1순위 목표 | 기능 완성 + 트랙 진입 | **안정 운영 + 점진 개선** |
| 변경 빈도 | 한 사이클 5~15 PR | **한 사이클 1~3 PR (hotfix 우선)** |
| 에이전트 가동 | spec/plan + 병렬 dispatch | **on-demand + 1~2 actor 직렬** |
| 시간 단위 | "사이클" (PR 묶음) | **"incident" + "주간 운영"** |
| 관측성 | dogfooding evidence | **SLA·SLO·alert 자동화 (backend-core 책임) + Visual 회귀 자동화 (qa 책임)** |
| 검증 lens | "동작하는가" | **"SLA 충족하는가" + "사고 대응 가능한가" + "사용자 시나리오 회귀 0건인가" (qa)** |
| Wave 진행 | W1·W2·W3·W4·W5 | **W3·W4·W5 보류 권한 (사용자 LOCK)** |
| 백로그 | 누적 OK | **운영 무관 후순위 trim 의무** |

---

## 2. 결정 (3 산출물 통합 + 2단계 amend)

### 2-1. 전영역 코드베이스 audit 결과 (Fitness Gap P0/P1/P2 매트릭스)

본 ADR §3 참조. 직접 코드 grep·git log·SoT 인용 기반.

### 2-2. sub-agent reshuffle (8 → 7 hierarchy + qa Visual 격상)

| 신 모델 | 책임 | 출신 | 분류 |
|---------|------|------|------|
| architect | 시스템 설계·ADR·운영 헌법 amend·SLO/SLA 정의 | 기존 | **유지 + 운영 ADR 양식 추가** |
| backend-core | Spring Boot 도메인·인프라·persistence-shared·KIS adapter + **운영 인프라 단일 owner (logback·Actuator/Prometheus·SLO/SLA·DR/backup·runbook·persistence-shared monitoring)** | 기존 | **유지 + P0 OPS 6건 인수** |
| frontend | React/TS/LWC v4·STOMP UI·운영 dashboard (Symbol·Watchlist·SystemEvent 등) + **TradingView LWC v4 컴포넌트 단일 owner (chart-curator 이관)** | 기존 | **유지 + LWC v4 흡수** |
| market-data | KIS API·시세·뉴스·공시 어댑터 + **캔들 SLA 정합성 단일 owner (chart-curator 캔들 적재 책임 이관)** | 기존 | **유지 + 캔들 SLA 흡수** |
| strategy-engine | DSL·지표·백테스트·시그널·리스크·주문 path | 기존 | **유지** |
| ai-engineer | Spring AI·프롬프트·AI 옵션 보조 (사용자 트리거만) | 기존 | **유지 (W4 진입 시 가동)** |
| **qa (격상)** ★ | **Visual QA "품질 헌터"** — Playwright + 스크린샷 + 시각 회귀 + E2E + 사용자 시나리오 자동 검증 + incident response 시나리오 재현 | 기존 (격상) | **유지 + Visual 격상** |

#### 2-2-A. 해고 (1명) — 본 ADR amend 결정

| 에이전트 | 해고 사유 | 책임 인수 |
|---------|----------|----------|
| **chart-curator** | 8 hierarchy 운영 부담 ↑ + LWC v4와 캔들 SLA는 영역 본질이 frontend(차트 컴포넌트)·market-data(KIS 어댑터 일부) 각각 단일 owner 가능 + 단일 owner 분기 책임 분기 risk 감수보다 명확한 분리가 운영 단계 SLA 측정 단순화 + 사용자 2단계 결재에 chart-curator 보존 명시 없음 (default = 1단계 흡수 제안 채택 → 본 amend에서 "흡수" → "해고+책임 이관"으로 명확화) | TradingView LWC v4 컴포넌트(`frontend/src/components/chart/*`) → **frontend** / 캔들 적재 워커·SLA 정합성(`consumer/.../ws/*KisCandle*` + `api/.../CandleRefreshService.kt`) → **market-data** / `.claude/agents/chart-curator.md` archive |

#### 2-2-B. 유지 + 강화 (2명) — 본 ADR amend 결정

| 에이전트 | 강화 사유 | 강화 본문 |
|---------|----------|----------|
| **backend-core** | sre 영입 거절 사용자 결재 정합 + 운영 단계 핵심 actor 부재 → backend-core가 P0 OPS 6건 단일 owner 책임 인수 + backend-core 페르소나 "리팩토링맨"(진보)는 인프라 개선·DRY·최신 패턴(JSON logback encoder · Micrometer · Prometheus exporter)와 본질적 정합 + past 7w 227건 가동률(architect 295 다음 2위)로 부담 흡수 가능 | P0 OPS 1·2·3·4·5·6 (logback JSON encoder · Actuator/Prometheus 노출 · SLO/SLA + alert routing · DR/backup · runbook 5 시나리오 · persistence-shared monitoring) — backend-core 직접 dispatch + architect spec 동반 |
| **qa (격상)** ★ | 사용자 2단계 명시 "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든 스크린샷 등 하면서 테스트 시킬라했어" → past 7w 1건 dispatch 결함의 root cause는 단순 QA 게이트 룰이 아니라 **"품질 헌터" 페르소나 부재** + Playwright·스크린샷·시각 회귀 도구 매트릭스 부재 → 본 ADR이 격상 design 적용 | "품질 헌터" 페르소나 — 사용자 시점 시나리오 자동 재현 + 스크린샷·비디오·DOM snapshot evidence 수집 + frontend PR 시 자동 dispatch + 정기 주간 SLA Visual 검증 + incident response 자동 재현 |

#### 2-2-C. 보류 (0명 — 1단계 ai-engineer 보류 제안 amend)

ai-engineer 1단계 제안 "보류 (archive)" → 본 ADR amend "**유지 (W4 진입 시 가동)**". 사용자 2단계 결재가 ai-engineer 보류에 명시적 동의 없음 → 안전 default = 보존. archive·해고 0건. W4(T-AI·T-MCP) 진입 시점에 active dispatch 재개.

#### 2-2-D. 영입 거절 (1명 — 1단계 sre 신규 영입 제안 amend)

sre 1단계 제안 "신규 영입" → 본 ADR amend "**영입 거절**". 사용자 2단계 결재 "최고 훌륭한 팀원으로 다시 구성" → 기존 actor 강화 + 운영 부담 ↑ 회피가 의도. P0 OPS 6건 책임은 backend-core 인수.

#### 2-2-E. 페르소나 매트릭스 재배치 (운영 단계 — 7 hierarchy)

| 영역 | 페르소나 |
|------|---------|
| backend-core | **진보 — "리팩토링맨"** (ADR 0087 보존 + 운영 인프라 인수 — JSON logback·Micrometer·Prometheus exporter 본질적 정합) |
| frontend | **보수 — "안정빌드"** (chart-curator 페르소나 흡수 — LWC v4·차트 컴포넌트 자본 path lens 보존) |
| market-data | **보수 — "안정빌드"** (chart-curator 캔들 정합성 lens 흡수 — KIS API·자본 path 직결) |
| strategy-engine | **보수 — "안정빌드"** (DSL·시그널·주문 path 자본 흐름 직결) |
| ai-engineer | **균형 — 보조 분석** (W4 진입 시 가동 — LLM 비용 통제 + 매매 결정권 0) |
| **qa (격상)** ★ | **방어 — "품질 헌터"** (사용자 시점 시나리오 자동 재현 + 스크린샷·비디오 evidence 수집 + 회귀 0 LOCK lens) |
| architect | **균형 — 결정자** (보수+진보+방어 cross-lens) |

cross-review 효과:
- frontend·market-data PR 1건 → backend-core 진보 lens 자동 검토 (리팩토링 제안) + qa 방어 lens 자동 검토 (Visual 회귀 0건 검증)
- strategy-engine PR (자본 path) → backend-core 진보 lens 옵션 + qa 방어 lens 의무 검토 (시그널 → 주문 시나리오 E2E)
- backend-core 진보 PR → market-data 보수 lens + qa 방어 lens 의무 cross-review (운영 인프라 변경 Visual 회귀)

### 2-3. 운영 단계 transition plan (7 단계 + 헌법 §10 신설)

본 ADR §4 참조. mindset 전환 + 신규 룰 + 항목별 결정 매트릭스 + 점진 고도화 우선순위 + 운영 ADR 양식.

### 2-4. qa Visual 격상 design (신규 spec 발행 — 본 ADR amend 신설)

별도 spec 발행: `_workspace/specs/2026-05-21-qa-visual-uplift.md` — Playwright 도구 매트릭스 + 페르소나 본문 + 트리거 매트릭스 + 기본 시나리오 5건 + 셋업 가이드.

### 2-5. chart-curator 책임 이관 매트릭스 (신규 spec 발행 — 본 ADR amend 신설)

별도 spec 발행: `_workspace/specs/2026-05-21-chart-curator-handover.md` — 파일·테스트·디스패치 사이클 명세 + frontend·market-data 인수 절차.

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
| frontend (246 file / 33489 LOC) | A | A | **C** (관측 UI 부족 + SystemEvent 외 dashboard 0 + **Playwright·시각 회귀 0건** ★ qa Visual 격상 1순위 target) | MEDIUM | **B-** |
| docker compose (인프라 3 + 앱 4 + 디버깅 2) | A | A (ADR 0008) | **C** (Prometheus 0 / Grafana 0 / Loki 0) | LOW | **B-** |
| logging | C | — | **D** (logback.xml 미발견 + JSON 로깅 0 + 중앙 집계 0) | HIGH | **D** |

**종합**: 코드 품질 + 아키텍처는 A·B+ 수준. **운영 적합성은 C·D 다발 — P0 갭 다수.**

### 3-2. P0 (즉시 처리 — 운영 단계 진입 차단)

본 ADR amend: **backend-core가 P0 OPS 6건 단일 owner 책임 인수** (sre 영입 거절 사용자 결재 정합).

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| **P0-OPS-1** | logging | logback.xml 미발견 + JSON 구조 로깅 0 + correlation id 0 | logback-spring.xml 발행 (JSON encoder + level threshold + KST timezone + traceId/spanId/userId/orderId MDC) — **backend-core 1차 dispatch** |
| **P0-OPS-2** | observability | Spring Actuator 의존만 추가 + `management.endpoints.web.exposure.include` 0 + Prometheus 0 | application.yml `management.endpoints.web.exposure.include: health,info,metrics,prometheus` + `io.micrometer:micrometer-registry-prometheus` 의존 + docker compose Prometheus + Grafana 추가 — **backend-core 1차 dispatch** |
| **P0-OPS-3** | alerting | Slack alert는 ADR 0044 + NotificationDispatcher 구축됨 BUT 임계치 정의(SLO) 0 + alert routing matrix 0 | SLO/SLA 정의 + alert routing matrix (P1·P2 트리거·channel·on-call) ADR 별도 발행 — **architect spec + backend-core 1차 dispatch** |
| **P0-OPS-4** | DR/backup | postgres pg_dump 자동화 0 + Kafka offset 백업 0 + Redis snapshot 정책 0 | postgres pg_dump cron + Kafka topic backup + Redis snapshot 정책 ADR 별도 발행 — **architect spec + backend-core 1차 dispatch** |
| **P0-OPS-5** | runbook | `docs/operations/` 2 파일만 (initial-setup + kis-api-reference) — incident response·KIS API 차단 대응·KillSwitch 토글 절차 등 0 | `docs/operations/runbook/` 디렉토리 신설 + 5 시나리오 1차 (incident response · KIS 차단 · KillSwitch 토글 · DB 복구 · 재배포) — **backend-core 1차 dispatch** |
| **P0-OPS-6** | persistence-shared 운영 정합 | Outbox Repository wiring 완료(ADR 0088) BUT 운영 monitoring 0 (drain lag·notification retry queue depth 0) | actuator metrics 노출 + Grafana panel + Slack alert (drain lag > 60s) — **backend-core 1차 dispatch (단독)** |

### 3-3. P1 (1~2 사이클 내 처리)

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| P1-OPS-1 | batch test | test class 5건만 (DailyCandleBackfillJob·PerformanceJob 등 cron 자체 회귀 부족) | batch 모듈 통합 테스트 보강 — strategy-engine + **qa Visual E2E 시나리오** |
| P1-OPS-2 | domain test | test class 4건만 (도메인 invariant unit test 부족) | port·entity·rule 단위 테스트 — backend-core 1차 |
| P1-OPS-3 | persistence-shared test | test class 2건 (SystemEventLoggerAdapterTest·SystemEventKafkaProducerTest) | Outbox·notification adapter 통합 테스트 — backend-core 1차 |
| P1-OPS-4 | KIS Rate Limit Bucket4j Phase 2 | ADR 0037 amend + spec 발행 완료 (backlog §0-A) + Phase 1 backend-core 합류 + Phase 2 ~20 adapter 합류 대기 | market-data Phase 2 dispatch — backlog §0-A 처리 |
| P1-OPS-5 | KillSwitch + reconcile gap detection | KillSwitch (ADR 0043) + reconcile (ADR 0041) 구축됨 BUT 발사 빈도 dashboard 0 | backend-core dashboard + 발사 빈도 → reconcile 정밀도 개선 점진 사이클 |
| P1-OPS-6 | frontend 운영 dashboard + **Visual 회귀 자동화** | SystemEvent UI 외 운영 dashboard 0 (Kafka lag·Redis hit ratio·KIS rate limit budget·outbox drain lag) + **Playwright Visual 회귀 0건** ★ | frontend `/ops` 페이지 신설 — frontend + backend-core 합작 + **qa Visual 시나리오 자동 회귀** |

### 3-4. P2 (3+ 사이클 후 처리 / 점진 고도화)

| ID | 영역 | gap | 권장 처리 |
|----|------|-----|----------|
| P2-OPS-1 | backlog trim | open 11+ §(P1-4·P0-A 외) 일부는 운영 단계 무관 — Lab·미래 트랙 의제 | backlog §별 fitness 재평가 + 후순위/폐기 결정 — architect 단독 사이클 |
| P2-OPS-2 | Wave 진행 결정 | W4(AI·MCP) · W5(LWC·Learn) 진입 vs 보류 | 사용자 결재 의무 항목 — 본 ADR §6 참조 |
| P2-OPS-3 | ADR open Proposed 9건 정리 | 0076·0077·0078·0082·0087·0088·0089·0090·0093 Proposed | Phase 1 머지 후 Accepted 격상 — architect 사이클 |
| P2-OPS-4 | docs/operations runbook 확장 | P0-OPS-5 5 시나리오 후 — 분기별 갱신 의제 | backend-core 분기 사이클 |
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
| 첫 질문 | "동작하는가" | **"SLA 충족하는가 + 롤백 가능한가 + Visual 회귀 0건인가"** |
| sub-agent 가동 | spec/plan 후 병렬 | **on-demand + backend-core(OPS) + qa(Visual) 1순위** |
| dogfooding evidence | "기능 발견" | **"SLO 위반 발견 + runbook 갱신 + Visual 회귀 evidence"** |

### 4-2. 운영 헌법 §10 신설 (architect 발행 의무 — 본 PR Phase B 포함)

본 ADR Accepted 격상 후 `_workspace/arch/agent-charter.md` §10 신설 (기존 §9 운영 메모는 그대로 보존):

```
## §10. 운영 단계 룰 (2026-05-21 ADR 0094 신설)

### 10-1. SLO/SLA 의무
- 자동매매 시그널 emit ~ 주문 발사 지연 P95 < 5초 (P99 < 10초)
- KIS WS 연결률 (장중 09:00~15:30) > 99%
- account API p95 < 500ms
- Outbox drain lag P95 < 30초
- Redis hit ratio > 95%
- DB connection pool 활용률 < 80%
- 위반 시 SystemEvent P1 자동 발사 + Slack alert (ADR 0044 reuse)

### 10-2. 변경 관리 의무
- 모든 PR diff > 50 LOC 또는 자본 path 직결 시 rollback plan 본문 명시 의무
- canary 배포: dev → staging → REAL (자동매매 진입 전 30분 staging dwell)
- frontend 핵심 화면(Dashboard·Orders·Positions·Settings) 변경 PR은 qa Visual 시나리오 자동 dispatch
- 사용자 결재 가시화: PR description "운영 영향 매트릭스" 섹션 의무 (SLO 영향·예상 alert·롤백 단계 3건 + Visual 회귀 evidence)

### 10-3. on-call rotation (사용자 단독 운영자 단순화)
- 1차: 사용자(최고 관리자) — KillSwitch 토글 권한 보유 + Slack alert 1차 수신
- 2차: backend-core sub-agent — incident classification + runbook 적용 + DR/backup 가동
- 3차: architect + 영역 sub-agent (market-data·strategy-engine·frontend·qa) — root cause + ADR 발행

### 10-4. 정기 backup·DR
- postgres pg_dump cron 매일 03:00 KST + 7일 retention (backend-core 책임)
- Redis snapshot 매일 03:30 KST + 3일 retention (backend-core 책임)
- 분기별 DR drill (사용자 결재 의무) + qa Visual E2E 시나리오 자동 재현 의무

### 10-5. 신규 ADR 양식 (운영 단계)
모든 신규 ADR §결과 직전에 다음 3 슬롯 의무 추가:
1. **SLO 영향** — 본 결정이 §10-1 SLO에 미치는 영향 (개선/유지/저하 + 수치 변동)
2. **rollback plan** — 본 결정이 운영에 부적합 발견 시 즉시 되돌리는 절차 (코드 revert · DB 마이그레이션 down · 설정 toggle 등)
3. **관측 매트릭스** — 본 결정 결과를 운영 단계에서 측정하는 metric 또는 alert (Prometheus query · Grafana panel · Slack alert) + Visual 회귀 시나리오 (frontend 영향 시)
```

### 4-3. release 정책 갱신 (사용자 메모리 `feedback_release_policy_patch_first` 정합)

| 버전 | 의미 |
|------|------|
| v3.7.0 | W3 종착 + chart-curator 영입 + 안정성 누적 (현재 baseline) |
| v3.8.0 | **운영 단계 1차 baseline — 7 hierarchy 정착 + qa Visual 격상 + P0 OPS 6건 해소 + observability stack + runbook** |
| v3.8.x patch | 운영 hotfix + SLO 위반 대응 + P0/P1 gap 해소 |
| v3.9.0 | W4 진입 결재 시 — AI·MCP 옵션 트랙 마감 (ai-engineer active dispatch 재개) |
| v4.0.0 | 실서비스 cut (`MAGICJAR_MODE=REAL` 정식 진입 + multi-tenant 인증) |

### 4-4. 항목별 결정 매트릭스 (W4·W5·backlog)

| 항목 | 본 ADR 이후 | 사유 |
|------|------------|------|
| W4 T-AI (AI 옵션 보조) | **보류 — 사용자 결재 시 진입** | LLM 비용 + 운영 단계 1순위는 안정성 (ai-engineer는 유지하되 active dispatch 0) |
| W4 T-MCP (MCP 서버 노출) | **보류 — 사용자 결재 시 진입** | 외부 노출 → 인증·rate limit 필요 → 운영 안정 baseline 후 |
| W5 T-LWC (LWC 확장) | **진행 OK** (chart-curator 해고 후 frontend 단일 dispatch) | 차트 점진 개선은 운영 단계와 양립 + qa Visual 회귀 자동 |
| W5 T-LEARN (학습/검증) | **부분 진행** (운영 단계 사용자 학습 자료 우선) | mindset 전환과 정합 |
| backlog §0-A persistence-shared Phase 2~3 | **즉시 처리** | P0 자본 흐름 차단 해소 |
| backlog §0 P1-4 Rate Limit Phase 2 | **즉시 처리** | P0 KIS 차단 risk 해소 |
| backlog §1·§5·§7·§8 etc | **architect 사이클 trim** | 운영 무관 평가 + 후순위/폐기 |

### 4-5. 점진 고도화 우선순위 (운영 데이터 기반)

운영 단계 진입 후 의사결정 lens — **"발사 빈도·alert 횟수·SLO 위반 빈도 + Visual 회귀 빈도"** 기반:

1. KillSwitch 발사 빈도 ≥ 주 1회 → reconcile 정밀도 개선 trigger
2. KIS rate limit 위반 시도 ≥ 주 1회 → Bucket4j Phase 3 (Circuit Breaker) trigger
3. Outbox drain lag P95 > 30초 → consumer 모듈 캐패시티 plan
4. STOMP backfill 호출 빈도 → 사용자 UX 개선
5. SystemEvent ERROR 발사 패턴 → root cause ADR 발행
6. **qa Visual 회귀 detect 빈도 ≥ 주 1회 → frontend 안정성 개선 trigger** (신규)

### 4-6. sub-agent reshuffle 적용 절차 (본 ADR Accepted 후 — 본 PR 통합)

본 PR (Phase A·B 통합)에서 다음 정합 동시 적용:

1. **ADR 0094 amend Accepted** (본 파일)
2. **운영 헌법 §10 신설** (`_workspace/arch/agent-charter.md`)
3. **`.claude/agents/chart-curator.md` archive** (책임 이관 + 페르소나 보존 의제는 운영 단계 lens 재평가)
4. **`.claude/agents/qa.md` Visual 격상 amend** (페르소나 "품질 헌터" + Playwright 도구 매트릭스 + 시나리오 트리거)
5. **`.claude/agents/frontend.md` 페르소나 amend** (보수 "안정빌드" 흡수 — chart-curator LWC v4 책임 이관)
6. **`.claude/agents/market-data.md` 페르소나 amend** (보수 "안정빌드" 흡수 + 캔들 SLA 정합성 책임 인수)
7. **`.claude/agents/backend-core.md` 책임 amend** (P0 OPS 6건 단일 owner — 진보 "리팩토링맨" 정합)
8. **`.claude/skills/magicjar-orchestrator/SKILL.md` 7 actor 매트릭스 갱신** (chart-curator 제거 + qa 격상 페르소나 + 책임 이관 매트릭스)
9. **`docs/architecture/master-architecture.md` §10 SoT 정합** (ADR 0094 row 갱신)
10. **ADR 0080 amend** (7 actor + qa 페르소나 격상 — opus 4.7 LOCK 유지)
11. **ADR 0085 status Superseded by ADR 0094** (chart-curator 해고 + 책임 이관)
12. **ADR 0087 amend** (7 actor 페르소나 매트릭스 — qa Visual "품질 헌터" 신규)
13. **`_workspace/arch/adr/README.md` 인덱스 갱신** (0094 row + status 변경 반영)
14. **`_workspace/specs/2026-05-21-qa-visual-uplift.md` 신규 spec 발행**
15. **`_workspace/specs/2026-05-21-chart-curator-handover.md` 신규 spec 발행**
16. **`CHANGELOG.md` row 추가**
17. **ai-engineer 유지** (해고·archive 0건 — W4 진입 시 가동)

### 4-7. orchestrator dispatch 매트릭스 갱신 (운영 단계 — 7 actor)

| 트리거 | 1순위 actor | 2순위 actor |
|--------|------------|------------|
| 사고 발생 (SystemEvent ERROR + Slack alert) | **backend-core** (runbook 적용) | architect (root cause ADR) + qa (시나리오 재현) |
| SLO 위반 (1주 누적) | **backend-core** (관측 metric) | strategy-engine (자본 path) 또는 market-data (KIS API) |
| dogfooding 결함 (사용자 보고) | 영역 sub-agent | qa (Visual 시나리오 재현) + backend-core (운영 영향 평가) |
| frontend 핵심 화면 변경 | frontend | **qa Visual 시나리오 자동 dispatch** |
| 신규 트랙 진입 (W4·W5) | architect (spec) | 영역 sub-agent + qa (E2E plan) |
| 정기 운영 점검 (주간) | backend-core (관측) + qa (Visual 회귀) | architect (헌법 amend) |
| 백로그 trim | architect | — |

---

## 5. 트레이드오프

### 5-1. 채택 (선택지) — 본 ADR amend 최종

- **sub-agent 7 hierarchy** — 8 → 7 (chart-curator만 해고 + qa Visual 격상 + ai-engineer 유지 + sre 영입 거절)
- **qa Visual "품질 헌터" 격상** — Playwright + 스크린샷 + 시각 회귀 + E2E + 사용자 시나리오 자동 검증
- **backend-core P0 OPS 6건 인수** — sre 영입 거절 사용자 결재 정합
- **운영 헌법 §10 신설** — 5 룰 (SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식)
- **release 정책 v3.8.0 운영 단계 baseline** — 사용자 메모리 정합
- **W4·W5 부분 보류** — 사용자 결재 의무 + ai-engineer 유지로 W4 진입 즉시 가동
- **backlog trim 의제 사이클** — architect 단독

### 5-2. 거부 옵션 (왜 안 했는가) — 본 ADR amend

| 옵션 | 거부 사유 |
|------|----------|
| 8 hierarchy 유지 + sre 추가만 (9 actor) | 운영 부담 ↑ + 8 hierarchy LOCK 이미 운영 부담 한계(ADR 0085 §1-2 evidence) + 사용자 명시 "최고 훌륭한 팀원" → 기존 actor 강화 우선 |
| 6 hierarchy (qa 해고) | **사용자 명시 거절** — "큐에이도 지금 필요한데" → qa 해고 X. Visual 격상으로 본질 재정의 |
| sre 신규 영입 | **사용자 명시 거절** (orchestrator 자율 위임) → backend-core가 운영 인프라 인수. "최고 훌륭한 팀원" 의도 = 기존 actor 강화 |
| ai-engineer 해고 (재영입 path 0) | 사용자 2단계 결재에 ai-engineer 보류·해고 명시 없음 → 안전 default = 유지. W4 진입 시 즉시 가동 |
| chart-curator 보존 (단순 흡수) | 1단계 제안 amend → 책임 분기 risk 회피 위해 명확한 해고 + 책임 이관 (frontend LWC + market-data 캔들 SLA) 선택 |
| qa 단순 페르소나만 추가 (도구 매트릭스 0) | 사용자 명시 "스크린샷 등 하면서 테스트 시킬라했어" → Playwright 도구 매트릭스 의무 + spec 발행 |

---

## 6. 사용자 결재 결과 (최고 관리자 위임 — orchestrator 자율 결정)

### 6-1. 사용자 위임 결재 완료 (1단계 → 2단계 amend)

| # | 1단계 항목 | 1단계 권장 | **2단계 사용자 결재** |
|---|-----------|----------|---------------------|
| **D-1** | sub-agent 8 → 6 reshuffle | (a) 채택 | **amend → 8 → 7** (chart-curator 해고만 채택) |
| **D-2** | qa 해고 + ai-engineer 보류 | (a) 둘 다 채택 | **거절 → qa 격상 + ai-engineer 유지** |
| **D-3** | chart-curator 흡수 | (a) 채택 | **amend → 해고 + 책임 이관** (LWC → frontend, 캔들 SLA → market-data) |
| **D-4** | sre 신규 영입 | (a) 채택 | **거절 → backend-core P0 OPS 6건 인수** |
| **D-5** | 운영 단계 진입 시점 v3.8.0 | (b) P0 OPS 1차 해소 후 cut | **유지** |
| **D-6** | W4 진입 시점 v3.9.0 | (b) v3.8.x 안정 누적 후 | **유지** |
| **D-7 (신규)** | qa Visual 격상 | — | **신규 — Visual QA "품질 헌터"** (Playwright·스크린샷·시각 회귀·E2E) |

### 6-2. 옵션 검토 결과 (1단계 → 2단계)

| # | 항목 | 결과 |
|---|------|------|
| O-1 | logback JSON encoder 선택 (logstash-logback-encoder vs spring-boot 기본) | backend-core P0-OPS-1 dispatch 시 결정 — architect spec |
| O-2 | Prometheus + Grafana 배포 방식 (docker compose 통합 vs 별도 cluster) | docker compose 통합 권장 (운영 부담 ↓) — backend-core P0-OPS-2 dispatch 시 |
| O-3 | DR drill 분기 주기 | 분기 1회 권장 + qa Visual E2E 시나리오 자동 재현 의무 (§10-4) |
| O-4 | sre 페르소나 본문 | **거절 (영입 거절)** |
| O-5 (신규) | qa Visual 도구 매트릭스 (Playwright vs Cypress vs Percy vs Chromatic) | qa Visual spec에서 본문 결정 — `_workspace/specs/2026-05-21-qa-visual-uplift.md` |

---

## 7. 결과

### 7-1. 긍정

- **운영 단계 mindset LOCK** — sub-agent·헌법·release 정책 일관 정합
- **8 → 7 hierarchy** — 운영 부담 ↓ + chart-curator 책임 명확 분리 + qa Visual 격상으로 dispatch 회복 + ai-engineer 유지로 W4 진입 path 보존
- **qa Visual "품질 헌터" 격상** — 사용자 시점 시나리오 자동 재현 + 스크린샷 evidence 수집 (사용자 명시 충실)
- **backend-core P0 OPS 6건 단일 owner** — 운영 인프라 일관 정합 + sre 영입 거절 사용자 결재 정합
- **운영 헌법 §10 신설** — SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식 단일 출처 (기존 §9 운영 메모 보존)
- **P0 OPS 6건 ID 부여** — backend-core 1차 dispatch 가능
- **release 정책 v3.8.0 운영 baseline** — 사용자 메모리 정합 + 점진 고도화 경로 명확

### 7-2. 부정 (대응 plan)

- **backend-core 부담 ↑** (P0 OPS 6건 인수) — 페르소나 "리팩토링맨"(진보) + past 7w 227건 가동률 → 흡수 가능. 부담 누적 시 ADR 추가 발행 검토
- **chart-curator 해고 시 책임 분기 risk** (캔들 SLA가 frontend·market-data 둘에 산재) — 본 ADR §2-2-A 명시 분리 + 별도 spec `2026-05-21-chart-curator-handover.md` 책임 매트릭스 + cross-review 페르소나 매트릭스 LOCK
- **qa Visual 격상 학습 cost** — Playwright 도구 매트릭스 + spec 1회 + 첫 3 사이클 architect cross-review 의무
- **W4 보류 → ai-engineer 가동률 0 누적** — ai-engineer 본문 유지 + W4 진입 시 즉시 active dispatch 재개 path 보존

### 7-3. SLO 영향 (운영 헌법 §10-5 적용 — 본 ADR이 첫 적용 사례)

본 ADR 자체는 메타·하네스 변경 — §10-1 SLO 직접 영향 0. BUT P0 OPS 1·2·3 처리 후 SLO 측정 가능성 0 → 100% 회복. qa Visual 격상 후 Visual 회귀 0건 SLO 신규 추가 가능성 (v3.8.x 결재 후).

### 7-4. rollback plan

본 ADR Accepted 후 적용 PR 단일 revert 가능 — 에이전트 정의(`.claude/agents/*.md`) + 헌법 §10 본문 추가 + 책임 이관 spec 2건 add-only. revert 시:
- chart-curator 영입 ADR 0085 status 복구
- qa Visual 격상 amend revert (페르소나 본문 원상복구)
- backend-core 책임 amend revert
- 운영 헌법 §10 본문 제거
- ai-engineer는 유지 (변경 0건 → revert 불필요)

### 7-5. 관측 매트릭스

본 ADR 적용 후 분기 사이클 사용자 보고:
- SLO 위반 빈도 (backend-core dashboard) · alert noise · incident MTTR · runbook 갱신 횟수
- qa Visual 회귀 detect 빈도 + 시나리오 자동 재현 성공률 (qa dashboard)
- backend-core OPS 부담 (사이클당 OPS PR 비율) — 50% 초과 시 ADR 추가 발행

---

## 8. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-21 | **ADR 0094 amend Accepted — orchestrator 자율 위임 결재 반영** (8 → 7 hierarchy + qa Visual 격상 + ai-engineer 유지 + sre 영입 거절 + backend-core P0 OPS 6건 인수 + 헌법 §10 신설 + spec 2건 신규 발행) | architect (사용자 위임 결재) |
| 2026-05-21 | ADR 0094 Proposed — 운영 단계 transition master plan 발행 (audit + agent reshuffle 1단계 6 hierarchy + transition + 헌법 §9 신설 plan) | architect (사용자 결재 대기) |
