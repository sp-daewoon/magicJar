# ADR 0049 — GitHub Actions = release 트리거 한정 (일상 검증은 pre-push hook 단독)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: 사용자(최고 관리자) · architect 정합 발행
- 관계: ADR 0008(Docker 표준) · ADR 0031(베이스라인 + release/v* immutable) · ADR 0032(Husky git hooks) · ADR 0034(머지 흐름 단순화)

## 1. 컨텍스트

이전 정책(ADR 0008·0030 §10 "CI/CD" 절)은 PR 검증·main push 이미지 발행·release 태깅을 모두 GitHub Actions에서 수행했다. 결과:

1. **외부자원 블로커** — billing 한도·private repo runner quota·workflow 의존(actions/setup-java, gradle/actions/setup-gradle 등)이 한 번이라도 깨지면 PR 머지가 정지. 사용자(최고 관리자) 관찰 — "외부자원 의존 자체가 자동매매 본체 개발 속도를 좌우하면 안 된다."
2. **이중 검증** — pre-push hook이 이미 `./gradlew check` 실행. PR CI가 같은 작업을 ubuntu-latest runner에서 한 번 더 — runner 가동 시간·캐시 warm-up·이슈 대응 비용이 매 PR마다 누적.
3. **로컬 신뢰성 우선** — 코드 변경의 1차 검증 책임은 commit author(에이전트 또는 사용자) 본인. pre-commit ktlint + pre-push gradle check + Husky author 화이트리스트(ADR 0032)로 충분. main 보호는 GitHub branch protection이 별도로 담당.
4. **release 컷 = 진짜 외부 산출물** — `v*.*.*` 태그 push 시점은 ghcr.io 이미지·GitHub Release draft를 자동으로 발행해야 가치가 큼. **trigger 시점 자체가 드물고**(베이스라인 컷 사이클), 실패해도 일상 개발이 막히지 않음.

## 2. 결정

### 2-1. GitHub Actions 트리거 = 다음 2종만

```yaml
on:
  push:
    tags:
      - 'v*.*.*'
    branches:
      - 'release/v*'
```

| 트리거 | 발행 산출물 | 사유 |
|---|---|---|
| `v*.*.*` 태그 push | 5 서비스(api·batch·consumer·pyworker·frontend) ghcr.io semver 이미지 + GitHub Release draft | 베이스라인 컷 = 외부 사용자에게 publish |
| `release/v*` 브랜치 신규 push | 동일 (베이스라인 immutable 보존 — ADR 0031) | release 브랜치 push 자체가 컷 시점 |

**폐기 트리거**:
- ❌ `pull_request` (PR 검증) — pre-push hook 단독.
- ❌ `push: branches: [main]` (main push 이미지 발행) — release 컷에 통합.

### 2-2. 일상 검증 = pre-push hook 단독 책임 (ADR 0032 보강)

`./gradlew check`는 그대로 + frontend 변경 시 `pnpm typecheck && pnpm build` 추가. 조건부 실행으로 frontend 미변경 시 pnpm 단계 skip.

**pre-push 보강 사항**:

```sh
# frontend 변경 감지
FRONTEND_CHANGED="$(git diff --name-only HEAD@{u}..HEAD 2>/dev/null | grep '^frontend/' || true)"
if [ -n "$FRONTEND_CHANGED" ] && [ -d frontend ] && [ -f frontend/package.json ]; then
  if command -v pnpm >/dev/null 2>&1; then
    echo "→ frontend pnpm typecheck + build (변경된 frontend/* 감지)"
    (cd frontend && pnpm install --frozen-lockfile --silent && pnpm typecheck && pnpm build) || {
      echo "✗ pre-push: frontend typecheck/build 실패."
      exit 1
    }
  else
    echo "ℹ pre-push: pnpm 미설치 — frontend 검증 skip (CI release 시점에 검증)."
  fi
fi
```

frontend 변경 0건 시 pnpm 호출 0회 → 백엔드 PR 속도 영향 없음.

### 2-3. release.yml 재구성

기존 `.github/workflows/ci.yml`은 **삭제**. 기존 `release.yml`은 다음 스코프로 재작성:

1. JVM 4 모듈 통합 빌드 (`./gradlew check + bootJar` — domain 제외 bootJar)
2. frontend `pnpm install --frozen-lockfile + pnpm typecheck + pnpm build`
3. 5 서비스 Docker 이미지 빌드·push (ghcr.io semver — `{version}`·`{major}.{minor}`·tag ref)
4. GitHub Release draft 자동 생성 (`generate_release_notes: true`)

