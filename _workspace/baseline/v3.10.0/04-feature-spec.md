# v3.10.0 Feature Spec — Candle Basis and Recollect

- baseline: v3.10.0
- 작성일: 2026-05-31
- 작성자: backend-core/orchestrator

## 기능 1. 3m interval 정식 지원

| 항목 | 내용 |
|------|------|
| REST | Symbol candle 조회/refresh, indicator/pattern/backtest DTO interval 목록 |
| 도메인 | `CandleInterval.THREE_MIN`, `BoundaryDetector`, `CandleAggregator` |
| 영속 | `V58__candle_add_3m_interval.sql` |
| Kafka | candle closed interval payload 확장, topic family 유지 |
| UI | Symbol interval toggle, candle count cap |
| 테스트 | domain aggregator/boundary, api binding/controller, frontend cap tests |
| 한계 | KIS 1m 원천이 없으면 3m 집계도 만들 수 없음 |

## 기능 2. 4h/12h 주요 기준봉

| 항목 | 내용 |
|------|------|
| 목적 | FVG/오더블럭 HTF 기준 확인 |
| 집계 | regular session boundary 기준 |
| 화면 | Symbol chart, screener basis selector |
| 시그널 | boundary closing listener interval 목록 확장 |
| 한계 | 한국 정규장 길이상 12h는 고정 세션 집계 성격이 강함 |

## 기능 3. 사용자 실행형 스크리너

| 항목 | 내용 |
|------|------|
| REST | `POST /api/screener/evaluate` request에 `interval` |
| 기본값 | `1d` |
| UI | Screener modal basis interval select |
| MCP | `run_screener` tool interval enum 확장 |
| 정책 | "밤 스크리닝" 이름 제거. 사용자가 언제든 실행 |
| 한계 | universe coverage는 데이터 보유량에 의존 |

## 기능 4. 캔들 재수집

| 항목 | 내용 |
|------|------|
| REST | `POST /api/symbols/{code}/candles/recollect?intervals=...` |
| 서비스 | credential preflight → delete → refresh → aggregate rebuild |
| UI | Symbol Chart `재수집` 버튼 + 확인창 |
| 응답 | `refreshedIntervals`, `elapsedMs` |
| 보호 | credential 없음이면 삭제 전 차단 |
| 한계 | 삭제 후 KIS 실패 시 해당 interval은 임시 공백 가능 |

## 기능 5. 방향성 가드 문서화

| 항목 | 내용 |
|------|------|
| Claude | `CLAUDE.md`, `.claude/skills/magicjar-direction-guard/SKILL.md` |
| Codex | `/Users/ak-song/.codex/skills/magicjar-direction-guard/SKILL.md` |
| 목적 | 코드 방향 전환, 사이트 이슈, 데이터/자본/API/운영 영향 사전 점검 |

## 릴리즈 리스크

| 리스크 | 완화 |
|--------|------|
| KIS 1m 장외 공백 | `1d` 기본 screener + 결측 명시 |
| 재수집으로 interval 비움 | credential preflight, 사용자 확인창 |
| interval 목록 불일치 | backend/frontend/domain/consumer targeted tests |
| 기존 전략 방향 흔들림 | 주문/리스크 path 미변경 |

