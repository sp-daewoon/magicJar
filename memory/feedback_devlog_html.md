---
name: 개발일지 HTML 매일 관리 정책
description: /devlog 호출 시 _workspace/devlog/YYYY-MM-DD[-N].html 신규 작성(덮어쓰기 금지) + index.html 갱신. 사용자 명시 호출에만.
type: feedback
originSessionId: 64638064-5610-474e-8f54-b9d51c8468ab
---
사용자 명시(2026-05-03): "이것도 오늘 개발일지처럼 저장해놓게 html도 이런식으로 매일 관리하자"
사용자 명시(2026-05-05): "devlog 남길 때 중복으로 html 덮어쓰지 말고 파일 패턴 바꿔서 만들 때마다 증가하게 변경해줘"

**Why:** 누적 작업 이력을 사용자가 한눈에 추적할 수 있는 매일 단위 개발일지. 세션 컨텍스트가 다음 세션에 끊겨도 HTML로 영속. 같은 날 여러 회차 호출 시 직전 스냅샷이 남아야 시점별 비교가 가능 — 그래서 덮어쓰기 X, 회차 +1 신규 파일.

**디렉토리 구조:**
- `_workspace/devlog/YYYY-MM-DD.html` — 같은 날 첫 회차
- `_workspace/devlog/YYYY-MM-DD-2.html`, `-3.html`, … — 같은 날 N회차 (시퀀스 +1, base 파일 존재 시 -2부터)
- `_workspace/devlog/index.html` — 전체 항목 목록 (최신 우선, 회차마다 별도 `<li>`)

**HTML 표준 섹션 (7개):**
1. 머지 PR 매트릭스 (트랙·에이전트·모델)
2. PostgreSQL 테이블 변경 (Flyway 추가분)
3. Kafka 토픽·데이터 흐름 변경
4. Redis 사용처 변경
5. sub-agent 디스패치 내역 (호출 횟수·이슈/복구)
6. sanity/dogfooding 결과 (화면·데이터·컨테이너 상태)
7. 다음 세션 일정 (우선순위·사이즈)

**디자인:** inline CSS·SF Pro/Pretendard 폰트·표 위주·badge 색상 (b-ok 초록·b-warn 노랑·b-pending 파랑·b-todo 분홍).

**How to apply:**
- **`/devlog` 슬래시 커멘드로만 트리거** (자동 작성 X — 사용자 명시 호출 시에만)
- 커멘드 정의: `.claude/commands/devlog.md`
- 사용자가 "/devlog" 호출 시 그 시점까지 작업 누적 정리
- **호출할 때마다 신규 파일 생성, 덮어쓰기 금지.** 같은 날 첫 호출은 `YYYY-MM-DD.html`, 두 번째부터 `YYYY-MM-DD-2.html`·`-3.html` …
- 시퀀스 산출: 해당 날짜로 시작하는 기존 파일 중 최대 번호 + 1 (base만 있으면 -2부터)
- index.html: 같은 날 회차도 별도 `<li>` 추가 — 갱신 X, 누적 O. 두 번째 회차부터 링크 텍스트에 `#2`, `#3` 명시
- HTML 헤더에 회차 번호 + 작성 시각(`HH:mm KST`) 표기
- 첫 세션 진입 시 `_workspace/devlog/index.html` 확인. 새 작성은 사용자 호출 후

**참조 예시:** `_workspace/devlog/2026-05-03.html` (v2 베이스라인 + W1~W4 + 24 PR 머지)
