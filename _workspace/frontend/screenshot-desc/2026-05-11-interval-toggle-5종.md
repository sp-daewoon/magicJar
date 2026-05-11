# IntervalToggle 5종 활성화 dogfooding 결과 설명
날짜: 2026-05-11
브랜치: agent/frontend/interval-toggle-5종
ADR: 0062·0063

## 화면 1: IntervalToggle 10종 버튼 노출
URL: http://localhost:5173/symbol/000660 (SK하이닉스)

### 변경 전 (8종)
버튼 순서: 1분 | 5분 | 30분 | 1시간 | 일 | 주 | 월 | 년

### 변경 후 (10종)
버튼 순서: 1분 | 5분 | 15분 | 30분 | 90분 | 1시간 | 일 | 주 | 월 | 년

추가된 버튼:
- "15분" — 15m 합산 (ADR 0062 precomputed)
- "90분" — 90m 합산 (ADR 0062 precomputed)

---

## 화면 2: 5m/15m/30m/90m/1h 빈 응답 graceful 처리
URL: http://localhost:5173/symbol/000660 → "15분" 버튼 클릭

AggregateBackfillRunner 실행 전이므로 빈 배열 반환 예상.
표시 내용:
- 황색 pulse 인디케이터
- "15m 합산 데이터 준비 중" 제목
- "1분봉 tick 데이터를 15m봉으로 집계하고 있습니다." 안내
- "AggregateBackfillRunner 실행 후 데이터가 표시됩니다."
- "잠시 후 새로고침해 주세요."

vs 기존 1m/1d 등 일반 인터벌 빈 응답:
- "캔들 데이터 수집 중" + "종목 추가 후 처음 데이터를 수집하고 있습니다."

---

## 화면 3: LWC X축 NXT 시간 범위 동작 분석
URL: http://localhost:5173/symbol/000660 → "1분" 버튼 → 1m 차트

### LWC v4 X축 동작 분석
- LWC v4는 `setData()` 또는 `update()`로 전달된 UTCTimestamp 기반으로 X축 범위를 자동 결정
- NXT 시장(08:00~20:00 KST) 데이터가 입력되면 X축이 해당 범위로 자동 확장
- 별도 `setVisibleRange()` 호출 불필요 — LWC v4의 `fitContent()` 호출만으로 전체 범위 표시
- `tickMarkFormatter`의 `Asia/Seoul` timezone 설정으로 KST 시간 올바르게 표시
- NXT 08:00~20:00 범위의 ts가 ASC 정렬로 입력되면 자동 정합

### 현재 상태 (market-data NXT hotfix 머지 전)
- backend에서 NXT 시각(08:00~09:00 및 15:30~20:00) 데이터 미수집
- consumer NXT cover hotfix 머지 후에야 실제 NXT 시각 캔들 수신 가능
- frontend LWC 측에는 추가 변경 불필요 (ts ASC → X축 자동 확장 정합)

---

## 빌드 검증
- `pnpm typecheck` GREEN (타입 에러 0건)
- `pnpm build` GREEN (1.25s, 7 bundle 파일)
- dev server: http://localhost:5173 200 OK
