# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record) 인덱스. 번호는 증가만, 대체는 새 ADR 발행.

> **v2 베이스라인 (2026-05-03)** — 사용자 비전 정정 후 ADR 18건(0031~0048) 신규 발행. 기존 ADR 8건 `_archive/` 이동(SUPERSEDED 6 + DEPRECATED 2). 본 README는 Active vs Archive 분리 표기.

---

## §1. Active ADR (40건 — Single Source of Truth)

### §1-1. 기존 v1 — Active (22건)

| 번호 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| 0001 | 언어·프레임워크·빌드 시스템 선택 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle Kotlin DSL) | Active | 2026-04-25 |
| 0003 | 증권사 API로 한국투자증권 KIS OpenAPI 선택 | Active | 2026-04-25 |
| 0006 | 주문 실행기 3-mode 구조와 안전 게이트 | Active (보강) | 2026-04-25 |
| 0007 | 매매일지(Trading Journal) 아키텍처 | Active | 2026-04-25 |
| 0010 | Git 훅 관리 — Husky v9 | Active | 2026-04-25 |
| 0011 | QA 스크린샷 게이트 | Active (개정 2026-05-01 시나리오 모드) | 2026-04-25 / 2026-04-27 / 2026-05-01 |
| 0013 | 단일 Worktree + 순차 작업 정책 | Active | 2026-04-25 |
| 0014 | 에이전트별 모델 차등 + 에스컬레이션 | Active (보강 2026-04-27) | 2026-04-25 / 2026-04-27 |
| 0015 | 머지 흐름 단순화 (`agent/* → main`) | Active | 2026-04-25 |
| 0016 | QA On-Demand 정책 (MVP) | Active (보강 2026-05-01) | 2026-04-25 / 2026-05-01 |
| 0017 | 에이전트별 Git Author 분리 | Active | 2026-04-25 |
| 0018 | Task 파일 시스템 | Active | 2026-04-25 |
| 0019 | MSA 모듈 재구성 (4 JVM + pyworker + frontend) | Active | 2026-04-27 |
| 0020 | Kafka 이벤트 백본 (Spring Kafka + aiokafka, JSON 스키마 v1) | Active | 2026-04-27 |
| 0021 | 하이브리드 런타임 — api / batch / consumer 3 JVM 앱 | Active | 2026-04-27 |
| 0022 | 프론트엔드 SPA 전환 — React + TS + Vite + LWC | Active (TradingView Advanced 부분 폐기 — 0044) | 2026-04-27 |
| 0023 | Python 모듈 도입 (pyworker — FastAPI + Playwright + APScheduler) | Active | 2026-04-27 |
| 0024 | Docker 표준 — 모듈별 Dockerfile + compose 통합 기동 | Active | 2026-04-27 |
| 0025 | 데이터 저장소 — PostgreSQL 단일 인스턴스 + 스키마 분리 | Active | 2026-04-27 |
| 0026 | 객체 저장소 도입 — MinIO (S3 호환) | Active | 2026-04-27 |
| 0027 | v0 베이스라인 태그(`v0.1.0`) + `release/v0` immutable 보존 브랜치 | Active | 2026-04-30 |
| 0030 | 오케스트레이터 = architect 일체화 (메타 규약) | Active | 2026-05-01 |

### §1-2. v2 신규 (18건 — 2026-05-03)

