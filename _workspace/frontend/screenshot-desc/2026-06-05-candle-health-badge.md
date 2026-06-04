# 캔들 수집 건강도 1줄 표시 (readiness P1)

화면: 종목 상세 `/symbol/:code`

## 표시 위치
- 차트 영역(`SymbolChart`) 바로 위, "차트 해석" AI 진입점(`AiExplainEntry type=CHART`) 아래.
- `CandleHealthBadge` 컴포넌트 (`data-testid="candle-health-badge"`).

## 데이터 출처
- `GET /api/symbols/{code}/candle-health` (orchestrator LOCK 계약, market-data 병렬 구현).
- hook `useCandleHealth`: 상세 진입 시 1회 + 60s 폴링(백그라운드 탭 멈춤).

## 상태별 모양 (QA 시각 회귀 포인트)
| status / reason | tone | 모양 | 예시 문구 |
|---|---|---|---|
| FRESH / OK_REALTIME | emerald(녹색) | 작은 점 + 짧은 라벨 (inline, 폭 최소) | "캔들 실시간 수집 정상" |
| STALE / NO_TICK·WS_NOT_SUBSCRIBED·INSERT_FAILED | amber | 경고 아이콘 + "캔들 수집 · {reasonDetail}" | "최근 체결이 수신되지 않아 캔들이 갱신되지 않았습니다" |
| NO_DATA / BACKFILL_FAILED·BACKFILL_PERMANENT_GAP | red | 경고 아이콘 + 1줄 | "해당 구간은 영구 갭으로 수집할 수 없습니다" |
| reason=MARKET_CLOSED (status 무관) | neutral(회색) | 정상 장외 — 위협 색 아님 | "장 마감 — 다음 거래일 개장 시 재개됩니다" |
| 조회 실패 (404/에러) | neutral(회색) | "캔들 수집 상태 확인 불가" (가짜 FRESH 금지) | — |
| 최초 로딩 (data·error 없음) | — | 렌더 없음 (저빈도 위젯, 스켈레톤 불필요) | — |

## 방어적 파싱
- 계약 밖 신규 reason 값이 와도 throw 없이 `reasonDetail` 원문을 그대로 1줄 표시.
- `reasonDetail`이 비면 reason 코드 기반 한국어 fallback 라벨.

## QA 확인 시나리오
1. 장중 정상 종목 → 작은 녹색 점 "캔들 실시간 수집 정상" (차트 레이아웃 변형 없음).
2. 1m stale 종목 → amber 1줄 사유 노출.
3. 장 마감 시간대 진입 → 회색 "장 마감" (빨강·노랑 아님).
4. 백엔드 candle-health 미배포 상태 → 회색 "캔들 수집 상태 확인 불가" (정상으로 오인 금지).
