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
| 0004 | MSA 모듈 재구성 (5 JVM + frontend) — 2026-05-10 갱신: 4 → 5 (pattern-engine 추가, ADR 0054 정합) |
| 0005 | 하이브리드 런타임 — api / batch / consumer 3 JVM |
| 0006 | PostgreSQL 단일 + 스키마 분리 |
| 0007 | Kafka 이벤트 백본 |
| 0008 | Docker 표준 — 모듈별 Dockerfile + compose 통합 기동 |
| 0054 | pattern-engine 라이브러리 모듈 분리 (domain 순수성 보존 + api·consumer 공유) |

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
| 0068 | T-O 주문 실행 — Proposed (Risk ALLOWED → KIS REST + WS H0STCNI0 체결 추적 + 3 테이블 영속 + 실계좌 3중 게이트) |
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

## §9. 메타·운영·하네스 (7건)

| 번호 | 제목 |
|------|------|
| 0030 | 마스터 아키텍처 = 단일 진실원 (SoT) |
| 0031 | 베이스라인 태그 + release/v* immutable 보존 브랜치 |
| 0032 | Husky v9 git hooks (pre-commit author whitelist + pre-push) |
| 0033 | 단일 worktree + 순차 작업 정책 |
| 0034 | 머지 흐름 단순화 (`agent/* → main`) |
| 0035 | 에이전트별 모델 차등 + 에스컬레이션 |
| 0036 | Orchestrator Meta Rules (git-author + task-file-system + architect-as-orchestrator) |
| 0078 | 내부 자산 추출 전략 — Proposed (재사용 가능한 프로젝트 부트스트랩 템플릿 + Plugin Independence 제약) |

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
- **신규 진입 시 후속 ADR 번호** — **0079부터 sequential**. 예약 슬롯은 §12 참조.
- **변경 이력 기록 의무** — 의미 있는 ADR 발행·재발행·supersede·예약 해제 시 §14에 한 줄 추가.

## §12. 예약 슬롯 (Reserved)

후속 ADR 발행 시 사용 가능한 예약 번호.

| 번호 | 사유 |
|------|------|
| 0058 | Reserved — T-PAT(57 캔들 패턴 표준) 기 머지 잔재, 추후 본문 ADR로 격상 검토 |
| 0059 | _Closed_ — Screener 표준 후보였으나 ADR 0071에 흡수 완료 (2026-05-14) |
| 0060 | _Closed_ — Performance 표준 후보였으나 ADR 0072에 흡수 완료 (2026-05-14, T-J + T-PERF 묶음) |
| 0065 | Reserved — 신규 트랙·횡단 정책 진입 시 우선 사용 (의도적 갭) |
| 0077 | Reserved — ADR 0076에 분석 내용 흡수됨, 후속 OPSP8996 deep dive 필요 시 재사용 |

## §13. 후속 예정 (Proposed)

현재 Proposed 상태로 사용자 결정·후속 dispatch 대기 중인 ADR. Accepted/Superseded 전환 시 §14 변경 이력에 기록 + 본 표에서 제거.

| 번호 | 제목 | 대기 사유 |
|------|------|---------|
| 0064 | Redis boundary fanout + STOMP 실시간 합산 갱신 | 사용자 결정 의제 3건 위임 (A·B·C) — architect 권장 A-1+B-1+C-1 |
| 0068 | T-O 주문 실행 | 사용자 결정 의제 8건 위임 (A~H) — architect 권장 A-1~H-1, 실계좌 3중 게이트 의무 |
| 0076 | KIS WS NXT/시간외 시세 sync 표준 정합 | v3.6.9 Phase 1·2·3 채택 완료 — Proposed 상태 유지 (분석 사이클, 후속 코드 변경은 별 트랙) |
| 0078 | 내부 자산 추출 전략 | 사용자 명시적 호출 단일 조건 시 시동 — Phase 1~3 분리, Plugin Independence 제약 LOCK |

## §14. 변경 이력

본 README 자체 + ADR 발행/supersede 시계열. minor cut 단위로 누적.

| 일자 | 사이클 | 변경 |
|------|--------|------|
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
