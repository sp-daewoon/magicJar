# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record). 70건 sequential (0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074 Accepted + 0064·0073 Proposed — 0058~0060·0065 예약). 2026-05-12 v3.2.3 doc cut — ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 재통합 갱신 (status 정합, 결정 변동 0). 2026-05-12 v3.3.0 §34 plan — ADR 0064 신규 (Redis boundary fanout + STOMP 실시간 합산 갱신). 2026-05-12 v3.4.0 cut — ADR 0066 Accepted (T-SIG 실시간 시그널 엔진). 2026-05-12 v3.5.0 cut — ADR 0067 Accepted (T-RISK 리스크 관리). 2026-05-13 v3.6.0 cut — ADR 0068 Accepted (T-O 주문 실행, 채택 A-1~H-1). 2026-05-13 v3.6.1 dogfooding 결함 응답 — ADR 0069 Accepted (SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 추출 + 미래 확장 5 step 표준 절차). 2026-05-13 v3.6.2 patch — ADR 0070 Accepted (T-RECON + T-NOTIFY 묶음 spec/plan — W3 자본 보호 prereq, ADR 0041·0044 baseline reuse + Phase 분리 LOCK). 2026-05-14 v3.6.3 patch — ADR 0071 Accepted (T-SC Screener spec/plan — W3 다섯 번째 트랙, "전략으로 종목 찾기" 흐름 합류, universe·candle 영속 reuse + KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0, A-1~H-1 8 의제 architect 자율 LOCK). 2026-05-14 v3.6.4 patch — ADR 0072 Accepted (T-J + T-PERF 묶음 spec/plan — W3 마지막 사이클, Trading Journal 영속 + Performance 통계 합류, 5 Kafka 이벤트 listen 자동 INSERT + 5 지표 fixed + batch DailyPerformanceJob 매일 00:30 KST + Redis cache TTL 1h, KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0, A-1~H-1 8 의제 architect 자율 LOCK). 2026-05-14 v3.6.5 analysis — ADR 0073 Proposed (T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — 사용자 의제 응답 "주요 뉴스는 시작해?" 2026-05-14, 옵션 A·B·C·D 매트릭스 + 사용자 결정 의제 8건 위임, architect 권장 A-1+2-1+3-1+4-2+5-1+6-1+7-1+8-1, KIS-first §B-3 정합 OK, 코드 변경 0 LOCK 본 사이클은 analysis ADR + backlog §52 등재만). **2026-05-14 v3.6.5 patch (재발행) — ADR 0074 Accepted (SettingsModal 풀세트 — KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합 · backlog §44+§45 묶음 흡수). 직전 architect dispatch ADR 번호 충돌(T-NEWS 0073 vs 본 ADR 0073) → PR #415 close됨 → 본 ADR은 0074로 재발행. 결정 내용 변동 0(번호만 갱신). REST 신규 5건 + Kafka 신규 1(system.kis-key-changed.v1) + killswitch.engaged.v1 schema 확장 + Flyway 0건 LOCK + Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer 병렬 + Phase 2 frontend 직렬).** 본 README는 **목차 전용** — 결정 본문은 각 ADR 파일에서 읽는다.

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

## §5. 시장 데이터·외부 API (12건 — 8건 + 0062·0063 Accepted + 0064·0073 Proposed)

| 번호 | 제목 |
|------|------|
| 0013 | Market Session FSM (KRX 영업일·장중·장외·휴장) |
| 0014 | 시세 데이터 Fallback 3-tier (live → cache → last-day-close) |
| 0015 | REJECTED Audit Trail |
| 0016 | KIS REST/WS HTTP 정밀화 |
| 0017 | Instrument Master 1급 시민 |
| 0018 | Corp Action raw + adjusted |
| 0019 | 캔들 저장 정책 (Watchlist 기반) — **Accepted (2026-05-12 v3.2.3 doc cut — §2 매트릭스 NXT 시각 + 합산 5종 재통합. Yahoo 의존 부분만 보존 rollback baseline)** |
| 0048 | KIS WebSocket 41건 구독 한도 + 우선순위 정책 (slot 1+30+10·priority eviction·polling fallback) |
| 0057 | **KIS OpenAPI 단일 데이터 출처 (Yahoo Finance·네이버 등 보조 출처 폐기) — 5m~1h은 1m 합산 가공 방향 확정 (backlog §31 구현 사이클)** |
| 0062 | **5m·15m·30m·90m·1h 합산 가공 plan — Accepted (2026-05-11 사용자 결정 A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화)** |
| 0063 | **NXT/통합거래 매트릭스 cover (분봉·일봉 시장 분류 코드) — Accepted (2026-05-11 사용자 결정 A UN 통합 + 1 08:00~20:00 + threshold 700)** |
| 0064 | **Redis boundary fanout + STOMP 실시간 합산 갱신 plan — Proposed (2026-05-12 사용자 결정 대기 — 의제 A·B·C / architect 권장 A-1+B-1+C-1)** |
| 0073 | **T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — Proposed (2026-05-14 사용자 의제 응답 "주요 뉴스는 시작해?", analysis only, 옵션 A·B·C·D + 사용자 결정 의제 8건, architect 권장 옵션 A T-NEWS 별도 트랙 신설 W3.5 또는 W4 prereq)** |

