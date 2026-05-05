# magicJar (마법의항아리)

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반 **개인 맞춤형 한국 주식 자동매매 시스템**. 룰 기반 본체 + AI 옵션 보조 + MCP 서버 미래 노출 (ADR 0001).

## 아키텍처 개요

```
JVM 4 모듈 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle KTS)
├─ domain/        순수 도메인 + port (Spring·JPA 의존 0)
├─ api/           REST/STOMP + 외부 어댑터 (KIS·Yahoo·DART·Naver) + Kafka producer
├─ batch/         Spring Batch — 일봉 backfill·전략 백테스트 잡
└─ consumer/      Kafka 소비 + KIS WS + 시그널·주문 dispatch + AI 분석

frontend/        React 19 + TS + Vite + Tailwind + LWC v4 (TradingView Lightweight Charts)

docker/          docker compose 통합 기동 (인프라 3 + 앱 4 = 7 컨테이너)
```

## 기술 스택

- **언어**: Kotlin 2.0 (K2) · TypeScript 5
- **프레임워크**: Spring Boot 3.3 + Spring AI · React 19 + Vite
- **인프라**: PostgreSQL 16 · Kafka 3.7 (KRaft) · Redis 7
- **외부 API**: KIS OpenAPI (REST + WebSocket) · Yahoo Finance · DART · 네이버 금융 RSS
- **AI**: Anthropic (1차) · OpenAI (옵션) — 사용자 트리거 한정 (ADR 0026)
- **CI/CD**: GitHub Actions matrix + ghcr.io 자동 발행

## 빌드·실행

### 빠른 시작 — Docker Compose (권장)

```bash
# 1) .env 파일 작성
cp .env.example .env
# KIS 키, ANTHROPIC_API_KEY 등 채우기

# 2) Gradle bootJar (api/batch/consumer Dockerfile은 호스트 산출물 COPY)
./gradlew :api:bootJar :batch:bootJar :consumer:bootJar

# 3) docker compose 통합 기동
docker compose -f docker/docker-compose.yml up -d

# 4) 컨테이너 상태 확인
docker compose -f docker/docker-compose.yml ps
```

또는 한 줄 헬퍼:

```bash
scripts/build-and-deploy.sh
```

### 개발 모드 — IDE bootRun + Infra 컨테이너

```bash
# 인프라(postgres·kafka·redis)만 컨테이너로
docker compose -f docker/docker-compose.infra.yml up -d

# IDE에서 :api:bootRun, :batch:bootRun, :consumer:bootRun 직접 실행
# frontend는 pnpm dev (포트 5173)
cd frontend && pnpm install && pnpm dev
```

## 접근 URL

| URL | 설명 |
|-----|------|
| http://localhost:3000 | Frontend SPA |
| http://localhost:8080 | api-app (REST + STOMP) |
| http://localhost:8080/swagger-ui.html | API 문서 |
| http://localhost:8080/actuator/health | api 헬스체크 |
| http://localhost:8081 | batch-app |
| http://localhost:8082 | consumer-app |
| http://localhost:8090 | Kafka UI (디버깅) |
| http://localhost:8888 | Dozzle (모든 컨테이너 로그) |

## 디렉토리 구조

```
.
├── domain/ api/ batch/ consumer/    JVM 4 모듈
├── frontend/                        React SPA
├── docker/                          docker-compose 3 yml
├── docs/
│   ├── architecture/                마스터 아키텍처 + 코딩 컨벤션 + 외부 API 정책
│   ├── operations/                  운영 가이드
│   └── superpowers/specs/           트랙 spec
├── _workspace/
│   ├── arch/adr/                    ADR (Architecture Decision Records)
│   ├── operations/                  운영 runbook
│   ├── strategy/                    지표·패턴 카탈로그
│   ├── market-data/                 KIS 엔드포인트 매핑
│   └── ai/                          AI 프롬프트 카탈로그
├── scripts/                         CLI 도구 (task·smoke·build-and-deploy)
├── .claude/                         하네스 (agents·skills·hooks·commands)
├── .husky/                          git hooks
└── .github/                         CI/CD workflows
```

## 단일 진실원

- **마스터 아키텍처**: `docs/architecture/master-architecture.md`
- **ADR 인덱스**: `_workspace/arch/adr/README.md`
- **트랙 로드맵**: `docs/architecture/tracks-roadmap.md`
- **외부 API 정책**: `docs/architecture/external-api-policy.md` (KIS 호출 빈도·토큰 운영)
- **개발 규약**: `docs/architecture/development-rules.md`
- **코드 컨벤션**: `docs/architecture/code-convention.md`

## CI/CD

- **PR 검증**: JVM 4 모듈 `:check` + `:bootJar` (matrix) + frontend `pnpm test/build`
- **main push**: 4 서비스(api·batch·consumer·frontend) 컨테이너 ghcr.io 자동 발행
- **release**: `v*.*.*` 태그 push → semver 이미지 + GitHub Release draft

## 라이선스

TBD (Private)
