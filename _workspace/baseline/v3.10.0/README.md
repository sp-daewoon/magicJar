# magicJar v3.10.0 Baseline — Candle/Orderblock Screening Release

> **작성 시점**: 2026-05-31 v3.10.0 태그 컷 준비
> **사이클**: v3.9.1 → v3.10.0 minor
> **상태**: 통합 baseline. Codex가 Claude 운영 규칙을 기준으로 단일 worktree에서 작성.
> **권위**: 본 baseline은 릴리즈 시점 요약이며, 현행 정책 권위는 `docs/architecture/candle-data-policy-v2.md`, `docs/architecture/external-api-policy.md`, `_workspace/INDEX.md`이다.

## 한 장 요약

v3.10.0은 FVG/오더블럭 후보 탐색을 위해 캔들 기반을 넓힌 minor release다. 방향은 기존 KIS-first 원칙을 유지한다. 데이터가 비는 구간을 mock으로 채우지 않고, KIS 정규장 1분봉을 원천으로 삼아 가능한 구간만 저장하고, 사용자가 필요할 때 스크리닝과 차트 재수집을 실행할 수 있게 한다.

핵심 변화는 세 가지다.

1. `3m`을 정식 candle interval에 추가하고 `4h`, `12h`까지 집계·경계·시그널 흐름에 연결했다.
2. 스크리너를 "밤 스크리닝" 같은 시간대 개념이 아니라 사용자가 언제든 실행하는 후보군 탐색 기능으로 정리했다. 기준봉은 `1d` 기본이며 `1m`부터 `1y`까지 선택 가능하다.
3. 차트 캔들이 이상하게 모였을 때 사용자가 현재 interval을 삭제 후 재수집할 수 있는 `재수집` 경로를 추가했다. 집계봉은 1분봉 소스를 다시 확보한 뒤 재집계한다.

자본 흐름은 변경하지 않았다. 주문, 리스크 게이트, 실계좌 3중 게이트, AI 매매 결정권 0 원칙은 v3.9.1 그대로다.

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | 캔들/스크리너 변경의 아키텍처 경계 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 수집, 집계, 재수집, 스크리닝 데이터 흐름 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 시나리오와 KIS 1분봉 제약 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | 기능 명세와 API/DB/UI 계약 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | Symbol Chart와 Screener UX 변화 |
| [06-history-traceability.md](06-history-traceability.md) | architect | 커밋, 정책, 검증 추적 |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 방향성 가드와 잔여 리스크 |

## v3.9.1 → v3.10.0 변경 매트릭스

| 영역 | v3.9.1 | v3.10.0 |
|------|--------|---------|
| 캔들 interval | 1m, 2m, 5m, 15m, 30m, 90m, 1h 중심 | 3m 추가, 주요 집계봉 4h/12h 포함 |
| 후보 탐색 | 전략/패턴 호출 경로 중심 | 사용자가 언제든 실행하는 screener basis interval 선택 |
| 기본 넓은 수집 | 분봉 중심 사고가 섞임 | 전종목 넓은 갱신 기본은 `1d` |
| 집계 원천 | 1m 기반 유지 | 1m KIS 정규장 제약 명시 + 집계봉 재생성 |
| 데이터 이상 대응 | 운영자/개발자 수동 조치 | Symbol 차트에서 현재 interval 재수집 |
| mock 데이터 | 금지 | 금지 유지. 결측은 결측으로 표시 |
| 자본 흐름 | 주문/리스크/체결 path | 변경 없음 |

## 릴리즈 체크

| 항목 | 상태 |
|------|------|
| main 최신 반영 | `origin/main` 기준 포함 |
| 방향성 가드 | `magicjar-direction-guard` 기준으로 정책/UX/운영 영향 확인 |
| DB 변경 | Flyway `V58__candle_add_3m_interval.sql` |
| 릴리즈 문서 | CHANGELOG, README, baseline, master index 갱신 대상 |
| 검증 | domain, consumer, api targeted tests + frontend test/typecheck |

