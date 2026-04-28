# Phase E-P3 — CI/CD GitHub Actions matrix + ghcr.io 푸시

> architect 에이전트(opus, ADR 0014) 단독 위임.

**Goal:** GitHub Actions로 5 서비스(api·batch·consumer·pyworker·frontend) matrix build·test·docker push. PR 시 자동 빌드·테스트, main 머지 시 ghcr.io에 `:<short-sha>` + `:latest` 태그 푸시. ADR 0019(MSA) + ADR 0024(Docker) 운영 차원 완성.

**Source:** main `4854988` 이후. spec §3 §10 + ADR 0019·0024.

**Branch:** `agent/architect/e3-cicd`
**Author:** `bot+architect@magicjar.local`
**Model:** opus

---

## 0. Pre-flight

- [ ] main pull, `./gradlew clean build` SUCCESSFUL 확인
- [ ] `_workspace/_legacy/` 사용 카탈로그 — 신규 GitHub Actions에 영향 없는지 검토
- [ ] GitHub repo 설정: Actions 활성화 + ghcr.io packages 권한 (write 권한은 GITHUB_TOKEN 자동)

---

## 1. `.github/workflows/` 구성 (3 워크플로)

### 1-A. `ci.yml` — PR + main push 검증

```yaml
name: CI
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

permissions:
  contents: read
  packages: write   # main push 시 ghcr.io 푸시
  id-token: write

jobs:
  jvm:
    name: JVM (${{ matrix.module }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        module: [domain, api, batch, consumer]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :${{ matrix.module }}:check
      # api/batch/consumer는 bootJar
      - if: matrix.module != 'domain'
        run: ./gradlew :${{ matrix.module }}:bootJar
      - if: matrix.module != 'domain'
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.module }}-jar
          path: ${{ matrix.module }}/build/libs/*.jar
          retention-days: 7

  pyworker:
    name: pyworker
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - uses: astral-sh/setup-uv@v3
      - run: cd pyworker && uv sync --frozen --extra dev
      - run: cd pyworker && uv run pytest -v

  frontend:
    name: frontend
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - run: cd frontend && pnpm install --frozen-lockfile
      - run: cd frontend && pnpm test
      - run: cd frontend && pnpm build
      - uses: actions/upload-artifact@v4
        with:
          name: frontend-dist
          path: frontend/dist
          retention-days: 7

  docker-build:
    name: Docker (${{ matrix.service }})
    needs: [jvm, pyworker, frontend]
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        service: [api, batch, consumer, pyworker, frontend]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      # JVM 서비스만 jar 빌드 다시 (캐시 또는 artifact 사용)
      - if: contains(fromJSON('["api","batch","consumer"]'), matrix.service)
        run: ./gradlew :${{ matrix.service }}:bootJar
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: ./${{ matrix.service }}
          file: ./${{ matrix.service }}/Dockerfile
          push: true
          tags: |
            ghcr.io/${{ github.repository_owner }}/magicjar-${{ matrix.service }}:${{ github.sha }}
            ghcr.io/${{ github.repository_owner }}/magicjar-${{ matrix.service }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### 1-B. `release.yml` — 태그 기반 릴리스 (선택, 본 phase는 skeleton만)

`v*.*.*` 태그 푸시 시 GitHub Release 생성 + 이미지 태그 `:v1.2.3` 추가.

### 1-C. `lint.yml` — kotlinter, ktlint, ruff (선택)

ktlint는 이미 Gradle에 포함. 추가 lint는 본 phase 범위 외, ci.yml의 `:<module>:check`가 ktlint 실행하므로 충분.

→ **본 phase는 ci.yml + release.yml만 작성**.

---

## 2. ghcr.io 권한·시크릿

- `GITHUB_TOKEN`은 자동 주입, 별도 시크릿 불필요
- repo 설정: Settings → Actions → General → Workflow permissions → "Read and write permissions" 활성화
- repo 설정: Settings → Packages → Inherit access from source repository (자동)

→ architect가 GitHub UI 변경은 직접 못함. plan 본문에 사용자 1회 수동 설정 안내 명시.

---

## 3. .github/CODEOWNERS (선택)

```
*                @sp-daewoon
.github/         @sp-daewoon
docker/          @sp-daewoon
_workspace/arch/ @sp-daewoon
```

→ 본 phase 범위. 단순 1인 owner라 효과 미미하지만 향후 팀 확장 대비.

---

## 4. README.md "CI/CD" 섹션 추가

- 각 PR 시 자동 빌드·테스트
- main 머지 시 ghcr.io 자동 푸시
- 이미지 pull: `docker pull ghcr.io/sp-daewoon/magicjar-<service>:latest`
- 배지 추가 (선택): ![CI](https://github.com/sp-daewoon/magicJar/actions/workflows/ci.yml/badge.svg)

---

## 5. CLAUDE.md + _harness-state.json 갱신

- 변경 이력 1줄 (Phase E-P3 — CI/CD 도입)
- harness-state Phase E 진척: P1 ✅ + P2 ✅ + P3 ✅

---

## 6. 종료 게이트

- [ ] `.github/workflows/ci.yml` 작성 + 로컬 yaml lint 통과 (`actionlint` 또는 GitHub UI 검증)
- [ ] PR 발행 후 첫 CI run 통과 (jvm matrix 4 + pyworker + frontend = 6 jobs PASS)
- [ ] main 머지 후 docker-build 5 jobs 모두 통과 + ghcr.io에 5 이미지 push 확인
- [ ] `docker pull ghcr.io/<owner>/magicjar-api:latest` 가능 여부 (사용자 확인 필요 — public 또는 인증)
- [ ] CLAUDE.md + harness-state.json 갱신
- [ ] PR `agent/architect/e3-cicd` → main 발행

---

## 7. 통보 경계 (자율 vs 사용자)

다음만 사용자 통보, 그 외 자율:
1. **GitHub repo 설정 변경 필요**: Actions 권한, Packages 인증 → 사용자 1회 수동 설정 안내 (PR 본문에 체크리스트로 명시)
2. **ghcr.io 이미지 visibility (public/private)** 결정 — 기본 private. public 원하면 사용자가 settings에서 변경.
3. **breaking change**: 기존 docker compose의 `build: { context: ../<svc> }` 가 `image: ghcr.io/...` 로 바뀌어야 하는지 — production-ready compose는 본 phase 범위 외, 일단 build 유지

---

## 8. 후속 (Phase E-P4+)

- E-P4: k8s/ECS manifest + 부하 테스트 (architect)
- E-P5: 실제 KIS 모의 키 e2e 검증 (사용자 + market-data)
- E-P6: 도메인 회귀 테스트 강화 + Phase F(실서비스 진입 준비) 트리거 검토
