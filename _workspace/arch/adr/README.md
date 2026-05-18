# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record) 목차 — **본 README는 인덱스 전용**, 결정 본문·트레이드오프·sub-agent 매트릭스·spec 인용은 각 ADR 파일에서 읽는다. 변경 이력은 §14 참조.

## §1. 비전 (1건)

| 번호 | 제목 |
|------|------|
| 0001 | 시스템 비전 — 룰 본체 + AI 옵션 + MCP 서버 미래 |

## §2. 기술 스택 + 모듈 구조 (8건)

| 번호 | 제목 |
|------|------|
| 0002 | 언어·프레임워크 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle KTS) |
| 0003 | 증권사 API로 한국투자증권 KIS OpenAPI 선택 |
| 0004 | MSA 모듈 재구성 (6 JVM + frontend) — 2026-05-18 갱신: 5 → 6 (indicator-impl 추가, ADR 0083 정합) |
| 0005 | 하이브리드 런타임 — api / batch / consumer 3 JVM |
| 0006 | PostgreSQL 단일 + 스키마 분리 |
| 0007 | Kafka 이벤트 백본 |
| 0008 | Docker 표준 — 모듈별 Dockerfile + compose 통합 기동 |
| 0054 | pattern-engine 라이브러리 모듈 분리 (domain 순수성 보존 + api·consumer 공유) |
| 0083 | indicator-impl 라이브러리 모듈 분리 (IndicatorSource 모듈 경계 결함 fix — domain 순수성 + api·consumer 공유, ADR 0054 패턴 1:1) — **Accepted (2026-05-19, v3.6.15 cut)** |

## §3. 프론트엔드 (2건)

| 번호 | 제목 |
|------|------|
| 0009 | 프론트엔드 SPA (React + TS + Vite + LWC v4) |
| 0055 | Strategy Builder UI 컴포넌트 스택 (B+C-lite 하이브리드 — 트리 폼 빌더 + textarea DSL 토글, Reactflow·Monaco 거부, 의존 추가 0) |

## §4. 도메인 본체 — 멀티테넌트·매매 (3건)

| 번호 | 제목 |
|------|------|
| 0010 | Multi-tenant 구조 |
| 0011 | 주문 실행기 3-mode + 안전 게이트 |
| 0012 | 매매일지 아키텍처 |

## §5. 시장 데이터·외부 API (13건)

| 번호 | 제목 |
|------|------|
| 0013 | Market Session FSM (KRX 영업일·장중·장외·휴장) |
| 0014 | 시세 데이터 Fallback 3-tier (live → cache → last-day-close) |
| 0015 | REJECTED Audit Trail |
| 0016 | KIS REST/WS HTTP 정밀화 |
| 0017 | Instrument Master 1급 시민 |
| 0018 | Corp Action raw + adjusted |
| 0019 | 캔들 저장 정책 (Watchlist 기반) — Accepted (v3.2.3 §2 매트릭스 NXT/합산 5종 재통합) |
| 0048 | KIS WebSocket 41건 구독 한도 + 우선순위 정책 (slot 1+30+10·priority eviction·polling fallback) |
| 0057 | KIS OpenAPI 단일 데이터 출처 (Yahoo Finance·네이버 등 보조 출처 폐기) — 5m~1h은 1m 합산 가공 |
| 0062 | 5m·15m·30m·90m·1h 합산 가공 — Accepted (백엔드 precomputed + 과거 30 거래일 + 5종 활성화) |
| 0063 | NXT/통합거래 매트릭스 cover (분봉·일봉 시장 분류 코드) — Accepted (UN 통합 + 08:00~20:00 + threshold 700) |
| 0064 | Redis boundary fanout + STOMP 실시간 합산 갱신 — Proposed |
| 0073 | T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — **Superseded by ADR 0075** |
| 0075 | T-NEWS 트랙 진입 spec/plan — Accepted (DART + Naver Open API + Flyway V32·V33 + Kafka 2 토픽 + Redis dedup/rate limit) |
| 0076 | KIS WS NXT/시간외 시세 sync 표준 정합 — Proposed (v3.6.9 Phase 1·2·3 채택 완료) |
| 0077 | **OPSP8996 ERROR 재발 deep dive (v3.6.11 hotfix) — 자격증명 미등록 시나리오 + MOCK type 시세 connect 정합 결함 + retry counter logic 결함 — Proposed (2026-05-17)** |

