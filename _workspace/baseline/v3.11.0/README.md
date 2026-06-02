# magicJar v3.11.0 Baseline — Scenario Replay Sandbox 진입 시점 종합 기능 정의서

> **작성 시점**: 2026-06-03 KST v3.11.0 baseline 진입 (main `9c5b1677` 이후)
> **사이클**: v3.10.1 운영 패치 이후 T-SIM 시나리오 리플레이 설계 진입
> **상태**: skeleton — Scenario Replay Sandbox spec/ADR 고정 후 구현 단계 대기

## 목적

v3.10.1까지의 자동매매·캔들·리스크 운영 패치를 기준점으로 고정하고, 장중이 아니어도 전략 신호·리스크·가상 주문·가상 체결·오류 이벤트를 QA처럼 재현할 수 있는 `T-SIM` Scenario Replay Sandbox로 진입한다.

핵심 방향은 실전 투자와 같은 전략/리스크 시나리오를 사용하되, live 주문 Kafka·KIS 주문 adapter·live 주문/리스크 테이블·Slack TRADE 알림과 충돌하지 않는 별도 런타임으로 분리하는 것이다.

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | Mermaid 4 모듈 + 외부 통합 + frontend 아키텍처 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름 + Kafka 토픽 매트릭스 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 + KIS-first raw 출처 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | 트랙 기능 명세 상세 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리 |
| [06-history-traceability.md](06-history-traceability.md) | architect | PR·ADR·기능 매핑 |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 운영 헌법 + ADR 진화 |

## 한 장 요약 (TBD)

(architect가 본격 작성)

## 작성 규약

- 본문 한국어, 코드/명령은 영어 그대로
- Mermaid 다이어그램 의무
- raw 출처 인용 의무 (KIS API · DART · 코드 path)
- 추측·관념적 서술 금지
- TBD 표시 — 미작성 영역
