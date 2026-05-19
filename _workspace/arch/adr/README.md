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
| 0088 | **persistence-shared 라이브러리 모듈 분리 (Outbox Repository wiring 결함 fix — domain port + adapter 분리 + api·consumer 공유, ADR 0083 패턴 1:1) — Proposed (2026-05-19, Phase 1 backend-core 후 Accepted 격상 예정)** |

## §3. 프론트엔드 (2건)

| 번호 | 제목 |
|------|------|
| 0009 | 프론트엔드 SPA (React + TS + Vite + LWC v4) |
| 0055 | Strategy Builder UI 컴포넌트 스택 (B+C-lite 하이브리드 — 트리 폼 빌더 + textarea DSL 토글, Reactflow·Monaco 거부, 의존 추가 0) |

## §4. 도메인 본체 — 멀티테넌트·매매 (4건)

| 번호 | 제목 |
|------|------|
| 0010 | Multi-tenant 구조 |
| 0011 | 주문 실행기 3-mode + 안전 게이트 |
| 0012 | 매매일지 아키텍처 |
| 0089 | **userId 타입 정합 — backend·frontend cross-cutting string ↔ number 영구 차단 (옵션 A backend·frontend 모두 number 통일) — Proposed (2026-05-19, Phase 1 backend-core + frontend 합작 PR 후 Accepted 격상 예정)** |

## §5. 시장 데이터·외부 API (14건)

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
| 0063 | NXT/통합거래 매트릭스 cover (분봉·일봉 시장 분류 코드) — Accepted (UN 통합 + 08:00~20:00 + threshold 700, ADR 0091로 market-level 분기 보강) |
| 0064 | Redis boundary fanout + STOMP 실시간 합산 갱신 — Proposed |
| 0073 | T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — **Superseded by ADR 0075** |
| 0075 | T-NEWS 트랙 진입 spec/plan — Accepted (DART + Naver Open API + Flyway V32·V33 + Kafka 2 토픽 + Redis dedup/rate limit) |
| 0076 | KIS WS NXT/시간외 시세 sync 표준 정합 — Proposed (v3.6.9 Phase 1·2·3 채택 완료) |
| 0077 | **OPSP8996 ERROR 재발 deep dive (v3.6.11 hotfix) — 자격증명 미등록 시나리오 + MOCK type 시세 connect 정합 결함 + retry counter logic 결함 — Proposed (2026-05-17)** |
| 0090 | **`instrument_status_history` 테이블 신설 — chart-curator §12 4축 active gap detection axis 2·3 시점 정밀도 향상 (거래정지·정리매매·상장폐지 history 추적) — Proposed (2026-05-19, Phase 1 market-data + Phase 2 chart-curator 합작 후 Accepted 격상 예정)** |
| 0091 | **NXT 통합 시장코드 종목 단위 resolver — Accepted (2026-05-20, KOSPI=UN/KOSDAQ=J market-level 분기 폐기 + is_kospi200/is_kosdaq150 allow-list + J fallback)** |

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
| 0081 | **계좌 잔고·예수금 사용자 노출 endpoint + UI (T-ACCT 신규 트랙) — Accepted (2026-05-19 격상 · Phase 1·2·3·4 머지 evidence · v3.6.17 release 포함)** |
| 0082 | **사용자 수동 주문 발행 트랙 (T-MAN — 가상계좌 통합테스트 dogfooding + 사용자 최고 관리자 통제) — Proposed (2026-05-18 · spec 발행 단계)** |
| 0085 | **chart-curator 신규 에이전트 영입 (8번째 sub-agent) — TradingView LWC v4 차트 + 캔들 데이터 SLA·정합성 단일 owner — Accepted (2026-05-19 격상 · T-CC Phase 1 + Phase 2-A 머지 evidence · v3.6.17 release 포함 · 2026-05-19 §Persona amend — 보수적 시니어 백엔드 개발자 "안정빌드")** |
| 0086 | **T-CC Phase 2 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 10건 (신입 chart-curator 보호) — Accepted (2026-05-19 격상 · Phase 2-A 햇징 룰 10건 모두 구현 + 27 단위 테스트 ALL GREEN · v3.6.17 release 포함)** |
| 0087 | **Sub-agent 페르소나 매트릭스 — 보수적 chart-curator ↔ 진보적 backend-core 짝 시스템 — Proposed (2026-05-19, Phase 1 후 Accepted 격상 예정)** |

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
- **신규 진입 시 후속 ADR 번호** — **0091부터 sequential**. 0082(T-MAN)·0083(indicator-impl)·0084(주문 path 안정성)·0085(chart-curator 영입)·0086(T-CC Phase 2 데이터 햇징 룰)·0087(sub-agent 페르소나 매트릭스)·0088(persistence-shared 모듈)·0089(userId 타입 정합)·0090(instrument_status_history) 점유 완료. 예약 슬롯은 §12 참조.
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
| 0082 | T-MAN 사용자 수동 주문 발행 트랙 | `POST /api/orders/manual` + `/orders/new` 페이지 + OrderPreviewModal 이중 confirm + 60s cooldown + 3중 게이트 동일 적용 + ADR 0068 `OrderRouterKafkaListener` 12 step 재사용(자본 path 단일 LOCK) + T-RISK Guard chain 우회(KillSwitch만 재검증) + sentinel signalId=0L · source="MANUAL". v3.6.15 patch cut 후보 — Phase 1(backend-core api/controller) + Phase 2(frontend) 병렬 dispatch 가능 → 머지 후 Accepted 격상 |
| 0084 (P1·P2 잔류) | 주문 path 안정성 P1·P2 후속 트랙 | **P0 4건은 2026-05-19 Accepted 격상 마감 (v3.6.16 cut · §14 변경 이력)**. 잔류 P1(outbox·STOMP 영속·MDC) v3.6.17 + P2(정정·MarketSession) v3.6.18 또는 W4 묶음. Flyway V37·V38 + Kafka 1 추가 토픽(order.modify.intent.v1 옵션). sub-agent 6~10 PR. 사용자 결재 의제 잔류. |
| 0088 | persistence-shared 모듈 (Outbox wiring 결함 fix) | **Proposed (2026-05-19)** — qa cross-validation evidence + backend-core 자가 진단 PR #524·#523 운영 흐름 작동률 0% root cause. consumer ObjectProvider<OrderEventOutboxRepository>·<NotificationOutboxRepository> always empty 결함 (어댑터 api 단독 발행 + consumer→api 의존 0 ADR 0004 LOCK 정합). 옵션 B 채택 — persistence-shared 라이브러리 모듈 신설(ADR 0054·0083 패턴 1:1 — root 직속 Kotlin + Spring Context + JPA Boot main 0, 컨테이너 0). 9 파일 git mv + 패키지 rename + Gradle 4 파일. Flyway 권위 api 단독 유지 LOCK. Phase 1 backend-core 단일 PR ~10 파일 ~200 LOC dispatch 대기. cross-review — 자본 path 직결 chart-curator 1차 + 모듈 경계 backend-core 1차. v3.7.0 minor cut 후보(`feedback_release_policy_patch_first` 정합 — patch 누적 후 W4 마감 시 minor). 자본 흐름 작동률 0% → 100% 회복. |
| 0089 | userId 타입 정합 (backend·frontend cross-cutting) | **Proposed (2026-05-19)** — frontend PR #549 P2-F1 evidence + cross-cutting drift 5+ 영역 (account/order/watchlist=string · journal/performance/screener=number · useOnboardingStore.registeredUserId=number\|null). 옵션 A 채택 — backend·frontend 모두 `number`(Long → JS number) 통일. DB BIGINT → JS number 자연 정합 + MAX_SAFE_INTEGER 안전권 + MCP future spec(ADR 0027) int64 baseline. Phase 1 backend-core DTO 1:1 정합 검증 + Phase 2 frontend 5+ 파일 type 정정 (~50~80 LOC type only logic 0 변경). |
| 0090 | `instrument_status_history` 테이블 (chart-curator §12 4축 axis 2·3 정밀도) | **Proposed (2026-05-19)** — chart-curator PR #554 P2-1·P2-2 evidence + 한국석유 dogfooding cycle cross-ref 잠재 false positive risk. Flyway V42(또는 next 시퀀스) `instrument_status_history(instrument_code, status, effective_from, effective_until, reason, recorded_at)` + 인덱스 2(`(code, from DESC)` + 활성 partial). 운영 path market-data Phase 1 (`InstrumentMasterRefreshJob` cron 보강 + history INSERT/UPDATE + audit_trail `INSTRUMENT_STATUS_CHANGED`) + chart-curator Phase 2 (`CandleGapDetectorAdapter.passesActiveGapAxes()` history lens 통합). KIS endpoint 신규 호출 0 LOCK + AI 호출 0 + mock 합성 0. |
| 0087 | Sub-agent 페르소나 매트릭스 (chart-curator 보수 ↔ backend-core 진보) | **Proposed (2026-05-19)** — 사용자 명시 옵션 A 채택. backend-core 진보적 페르소나(15년차 리팩토링맨) 부여 + chart-curator 보수적 페르소나(ADR 0085 amend)와 짝 매트릭스 LOCK. Phase 1 backend-core 운영 evidence 누적(💡/🔄/✨ 출력 형식 + Kotlin 함수형 스타일 + cross-review 1회 이상 dogfooding) 후 Accepted 격상 예정. 다른 6 sub-agent 페르소나 부여 보류(사용자 결정 의제). |

