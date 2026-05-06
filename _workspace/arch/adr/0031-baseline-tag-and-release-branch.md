# ADR 0031: v0 베이스라인 태그 + release/v0 immutable 보존 브랜치

- 상태: Accepted
- 작성일: 2026-04-30
- 결정자: architect (사용자 협의)
- 관계: ADR 0009(브랜치 전략 v1), ADR 0013(단일 worktree·순차), ADR 0015(머지 1단 agent/* → main), ADR 0004(MSA 재구성)

## 맥락

Phase D(MSA 토대) + Phase E-P1·E-P2·E-P3(REST 5종 + KIS stub/real 분기 + e2e backtest + GitHub Actions CI/CD)까지 main에 머지된 시점이다. 사용자가 직접 `docker compose`로 9 컨테이너를 띄워 동작을 확인했다.

이 시점의 main은 **"껍데기 v0"** — 모듈·계약·CI 골격은 갖췄으나, 실 매매·UI 살·버그 수정은 앞으로 main 트렁크에서 살을 붙여나갈 베이스다. 문제는:

1. 앞으로 기획 변경·버그 수정·기능 추가가 누적되면서 "껍데기 베이스"가 묻혀 사라질 위험
2. 어딘가에서 큰 결정이 잘못되면 "그 껍데기 시점으로 되돌아가서 다시 시작"하고 싶을 수 있음
3. v0 시점이 명시되지 않으면 git history를 길게 거슬러 올라가야 함

## 검토한 대안

| 대안 | 동작 | 부담 | 적합성 |
|------|------|------|--------|
| A. 태그만 (`v0.1.0`) | 태그 찍고 main에서 계속 진행 | 0 | 태그가 immutable이라 충분하지만 브랜치로 가시화 안 됨 |
| B. **태그 + `release/v0` immutable 보존 브랜치** (본 결정) | `1aa3949`에 태그 + 브랜치 양쪽 생성, 브랜치는 push·merge·delete 모두 차단 | 낮음 | 베이스라인 가시화 + 안전망 + trunk 정신 유지 |
| C. GitFlow(develop/main 분리) | develop 트렁크 + main=릴리스 | 높음 | 1인 owner에 과함, ADR 0015와 충돌 |

## 결정

**옵션 B 채택.**

### 1. 태그
- **`v0.1.0`** annotated tag, `1aa3949` (Phase D+E 완료 시점) 위에 생성
- 메시지에 Phase D/E 완료 사실과 "살 붙이기 베이스" 명시
- 의미: "v0 = 껍데기 라인". v1은 실제 매매·UI 살이 붙어 운영에 들어갈 시점에 별도 선언

### 2. `release/v0` 브랜치
- `1aa3949` 동일 commit에서 분기, **immutable**
- 용도: **체크아웃 전용**. "그 시점 코드를 다시 보고 싶다"·"베이스 비교"·"바닥부터 재시작" 용
- v0 hotfix는 받지 **않는다**. 껍데기는 그대로 보존하고, 새 보정은 main 트렁크에서 전진하며 다음 베이스라인(`v0.2.0` 또는 `v1.0.0`)으로 컷

### 3. 보호 메커니즘
- **로컬 가드(즉시 적용)**: `.husky/pre-push`에 `refs/heads/release/*` push 차단 룰 추가. 1인 owner 환경 + ADR 0017 author 가드와 결합되면 사실상 immutable
- **원격 가드(보류)**: GitHub Branch Protection / Repository Rulesets은 private repo + Free 플랜에서 미지원(403). 다음 중 사용자 결정에 따라 후속 처리:
  - (a) repo public 전환 → classic protection / rulesets 사용 가능
  - (b) GitHub Pro 업그레이드 → private에서도 사용 가능
  - (c) 현 상태 유지 → 로컬 가드 + 정책 명문화 + 1인 운영 신뢰로 충분

### 4. 운영 규약
- **살 붙이기·버그 수정**: 지금 흐름 그대로 `agent/<role>/<topic>` → `main` (ADR 0015 유지)
- **다음 베이스라인 컷**: main이 안정적이고 의미 있는 단계 도달 시 `vX.Y.0` 태그 + `release/vX.Y` 분기. 직전 release 브랜치는 그대로 보존
- **v0 시점 코드 참조**: `git checkout v0.1.0` 또는 `git checkout release/v0` (둘 다 동일 commit)
- **CI/CD 영향**: `release.yml`이 `v*.*.*` 태그에 반응 → ghcr.io 이미지 + GitHub Release draft 자동 생성 (ADR 0004 운영 차원)

## 비-결정(Non-Goals)

- **GitFlow 도입**: 1인 owner + ADR 0013/0015 정신과 충돌. 다인 협업이 시작되면 재검토
- **`release/v0`에 hotfix 머지**: 껍데기는 immutable. 살 붙이기는 main에서만
- **태그 재할당·force-push**: 어떤 경우에도 금지. v0.1.0이 잘못되었다면 `v0.1.1` 새로 컷

## 결과

- 안전망: "껍데기로 되돌아갈" 명확한 좌표가 생김
- trunk 정신 유지: ADR 0015 단일 머지 흐름 그대로
- 부담 최소: 새 워크플로우 학습 비용 0
- 후속 결정 거리: 원격 보호 활성화 옵션 (사용자 결정 시점에 ADR 보강)

## 참고

- 베이스라인 컷 시 `git push origin v<X.Y.Z>` + `git push origin release/v<X.Y>`
