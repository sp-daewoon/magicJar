## 요약
<!-- 1~3줄. 무엇을 바꿨고 왜. -->

## 담당
- Agent: <!-- 예: strategy-engine -->
- Skill(s): <!-- 예: magicjar-backtest-engine, magicjar-trading-journal -->
- 관련 ADR: <!-- 예: 0006, 0007 -->

## 변경 유형
- [ ] feat (새 기능)
- [ ] fix (버그 수정)
- [ ] refactor (동작 유지)
- [ ] docs / chore / test
- [ ] UI / 사용자 흐름에 영향 있음 → 스크린샷 첨부 의무 (ADR 0011)

## 테스트 방법
<!-- 빌드·단위·수동 시나리오. 예:
- `./gradlew :strategy:test`
- `./gradlew :app:bootRun --args='--spring.profiles.active=mock'` → `/journal` 접속
-->

## QA 체크리스트 (QA 에이전트 작성)
- [ ] 경계면 매트릭스 재검증 (`_workspace/qa/boundary-matrix.md`)
- [ ] 스모크 시나리오 통과
- [ ] 스크린샷 저장 완료 (`_workspace/qa/screenshots/<slug>/` + `INDEX.md`) — UI 변경 시 필수
- [ ] 보고서 생성 (`_workspace/qa/report-<ts>.md`, `Status: APPROVED`)

## Screenshot Summary
<!-- UI 변경 있을 때만. 핵심 화면 1장 이미지 링크 + 나머지 경로 설명.
예:
![journal-list](../../_workspace/qa/screenshots/agent-frontend-journal-list/01-home-happy.png)
나머지는 `_workspace/qa/screenshots/agent-frontend-journal-list/` 참고.
-->

## 비고
<!-- 후속 작업·미결 이슈·리뷰 포인트 -->