| 번호 | 제목 | 핵심 결정 | master §위치 |
|------|------|----------|-------------|
| 0031 | 시스템 비전 v2 — 룰 본체 + AI 옵션 + MCP 서버 미래 | AI 결정권 0. 오프라인 1순위. | §1·§3·§10 |
| 0032 | Market Session FSM (KRX 영업일·장중·장외·휴장) | 4 상태 FSM + 60s cache. | §A.2·§3-2·§7 |
| 0033 | 시세 데이터 Fallback 3-tier (live → cache → last-day-close) | stale=true 명시 + UI 배지. | §A.2·§3-2 |
| 0034 | REJECTED Audit Trail | Guard reject·KisApiException 모두 orders INSERT. | §C.13·§3-1 |
| 0035 | KIS REST/WS HTTP 정밀화 (EGW00202 hotfix + hashkey 표준) | RestClient 표준 + raw 로깅. | §외부.KIS·§3-1 |
| 0036 | KIS Strategy Builder 흡수 = 명세 port (AI hook 제거) | DSL·80지표·57패턴·10프리셋 명세만. .kis.yaml 호환. | §B.5·§B.7·§B.8 |
| 0037 | Instrument Master 1급 시민 | KIS·KRX 일일 sync + Redis 검색 인덱스. 상장폐지 보존. | §A.1 |
| 0038 | Corp Action raw + adjusted | candle 양 컬럼 + retroactive 재계산. 룩어헤드 차단. | §A.3·§3-5 |
| 0039 | Indicator Source 추상화 (`IndicatorSource` plug-in) | KIS·TA-Lib·Native·KRX 동일 port. | §B.5·§7-3 |
| 0040 | Strategy DSL = `.kis.yaml` 호환 superset | magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건). | §B.7 |
| 0041 | Signal Engine = 룰 매칭 only (AI 호출 0) | DSL 평가만. AI 컨설트는 W4 옵션 분리. | §C.11 |
| 0042 | Risk Guard chain 6단 | 1차 매우 느슨 default. PositionLimit 종목당 10%·일일 -3%. | §C.13 |
| 0043 | Multi-tenant 구조 (트랙1 완료 흡수) | user_id 컬럼 의무. 인증은 post-MVP. | §1·§D.18·§6-2 |
| 0044 | LWC 확장 + 자체 도형/주석/패턴 (TradingView Advanced 폐기) | LWC v4 공식 API + 자체 overlay layer. | §D.16·§9 |
| 0045 | MCP 서버 미래 노출 (W4) | tool 5종. api 모듈 내 endpoint 1차. localhost. | §1-5·§D.18·§3-4 |
| 0046 | AI 옵션 보조 (사용자 트리거 한정) | 매매 트리거 0. Anthropic 1차. 5분 cache. | §1·§3-3·§D.17 |
| 0047 | 새 v2 마스터 = 단일 진실원 | 트랙 spec/plan은 §X 위치 명시 의무. 충돌 시 master 선 개정. | §1·§13 |
| 0048 | 1분봉 단일 영속 + 어플리케이션 레벨 합성 + Yahoo 하이브리드 | M1·D1만 영속, 임의 N분봉 백엔드 합성, KIS WS 누적 + Yahoo cold-start. v1 정책 supersede. | §A.2·§3-5·§6-2·§외부 매트릭스 |

---

## §2. Archive (8건 — `_archive/` 이동, 참조용 보존)

| 번호 | 제목 | 사유 | 대체 |
|------|------|------|------|
| 0002 | Gradle 멀티모듈 구조 (수직 7) | 수직 → 수평 분할 전환 | by 0019 |
| 0004 | 영속 저장소와 캐시 전략 (PostgreSQL + Redis) | 본격 결정 보강 | by 0025 |
| 0005 | LLM 공급자와 Spring AI 추상화 | 1차안 "AI 본체 결정권" 가정 폐기 | by 0046 |
| 0008 | 하네스 운영 페이지 노출 정책 | React SPA 흡수로 재구현 | by 0022 |
| 0009 | 브랜치 전략과 머지 규칙 (Feature + QA 게이트) | 단일 머지 흐름 + QA on-demand로 단순화 | by 0015 + 0016 |
| 0012 | Feature → QA → Main 2단 머지 | qa 영속 브랜치 폐기 | by 0015 |
| 0028 | v1.0.1 베이스라인 + release/v1 | 사용자 명시 정합. v2 베이스라인 표준은 후속 ADR | DEPRECATED |
| 0029 | 데이터는 실 KIS 베이스 — Dev/Prd 분리는 OrderSender만 | 1차안 "AI 직접 매매" 가정에 묶임 | by 0033 + 0046 |

---

## §3. 후속 예정 (Proposed 후보)

- **0049** (T6 진입 시) — 57 캔들 패턴 표준
- **0050** (T9 진입 시) — Backtest 표준 (룩어헤드·세금·수수료·슬리피지)
- **0051** (T10 진입 시) — Screener 표준
- **0052** (T14 진입 시) — Performance 표준
- **0053+** v2 베이스라인 컷 (별도 release branch 결정 시)
- 실서비스 진입 시 QA 강제 게이트 회귀 (ADR 0016 sunset)
- 자연어 → DSL 번역 (W4 후속)
- TimescaleDB 도입 (ADR 0025 후속, 일봉 ≥1억 행 시)

---

## §4. 운영 규약

본 README는 **목차 전용**. 결정 본문은 각 ADR 파일에서 읽는다.

- **새 ADR 발행 시** — 본 README + master §10 + tracks-roadmap §3 + CLAUDE.md 변경 이력 동시 갱신.
- **Superseded/Deprecated 처리 시** — 본문 상단 헤더 + `_archive/` 이동 + 본 README §2 추가.
- **REORGANIZATION.md** — v2 베이스라인 진입 시 1회성 분류 보고서. 발행 후 보존(역사 추적).
