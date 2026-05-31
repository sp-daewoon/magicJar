# v3.10.0 Policy Evolution — Direction Guard Applied

- baseline: v3.10.0
- 작성일: 2026-05-31
- 작성자: architect/orchestrator

## 방향성 보존 메모

Current direction: magicJar는 KIS-first 실제 데이터와 룰 기반 자동매매 본체를 유지한다. AI와 스크리너는 보조 도구이며, 주문 결정권과 자본 path를 임의로 바꾸지 않는다.

Change intent: FVG/오더블럭 후보 탐색에 필요한 기준봉과 데이터 복구 UX를 추가한다.

Direction impact: preserved. 기존 자동매매 본체는 그대로 두고 캔들/후보 탐색의 입력 품질과 사용성만 보강한다.

Risks checked: data gaps, KIS rate/시간 제약, API 계약, DB migration, frontend 오해 가능성, 검증 범위.

## 정책 결정

| 결정 | 내용 |
|------|------|
| mock 금지 | 데이터가 없으면 없다고 표시한다 |
| `1d` 기본 | 전종목 넓은 후보 탐색은 일봉을 기본으로 한다 |
| 사용자 실행 | 스크리너는 특정 시간대 기능이 아니라 on-demand 기능이다 |
| 1m 원천 | intraday 집계는 가능한 한 KIS 1m 정규장 데이터를 원천으로 한다 |
| 재수집 주의 | delete/rebuild 동작은 사용자 확인 후 실행한다 |

## Claude/Codex skill 정합

| 위치 | 변경 |
|------|------|
| `CLAUDE.md` | 비트리비얼 변경 전 direction guard 적용 지시 |
| `.claude/skills/magicjar-direction-guard/SKILL.md` | repo-local skill 보존 |
| Codex global skill | `/Users/ak-song/.codex/skills/magicjar-direction-guard/SKILL.md` |

## 롤백

| 대상 | 롤백 방법 |
|------|-----------|
| UI recollect | frontend SymbolChart 변경 revert |
| API recollect | `SymbolController`/`CandleRefreshService` 변경 revert |
| 3m interval | Flyway down 없음. 배포 전이면 migration 제외, 배포 후에는 후속 migration 필요 |
| screener basis | request interval 기본값 `1d` 유지하며 selector 제거 가능 |

## 운영 리스크

| 리스크 | 판단 |
|--------|------|
| KIS 호출량 증가 | 재수집은 사용자 명시 동작. 대량 자동 호출 아님 |
| 데이터 삭제 | credential preflight로 무자격 삭제 방지 |
| 시그널 오판 | mock 데이터 금지로 가짜 후보 방지 |
| 방향 전환 | 주문/리스크/AI 결정권 변경 없음 |

