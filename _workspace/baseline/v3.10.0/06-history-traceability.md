# v3.10.0 History and Traceability

- baseline: v3.10.0
- 작성일: 2026-05-31
- 작성자: architect/orchestrator

## Git 시계열

| commit | 내용 |
|--------|------|
| `8b610491` | smart money high timeframe strategy support |
| `49edc9e2` | 12h candles fixed session boundary |
| `61ca592e` | intraday candles KRX regular session alignment |
| working tree | 3m interval, screener basis, recollect, policy/docs/tests/release artifacts |

## 정책/문서 연결

| 문서 | 역할 |
|------|------|
| `docs/architecture/candle-data-policy-v2.md` | 캔들 수집/집계/결측 정책 |
| `docs/architecture/external-api-policy.md` | KIS 호출/운영 제약 |
| `CLAUDE.md` | Claude 운영 규칙 + direction guard |
| `.claude/skills/magicjar-direction-guard/SKILL.md` | repo-local skill |
| `_workspace/INDEX.md` | 단일 진입점 |

## 검증 추적

| 범위 | 명령 |
|------|------|
| domain | `./gradlew :domain:test --tests "...CandleAggregatorTest" --tests "...BoundaryDetectorTest"` |
| consumer | `./gradlew :consumer:test --tests "...BoundaryClosingKafkaListenerTest"` |
| api | `./gradlew :api:test --tests "...ScreenerServiceTest" ...` |
| frontend tests | `pnpm --dir frontend test -- ...` |
| frontend typecheck | `pnpm --dir frontend typecheck` |
| whitespace | `git diff --check` |

## 릴리즈 산출물

| 산출물 | 위치 |
|--------|------|
| release row | `CHANGELOG.md` |
| baseline | `_workspace/baseline/v3.10.0/` |
| baseline html | `_workspace/baseline/v3.10.0/html/` |
| release table | `README.md` |
| master index | `_workspace/master.html` |
| task | `_workspace/tasks/T-20260531-1759-v3-10-0-candle-orderblock-release.md` |

## 미해결/후속 후보

| 후보 | 이유 |
|------|------|
| KIS 1m 장외 수집 대체 정책 | 외부 데이터 소스 도입 여부는 별도 결정 필요 |
| 재수집 실패 후 복구 UX | 현재는 실패 시 interval 공백 가능 |
| full visual E2E | dev server 확인은 했으나 release 전 별도 Playwright 확대 가능 |