**main push 이미지 발행은 폐기**. 사용자가 일상 통합 기동을 원하면 `scripts/build-and-deploy.sh`로 로컬에서 빌드(host gradle + docker compose). 외부에 publish하는 시점은 release 컷뿐.

### 2-4. branch protection (보조)

main에 다음 protection 권장 (사용자가 GitHub 콘솔에서 설정):
- pull request review required: 0 (사용자 1인 운영) 또는 1
- restrict pushes — main 직접 push 차단 (ADR 0009 정합)
- ❌ status check required — 일상 PR에 GitHub Actions 없음. status check 의존 0.

## 3. 결과

### 3-1. 즉시

- `.github/workflows/ci.yml` 삭제
- `.github/workflows/release.yml` 재구성 (트리거 + 5 서비스 빌드·push + GitHub Release)
- `.husky/pre-push` 보강 (frontend 변경 시 pnpm typecheck + build)
- 외부자원 의존 사라짐 — billing·runner quota·action version skew 0

### 3-2. trade-off

| 측면 | 이전 | 이후 |
|---|---|---|
| PR 검증 비용 | runner 5분·5 job matrix | 로컬 pre-push 1~3분·zero billing |
| 검증 신뢰성 | runner = clean 환경 | 로컬 = 개발자 환경(JDK·node 버전 등 host에 의존) |
| 머지 차단 | status check fail → 자동 차단 | author + reviewer 책임 |
| 외부 발행 | main push마다 ghcr.io | release 컷에만 ghcr.io |

**보완 장치**:
- `.husky/pre-push`가 author 환경에서 일관 실행 — version skew는 author 책임.
- ADR 0035 "에스컬레이션" 정합 — 결과 부족 시 1회 재시도, 그래도 실패하면 사용자 결정.
- release 컷 시점에 통합 ghcr.io publish가 최종 검증 게이트 역할.

### 3-3. release 컷 시 행동 (운영 절차)

```
1. main 안정화 확인 (모든 PR 머지 + 수동 통합 테스트)
2. CHANGELOG.md 새 버전 헤더 작성
3. git tag v3.1.0 + git push origin v3.1.0
4. release.yml 자동 실행:
   ├─ ./gradlew check + bootJar (4 모듈)
   ├─ frontend pnpm typecheck + build
   ├─ 5 서비스 Docker 이미지 빌드 + ghcr.io push (semver tags)
   └─ GitHub Release draft 생성
5. 사용자가 Release draft 검토 + publish
6. release/v3.1 브랜치 신설 (immutable 베이스라인 — ADR 0031)
```

`release/v*` 브랜치 push도 동일 트리거로 release.yml 실행 — 신규 베이스라인 컷 자동화.

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **현행 유지 (PR/main/release 3 트리거)** | 외부자원 블로커 + 이중 검증 비용. 자동매매 본체 개발 속도 저해. |
| **PR 검증만 유지 + main 발행 폐기** | pre-push가 같은 검증 — 이중 비용 그대로. 절약 효과 부분적. |
| **모든 GitHub Actions 폐기 (release도 manual)** | release 컷이 가치 큼(외부 publish + draft note). manual은 누락 위험. |
| **self-hosted runner** | 사용자 1인 운영. 인프라 관리 부담 + 보안 노출. |

## 5. 검증

- `gh workflow list` → `Release` 1개만 표시
- `.github/workflows/` 디렉토리에 `release.yml` 1개 파일
- main push 시 GitHub Actions UI에 신규 run 0건 (폐기 정합 확인)
- `v*.*.*` 태그 push 시 `Release` workflow 자동 실행 + ghcr.io 이미지 5건 + Release draft 1건
- 일반 PR push 시 pre-push hook이 로컬에서 `./gradlew check` 실행, GitHub Actions UI에 PR 검증 run 0건

## 6. 참고

- ADR 0008 — Docker 표준 (이미지 빌드·publish 흐름 정합)
- ADR 0031 — 베이스라인 태그 + release/v* immutable (release 컷 정의)
- ADR 0032 — Husky git hooks (pre-commit + pre-push 단독 책임)
- ADR 0034 — 머지 흐름 단순화 (`agent/* → main` rebase, status check 의존 0)
- `docs/architecture/master-architecture.md` §"CI/CD" — 본 ADR로 정합 갱신