## §6. 전략·신호·리스크 (10건)

| 번호 | 제목 |
|------|------|
| 0020 | KIS Strategy Builder 흡수 = 명세 port |
| 0021 | Indicator Source 추상화 |
| 0022 | Strategy DSL = .kis.yaml 호환 superset |
| 0023 | Signal Engine = 룰 매칭 only (AI 호출 0) |
| 0024 | Risk Guard chain 6단 |
| 0056 | Backtest Engine Architecture (룩어헤드 slice-by-time 가드 + 세·수수료·슬리피지 모델 open extension + ADR 0047 sandbox 통합 + abort quota 50) |
| 0066 | **T-SIG 실시간 시그널 엔진 (룰 매칭 + Kafka boundary listen + 영속 + STOMP fanout) — Accepted (2026-05-12 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1 채택)** |
| 0067 | **T-RISK 리스크 관리 (Signal → Risk Gateway consumer → Order intent 의무 게이트 + 4 도메인 영속 + STOMP 알림 + auto-PAUSE) — Accepted (2026-05-12 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택)** |
| 0068 | **T-O 주문 실행 (Risk ALLOWED → KIS REST 매수·매도 + WS H0STCNI0 체결 추적 + 사용자 수동 정정/취소 + 3 테이블 영속 + STOMP 4 event + 멱등성 Redis SETNX + 실계좌 3중 게이트) — Proposed (2026-05-12 결정 의제 8건 대기 — architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1)** |
| 0071 | **T-SC Screener spec/plan (universe × DSL → 후보 + 랭킹 + Redis cache 30m + 옵션 영속 V28) — Accepted (2026-05-14 architect 자율 결정 · 사용자 위임). A-1~H-1 8 의제 LOCK. 3 Phase 직렬(strategy-engine domain+consumer → backend-core api+Redis+옵션 Flyway V28 → frontend Strategy Builder 합류+ScreenerResultModal). universe=instrument 영속 reuse + candle=영속 reuse → KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0. spec: `_workspace/specs/pr-393-t-sc-spec.md`.** |
| 0072 | **T-J + T-PERF 묶음 spec/plan (Trading Journal 영속 + Performance 통계 + Flyway V29·V30 + REST 5 endpoint + batch DailyPerformanceJob) — Accepted (2026-05-14 architect 자율 결정 · 사용자 위임 · W3 마지막 사이클). A-1~H-1 8 의제 LOCK. 3 Phase 직렬(Phase 1 backend-core T-J: domain+Flyway V29(trading_journal+journal_note)+JournalEventConsumer 5 Kafka listen(order.filled+signal.generated+risk.violation+reconcile.drift+position.updated)+REST 3 endpoint Swagger 4단 · Phase 2 backend-core T-PERF: domain+Flyway V30(performance_summary+pnl_daily)+batch DailyPerformanceJob 매일 00:30 KST cron+REST 2 endpoint+Redis cache TTL 1h+PerformanceCalculator pure math MDD NUMERIC(8,4) · Phase 3 frontend: `/journal`+`/performance` 2 페이지+AppHeader 메뉴 2 추가). 5 지표 fixed(총 P&L·일별 P&L·MDD·승률·평균 손익비) — Sharpe·Sortino·CAGR backlog §61. 멱등성 UQ `ux_trading_journal_source`(source_table+source_id+event_type) Kafka at-least-once 재처리 안전. Kafka 신규 publish 0 + listen 5(기존 토픽 reuse). STOMP 신규 0(polling). KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0(영속 부재 시 "매매일지가 비어있습니다" 한국어). spec: `_workspace/specs/pr-402-t-j-perf-spec.md`.** |

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
| 0041 | Broker Reconciliation Job (KIS 잔고 ↔ 내부 position 정합) — **ADR 0070 묶음 spec 정밀화 (2026-05-13)** | W1·W3 |
| 0042 | Position Bootstrap & Recovery (앱 시작·WS 재연결 시 잔고 복구) | W1 |
| 0043 | KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책) | W3 |