## §6. 전략·신호·리스크 (10건)

| 번호 | 제목 |
|------|------|
| 0020 | KIS Strategy Builder 흡수 = 명세 port |
| 0021 | Indicator Source 추상화 |
| 0022 | Strategy DSL = .kis.yaml 호환 superset |
| 0023 | Signal Engine = 룰 매칭 only (AI 호출 0) |
| 0024 | Risk Guard chain 6단 |
| 0056 | Backtest Engine Architecture (룩어헤드 slice-by-time 가드 + 세·수수료·슬리피지 모델 + ADR 0047 sandbox + abort quota 50) |
| 0066 | T-SIG 실시간 시그널 엔진 — Accepted (룰 매칭 + Kafka boundary listen + 영속 + STOMP fanout) |
| 0067 | T-RISK 리스크 관리 — Accepted (Signal → Risk Gateway consumer → Order intent 의무 게이트 + 4 도메인 영속 + auto-PAUSE) |
| 0068 | T-O 주문 실행 — **Accepted (2026-05-18 본 트랙 마감 — Phase 1·2·3 합류 종결 + dogfooding 케이스 5 GREEN + KIS WS H0STCNI9 MOCK subscribe emit=OK)** (Risk ALLOWED → KIS REST + WS H0STCNI0/H0STCNI9 체결 추적 + 3 테이블 영속 + 실계좌 3중 게이트) |
| 0071 | T-SC Screener — Accepted (universe × DSL → 후보 + 랭킹 + Redis cache 30m + 옵션 영속 V28) |
| 0072 | T-J + T-PERF 묶음 — Accepted (Trading Journal 영속 + Performance 통계 + Flyway V29·V30 + batch DailyPerformanceJob) |

## §7. 시각화·AI·MCP (3건)

| 번호 | 제목 |
|------|------|
| 0025 | LWC 확장 + 자체 도형/주석/패턴 |
| 0026 | AI 옵션 보조 (사용자 트리거 한정 + 분석 코멘트) |
| 0027 | MCP 서버 미래 노출 |

## §8. Watchlist · 차트 데이터 흐름 (2건)

| 번호 | 제목 |
|------|------|
| 0028 | Watchlist 기반 Ingestion Worker (1~5초 polling) |
| 0029 | 당일 진행 중 캔들 Redis 캐시 |

## §9. 메타·운영·하네스 (9건)

| 번호 | 제목 |
|------|------|
| 0030 | 마스터 아키텍처 = 단일 진실원 (SoT) |
| 0031 | 베이스라인 태그 + release/v* immutable 보존 브랜치 |
| 0032 | Husky v9 git hooks (pre-commit author whitelist + pre-push) |
| 0033 | 단일 worktree + 순차 작업 정책 |
| 0034 | 머지 흐름 단순화 (`agent/* → main`) |
| 0035 | ~~에이전트별 모델 차등 + 에스컬레이션~~ — **Superseded by 0080 (2026-05-18)** |
| 0036 | Orchestrator Meta Rules (git-author + task-file-system + architect-as-orchestrator) |
| 0078 | 내부 자산 추출 전략 — Proposed (재사용 가능한 프로젝트 부트스트랩 템플릿 + Plugin Independence 제약) |
| 0079 | **마스터 시각화 HTML 표준화 (`scripts/master-index.mjs` + `/master-index` slash + release.sh 통합) — Accepted (2026-05-17)** |
| 0080 | **전체 에이전트 Opus 4.7 통일 (ADR 0035 supersede) — Accepted (2026-05-18)** |
| 0081 | **계좌 잔고·예수금 사용자 노출 endpoint + UI (T-ACCT 신규 트랙) — Proposed (2026-05-18 · spec 발행 단계)** |
| 0082 | **사용자 수동 주문 발행 트랙 (T-MAN — 가상계좌 통합테스트 dogfooding + 사용자 최고 관리자 통제) — Proposed (2026-05-18 · spec 발행 단계)** |

## §10. 외부 API 강제 정책 + UI 흐름 (4건)

