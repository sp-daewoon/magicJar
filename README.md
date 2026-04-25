# magicJar (마법의항아리)

> **부자될꼬야** — 돈이 알아서 차는 마법의 항아리, 개인 맞춤형 한국 주식 자동매매 시스템

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반. **백테스트 → 모의투자 → 실전** 순서로 단계적 개발을 목표로 한다.

## 상태

**Phase B-1 완료: Gradle 멀티모듈 스캐폴딩 + `core` 도메인/포트 + `app` 부팅 뼈대.**
다음 단계(Phase B-2)는 market-data / strategy / ai / frontend 4개 에이전트가 병렬로 각 모듈을 구현한다.

## 핵심 스택 (예정)

- **Kotlin 2.0 (K2)** + JDK 21 + Spring Boot 3.3+, Gradle Kotlin DSL (멀티모듈)
- Spring AI (ChatClient, 기본 공급자 Anthropic Claude)
- KIS OpenAPI (REST + WebSocket, 실계좌/모의계좌)
- PostgreSQL / Redis (캐시·실시간 시세)
- Thymeleaf + HTMX + Chart.js (MVP 대시보드)
- Resilience4j, Bucket4j (외부 API 안정성)

## AI 기능 (예정)

1. 매매 전략 생성·자연어 설명
2. 뉴스·공시 감성 분석 (DART + 네이버 금융)
3. 포트폴리오 리밸런싱 조언
4. 자연어 → 차트 조건식 변환 + 종목 발굴

## 꼭 챙기는 기능

- **매매일지(Trading Journal)** — 모든 매매 자동 기록 + AI 복기 + 사용자 메모
- **하네스 운영 페이지(`/harness`)** — 지금 어떤 에이전트가 무엇을 하고 있는지 웹에서 직접 조회

## 빌드 · 실행 방법

### 사전 준비

1. JDK 21 (Temurin). 설치되어 있지 않아도 Gradle Toolchain + foojay resolver가 자동 다운로드한다.
2. Docker / Docker Compose (PostgreSQL 15 + Redis 7 로컬 기동용).
3. 저장소 클론 직후 `npm install` 1회 → Husky 훅 자동 설치 (ADR 0010).

### 인프라 기동 (선택, DB 사용 시)

```bash
docker compose up -d
```

### 환경변수

```bash
cp .env.example .env
# .env 파일 열어 값을 채워넣을 것 (모든 키는 선택값이며, 미설정 시 해당 기능이 자동 비활성)
```

### 빌드 · 실행

```bash
# 전체 빌드
./gradlew build

# core 모듈만
./gradlew :core:build

# 앱 클래스 컴파일 (DB 없이 가능)
./gradlew :app:classes

# 앱 실행 (모의투자 프로파일, DB 필요)
SPRING_PROFILES_ACTIVE=mock ./gradlew :app:bootRun

# 백테스트 프로파일
SPRING_PROFILES_ACTIVE=backtest ./gradlew :app:bootRun
```

### 프로파일 / 모드

- `mock` (기본): `magicjar.mode=MOCK` — KIS 모의계좌 경로
- `backtest`: `magicjar.mode=BACKTEST` — 가상 체결, 과거 데이터 기반
- `real`: `magicjar.mode=REAL` + `magicjar.allow-real=true` **둘 다** 필요 (ADR 0006 이중 스위치)

필수 환경변수(설정 시 활성화, 미설정 시 해당 기능 비활성):

```
KIS_MOCK_APP_KEY=...
KIS_MOCK_APP_SECRET=...
KIS_ACCOUNT_CANO=...
KIS_ACCOUNT_PRDT=01
ANTHROPIC_API_KEY=...   # 미설정 → NoOpAiClient 주입, AI 기능 비활성 부팅
DART_API_KEY=...        # 미설정 → 공시 수집 비활성
```

## 개발 워크플로우 요약 (v2 — ADR 0013~0018)

- **단일 메인 worktree** + 브랜치 전환 순차 작업 (ADR 0013)
- **머지 1단**: `agent/<agent>/<task> → main` 직행 (ADR 0015 — qa 영속 브랜치 폐기)
- **모델 차등** (ADR 0014): architect / ai-engineer / strategy-engine = **opus**, 나머지 4명 = **sonnet**. 부족 시 1회 opus 에스컬레이션
- **Git author 분리** (ADR 0017): 에이전트별 `magicjar-<agent>` author로 commit → `git log`에서 누가 했는지 즉시 식별
- **Task 파일 시스템** (ADR 0018): 작업 시작 시 `_workspace/tasks/<id>.md` 생성. `node scripts/task.mjs <start|check|touch|block|done|archive|list>` CLI
- **QA On-Demand** (ADR 0016): 7개 트리거(돈 흐름·도메인 변경·새 UI 흐름·보안·회귀·Phase 통합·명시 판단) 시에만. 평소엔 셀프 + 자동 검사
- 커밋: Conventional Commits. 훅: Husky(`pre-commit` 시크릿·ktlint, `pre-push` main 차단·`./gradlew check`)
- 권한: `.claude/settings.json`이 일상 자동 허용, `gh pr merge`·main push·`npm publish`만 사용자 승인
- 상세: `_workspace/arch/branching-and-qa-gates.md`, ADR 0009·0013·0015·0016·0018

## Claude Code 하네스

본 저장소는 Claude Code 하네스로 자동화된다. `CLAUDE.md` 참고.