## §10-2. 운영·UX Fitness 갭 (5건)

자동매매 본체 운영 안정성 + 첫 사용자 경험 — 2026-05-07 PR-3b 발굴 + 2026-05-13 dogfooding 결함 발굴(0069) + 2026-05-14 SettingsModal 풀세트 신규(0074).

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0044 | Notification System (Slack webhook 1차 + Kafka 트리거 + outbox fallback) — **ADR 0070 묶음 spec 정밀화 (2026-05-13)** | W3 (T-NOTIFY) |
| 0045 | Graceful Shutdown & In-flight 처리 (SIGTERM · Kafka manual commit · idempotency) | W1 (횡단 — 모든 트랙 의무) |
| 0046 | Onboarding & Credential Registration (테이블 3종 분리 · 마스터 키 단일 예외) | W1 ★ (T-ONBOARD 신설 후보 — T-W1보다 먼저) |
| 0069 | **SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 추출 + 미래 확장 구조 (1차 PR scope: RealTradingConsentSection + KisCredentialSection + SystemConfigSection)** | W3 T-O 보강 (dogfooding 자본 보호 검증 unblock) |
| 0074 | **SettingsModal 풀세트 (KIS 키 변경 PUT·GET·DELETE + KillSwitch 수동 토글 GET·PUT + AI 키 카테고리 사전 정합) — Accepted (2026-05-14 재발행, 직전 0073 번호 충돌 응답)** | W3.5 v3.6.5 patch (운영 path · 자본 보호 종착 · T-AI prereq) |

## §10-3. DSL 안전성 (1건)

자동매매 본체 안정성 — 2026-05-07 PR-3c 발굴 (이전 감사 6번 — DSL Evaluation Sandbox).

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0047 | DSL Evaluation Sandbox (정적 검증 + 런타임 timeout/CPU budget + thread pool 격리 + auto-disable) | W2 (T-DSL 합류) · W3 (T-SIG·T-BT 합류) |

## §10-4. 메타·CI/CD·하네스 정합 (3건)

자동매매 본체 운영 속도 + 협업 충돌 회피 — 2026-05-07 PR-8 / 2026-05-08 PR-26 발굴.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0049 | GitHub Actions = release 트리거 한정 (일상 검증은 pre-push hook 단독) | 횡단 (모든 Wave) |
| 0050 | CHANGELOG 슬롯 채번룰 (architect 사전 발행 + sub-agent in-place edit) | 횡단 (병렬 디스패치 사이클) |
| 0051 | Spring Data Repository default fun 함정 (회피 패턴) | 횡단 (backend-core·market-data 핵심 risk) |
| 0052 | Baseline Documentation Cycle (skill + slash + 스크립트 4종) | 횡단 (release 사이클·문서화) |
| 0053 | Living Policy Docs (현행 권위 단일 위치 + INDEX·policies/ 분리) | 횡단 (문서 정합·인지 비용·토큰 효율) |

## §11. 운영 규약

- **새 ADR 발행 시** — 본 README + master §10 + tracks-roadmap §3 동시 갱신
- **Superseded 처리 시** — 본문 상단 헤더 + 본 README 행 supersede 표기 (`_archive/` 이동은 v3.3.0 cycle에 일괄)
- **신규 진입 시 후속 ADR 번호** — 0075부터 sequential (0001~0057 Accepted + 0058~0060·0065 예약 + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074 Accepted + 0064·0073 Proposed 2026-05-12·2026-05-14 — §2·§3·§5·§6·§10-1·§10-2·§10-3·§10-4 정합).

## §12. 후속 예정 (Proposed)

