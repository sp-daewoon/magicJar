# 단타 발굴 화면 (R2 — ADR 0117)

- URL: `/strategy/daytrade-discover`
- 진입: 헤더 네비 "전략" 그룹 → "단타 발굴" (그룹 첫 항목)
- 요구 API: `POST /api/screener/daytrade-discover` (백엔드 PR #835, main 머지) · `GET /api/watchlists`(등록 여부 표시) · `POST /api/watchlists`(명시적 단건 추가)
- 스크린샷: `2026-06-09-daytrade-discover.png` (초기 상태 — backend 미연결, OnboardingGuard isError graceful)

## 구성

1. 헤더 — "단타 발굴" + 설명("전략을 미리 작성하지 않아도 시스템 빌트인 단타 setup 프리셋으로 오늘의 후보 TOP 30을 점수와 함께 제시 · 장전 setup=일봉 · KIS 추가 호출 0 · 룰 기반 AI 호출 0").
2. 컨트롤 패널
   - 빌트인 프리셋 버튼 4종: 전체(합집합) · 갭·RVOL·거래대금(`DAYTRADE_GAP_RVOL_TURNOVER`) · 변동성 확장(`DAYTRADE_VOLATILITY_EXPANSION`) · 모멘텀 돌파(`DAYTRADE_MOMENTUM_BREAKOUT`). 선택 시 emerald 강조 + 하단 설명.
   - 실시간 관찰 토글 — "발굴 후보 실시간 관찰 시작 (30분)" + 가드 문구 "STRATEGY 실시간 시세 수요로만 등록됩니다. 관심종목(watchlist)에 추가되지 않습니다."
   - "후보 발굴" 버튼 (userId 없으면 disabled).
3. 결과 영역
   - 요약 row: Universe · 평가 · 후보 · 소요(ms/s) + (관찰 등록 시) "실시간 관찰 N종목 등록" 뱃지.
   - 프리셋 메타 카드: 서버 권위 `presets[]`(V75 시드 row)의 title·candidate_key·entryRules 노출 ("왜 이 setup인가").
   - skip 사유 뱃지 (`ScreenerSkipReasonsBadge` 재사용 — 캔들 미수집·부족 정직 표기, mock 0).
   - 후보 랭킹 테이블 (`DaytradeMatchTable`): 순위·종목명/코드·종합점수(bar)·점수구성(compact 4-bar). row 클릭 펼침 → 점수 분해 전체(`DaytradeScoreBreakdown` signal·volume·atr·cap 가중) + 시그널 사유 + 액션(종목 상세 링크 · 명시적 "관심종목 추가"). rank/score 정렬 토글.

## 빈/에러 상태 (mock 0)

- userId 없음 → "사용자 설정 미완 + 설정 화면 안내".
- 발굴 중 → 스피너.
- 에러 → 에러 메시지 + 재시도.
- 후보 0건 → evaluatedCount=0이면 "평가 가능한 일봉 데이터가 아직 없습니다", 캔들 skip이면 "일봉 데이터 수집 중인 종목 제외", 그 외 "조건 충족 후보 없음" + "현재 일봉 전수 수집 진행 중일 수 있음 (mock 후보 합성 안 함)".

## owner 가드 (자동 watchlist 삽입 0)

- 발굴 결과는 watchlist에 자동 write하지 않는다. `WatchlistBulkRegisterButton`(일괄 등록) 미사용 — 발굴 화면에서 import 0.
- 관심종목 추가는 row 펼침 후 사용자가 "관심종목 추가"를 명시적으로 클릭해야만 단건 `POST /api/watchlists` 발생. 이미 등록된 code는 "관심종목 등록됨" 표시(중복 회피).
- 실시간 관찰 토글(`startRealtimeWatch=true`)은 백엔드 STRATEGY owner WS 수요 등록일 뿐 watchlist write 0 (응답 `realtimeWatchRegistered`로 확인, watchlist는 항상 0).
- 자동 주문 버튼 0.

## 검증 비고

- typecheck·build GREEN. E2E(실데이터 후보 표시)는 일봉 전수 backfill 완료 후 검증 예정 (현재 부분 데이터 → 빈 상태가 정상).
