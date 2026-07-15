# magicJar Frontend

React 19 + TypeScript + Vite + Tailwind CSS + TradingView Lightweight Charts v4

## 개발 환경

```bash
# frontend/.node-version과 동일한 검증 Node
node --version  # v24.18.0

# pnpm 설치 (없는 경우, packageManager와 동일 버전)
corepack enable && corepack prepare pnpm@9.15.9 --activate

# 의존성 설치
pnpm install --frozen-lockfile

# 개발 서버 실행 (5173 포트)
pnpm dev

# 타입 체크
pnpm tsc --noEmit

# 프로덕션 빌드
pnpm build

# 빌드 결과 미리보기
pnpm preview
```

## 스택

| 역할 | 라이브러리 |
|------|-----------|
| UI 프레임워크 | React 19 |
| 언어 | TypeScript 5 |
| 번들러 | Vite 6 |
| CSS | Tailwind CSS 3 |
| 라우터 | react-router-dom 7 |
| 서버 상태 | TanStack Query 5 |
| 클라이언트 상태 | Zustand 5 |
| 차트 | TradingView Lightweight Charts v4 |

## 화면 구조

| URL | 컴포넌트 | 상태 |
|-----|---------|------|
| `/` | DashboardPlaceholder | stub (PR-6+ 구현 예정) |
| `/setup` | SetupWizardPlaceholder | stub (PR-6 T-ONBOARD 구현 예정) |

## Docker

> **Wave 0 예외:** 현재 Docker build stage는 기존 `node:20-alpine`을 유지한다.
> 로컬·pre-push 검증 권위는 Node `24.18.0`이며, Docker image 정렬은 배포 설정 변경 승인이 필요한 후속 작업이다.

```bash
# legacy 멀티 스테이지 빌드 (node:20-alpine → nginx:alpine)
docker compose -f docker/docker-compose.yml build frontend

# 컨테이너 실행 (3000 포트)
docker compose up -d frontend
```