| 번호 | 제목 |
|------|------|
| 0037 | KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷) |
| 0038 | KIS OAuth 24h 토큰 + Redis 분산락 공통 모듈 |
| 0039 | 종목 마스터 갱신 Cron (KIS 갱신 시각 +5분) |
| 0040 | 관심종목 우선 UI 흐름 (Strategy Builder → 발굴 → Watchlist → 상세) |

## §10-1. 자동매매 Critical Fitness 갭 (3건)

자동매매 본체 정상 작동 의무 — 2026-05-07 architect 감사 발굴 6대 갭 중 Critical 3건.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0041 | Broker Reconciliation Job (KIS 잔고 ↔ 내부 position 정합) — **ADR 0070 묶음 spec 정밀화** | W1·W3 |
| 0042 | Position Bootstrap & Recovery (앱 시작·WS 재연결 시 잔고 복구) | W1 |
| 0043 | KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책) | W3 |

## §10-2. 운영·UX Fitness 갭 (5건)

자동매매 본체 운영 안정성 + 첫 사용자 경험.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0044 | Notification System (Slack webhook 1차 + Kafka 트리거 + outbox fallback) — **ADR 0070 묶음 spec 정밀화** | W3 (T-NOTIFY) |
| 0045 | Graceful Shutdown & In-flight 처리 (SIGTERM · Kafka manual commit · idempotency) | W1 (횡단) |
| 0046 | Onboarding & Credential Registration (테이블 3종 분리 · 마스터 키 단일 예외) | W1 ★ (T-ONBOARD 신설 후보) |
| 0069 | SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 추출 + 미래 확장 구조 | W3 T-O 보강 |
| 0074 | SettingsModal 풀세트 (KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합) — Accepted | W3.5 (T-AI prereq) |

## §10-3. DSL 안전성 (1건)

자동매매 본체 안정성.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0047 | DSL Evaluation Sandbox (정적 검증 + 런타임 timeout/CPU budget + thread pool 격리 + auto-disable) | W2 (T-DSL) · W3 (T-SIG·T-BT) |

## §10-4. 메타·CI/CD·하네스 정합 (5건)

자동매매 본체 운영 속도 + 협업 충돌 회피.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0049 | GitHub Actions = release 트리거 한정 (일상 검증은 pre-push hook 단독) | 횡단 |
| 0050 | CHANGELOG 슬롯 채번룰 (architect 사전 발행 + sub-agent in-place edit) | 횡단 (병렬 디스패치 사이클) |
| 0051 | Spring Data Repository default fun 함정 (회피 패턴) | 횡단 (backend-core·market-data 핵심 risk) |
| 0052 | Baseline Documentation Cycle (skill + slash + 스크립트 4종) | 횡단 (release 사이클·문서화) |
| 0053 | Living Policy Docs (현행 권위 단일 위치 + INDEX·policies/ 분리) | 횡단 (문서 정합·인지 비용·토큰 효율) |

## §11. 운영 규약

- **새 ADR 발행 시** — 본 README + `master-architecture.md` §10 + `tracks-roadmap.md` §3 동시 갱신.
- **Superseded 처리 시** — 본문 상단 헤더 + 본 README 행 supersede 표기. `_archive/` 이동은 v3.3.0 cycle에 일괄.
- **신규 진입 시 후속 ADR 번호** — **0085부터 sequential**. 0082(T-MAN)·0083(indicator-impl)·0084(주문 path 안정성) 점유 완료. 예약 슬롯은 §12 참조.
- **변경 이력 기록 의무** — 의미 있는 ADR 발행·재발행·supersede·예약 해제 시 §14에 한 줄 추가.

## §12. 예약 슬롯 (Reserved)

후속 ADR 발행 시 사용 가능한 예약 번호.

| 번호 | 사유 |
|------|------|
| 0058 | Reserved — T-PAT(57 캔들 패턴 표준) 기 머지 잔재, 추후 본문 ADR로 격상 검토 |
| 0059 | _Closed_ — Screener 표준 후보였으나 ADR 0071에 흡수 완료 (2026-05-14) |
| 0060 | _Closed_ — Performance 표준 후보였으나 ADR 0072에 흡수 완료 (2026-05-14, T-J + T-PERF 묶음) |
| 0065 | Reserved — 신규 트랙·횡단 정책 진입 시 우선 사용 (의도적 갭) |
| ~~0077~~ | _Closed_ — 2026-05-17 v3.6.10 cut 후 OPSP8996 재발 deep dive 발행으로 reserved 슬롯 발동 완료 |

