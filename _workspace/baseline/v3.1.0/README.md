# magicJar v3.1.0 Baseline — W1 마감 시점 종합 기능 정의서

> **작성 시점**: 2026-05-08 v3.1.0 태그 컷 (main `6b83899`, release/v3.1)
> **사이클**: PR-5 ~ PR-29d (30+ 사이클, ~3주)
> **상태**: skeleton — sub-agent 본격 작성 대기 (PR-30 사이클)

## 목적

W2(80지표·57패턴·DSL·Strategy Builder) 진입 직전, **현재 시스템이 무엇을 할 수 있는지** 시각화 가능한 수준으로 종합 정리. 향후 W2~W5 진입 시 각 트랙이 본 baseline 위에 쌓이는 구조 가시화.

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | Mermaid 4 모듈 + 외부 통합 + frontend 아키텍처 그림 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름 + Kafka 토픽 매트릭스 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 (셋업·Watchlist·Symbol·시세 fallback·corp action) + KIS-first raw 출처 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | W1 7 트랙 기능 명세 상세 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리 |
| [06-history-traceability.md](06-history-traceability.md) | architect | PR·ADR·기능 매핑 추적성 매트릭스 |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 운영 헌법 7§ + ADR 진화 어필 |

## 한 장 요약 (TBD — architect 본격 작성)

W1 ★ 트랙 6건 풀세트 클리어:

- T-ONBOARD ★★ — 셋업 마법사 + AES-256-GCM + 3 테이블
- T-W1 ★ — Watchlist CRUD + Position Bootstrap
- T-W2 ★ — Ingestion Worker (KIS WS H0STCNT0/H0STASP0 + Yahoo + 41 stream slot)
- T-S1 ★ — Symbol Detail + LWC 차트 + 호가 STOMP
- T-MS1 — Market Session FSM + Fallback 3-tier + REJECTED Audit + Flyway V6
- T-CA1 — Corporate Action + Adjusted Price + Flyway V7/V8 + KIS ksdinfo 6 endpoint + DART
- T-IM1 — Instrument Master KOSPI 70 + KOSDAQ 64 widths + 83 필드 + Redis 자동완성

ADR 누적 47→52건. 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § 룰 통합.

## 작성 규약

- 본문은 한국어, 코드/명령은 영어 그대로
- Mermaid 다이어그램 의무 (시각화 가능 수준)
- raw 출처 인용 의무 (KIS API · DART · 코드 path 명시)
- 추측·관념적 서술 금지 — 코드 inspection 결과만 인용
- TBD 표시: 미작성 영역은 명시
