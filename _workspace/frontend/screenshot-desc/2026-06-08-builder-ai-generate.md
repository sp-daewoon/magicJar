# 전략빌더 AI 전략 생성 UI (2026-06-08)

URL: `/strategy/builder` · 백엔드 `POST /api/strategies/ai-generate` (PR #793)

## 진입 버튼
- 위치: `StrategyBuilderCanvas` 하단 액션 버튼 행 맨 앞.
- 레이블: "AI 전략 생성" · violet(`bg-violet-600`) — AI 기능 일관. label 한 줄(래핑 금지).
- 우측에 "자연어로 입력하기 / DSL 직접 편집 / DSL 다운로드 / DSL 업로드" 기존 버튼 공존.

## 팝업 (StrategyAiGenerateModal)
- 오버레이 `bg-black/70`, 카드 `max-w-2xl` · violet 테두리 · `useModalBehavior`(ESC 닫기 + body scroll lock).
- 헤더: "AI 전략 생성" + "전략 의도를 적으면 Claude가 빌더에 바로 불러올 수 있는 후보를 설계합니다" + 닫기 버튼.
- 입력 영역:
  - "전략 의도 (필수)" textarea — placeholder "예: 거래량이 터지면서 20일선을 돌파하는 단타 / 과매도 반등 스윙". 빈 값 생성 시 amber 검증 메시지.
  - "기준봉" select (빌더 현재 interval 기본) + "제안 수" select(1~3개, 기본 1개).
  - "생성하기" 버튼(violet, Ctrl+Enter) + 성공 시 provider 표시.
- 로딩: violet 박스 "Claude가 전략을 설계 중입니다 · 최대 수 분까지 기다립니다" + 진행 바.
- 에러: 503 안내 / 일반 실패 메시지 (red 박스).
- warnings: amber 박스(예: 잔익절 경고).

## 제안 카드 (item별)
- name(굵게) + description(muted) + 우측 "빌더로 불러오기" 버튼(violet).
- entryDsl(emerald `<pre>`) / exitDsl(rose `<pre>`, 있을 때만).
- riskPlan 요약: "익절 1.5% · 손절 0.8% · 최소보유 3봉" (미지정 시 "리스크 플랜 미지정").
- rationale(muted).
- `dslValid=false` 카드: "빌더로 불러오기" 비활성(회색 + tooltip) + red 박스 "DSL 검증 실패: <dslError> — 불러오기가 비활성화되었습니다."

## 불러오기 후 (빌더 본문)
- entryDsl/exitDsl이 빌더 트리에 주입(DSL 미리보기 갱신) + canvasTab=entry.
- 우측 결과 영역에 violet "AI 생성 후보" 인디케이터: name + riskPlan 요약 + amber "저장하지 않았습니다. 검토·검증·평가 후 직접 저장하세요."
- 저장은 사용자가 "저장" → StrategySaveModal(name·익절·손절·최소보유봉 초기값 = AI 후보) 직접 수행. **자동 저장 0** (ADR 0112 AI 결정권 0).
- 기존 작성 중 조건이 있으면 confirm 후 덮어쓰기.
