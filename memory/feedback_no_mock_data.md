---
name: Mock 데이터 사용 금지 정책
description: 실서비스 흐름에서 mock 합성 데이터 사용 금지. backend 0건/에러 시 "데이터 없음" 명시 표시
type: feedback
originSessionId: 64638064-5610-474e-8f54-b9d51c8468ab
---
frontend·backend 어디서든 **사용자가 보는 화면 흐름**에서 mock 합성 데이터를 만들어 보여주는 패턴은 폐기한다. backend가 데이터를 못 주면 frontend는 "데이터 없음" 또는 명확한 에러 상태를 표시한다.

**Why:** 2026-05-03 사용자 명시 지적 — "왠만하면 쓰지말아줘". 2026-05-04 정정·강화 — "mock 정보는 너 혼자 junit할 때만 쓰셔". 운영 main src에 mock 합성 코드(MockTickGenerator·mock fallback 등)가 있는 것 자체가 정책 위반. 사이클 후보·plan에 mock 영역을 등장시키는 것도 위반. junit 테스트 fixture가 유일한 mock 영역.

**How to apply:**
- **운영 main src에 mock 합성 0건** — `MockTickGenerator`·`MockTickProperties`·`mock-tick.codes` 같은 운영 코드는 src/main에서 전부 제거 대상. junit src/test로 이전 또는 영구 삭제.
- mock spring profile(`application-mock.yml`)도 시스템 본체 운영에 사용 안 함. CI fixture가 필요하면 testcontainers·@TestConfiguration·MockK으로 처리.
- frontend `useXxx` hook의 try/catch에서 mock fallback 반환 패턴 폐기. backend 에러는 그대로 노출 또는 "데이터 없음" UI.
- frontend `MockTickGenerator`·`mock` flag·`mockReturnData`·`generateMockX` 등 식별자 광범위 검색·제거.
- 차트·카드·메타 panel이 데이터 0건이면 비워두거나 명확한 안내(예: "지수 데이터 미수신", "분봉 데이터 없음 — 장 시작 후 누적 예정")로 표시.
- 미니 차트 같은 시뮬레이션 도형도 backend 응답 없으면 표시 X (의사 데이터 합성 X).
- **다음 사이클 후보·handoff·plan에 "mock 활성화/전환/유지" 항목 등장 금지** — 모두 제거 또는 junit 이전이 default.
- 예외:
  - 단위 테스트(Vitest·MockK·@TestConfiguration 등) 안의 mock — 변경 영향 X
  - 개발용 stub adapter (e.g. `StubKisRestAdapter`)는 src/main 유지 가능 — 단 운영에서 활성 안 됨(`stub-kis` profile은 CI 한정)
- 실제 외부 source 끊김 시 cache 또는 last-known good 데이터로 fallback은 OK. 단 그 fallback이 사용자 인지 가능한 표시(예: "1분 전 데이터") 동반.
