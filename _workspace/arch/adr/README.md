# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record). 60건 sequential (0001~0057 Accepted + 0062·0063 Accepted 2026-05-11 + 0064 Proposed 2026-05-12 — 0058~0061 예약). 2026-05-12 v3.2.3 doc cut — ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 재통합 갱신 (status 정합, 결정 변동 0). 2026-05-12 v3.3.0 §34 plan — ADR 0064 신규 (Redis boundary fanout + STOMP 실시간 합산 갱신, 사용자 결정 의제 3건 대기). 본 README는 **목차 전용** — 결정 본문은 각 ADR 파일에서 읽는다.

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

## §5. 시장 데이터·외부 API (11건 — 8건 + 0062·0063 Accepted + 0064 Proposed)

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

## §6. 전략·신호·리스크 (6건)

| 번호 | 제목 |
|------|------|
| 0020 | KIS Strategy Builder 흡수 = 명세 port |
| 0021 | Indicator Source 추상화 |
| 0022 | Strategy DSL = .kis.yaml 호환 superset |
| 0023 | Signal Engine = 룰 매칭 only (AI 호출 0) |
| 0024 | Risk Guard chain 6단 |
| 0056 | Backtest Engine Architecture (룩어헤드 slice-by-time 가드 + 세·수수료·슬리피지 모델 open extension + ADR 0047 sandbox 통합 + abort quota 50) |

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
| 0041 | Broker Reconciliation Job (KIS 잔고 ↔ 내부 position 정합) | W1·W3 |
| 0042 | Position Bootstrap & Recovery (앱 시작·WS 재연결 시 잔고 복구) | W1 |
| 0043 | KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책) | W3 |

## §10-2. 운영·UX Fitness 갭 (3건)

자동매매 본체 운영 안정성 + 첫 사용자 경험 — 2026-05-07 PR-3b 발굴.

| 번호 | 제목 | 영향 Wave |
|------|------|------|
| 0044 | Notification System (Slack webhook 1차 + Kafka 트리거 + outbox fallback) | W3 (T-NOTIFY 신설 후보) |
| 0045 | Graceful Shutdown & In-flight 처리 (SIGTERM · Kafka manual commit · idempotency) | W1 (횡단 — 모든 트랙 의무) |
| 0046 | Onboarding & Credential Registration (테이블 3종 분리 · 마스터 키 단일 예외) | W1 ★ (T-ONBOARD 신설 후보 — T-W1보다 먼저) |

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
- **신규 진입 시 후속 ADR 번호** — 0065부터 sequential (0001~0057 Accepted + 0058~0061 예약 + 0062·0063 Accepted 2026-05-11 + 0064 Proposed 2026-05-12 — §2·§3·§5·§6·§10-1·§10-2·§10-3·§10-4·§5 정합).

## §12. 후속 예정 (Proposed)

| 후보 번호 | 제목 (예정) | 진입 트랙 | 사유 |
|---|---|---|---|
| 0058 | 57 캔들 패턴 표준 | T-PAT (기 머지 — 본문 ADR로 격상 검토) | 시프트 (2026-05-11 ADR 0057 KIS-only 채택으로 후속 0058+로 재시프트) |
| 0059 | Screener 표준 | T-SC | (시프트) |
| 0060 | Performance 표준 | T-PERF | (시프트) |
| 0061 | Backtest 결과 영속 (backtest_run 테이블 + GET endpoint) | T-BT 후속 (backlog §27-2) | (예약) |
| 0062 | **5m·15m·30m·90m·1h 합산 가공 — Accepted (2026-05-11)** | backlog §31 트랙 | 2026-05-11 사용자 결정 채택 — A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화. NXT 결합 영향 §9 추가 (1m 720/일 → 합산 ~71K rows). sub-agent 디스패치 매트릭스 진행 |
| 0063 | **NXT/통합거래 매트릭스 cover — Accepted (2026-05-11)** | backlog §32 트랙 | 2026-05-11 사용자 결정 채택 — A UN 통합 + 1 08:00~20:00 + threshold 700. sub-agent 디스패치 매트릭스 진행 |
| 0064 | **Redis boundary fanout + STOMP 실시간 합산 갱신 — Proposed (2026-05-12)** | backlog §34 트랙 (v3.3.0) | 2026-05-12 architect plan 발행. 결정 의제 3건 사용자 검토 위임 — A Redis 키 5종 vs 통합 / B boundary 트리거 in-process vs scheduled / C STOMP 5종 vs 단일. architect 권장 A-1 + B-1 + C-1. 채택 시 market-data + frontend 디스패치 |