## §14. 변경 이력

본 README 자체 + ADR 발행/supersede 시계열. minor cut 단위로 누적.

| 일자 | 사이클 | 변경 |
|------|--------|------|
| 2026-05-20 | ADR 0091 신설 — NXT 통합 시장코드 종목 단위 resolver | `KOSPI -> UN` 일괄 분기 결함 정정. KIS `J/NX/UN` 옵션은 유지하되 `UN` 적용 기준을 market-level에서 instrument-level로 보강하고, `UN` 응답 0건 시 `J` fallback으로 KRX 캔들을 보존. consumer/api 캔들 REST resolver와 정책 문서 정합. |
| 2026-05-19 | E chart-curator skill §함정 #8·#10 + 햇징 룰 §14 + ADR 0086 §14 amend (PR #587 regression 영구 차단) | **ADR 0086 Accepted (amend) — 햇징 룰 §14 KIS 호출 한도 cover 의무 추가 (13 → 14 룰)** + **chart-curator SKILL.md §함정 #8 amend (KIS 100건 한도) + §함정 #10 신설 (fix 후 regression 함정) + 햇징 매트릭스 §14 row 추가** — PR #587 chart-curator 진단 보고서 evidence(`RealKisRestAdapter` → `KisRestCandleAdapter` 재작성 시 페이지네이션 algorithm regression). **regression 시퀀스 evidence**: PR 2cbd4e8(2026-05-04 cursor 페이지네이션 검증 구현 + 한국석유 1년 backfill 100봉 → 243봉) → PR 915699b(2026-05-11 재작성 시 algorithm 누락 — 1m만 페이징 유지 + 일/주/월/년봉 단일 호출) → PR #587(2026-05-19 chart-curator 진단 + ASC 정규화만 fix → 페이지네이션 누락 → 빈 DB 재발). **§14 룰 본문 5건**: (1) 호출 한도 cover 의무(KIS-first MCP raw `inquire_daily_itemchartprice.py` docstring 정합 100건 LOCK) (2) cursor 페이지네이션 algorithm 의무(MAX_PAGES 6 + distinctBy boundary dedup + 응답 row 0 break + ASC 정규화 페이지 합산 후 마지막 단계) (3) helper class 분리 의무(예: `KisCursorPaginator`, `CandleSanityChecker` — caller inline 발행 금지 → 후속 재작성 algorithm regression 영구 차단) (4) helper 회귀 test 1건 의무(algorithm 자체 verification — pagination round-trip / sanity reject count / sort idempotence) (5) §함정 가드 cross-ref 매트릭스 의무(caller KDoc inline `// skill §함정 #N` 인용). **§함정 #8 amend**: KIS DESC 응답 정렬 + 100건 한도 함정 통합(룰 §11·§14 cross-ref) + 의무 5건(cursor 페이지네이션 + MAX_PAGES + distinctBy + 0-row break + ASC 정규화 마지막). **§함정 #10 신설**: fix 후 regression 함정(helper class 분리 + 회귀 test 1건 + caller inline 금지 + §함정 가드 KDoc cross-ref). 변경 매트릭스 6건: (1) `.claude/skills/magicjar-chart-curator/SKILL.md` — §함정 #8 amend + §함정 #10 신설 + 햇징 매트릭스 §14 row + 룰 § 헤더 13→14 + 적용 의무 영역 #8 항목 + cross-ref 매트릭스 #14 row (2) `_workspace/arch/adr/0086-t-cc-phase2-auto-backfill-data-hedging.md` — 상태 헤더 §14 추가 + §2-1 매트릭스 §14 row + §2-9 본문 신설(§14 룰 본문 5건 + 역사 evidence 시퀀스 + 구현 의제) + §3 긍정 결과 §14 line + §5 변경 이력 row (3) 본 ADR README §14 변경 이력 row(본 row) (4) `docs/architecture/master-architecture.md` §13 변경 이력 row (5) `CHANGELOG.md` row (6) task 파일. **운영 코드 0 touch** (Kotlin/TS/SQL 0 변경 — ADR amend + skill 본문 + README/master/CHANGELOG only). **자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**. 구현 의제 별도 사이클 위임 (chart-curator·market-data Phase — `KisCursorPaginator` helper class + 회귀 test + 5 parse 함수 합류). (architect, opus 4.7 1M) |
| 2026-05-19 | PR-36 baseline v3.7.0 architect 영역 4 파일 (README + 01-architecture + 06-history-traceability + 07-policy-evolution) | **baseline v3.7.0 init** — `_workspace/baseline/v3.7.0/` architect 영역 4 파일 발행(시스템 점검 + Phase 보강 + 운영 헌법 §1-A-extended·§2 4→6단계 진화 baseline · W4 진입 0). v3.6.0 → v3.7.0 사이 19 patch + 200+ PR + ADR 신규 22건(v3.6.x 16 + v3.7.0 6: 0085~0090) + amend 5건(0037·0085 v3.6.18·v3.6.19 + 0086 v3.6.18·v3.6.19) + Superseded 2건(0035 → 0080 · 0073 → 0075) + JVM 5 → 7 모듈(+ indicator-impl ADR 0083 + persistence-shared ADR 0088) + sub-agent 7 → 8(chart-curator ADR 0085 영입) + 페르소나 매트릭스 2건(chart-curator 보수 ADR 0085 amend + backend-core 진보 ADR 0087) + cross-review 운영 룰 LOCK(자본 path → chart-curator 1차 / 인프라 일반 → backend-core 1차) + 운영 흐름 작동률 0% → 100% 회복(ADR 0088 persistence-shared 모듈 — Outbox wiring 결함 영구 차단). 본 ADR README 변경 0 (architect 영역 4 baseline md 본문만 add-only). master-architecture §13 변경 이력 row 동반. 운영 코드 0 touch + 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. (architect, opus 4.7 1M, PR #N) |
| 2026-05-19 | ADR 0089·0090 신설 + ADR 0085 §2-1-extended amend + 헌법 §1-A-extended 보강 + setup-worktree-config.sh Step 2 발행 (3 묶음 docs only) | **ADR 0089 Proposed** — frontend PR #549 P2-F1 cross-cutting evidence(userId string vs number 영역별 불일치 5+ 영역) + backend·frontend cross-cutting risk → 옵션 A 채택(backend·frontend 모두 number 통일). DB BIGINT → JS number 자연 정합 + MAX_SAFE_INTEGER 안전권 + MCP future spec(ADR 0027) int64 baseline. Phase 1 backend-core DTO 1:1 정합 검증 + Phase 2 frontend 5+ 파일 type 정정 dispatch. **ADR 0090 Proposed** — chart-curator PR #554 P2-1·P2-2 evidence(`instrument_status_history` 미존재 → §12 4축 active gap detection axis 2·3 시점 부재 한계) + 한국석유 dogfooding cycle cross-ref 잠재 false positive risk → 신규 테이블 의제. Flyway V42(또는 next) `instrument_status_history(instrument_code, status, effective_from, effective_until, reason, recorded_at)` + 인덱스 2 + market-data Phase 1 cron 보강(`InstrumentMasterRefreshJob` history INSERT/UPDATE + audit_trail `INSTRUMENT_STATUS_CHANGED`) + chart-curator Phase 2 lens 통합(`CandleGapDetectorAdapter.passesActiveGapAxes()` history at ts). KIS endpoint 신규 호출 0 LOCK + AI 호출 0 + mock 합성 0. **ADR 0085 §2-1-extended amend** — chart-curator PR #554 P2-2 evidence("ADR 0085 §책임 영역 본문 명확화 결함") → 파일 경로별 단일 owner 매트릭스 LOCK: chart-curator 영역(`frontend/src/components/chart/*` + `frontend/src/pages/Symbol/SymbolChart·LightweightChart·IndicatorOverlay·PatternMarker` + `frontend/src/components/backtest/EquityCurveChart` + `frontend/src/features/performance/components/*Chart*`) ↔ frontend 영역(차트 외 일반 UI). 판정 원칙 4건. **헌법 §1-A-extended 보강** — 라운드 3 evidence (`extensions.worktreeConfig=true` 활성 상태에서도 PR #562·#564·#565 라운드 2 dispatch 모두 author 결함 재발) → 필수 worktree-local 발행 의무 추가(`git config --worktree user.email/name` 4 명령 LOCK) + 헬퍼 스크립트 Step 2 신설(`scripts/setup-worktree-config.sh` 기존 모든 worktree에 config.worktree 일괄 발행 idempotent). 운영 코드 0 touch (Kotlin/TS/SQL 0 변경 — ADR 신설 2 + ADR amend 1 + 헌법 + 헬퍼 스크립트 + ADR README + master + CHANGELOG only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. (architect, opus 4.7 1M) |
| 2026-05-19 | ADR 0037 amend — Bucket4j-Redis 단일 bucket LOCK + KisRateLimiterPort domain port spec + EGW00201/HTTP 429 backoff 매트릭스 정밀화 (P1-4 — market-data PR #555 P1-1·P1-2·P2-1 통합) | **ADR 0037 Accepted (amend)** — market-data PR #555 system audit 결과 통합. **§1-1 spec drift evidence** 추가 — ADR §1 LOCK "초당 10건" vs 코드 주석 "20 req/s" 15+ 위치 불일치 + Bucket4j stub DEGRADED 상태(`KisRateLimiterPort` 0 · Bucket4j dep 0 · 직접 webClient 호출 · sleep(50ms) 마진만 · EGW00201 명시 path 1곳 · HTTP 429 명시 path 1곳 · Circuit Breaker 0 · ArchUnit 0). **§2-1 메커니즘 LOCK** — Bucket4j 8.x + Redis backend 단일 bucket 채택(옵션 A) · Resilience4j 거부(분산 미지원 — share 분할 5/3/2 시 사용자 burst 흡수 0 risk). adapter 모듈 위치 옵션 b 권장(persistence-shared 패턴 1:1 재사용 — ADR 0088 정합) 또는 신규 kis-shared 모듈 · 옵션 a(api 단독) Phase 1 backend-core 결정 위임. `KisCallPriority` enum 도입(USER_PATH > INGESTION > BACKFILL) — Phase 1 동일 LOCK + Phase 2 priority queue 활성화. **§2-2 backoff 매트릭스 정밀화** — EGW00201/HTTP 429 1초 fixed backoff + 1회 재시도 → 재시도도 fail 시 `KisRateLimiterPort.onTpsExceeded()` 호출 + 명시 에러 throw(mock 합성 0 ADR 0014 정합) · 연속 5분 50% 이상 fail → Circuit Breaker OPEN 5분 + Slack alert P1(`notification_outbox` reuse ADR 0044) + backfill 큐 자동 pause · OPEN 상태 `acquire()` 즉시 throw `KisCircuitOpenException` · 5분 후 HALF_OPEN 자동 전환. exponential backoff 거부(KIS hard limit이므로 fixed 1초 충분). **§5 잔여 의제 Phase 분리 LOCK** — Phase 1 backend-core 단일 PR(domain port `KisRateLimiterPort` + `KisCallPriority` + `KisRateLimitTimeoutException` + `KisCircuitOpenException` + Bucket4j adapter + Bean wiring + Gradle dep `bucket4j-core:8.10.1`+`bucket4j-redis:8.10.1` + application.yml + 단위 테스트 4건 · ~300 LOC) → Phase 2 market-data 어댑터 통합(~20 어댑터에 `kisRateLimiterPort.acquire()` 합류 + EGW00201/429 처리 + spec drift 정정 "20 req/s"→"초당 10건" + sleep(50ms)→acquire 도입 · ~400 LOC) → Phase 3 Circuit Breaker + ArchUnit/ktlint 빌드 게이트(별도 사이클 backend-core+qa · ~300 LOC). cross-review: 자본 path 직결 → chart-curator 1차 lens(보수 🔴/🟡/🔵) + 모듈 경계 → backend-core 1차 lens(진보 💡/🔄/✨) ADR 0085·0087 페르소나 매트릭스 정합. 변경 매트릭스 6건: (1) `_workspace/arch/adr/0037-kis-rate-limit-enforcement.md` — §1-1 spec drift evidence § 신설 + §2-1·§2-2 amend + §5 잔여 의제 Phase 1·2·3 § 신설 + §6 변경 이력 § 신설 (2) `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md` — 본 spec 9 § 풀세트 신설(컨텍스트·결정 매트릭스 옵션 A·B·C·D·port spec Kotlin 본문·Bean wiring 매트릭스·backoff 매트릭스·spec drift 정정·Phase 분리·의존성/사용자 메모리 정합·검증 절차·rollback·비범위·변경 이력) (3) 본 README §14 변경 이력 row (4) `docs/architecture/master-architecture.md` §10 0037 row amend 마커 + §13 변경 이력 row (5) `_workspace/backlog.md` §1 의제 신규(P1-4 Phase 1 backend-core 대기) (6) `CHANGELOG.md` row. **운영 코드 0 touch** (Kotlin/TS/SQL 0 변경 — ADR amend + spec + ADR README + master + backlog + CHANGELOG only) + **자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**. Phase 1 backend-core dispatch 위임 (architect는 spec 발행까지). (architect, opus 4.7 1M) |
| 2026-05-19 | ADR 0088 신설 — persistence-shared 모듈 spec/plan (Outbox Repository wiring 결함 fix) + 헌법 §1-A-extended 신설 (worktreeConfig=true 의무) | **ADR 0088 Proposed** — qa cross-validation evidence + backend-core 자가 진단 PR #524·#523 운영 흐름 작동률 0% root cause 통합 + 2026-05-19 8 sub-agent system audit 5 evidence(architect·frontend·backend-core·ai-engineer·market-data) 통합 매트릭스. consumer ObjectProvider<OrderEventOutboxRepository>·<NotificationOutboxRepository> always empty 결함 → drain·retry worker 매 schedule cycle log.warn + skip → Kafka `order.requested` 토픽 발행 0건 → 주문 path 운영 흐름 작동률 0%. root cause — port(domain) GREEN + adapter(api/persistence 단독) FAIL + consumer→api 의존 0(ADR 0004 §의존 방향 LOCK 정합) → consumer ComponentScan에 api 패키지 부재 → ObjectProvider always null. ObjectProvider graceful skip 패턴이 모듈 경계 결함을 silent 은폐. 옵션 B 채택(persistence-shared 라이브러리 모듈 신설, root 직속, Kotlin + Spring Context + JPA, Boot main 0, 컨테이너 0). ADR 0054(pattern-engine) + ADR 0083(indicator-impl) 패턴 1:1 재사용(운영 인지 비용 0 — sub-agent + 사용자 학습 완료 evidence v3.6.15 cut). 모듈 매트릭스 7 모듈(6→7 갱신). 자원 매트릭스(어댑터 9 파일 이관 + Gradle 4 파일 변경): (1) `persistence-shared/order/outbox/` — `OrderEventOutboxRepositoryAdapter` + `OrderEventOutboxJpaRepository` + `OrderEventOutboxJpaEntity` (2) `persistence-shared/notification/outbox/` — `NotificationOutboxRepositoryAdapter` + `NotificationOutboxJpaRepository` + `NotificationOutboxJpaEntity` (3) `persistence-shared/stomp/` — `StompEventRepositoryAdapter` + `StompEventJpaRepository` + `StompEventJpaEntity`. 패키지 rename `kr.spdaewoon.magicjar.api.persistence.*` → `kr.spdaewoon.magicjar.persistence.shared.*`. Gradle 4 파일: `settings.gradle.kts` include row + `persistence-shared/build.gradle.kts` 신설 + `api/build.gradle.kts` 의존 row + `consumer/build.gradle.kts` 의존 row. 잔류(api 모듈) — `SystemConfigSlackAdapter`(Settings 모달 종속) + `StompEventPersister`(@MessageMapping 정합) + `StompEventBackfillController`(REST endpoint). Flyway 권위 api 단독 유지 LOCK(ADR 0006 §마이그레이션 정합 — V 시퀀스 충돌 차단). Hexagonal port·adapter·use case 3계층 분리 정석. **헌법 §1-A-extended 신설** — extensions.worktreeConfig=true 의무. root cause(5 sub-agent 동일 evidence) — git default `extensions.worktreeConfig=false` 상태에서 worktree `.git/config`가 main과 공유 → 한 worktree `git config user.email` 호출이 main 흡수 → 다른 worktree·sub-agent 휘발성 전파 → author 결함 PR 10+건 누적(PR #514·#525·#535·#537·#541·#546·#551·#552·#553·#555). 안정성 Cycle 1·2 헌법 §1-A 호출 의무만으로 차단 불가 — config 활성화가 root cause 본질. 신규 헬퍼 `scripts/setup-worktree-config.sh`(main `.git/config`에 `extensions.worktreeConfig=true` 적용 + 검증 GREEN/FAIL 출력). 8 sub-agent 운영 author 정합 영구 LOCK. **PR #556 close evidence** — 본 사이클에 close된 PR #556(T-AI W4 진입 spec)은 사용자 명시 "W4 진입 안 함" 위반으로 자율 close. 산출물(T-AI spec + ADR 0088 슬롯)은 사용자 W4 진입 시점에 재발행 가능 path 보존. **본 ADR 0088 발행으로 0088 슬롯 재점유(persistence-shared)** — 사용자 우선순위 정합(자본 흐름 차단 해소 > W4 진입). Phase 1 backend-core 단일 PR ~10 파일 + ~200 LOC dispatch. cross-review — 자본 path 직결 → chart-curator 1차 lens(보수, 🔴/🟡/🔵) + 모듈 경계 → backend-core 1차 lens(진보, 💡/🔄/✨)(ADR 0085·0087 페르소나 매트릭스 정합). 검증 — `WARN drain worker skip` 0건 LOCK + `WARN retry worker skip` 0건 LOCK + 매수 시그널 1건 issue → outbox row insert → drain 1 cycle ≤30s → SENT 갱신 → Kafka 발행 → consumer OrderRequestedKafkaProducer → OrderFilledKafkaProducer GREEN. 운영 흐름 작동률 **0% → 100% 회복**. v3.7.0 minor cut 후보(`feedback_release_policy_patch_first` v3.7=W4 마감 정합 — 본 P0-A는 patch 누적 후 W4 마감 시 minor). **운영 코드 0 touch**(본 architect spec PR — Kotlin/TS/SQL 0 변경). 변경 매트릭스: (1) `_workspace/arch/agent-charter.md` — §1-A-extended 신설 (P0-C, extensions.worktreeConfig=true 의무 + 헬퍼 scripts/setup-worktree-config.sh 인용 + root cause·검증 방법·역사 evidence 풀세트) (2) `scripts/setup-worktree-config.sh` — 헬퍼 스크립트 신설 + chmod +x (P0-C, `git rev-parse --git-common-dir` main `.git/config` 추적 + idempotent 적용 + 검증 GREEN/FAIL) (3) `_workspace/arch/adr/0088-persistence-shared-module-outbox-wiring.md` — 본 ADR 신설(맥락·대안 5건 매트릭스·결정·결과·사용자 명시 인용·관련 결정·변경 이력) (4) `_workspace/specs/2026-05-19-persistence-shared-module-outbox-wiring-spec.md` — 본 spec 10 § 풀세트 신설(결함 진단·코드 매트릭스·신규 모듈 구조·이관 매트릭스 9파일+Gradle 4파일·dispatch plan·검증 절차·v3.x cut·rollback·모듈 경계 정합 evidence·운영 코드 0 touch LOCK) (5) `_workspace/arch/adr/README.md` — §2 0088 row + §11 후속 sequential 0089 갱신 + §13 후속 예정 row + §14 변경 이력 row (6) `docs/architecture/master-architecture.md` — §10 헤더 카운트 + 본 표 0088 row + §13 변경 이력 row (7) `_workspace/backlog.md` — P0-A 의제 row 추가 + PR #556 close evidence row (8) `CHANGELOG.md` — row 추가. **운영 코드 0 touch** (Kotlin/TS/SQL 0 변경 — 헌법 + spec/ADR + 헬퍼 스크립트 only). **자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**. Phase 1 backend-core dispatch 위임 (architect는 spec 발행까지). (architect, opus 4.7 1M) |
| 2026-05-19 | ADR 0087 신설 — sub-agent 페르소나 매트릭스 (backend-core 진보적 페르소나 부여) | **ADR 0087 Proposed** — 사용자(최고 관리자) 2026-05-19 KST 명시 옵션 A 채택 — backend-core 페르소나 부여(진보적 시니어 백엔드 개발자 "리팩토링맨" · 15년차 · 좌우명 "레거시는 빚이다. 이자가 붙기 전에 갚아라"). chart-curator(보수, ADR 0085 amend) ↔ backend-core(진보, ADR 0087) 짝 페르소나 매트릭스 LOCK. **페르소나 본문**: 역할=시니어 백엔드 개발자 · 경력=15년차(스타트업~엔터프라이즈) · 별명="리팩토링맨" · 좌우명="레거시는 빚이다. 이자가 붙기 전에 갚아라" · **핵심 가치 5건**(코드 품질 최우선·최신 패턴 적용·DRY 원칙·테스트 주도·자동화 지향) · **코드 리뷰 7 lens**(중복 코드·Kotlin 표현·책임 분리·확장성·보일러플레이트·최신 라이브러리·성능 개선) · **선호 패턴 5건**(sealed class/interface·확장 함수·코루틴 비동기·함수형 체이닝·디자인 패턴) · **코드 작성 스타일**(`processItem` `?.let`·`?.takeIf`·`?.also` 함수형 체이닝 예시 + `ProcessResult` sealed interface 예시 사용자 명시 원문 100% 보존) · **피드백 스타일 5 인용**("이 로직이 3곳에서 반복됩니다." / "Kotlin scope 함수 활용 더 간결" / "OCP 위반" / "sealed class 컴파일 타임 안전성" / "이번 기회에 함께 개선") · **출력 형식** 💡 개선 제안/🔄 리팩토링/✨ 모던 패턴 적용 3 등급. **짝 페르소나 매트릭스 LOCK**: chart-curator(안정빌드·보수·🔴/🟡/🔵·require/check/try-catch·자본 path 직결) ↔ backend-core(리팩토링맨·진보·💡/🔄/✨·?.let/?.takeIf/sealed·Spring Boot 뼈대·도메인·인프라). **cross-review 운영 룰**: 자본 path 직결(캔들·주문·시그널·KIS 어댑터) → chart-curator 1차 lens / 인프라 일반(Spring·controller·port·Flyway·DTO) → backend-core 1차 lens. **8 actor hierarchy 유지**(ADR 0080) + 9번째 영입 운영 부담 회피 + backend-core 운영 evidence(PR #510 InfrastructureBeans 모듈화 · #524 AccountSummaryDto 함수형 매핑 · #532 KillSwitchRule strategy pattern 리팩토링 — 개선 지향·최신 패턴 fit). 변경 매트릭스: (1) `.claude/agents/backend-core.md` — frontmatter `description` 갱신 + 본문 §페르소나 § 신설(사용자 명시 원문 100% 보존 포함 Kotlin `processItem` 함수형 체이닝 + `ProcessResult` sealed interface 예시 + 짝 페르소나 매트릭스 표) (2) `_workspace/arch/adr/0087-sub-agent-persona-matrix-conservative-progressive.md` — 본 ADR 신설(7 § Context·Decision·Result·Alternatives·사용자 명시 인용·관련 결정·변경 이력) (3) `_workspace/arch/adr/README.md` — §9 0087 row + §11 후속 sequential 0088 갱신 + §13 후속 예정 row + §14 변경 이력 row (4) `docs/architecture/master-architecture.md` — §10 헤더 카운트 + 본 표 0087 row + §13 변경 이력 row (5) `CHANGELOG.md` — row 추가. **운영 코드 0 touch** (Kotlin/TS/SQL 0 변경 — 페르소나 정합 docs only). **자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**. 다른 6 sub-agent(architect·market-data·strategy-engine·ai-engineer·frontend·qa) 페르소나 부여 보류(사용자 결정 의제). Phase 1 backend-core 운영 evidence 누적 후 Accepted 격상. (architect, opus 4.7 1M) |
| 2026-05-19 | ADR 0085 amend — chart-curator 페르소나 재정의 (신입 → 보수적 시니어 백엔드 개발자 "안정빌드") | **ADR 0085 Accepted (amend)** — 사용자(최고 관리자) 2026-05-19 KST 명시. 영입 직후 첫 2 사이클 결함 5건 누적 evidence(PR #514 commit author attribution · PR #525 audit_trail JSON syntax error · PR #525 KillSwitch FK violation · PR #535 §12 "5+ 영업일 임의 임계치" 잘못된 제안 · PR #537·#541 author 재발) → 신입 자율 추론 부작용 → 보수적 페르소나 LOCK으로 root cause 영구 차단. chart-curator는 자본 흐름 직결 single owner이므로 보수적 페르소나(15년차 베테랑 "안정빌드" · 좌우명 "동작하는 코드를 함부로 건드리지 마라" · 핵심 가치 5건: 안정성 최우선·검증된 패턴·하위호환성·점진적 변경·방어적 프로그래밍 · 코드 리뷰 7 lens · 경계 패턴 5건 · Kotlin 코드 스타일 require/check/try-catch/logger · 출력 형식 🔴/🟡/🔵 3 등급)가 본질적으로 합리. 변경 매트릭스: (1) `.claude/agents/chart-curator.md` — frontmatter `description` 갱신 + 본문 §페르소나 § 신설(사용자 명시 원문 100% 보존 포함 Kotlin `processItem` 예시) (2) `.claude/skills/magicjar-chart-curator/SKILL.md` — §페르소나 § 신설(매트릭스 § 직전) + 데이터 햇징 룰 § 헤더에 페르소나 cross-ref 추가(13 룰 = 페르소나 운영 baseline) (3) `_workspace/arch/adr/0085-chart-curator-agent-introduction.md` — §2-2-1 §페르소나 신설 + §7 변경 이력 row. **운영 코드 0 touch** (페르소나 정합 docs only). (architect) |
| 2026-05-19 | ADR 0086 amend — 햇징 룰 §11·§12·§13 추가 (10 → 13 룰) | **ADR 0086 Accepted (amend)** — 한국석유(004090) PR #535 dogfooding evidence + 사용자(최고 관리자) 2026-05-19 KST 명시 정합 룰 추가. **§11 KIS raw 사전 ASC 정규화** — adapter layer parse 함수 반환 직전 `.sortedBy { it.ts.epochSeconds }` 의무. evidence: KIS `inquire-daily-itemchartprice` DESC 응답 + `CandleBackfillWorker.sanityCheck` ASC monotonic reject 99건 → insertedCount=0 → 5개월 갭 누적. **§12 Active gap detection (4축 교차 검증)** — 사용자 명시 인용 "갭 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation 이거 뭐야? 우리 정책이 있었어??? 실제 장이 열렸던 날과 아니였던 날을 관리하고, 그 장중이였는데 거래중지는 특별한 이력이없는데 데이터가 없는걸 찾아내는 검증 룰이 있어야지 않아?" 정합. 4축: (1) `market_calendar.is_business_day=TRUE` (ADR 0013) (2) `instrument.trading_halt=FALSE` 또는 history 부재 (3) `instrument.liquidation=FALSE` (4) `instrument.listed_at <= ts`. 4축 모두 TRUE인데 candle row 부재 = 명확한 결함 → 자동 backfill 의무 + `CANDLE_GAP_DETECTED` audit. §6 passive 가드와 대비 — §12는 active "이 조건인데 없으면 결함". chart-curator 신입 잘못된 "5+ 영업일 임의 임계치" 영구 차단. **§13 LWC chart instance state lift** — TradingView LWC v4 chart instance `useRef` 직접 props 전달 금지 + `useState` lift LOCK. React 18 StrictMode 이중 mount + ref mutation re-render 미발생 → 자식 useEffect cleanup이 disposed chart에 도달 race → `Uncaught Error: Object is disposed`. evidence: 한국석유 PR #535 결함 #1 (`SymbolChart.tsx` line 99·105 ref → props). chart-curator SKILL.md 매트릭스 13 룰로 갱신 + 함정 가드 §7·§8·§9 cross-ref row 추가. master-architecture §10 본 표 ADR 0086 amend 마커 + §13 변경 이력 row. backlog §79 Phase 2-B 의제에 §11·§12·§13 구현 매트릭스 추가. 운영 코드 0 touch (ADR + skill 본문만 + master·README·backlog 정합 갱신). (architect, opus 4.7 1M) |
| 2026-05-19 | ADR 0081·0085·0086 Accepted 격상 + 헌법 §2 worktree 잔재 root cause fix | **ADR 0081 Proposed → Accepted** — T-ACCT 4 Phase 마감 evidence(PR #492 backend `AccountQueryService` + KIS 어댑터 Phase 1·2 + PR #499 `KisCapitalSnapshotProviderAdapter` Phase 3 + PR #509 frontend Dashboard 격상 DashboardPage + AccountSummaryCard + HoldingsTable + BalanceCard Phase 4 · v3.6.17 release 포함). **ADR 0085 Proposed → Accepted** — T-CC Phase 1 dogfooding fix(PR #514) + Phase 2-A 햇징 룰 10건 LOCK(PR #525) · v3.6.17 release 포함 · chart-curator skill 데이터 햇징 § 신설 영구 보호. **ADR 0086 Proposed → Accepted** — Phase 2-A 햇징 룰 10건 모두 구현 + 27 단위 테스트 ALL GREEN(PR #525) · `CandleGapDetectorPort/Adapter` + `CandleBackfillWorker` + `CandleBackfillAdminController` + Flyway V39 `candle.backfill_batch_id` add-only + Phase 1 1 line edit 통합 · 한국석유 dogfooding cycle 검증 path 확보 · v3.6.17 release 포함. **헌법 §2 4단계 강화** — 사용자 명시 비판(2026-05-19 KST) "지금 또 헌법 무시한거발견했다 !!! /Users/ak-song/magic_ch1/.claude/worktrees 또 잔재가 엄청많고 로컬에도 에이전트 브랜치 잔뜩 있어. 작업이 다 끝났다고 하면서 남아있으면 내가 오해하잖아" 정합. root cause — `git worktree remove`는 git 추적 파일만 제거 → `.gradle/`·`.idea/`·`node_modules/` 캐시는 빈 wrapper 디렉토리로 남음 / Agent isolation 도구가 `worktree-agent-<id>` ref 별도 생성 → 헌법 §2 4단계 "agent/* 로컬 브랜치 삭제"에서 빠짐. §2-3 빈 wrapper rmdir 명시 + §2-4 worktree-agent-* force delete 명시 + §2-5 사이클 시작 자동 점검 4 항목 신설(task 파일·worktree list·빈 wrapper·임시 브랜치). magicjar-orchestrator SKILL.md 정합 갱신 동반. master-architecture §10 헤더 카운트 + 본 표 0081·0085·0086 row Accepted 마커 + §10 후속 예정 표 0081·0085·0086 row 제거 + §13 변경 이력 row 추가. 운영 코드 0 touch (status 격상 + 헌법 문서 갱신만). (architect) |
| 2026-05-19 | T-CC Phase 2 spec + 데이터 햇징 룰 매트릭스 (chart-curator 신입 보호) | **ADR 0086 Proposed** — T-CC Phase 1 마감(PR #514 — `LastCandleAtMonitor` + `LastCandleAtBootstrapRunner` + admin endpoint) 후속 자동 backfill 워커 spec + 데이터 햇징 룰 매트릭스 10건 LOCK. 사용자 명시(2026-05-19 KST 최고 관리자) "신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해. 아키텍처야 신입한테 위 내용 잘 상기시켜서 진행해" 정합 — chart-curator 신입 보호 룰 사전 LOCK. 10 룰(이중 검증·dry-run·rollback·idempotency·source-of-truth·갭 false positive/negative·재시도+KillSwitch·audit_trail·운영자 우회). 신규 4 컴포넌트(`CandleGapDetectorPort`·`CandleGapDetectorAdapter`·`CandleBackfillWorker`·`CandleBackfillAdminController`) + Flyway V39 micro `candle.backfill_batch_id VARCHAR(96) NULL` add-only(인덱스 0) + Phase 1 `LastCandleAtMonitor.checkAllCodes()` 1 line edit(`coldStartExecutor.submit` → `candleBackfillWorker.submit` chart-curator 자율 결정). 갭 검출 알고리즘 LOCK — 영업일 캘린더(ADR 0013) `BusinessDayCalendar.isBusinessDay()` 기반 expected sequence(30 거래일 windowDays LOCK) + DB actual sequence diff(`HashSet<Instant>` lookup O(1)). audit_trail 5 path 의무(`CANDLE_BACKFILL_START/SUCCESS/FAIL/SKIPPED/ROLLBACK`). KillSwitch 트리거 — 전체 backfill failure 시 자동매매 중지(자본 보호) / 부분 failure 시 warn + Slack notice. KIS endpoint 신규 호출 0 LOCK + AI 호출 0 LOCK + 합성·가공 적재 0 LOCK + 자본 영향 0(데이터 정합 보강만). chart-curator skill `_workspace/.claude/skills/magicjar-chart-curator/SKILL.md` § "데이터 햇징 룰 매트릭스" 신설(영구 보호 — ADR 0085 single owner 책임 귀속). spec 본문: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`. backlog §79 Phase 2 row 갱신(spec·ADR 0086 ref + 상태 🚧). dispatch — chart-curator 단일 PR(~835 LOC) Phase 2-A(domain port + 어댑터 + 워커 + admin + Flyway + Phase 1 1 line + unit test 10건) + Phase 2-B(Slack·audit integration 검증 — chart-curator 자율 결정). v3.6.18 patch cut 권장(ADR 0084 P1-3 묶음 — 영역 분리 병렬 가능 · `feedback_release_policy_patch_first` + `feedback_dev_priority_qa_batch` 정합). 운영 코드 0 touch (spec 사이클). (architect, opus 4.7 1M) |
| 2026-05-19 | chart-curator 신규 에이전트 영입 (8번째 sub-agent) | **ADR 0085 Proposed** — 사용자 dogfooding evidence(한국석유 004090 일봉 last=2025-12-24 ≈5개월 stale vs 1분봉 last=2026-05-18 15:19 정상) + 3 영역(market-data·strategy-engine·frontend) 교차 점유로 인한 결함 책임 분기 → 8번째 sub-agent chart-curator 영입. TradingView LWC v4 차트 컴포넌트(`frontend/src/components/chart/*`) + 캔들 layer(적재 워커·갭 검출·자동 backfill·MTF 정합성·corporate action adjustment) 단일 owner. agent-charter.md §0·§1-A·§8 갱신 + magicjar-orchestrator SKILL 8 actor + .claude/agents/chart-curator.md + magicjar-chart-curator SKILL + market-data.md·frontend.md 이관 표기 + CLAUDE.md 갱신. 운영 코드 0 touch (agent infra·docs only). 본 ADR 머지 후 즉시 chart-curator 첫 발화(한국석유 일봉 backfill + last_candle_at 모니터링 워커) + T-CC 트랙 backlog 신설. (architect) |
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