## §13. 후속 예정 (Proposed)

현재 Proposed 상태로 사용자 결정·후속 dispatch 대기 중인 ADR. Accepted/Superseded 전환 시 §14 변경 이력에 기록 + 본 표에서 제거.

| 번호 | 제목 | 대기 사유 |
|------|------|---------|
| 0064 | Redis boundary fanout + STOMP 실시간 합산 갱신 | 사용자 결정 의제 3건 위임 (A·B·C) — architect 권장 A-1+B-1+C-1 |
| 0076 | KIS WS NXT/시간외 시세 sync 표준 정합 | v3.6.9 Phase 1·2·3 채택 완료 — Proposed 상태 유지 (분석 사이클, 후속 코드 변경은 별 트랙) |
| 0077 | OPSP8996 ERROR 재발 deep dive (v3.6.11 hotfix) | 사용자 결정 의제 매트릭스 6건 위임 (G·H·I·J·K·L) — architect 권장 G-1 + H-1 + I-1 + J-1 + K-1 backlog + L-1 단일 PR. market-data 단일 디스패치 대기. |
| 0078 | 내부 자산 추출 전략 | 사용자 명시적 호출 단일 조건 시 시동 — Phase 1~3 분리, Plugin Independence 제약 LOCK |
| 0081 | T-ACCT 계좌 잔고·예수금 사용자 노출 | 3 endpoint(balance·positions·summary) + Dashboard UI + CapitalSnapshotProvider 실제 어댑터(ADR 0067 §7-1 cross-ref) + KIS inquire-balance output2 11 필드 LOCK. spec 발행 단계 — Phase 1·2 backend 머지 후 Accepted 격상 |
| 0082 | T-MAN 사용자 수동 주문 발행 트랙 | `POST /api/orders/manual` + `/orders/new` 페이지 + OrderPreviewModal 이중 confirm + 60s cooldown + 3중 게이트 동일 적용 + ADR 0068 `OrderRouterKafkaListener` 12 step 재사용(자본 path 단일 LOCK) + T-RISK Guard chain 우회(KillSwitch만 재검증) + sentinel signalId=0L · source="MANUAL". v3.6.15 patch cut 후보 — Phase 1(backend-core api/controller) + Phase 2(frontend) 병렬 dispatch 가능 → 머지 후 Accepted 격상 |
| 0084 (P1·P2 잔류) | 주문 path 안정성 P1·P2 후속 트랙 | **P0 4건은 2026-05-19 Accepted 격상 마감 (v3.6.16 cut · §14 변경 이력)**. 잔류 P1(outbox·STOMP 영속·MDC) v3.6.17 + P2(정정·MarketSession) v3.6.18 또는 W4 묶음. Flyway V37·V38 + Kafka 1 추가 토픽(order.modify.intent.v1 옵션). sub-agent 6~10 PR. 사용자 결재 의제 잔류. |

## §14. 변경 이력

본 README 자체 + ADR 발행/supersede 시계열. minor cut 단위로 누적.