| 후보 번호 | 제목 (예정) | 진입 트랙 | 사유 |
|---|---|---|---|
| 0058 | 57 캔들 패턴 표준 | T-PAT (기 머지 — 본문 ADR로 격상 검토) | 시프트 (2026-05-11 ADR 0057 KIS-only 채택으로 후속 0058+로 재시프트) |
| 0059 | ~~Screener 표준~~ → **ADR 0071 흡수 완료** (2026-05-14) | T-SC | **closed — ADR 0071 cover 완료** |
| 0060 | ~~Performance 표준~~ → **ADR 0072 흡수 완료** (2026-05-14) | T-PERF | **closed — ADR 0072 cover 완료 (T-J + T-PERF 묶음)** |
| 0061 | **Backtest 결과 영속 — Accepted (2026-05-12)** | T-BT R2 (backlog §37 DONE) | 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1 채택. 3 테이블(backtest_run·backtest_trade·backtest_position) + 5 endpoint. ADR 0056 §2-6 supersede. |
| 0062 | **5m·15m·30m·90m·1h 합산 가공 — Accepted (2026-05-11)** | backlog §31 트랙 | 2026-05-11 사용자 결정 채택 — A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화. NXT 결합 영향 §9 추가 (1m 720/일 → 합산 ~71K rows). sub-agent 디스패치 매트릭스 진행 |
| 0063 | **NXT/통합거래 매트릭스 cover — Accepted (2026-05-11)** | backlog §32 트랙 | 2026-05-11 사용자 결정 채택 — A UN 통합 + 1 08:00~20:00 + threshold 700. sub-agent 디스패치 매트릭스 진행 |
| 0064 | **Redis boundary fanout + STOMP 실시간 합산 갱신 — Proposed (2026-05-12)** | backlog §34 트랙 (v3.3.0) | 2026-05-12 architect plan 발행. 결정 의제 3건 사용자 검토 위임 — A Redis 키 5종 vs 통합 / B boundary 트리거 in-process vs scheduled / C STOMP 5종 vs 단일. architect 권장 A-1 + B-1 + C-1. 채택 시 market-data + frontend 디스패치 |
| 0066 | **T-SIG 실시간 시그널 엔진 — Accepted (2026-05-12)** | T-SIG 트랙 (W3 두 번째 트랙, v3.4.0 마감, backlog §40 DONE) | 2026-05-12 architect plan 발행 → 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1 채택. strategy-engine(Phase 1) + backend-core(Phase 2) + frontend(Phase 3) 3 PR 직렬 디스패치 완료 |
| 0067 | **T-RISK 리스크 관리 — Accepted (2026-05-12)** | T-RISK 트랙 (W3 세 번째 트랙, v3.5.0 마감, backlog §41 DONE) | 2026-05-12 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택. strategy-engine(Phase 1) + backend-core(Phase 2) + frontend(Phase 3) 3 PR 직렬 디스패치 완료. PR #351·#353·#354·#355·#356·#357 머지 |
| 0068 | **T-O 주문 실행 — Proposed (2026-05-12)** | T-O 트랙 (W3 네 번째 트랙, v3.6.0 후보, backlog §43) | 2026-05-12 architect plan 발행. 결정 의제 8건 사용자 검토 위임 — A 모의/실전 분기(env × 2 + 사용자 동의 3중 게이트 vs env 이중만 vs 단일) / B KIS endpoint(단일 port + adapter 내부 mode 분기 vs Spring profile vs lookup) / C 주문 가격(시장가/지정가 vs 슬리피지 % vs 지정가 only) / D 체결 추적(KIS WS H0STCNI0 실시간 vs REST polling 1분 vs dual) / E 정정/취소(사용자 수동 only vs 자동 + 수동 vs 자동만) / F 영속(3 테이블 request+execution+audit vs 2 테이블 vs JSONB blob) / G 알림(단일 STOMP + 4 event discriminator vs 단일 type vs 4 토픽) / H 멱등성(Redis SETNX 60s + DB UQ vs DB UQ만 vs 0). architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1. KIS-first raw 인용 4건(TTTC0012U·TTTC0011U·TTTC0013U·H0STCNI0). 실계좌 3중 게이트 + AI 호출 0 검증 의무. 채택 시 strategy-engine(Phase 1) + backend-core(Phase 2) + frontend(Phase 3) 직렬 디스패치 |
| 0070 | **T-RECON + T-NOTIFY 묶음 spec/plan — Accepted (2026-05-13)** | T-RECON·T-NOTIFY 트랙 (W3 마무리 prereq, v3.6.2 patch, backlog §46~§51) | 2026-05-13 architect 자율 결정 (사용자 위임 "W3 진행해서 마무리까지하자 W4 진입까지 자율 최종권한"). ADR 0041(T-RECON baseline) + ADR 0044(T-NOTIFY baseline) reuse + 정밀 명세 §1~§15 LOCK. Phase 분리 LOCK (T-RECON Phase 1 strategy-engine → Phase 2 backend-core → T-NOTIFY Phase 1 backend-core 직렬). KIS-first raw 4건(TTTC8434R·VTTC8434R 잔고 + CTRP6548R 계좌자산 + H0STCNI0 cross-ref). Flyway V25(reconcile_run) + V26(domain enum) + V27(notification_outbox + system_config.notification_slack_webhook ALTER). Kafka 신규 2(reconcile.drift.v1·reconcile.run.v1) + killswitch.engaged.v1 publish + listen 7. REST 3 endpoint(manual trigger + runs list + run detail) Swagger 4단. frontend 변경 0 LOCK (SettingsModal·KillSwitch baseline reuse, Risk 페이지 timeline UI는 후속 backlog §46). 자본 보호 흐름 LOCK(drift → KillSwitch ON → Slack P0 → STOMP). AI 호출 0 grep 검증 의무. spec 본문: `_workspace/specs/pr-392-recon-notify-spec.md`. CHANGELOG slot 4 anchor(architect + strategy-engine + backend-core-recon + backend-core-notify). |
| 0071 | **T-SC Screener spec/plan — Accepted (2026-05-14)** | T-SC 트랙 (W3 다섯 번째 트랙, v3.6.3 patch, backlog §52~§58 예정) | 2026-05-14 architect 자율 결정 (사용자 위임 동일). 8 의제 LOCK A-1~H-1 — A-1 동기 inline + Redis cache · B-1 instrument 영속 + 필터 · C-1 strategy-eval-pool reuse + timeout 500ms · D-1 Redis cache only + D-2 옵션 영속(env toggle) · E-1 multi-criteria 4 기준 fixed weights · F-1 REST 3 endpoint · G-1 candle 결손 graceful skip + skippedReasons · H-1 Strategy Builder 합류 + ScreenerResultModal. Phase 분리 LOCK (Phase 1 strategy-engine domain+consumer → Phase 2 backend-core api+Redis+옵션 Flyway V28 → Phase 3 frontend 직렬). universe=instrument 영속 reuse + candle=영속 reuse → KIS endpoint 호출 0 LOCK. Flyway V28(screening_run + screening_match, env toggle MAGICJAR_SCREENER_PERSIST=true). Kafka 신규 0. STOMP 신규 0. REST 3 endpoint(POST evaluate + GET runs list + GET runs detail) Swagger 4단 LOCK. AI 호출 0 grep 검증 의무. mock 합성 0. spec 본문: `_workspace/specs/pr-393-t-sc-spec.md`. CHANGELOG slot 3 anchor(architect + strategy-engine + backend-core + frontend — Phase별 sub-agent 합류 시 in-place edit). |
| 0072 | **T-J + T-PERF 묶음 spec/plan — Accepted (2026-05-14)** | T-J·T-PERF 트랙 (W3 마지막 사이클, v3.6.4 patch, backlog §59~§67 예정) |
| 0073 | **T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — Proposed (2026-05-14)** | T-NEWS 트랙 신설 후보 (W3.5 또는 W4 prereq, v3.6.5 analysis, backlog §52 등재) | 2026-05-14 architect 자율 결정 (사용자 위임 동일). 8 의제 LOCK A-1~H-1 — A-1 trading_journal 신규 + 5 Kafka listen 자동 INSERT · B-1 batch + inline 듀얼 · C-1 5 지표 fixed(총 P&L·일별 P&L·MDD·승률·평균 손익비) · D-1 performance_summary + pnl_daily 영속 · E-1 REST 5 endpoint · F-1 2 페이지 + AppHeader 메뉴 2 · G-1 5 토픽 listen(order.filled+signal.generated+risk.violation+reconcile.drift+position.updated) · H-1 AI 호출 0 LOCK. Phase 분리 LOCK (Phase 1 backend-core T-J: domain+Flyway V29(trading_journal+journal_note IF NOT EXISTS + 멱등성 UQ source_table+source_id+event_type)+JournalEventConsumer 5 Kafka listen+REST 3 endpoint Swagger 4단 한국어 · Phase 2 backend-core T-PERF: domain+Flyway V30(performance_summary+pnl_daily IF NOT EXISTS)+batch DailyPerformanceJob 매일 00:30 KST cron+inline aggregate fallback+REST 2 endpoint+Redis cache TTL 1h+PerformanceCalculator pure math MDD 알고리즘 NUMERIC(8,4) · Phase 3 frontend: `/journal`+`/performance` 2 페이지+AppHeader 메뉴 2 추가+LWC v4 equity curve+daily P&L bar chart). Sharpe·Sortino·CAGR 비범위(backlog §61). 차트 스냅샷 자동 영속 비범위(backlog §60). AI 회고 코멘트 W4 backlog §62. Kafka 신규 publish 0(기존 5 토픽 reuse listen만). STOMP 0(polling). REST 5 endpoint Swagger 4단 한국어 + example. KIS endpoint 호출 0 LOCK(모든 데이터 영속 reuse — order·signal·risk·reconcile·position 5 baseline). AI 호출 0 grep 검증 의무 6 디렉토리. mock 합성 0("매매일지가 비어있습니다" 한국어 graceful display). 검증 시나리오 19건 dogfooding evidence 의무. spec 본문: `_workspace/specs/pr-402-t-j-perf-spec.md`. CHANGELOG slot 4 anchor(architect + backend-core-tj + backend-core-tperf + frontend — Phase별 sub-agent 합류 시 in-place edit). |
| 0074 | **SettingsModal 풀세트 (KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합) — Accepted (2026-05-14 재발행)** | T-ONBOARD 풀세트 후속 (v3.6.5 patch, backlog §44+§45 묶음 흡수, 직전 0073 번호 충돌 응답) | 2026-05-14 architect 자율 결정 (사용자 자율 위임 "§44~§45부터 하자"). 직전 architect dispatch 시 T-NEWS(PR #414 머지)와 본 사이클(PR #415 close) 둘 다 ADR 0073 발행 → ADR 번호 충돌로 PR #415 close. 본 ADR은 0074로 재발행 + 결정 내용 변동 0(번호만 갱신). 12 § LOCK — §1 fitness · §2 KIS 키 변경 풀세트(PUT/GET/DELETE + KisAuthValidator 통과 UPDATE atomicity + Redis access_token + kis:approval DEL + kis:key-changed-lock SET 5초 + Kafka system.kis-key-changed.v1 publish + WS graceful reconnect, MOCK만 DELETE 허용 REAL DELETE 금지) · §3 KillSwitch 수동 토글(SettingsModal Section 4 신설 + GET·PUT /api/system-config/kill-switch + ConfirmDangerToggle reuse + 사유 입력 minLength=5 maxLength=500 + 60초 cooldown + killswitch.engaged.v1 schema 확장 engaged:boolean) · §4 OpenAI/Anthropic 키 카테고리(env 유지 + Section 5 UI 0건 1차 + T-AI 사이클 실구현 분리) · §5 Flyway 0건 LOCK(baseline reuse) · §6 REST 신규 5건 + SystemConfigController 신규 · §7 Redis invalidate 매트릭스 LOCK · §8 frontend UI 매트릭스(Section 2 submit 활성화 + Section 4 신설 + ConfirmDangerToggle 실구현 + 5 hook) · §9 Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer add-only 병렬 가능 + Phase 2 frontend 직렬) · §10 검증 시나리오 3건 · §11 ADR 0069 §7 backlog 흡수(§A·§D·§G 흡수 + §E 사전 정합 + §F·§H·§I·§J 잔류) · §12 Rollback 시나리오. AI 호출 0 grep 검증 의무. mock 합성 0(KillSwitch 초기 row 없을 때 "최초 토글 없음" + KIS 키 hasKey=false 시 "등록된 키 없음"). spec 본문: `_workspace/specs/pr-N-settings-kis-change-killswitch-ai-spec.md` (PR 번호 N은 본 PR 생성 후 확정). CHANGELOG slot 4 anchor(architect + backend-core-phase1 + consumer-phase3 + frontend-phase2 — Phase별 sub-agent 합류 시 in-place edit). |
