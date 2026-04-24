# magicJar ADR 목차

아키텍처 결정 기록(Architecture Decision Record) 인덱스. 번호는 증가만, 대체는 새 ADR 발행.

| 번호 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| 0001 | 언어·프레임워크·빌드 시스템 선택 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle Kotlin DSL) | Accepted | 2026-04-25 |
| 0002 | Gradle 멀티모듈 구조와 모듈 경계 | Accepted | 2026-04-25 |
| 0003 | 증권사 API로 한국투자증권 KIS OpenAPI 선택 | Accepted | 2026-04-25 |
| 0004 | 영속 저장소와 캐시 전략 (PostgreSQL + Redis) | Accepted | 2026-04-25 |
| 0005 | LLM 공급자와 Spring AI 추상화 (Anthropic + Ollama 폴백) | Accepted | 2026-04-25 |
| 0006 | 주문 실행기 3-mode 구조와 안전 게이트 | Accepted | 2026-04-25 |
| 0007 | 매매일지(Trading Journal) 아키텍처 | Accepted | 2026-04-25 |
| 0008 | 하네스 운영 페이지(`/harness`) 노출 정책 | Accepted | 2026-04-25 |
| 0009 | 브랜치 전략과 머지 규칙 (Feature-branch + QA 게이트) | Accepted | 2026-04-25 |
| 0010 | Git 훅 관리 — Husky v9 | Accepted | 2026-04-25 |
| 0011 | QA 스크린샷 게이트 | Accepted | 2026-04-25 |
| 0012 | Feature → QA → Main 2단 머지 흐름 (ADR 0009 보완) | Accepted | 2026-04-25 |

## 후속 예정(Proposed 후보)

- 0013: GitHub Actions CI 파이프라인 (build / check / qa-gate 자동화)
- 0014: KIS WebSocket 구독 상한(≈40) 대응 설계 (우선순위 큐 / 회전 구독)
- 0015: 실시간 시세 파이프라인 — Redis Stream vs Pub/Sub 선택
- 0016: 캔들 저장소 스케일업 트리거 및 TimescaleDB 도입 기준
- 0017: 프롬프트 캐시·토큰 최적화 정책
- 0018: commitlint · Release-Please 도입 여부
