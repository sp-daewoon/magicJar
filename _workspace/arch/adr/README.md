# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record) 인덱스. 번호는 증가만, 대체는 새 ADR 발행.

| 번호 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| 0001 | 언어·프레임워크·빌드 시스템 선택 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle Kotlin DSL) | Accepted | 2026-04-25 |
| 0002 | Gradle 멀티모듈 구조와 모듈 경계 | **Restructured by 0019** (수직 → 수평 분할 전환) | 2026-04-25 |
| 0003 | 증권사 API로 한국투자증권 KIS OpenAPI 선택 | Accepted | 2026-04-25 |
| 0004 | 영속 저장소와 캐시 전략 (PostgreSQL + Redis) | Accepted (확장 by 0025) | 2026-04-25 |
| 0005 | LLM 공급자와 Spring AI 추상화 (Anthropic + Ollama 폴백) | Accepted | 2026-04-25 |
| 0006 | 주문 실행기 3-mode 구조와 안전 게이트 | Accepted | 2026-04-25 |
| 0007 | 매매일지(Trading Journal) 아키텍처 | Accepted | 2026-04-25 |
| 0008 | 하네스 운영 페이지(`/harness`) 노출 정책 | Accepted (재구현 by 0022 React SPA) | 2026-04-25 |
| 0009 | 브랜치 전략과 머지 규칙 (Feature-branch + QA 게이트) | Accepted | 2026-04-25 |
| 0010 | Git 훅 관리 — Husky v9 | Accepted | 2026-04-25 |
| 0011 | QA 스크린샷 게이트 | **개정 2026-04-27** (CLIENT_LWC + SERVER_BROWSER 2 경로, MinIO 저장) | 2026-04-25 / 2026-04-27 |
| 0012 | Feature → QA → Main 2단 머지 흐름 | **Superseded by 0015** (본문 상단 명시 — 2026-04-27) | 2026-04-25 |
| 0013 | 단일 Worktree + 순차 작업 정책 | Accepted | 2026-04-25 |
| 0014 | 에이전트별 모델 차등 + 에스컬레이션 | Accepted (**footnote 보강 2026-04-27** — ai-engineer pyworker 책임 확장) | 2026-04-25 / 2026-04-27 |
| 0015 | 머지 흐름 단순화 (`agent/* → main`) | Accepted | 2026-04-25 |
| 0016 | QA On-Demand 정책 (MVP) | Accepted | 2026-04-25 |
| 0017 | 에이전트별 Git Author 분리 | Accepted | 2026-04-25 |
| 0018 | Task 파일 시스템 | Accepted | 2026-04-25 |
| 0019 | MSA 모듈 재구성 (5 모듈 + pyworker + frontend) | Accepted | 2026-04-27 |
| 0020 | Kafka 이벤트 백본 (Spring Kafka + aiokafka, JSON 스키마 v1) | Accepted | 2026-04-27 |
| 0021 | 하이브리드 런타임 — api / batch / consumer 3 JVM 앱 | Accepted | 2026-04-27 |
| 0022 | 프론트엔드 SPA 전환 — React + TS + Vite + TradingView Lightweight Charts (커스텀 지양 원칙) | Accepted | 2026-04-27 |
| 0023 | Python 모듈 도입 (pyworker — FastAPI + Playwright + APScheduler) | Accepted | 2026-04-27 |
| 0024 | Docker 표준 — 모듈별 Dockerfile + compose 통합 기동 | Accepted | 2026-04-27 |
| 0025 | 데이터 저장소 — PostgreSQL 단일 인스턴스 + 스키마 분리 | Accepted | 2026-04-27 |
| 0026 | 객체 저장소 도입 — MinIO (S3 호환) | Accepted | 2026-04-27 |
| 0027 | v0 베이스라인 태그(`v0.1.0`) + `release/v0` immutable 보존 브랜치 | Accepted | 2026-04-30 |
| 0028 | v1.0.1 베이스라인 태그 + `release/v1` immutable 보존 + pre-push 신규 ref 허용 | Accepted | 2026-05-01 |

## 후속 예정(Proposed 후보)

- TradingView Advanced Charts 전환 — **트리거**: LWC 공식 API로 안 되는 기능(그리기·custom study·심볼 비교·고급 replay)이 사업적으로 분명히 필요해진 시점 (ADR 0022 §"커스텀 지양 원칙"에 명시)
- GitHub Actions CI 파이프라인 (5 서비스 matrix 빌드, ADR 0024 후속)
- Avro / Schema Registry 도입 (ADR 0020 후속, 프로듀서 ≥3팀 또는 v2 필요 시)
- KIS WebSocket 구독 상한(≈40) 대응 — 우선순위 큐 / 회전 구독
- TimescaleDB 도입 (ADR 0025 후속, 일봉 ≥1억 행 또는 틱 영속화 시)
- DB-per-service 전환 (ADR 0025 후속, 운영 부하 분산 + 인력 ≥2 시)
- 프롬프트 캐시·토큰 최적화 정책
- commitlint · Release-Please 도입 여부
- 실서비스 진입 시 QA 강제 게이트 회귀 (ADR 0016 sunset)
