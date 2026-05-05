---
name: spec/plan 발행 범위 — 큰 신규 트랙만
description: 결함 정정·hotfix·정책 정합·작은 모듈 변경은 spec/plan 0건. spec/plan은 신규 트랙·새 모듈·다중 ADR 발행 시에만.
type: feedback
originSessionId: c9a91b29-bcfe-45a7-b32a-e9aaed0267c9
---
spec/plan 발행은 **신규 트랙 + 새 모듈/다중 ADR 발행** 작업에만 한정한다. hotfix·정책 정합 정정·작은 묶음 변경은 spec/plan 없이 바로 코드.

**Why:** 2026-05-05 사용자 지적. R3-5c-3 트랙(spec 12 decisions + 1885줄 plan) 패턴이 그대로 다음 hotfix 묶음 작업에 자동 전이되어 토큰 무자비하게 소비. PRs #36·#38·#39·#40·#41·#66 같은 hotfix는 모두 spec/plan 없이 바로 진행했던 게 정상. 사용자 결정이 이미 명확한 작업도 spec/plan으로 우회하려 한 것은 잘못.

**How to apply:**
- spec/plan 작성 대상: 신규 트랙(R3-5c-3·msa-restructure·qa-defect-loop·track1·track2 등) + 12+ 파일·새 모듈·다중 ADR 발행이 동시에 발생할 때만
- spec/plan 0건 대상: 결함 정정 묶음, 정책 회귀 hotfix, 단일 모듈 변경, 사용자 결정이 인용 가능할 만큼 명확한 작업, 외부 API 응답 헤더 추가 등
- 사용자가 결함 발견·진단 보고 후 "잡고 가자" 같은 신호를 주면 곧장 코드 변경. plan 보여주는 단계 0.
- 큰 작업이라도 사용자가 "spec/plan 빨리 끝내고 코드"라고 신호하면 그에 맞춰 압축.
- 2026-05-05 사이클 사례: 결함 5건(days=30·DB INSERT·Kafka 중복·Redis 미충전·throttle)을 spec/plan 0건 + 단일 PR로 처리.