| 일자 | 사이클 | 변경 |
|------|--------|------|
| 2026-05-19 | ADR 0083·0084 Accepted 격상 (P0 4건 + indicator-impl 3 결함 마감 evidence) | **ADR 0083 Proposed → Accepted** — indicator-impl 결함 3 P0 마감 evidence (v3.6.15 cut) — backend-core 단일 PR 머지 + api·consumer 양쪽 라이브러리 jar 의존 구동 + 81지표 사용자 노출 200 dogfooding evidence 확보. **ADR 0084 Proposed → Accepted (P0 baseline)** — P0 4건(P0-1 hashkey + P0-2 PENDING_RECONCILE + P0-3 WS race·backfill + P0-4 DLQ) 4 PR 머지 완료 + v3.6.16 cut. P1·P2 후속 잔류(별도 사이클 dispatch · backlog §78 잔류). §13 후속 예정 표에서 0083 row 제거 + 0084 row를 P1·P2 잔류 row로 갱신. (architect) |
| 2026-05-18 | T-MAN 신규 트랙 발행 (v3.6.15 patch prep) | **ADR 0082 Proposed** — 사용자 결함 진단 (최고 관리자 2026-05-18 KST) "주문을 하는 화면이 없어 --;; 잔액조회도" 정합. T-MAN 신규 W3 보강 트랙 — 자동 path(T-O `OrderRouterKafkaListener` 12 step) **0 touch** + 수동 entry point만 추가(`POST /api/orders/manual` + `/orders/new` 페이지). spec `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`. v3.6.15 patch cut 권장. (architect) |
| 2026-05-18 | 결함 3 indicator-impl 모듈 신설 (v3.6.14 patch prep) | **ADR 0083 Proposed** — 사용자 dogfooding evidence "GET /api/indicators?code=066570&name=ema → 400" + 옵션 B 명시. `indicator-impl/` 라이브러리 모듈 신설 + api·consumer 양쪽 의존 + 15 파일 git mv. ADR 0054 패턴 1:1. backend-core 단일 PR 권장. v3.6.14 patch cut 합류. (architect) |
| 2026-05-18 | 주문 path 안정성 종합 (v3.6.16+ patch prep) | **ADR 0084 Proposed** — 외부 코드 리뷰 13 코멘트(중복 제거 11 의제) 흡수. ✅ 2건 충족 + 🟡 5건 부분 + ❌ 4건 미충족. P0(hashkey·PENDING_RECONCILE·WS race·DLQ) v3.6.16 + P1(outbox·STOMP 영속·MDC) v3.6.17 + P2(정정·MarketSession) v3.6.18 또는 W4 묶음. spec `_workspace/specs/2026-05-18-order-path-stability-spec.md`. 운영 코드 0 touch. (architect) |
| 2026-05-18 | T-ACCT 신규 트랙 발행 (v3.6.14 patch prep) | **ADR 0081 Proposed** — 사용자 결함 진단 "지금 잔액조회 예수금 조회 이런게 아예 없는데?" (14:50 KST) 정합. KIS `inquire-balance` output2 11 필드(예수금·평가금·평가손익·자산증감) 사용자 노출 endpoint 3건(`GET /api/account/balance·positions·summary`) + Dashboard UI 격상(`DashboardPlaceholder → DashboardPage` + AccountSummaryCard + HoldingsTable) + CapitalSnapshotProvider 실제 어댑터 교체(ADR 0067 §7-1 cross-ref · POSITION_LIMIT 룰 활성화) + Redis cache `account:balance:{userId}` TTL 30s + api 직접 KIS 호출(consumer 0 touch · 관심사 분리) + 모듈 경계 정합(domain 신규 port `KisBalanceQueryPort` + api 신규 adapter `ApiKisBalanceQueryAdapter`). spec `_workspace/specs/2026-05-18-account-balance-positions-spec.md`. sub-agent dispatch 3 PR(backend-core 2 + frontend 1 + 옵션 1). v3.6.14 patch cut 권장(W3 보강 트랙 — `feedback_release_policy_patch_first` 정합). |
| 2026-05-18 | T-O 본 트랙 마감 (v3.6.13 patch prep) | **ADR 0068 Accepted 격상 (plan 단계 Accepted → 본 트랙 마감 Accepted)** — Phase 2 PR #481(Repository 합류) + Phase 1 B-2 PR #485(OrderExecution Adapter) + Phase 1 B-1 PR #486(KIS WS H0STCNI0/H0STCNI9 wiring) 직렬 머지 종결. dogfooding 케이스 5(CREDENTIAL_NOT_FOUND) GREEN evidence(orderId=2 status=REJECTED reject_reason=REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND) + 통합 빌드 evidence(KIS WS 체결통보 subscribe trId=H0STCNI9 mode=MOCK emit=OK). ADR 0068 §18 신규 § "Accepted 마감 evidence" + §19 변경 이력 shift + 본문 status row 격상. §13 후속 예정 표에서 0068 row 제거. backlog §43 ✅ 처리 완료 마킹 + tracks-roadmap §2 T-O row Accepted mark + CHANGELOG row 동반. 운영 코드 0 touch (문서 only). (architect) |
| 2026-05-18 | v3.6.11 후속 | **ADR 0080 Accepted** — 전체 에이전트 Opus 4.7 통일 (ADR 0035 supersede). 사유: W3 종착 + W4 진입 직전 단계에서 sub-agent 코드 한 줄 수정도 룰 본체·자본 흐름 결합으로 진화 — sonnet 패턴화 추론 risk 회피. backend-core·market-data·frontend·qa 4명 sonnet → opus. 토큰 비용 약 2배 증가 사용자 명시 수용. ADR 0035 status Superseded 표기. |
| 2026-05-17 | v3.6.11 hotfix | **ADR 0077 Proposed** — OPSP8996 ERROR 재발 deep dive (v3.6.10 cut 후 86건/1시간 재발). ADR 0076 6 가설 미발동 시나리오 — 자격증명 미등록 + MOCK type 시세 connect 정합 결함 + retry counter logic 결함. 신규 가설 G·H·I·J + 의제 6건(G·H·I·J·K·L) architect 권장 단일 PR. §12 reserved 슬롯 0077 발동 완료 표기 정합. |
| 2026-05-17 | v3.6.10 후속 | **ADR 0079 Accepted** — 마스터 시각화 HTML 표준화 (`_workspace/master.html` + `scripts/master-index.mjs` + `/master-index` slash + `scripts/release.sh` 통합 + 12 카테고리 두 축 + banner + ADR/spec/backlog 추출 자동화). Phase 1·2·3 PR #461·#462·본 사이클 직렬 머지. brainstorming 흐름 사용자 결정 8회 종합 (orchestrator). |
| 2026-05-17 | v3.6.10 patch | **본 README 큰 정리** — 도입부 슬림화 + §12 분리(예약 슬롯 / 후속 Proposed) + §12 행 paragraph 슬림화 + 0073 중복 §5 단일화 + 0058·0065·0077 placeholder 사유 명시 |
| 2026-05-16 | v3.6.9 후속 | ADR 0078 Proposed — 내부 자산 추출 전략 (§3.5 Plugin Independence 제약 사용자 명시) |
| 2026-05-15 | v3.6.9 patch | ADR 0076 Proposed — KIS WS NXT/시간외 시세 sync 표준 정합 (Phase 1·2·3 채택 완료) |
| 2026-05-15 | v3.6.7 patch | ADR 0075 Accepted — T-NEWS 트랙 진입 spec/plan (DART + Naver + Flyway V32·V33 + Kafka 2 토픽). ADR 0073 supersede |
| 2026-05-14 | v3.6.5 patch | ADR 0074 Accepted (재발행) — SettingsModal 풀세트. 직전 ADR 번호 충돌(0073) 응답으로 0074 재발행, 결정 내용 변동 0 |
| 2026-05-14 | v3.6.5 analysis | ADR 0073 Proposed — T-NEWS feasibility 분석 (analysis only, ADR 0075로 위임) |
| 2026-05-14 | v3.6.4 patch | ADR 0072 Accepted — T-J + T-PERF 묶음 spec/plan (W3 마지막 사이클) |
| 2026-05-14 | v3.6.3 patch | ADR 0071 Accepted — T-SC Screener spec/plan |
| 2026-05-13 | v3.6.2 patch | ADR 0070 Accepted — T-RECON + T-NOTIFY 묶음 spec/plan (ADR 0041·0044 baseline reuse) |
| 2026-05-13 | v3.6.1 patch | ADR 0069 Accepted — SettingsModal vs SetupWizard 책임 분리 |
| 2026-05-13 | v3.6.0 cut | ADR 0068 Proposed — T-O 주문 실행 |
| 2026-05-12 | v3.5.0 cut | ADR 0067 Accepted — T-RISK 리스크 관리 |
| 2026-05-12 | v3.4.0 cut | ADR 0066 Accepted — T-SIG 실시간 시그널 엔진 |
| 2026-05-12 | v3.3.0 plan | ADR 0064 Proposed — Redis boundary fanout + STOMP 실시간 합산 갱신 |
| 2026-05-12 | v3.2.3 doc cut | ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 재통합 갱신 (status 정합, 결정 변동 0) |
| 2026-05-11 | — | ADR 0062·0063 Accepted — 5m·15m·30m·90m·1h 합산 가공 + NXT/통합거래 매트릭스 cover. ADR 0057 KIS-only 채택 |
| 2026-05-10 | — | ADR 0054 추가 — pattern-engine 모듈 분리, ADR 0004 4→5 모듈 갱신 |
| 2026-05-07 | v3.0.1 | ADR 0001~0053 baseline. ADR 0041~0046 fitness 갭 6건 발굴. README 인덱스 체계 확립 |
