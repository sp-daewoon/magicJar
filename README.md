# magicJar (마법의항아리)

> **부자될꼬야** — 돈이 알아서 차는 마법의 항아리, 개인 맞춤형 한국 주식 자동매매 시스템

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반. **백테스트 → 모의투자 → 실전** 순서로 단계적 개발을 목표로 한다.

## 상태

**Phase C 완료**: 7개 PR(#2·#3·#4·#5·#6·#7·#8) main 머지 + 하네스 v2(PR #9) + bootRun hotfix(PR #10).
부팅 정상, 6개 페이지(`/`, `/instruments/{code}`, `/strategies`, `/portfolio`, `/journal`, `/harness`) 동작.
다음 단계(Phase D)는 잔여 QA 이슈 해소 + KIS/DB 환경 셋업 + 1종목 1전략 백테스트 통합 스모크.

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

## 빌드 · 실행 방법 (IntelliJ + Docker 기준)

### 0. 사전 준비 (1회만)

| 항목 | 비고 |
|------|------|
| **JDK 21 (Temurin)** | 미설치라도 Gradle Toolchain + foojay resolver가 자동 다운로드 |
| **Docker Desktop** | PostgreSQL 15 + Redis 7 로컬 컨테이너용. 실행 중이어야 함 (`docker info` 로 확인) |
| `npm install` | 저장소 클론 직후 1회 — Husky 훅 자동 설치 (ADR 0010) |

**IntelliJ 설정** (한 번만):
- Settings → Build Tools → Gradle → **Gradle JVM** = `21 (Temurin)`
- Settings → Kotlin Compiler → **Target JVM** = `21`
- 프로젝트 import: `build.gradle.kts` 선택 → "Open as Project" → Gradle sync 자동
- (권장) **EnvFile** 플러그인 설치 — Run Config 에서 `.env` 자동 로딩

### 1. 환경변수 준비

```bash
cp .env.example .env
```

`.env` 편집 — 최소 셋업이면 키 다 비워둬도 OK (해당 기능만 자동 비활성):

```bash
SPRING_PROFILES_ACTIVE=mock          # 또는 backtest
MAGICJAR_MODE=MOCK                   # 또는 BACKTEST
MAGICJAR_ALLOW_REAL=false            # 실계좌 가드, 평소 false 유지

KIS_MOCK_APP_KEY=...                 # 비우면 KIS 호출 비활성
KIS_MOCK_APP_SECRET=...
KIS_ACCOUNT_CANO=...
ANTHROPIC_API_KEY=sk-ant-...         # 비우면 NoOpAiClient 주입
DART_API_KEY=...                     # 비우면 공시 수집 비활성
```

> ⚠ `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` **둘 다 true**일 때만 실계좌 모드 (ADR 0006 이중 스위치).

### 2. 인프라 기동 (PostgreSQL + Redis)

```bash
docker compose up -d              # 백그라운드 기동
docker compose ps                 # postgres·redis 둘 다 Up 확인
docker compose logs -f postgres   # 로그 보기
docker compose down               # 끄기
docker compose down -v            # 데이터까지 삭제 (주의)
```

| 서비스 | 호스트 | 인증 |
|--------|--------|------|
| PostgreSQL | `localhost:5432` | DB/user/pwd 모두 `magicjar` |
| Redis | `localhost:6379` | 없음 |

IntelliJ 우측 **Database 패널** → `+` → PostgreSQL → 위 정보 입력 → Test Connection.

### 3. Gradle 빌드 (IntelliJ Gradle 패널 또는 터미널)

```bash
./gradlew clean build           # 전체 빌드 + 테스트
./gradlew :app:classes          # 빠른 컴파일 체크 (DB 없이)
./gradlew :app:bootJar          # 실행 가능한 jar 생성
```

### 4. 앱 실행

#### 옵션 A — IntelliJ Run Configuration (추천)

**Run → Edit Configurations… → `+` → Spring Boot**

| 항목 | 값 |
|------|------|
| Name | `magicJar (mock)` |
| Main class | `kr.spdaewoon.magicjar.MagicJarApplication` |
| Module classpath | `magic_ch1.app.main` |
| JRE | `21` |
| Active profiles | `mock` (또는 `backtest`) |
| Environment variables | EnvFile 탭에서 `.env` 추가 (또는 수동 입력) |

▶️ 실행 → 콘솔에 `Started MagicJarApplication in X seconds` → 접속:

| URL | 설명 |
|-----|------|
| http://localhost:8080/ | 홈 (시장 개요·관심 종목·최근 매매) |
| http://localhost:8080/instruments/005930 | 종목 상세 (캔들·뉴스·AI 인사이트) |
| http://localhost:8080/strategies | 전략 목록 / 백테스트 |
| http://localhost:8080/portfolio | 포트폴리오 |
| http://localhost:8080/journal | 매매일지 |
| http://localhost:8080/harness | 하네스 운영 + 에이전트 대시보드 (탭) |
| http://localhost:8080/actuator/health | 헬스체크 |

#### 옵션 B — 터미널 한 방

```bash
# .env 로드 + mock 프로파일
export $(grep -v '^#' .env | xargs) && ./gradlew :app:bootRun

# 프로파일만 바꿔서
SPRING_PROFILES_ACTIVE=backtest ./gradlew :app:bootRun
```

### 5. 종료 (역순)

```bash
# 1. 앱 끄기 — IntelliJ ■ 또는 Ctrl+C
# 2. 인프라 끄기
docker compose down
```

### 프로파일 / 모드

| 프로파일 | `magicjar.mode` | 용도 |
|---------|-----------------|------|
| `mock` (기본) | `MOCK` | KIS 모의계좌 경로 |
| `backtest` | `BACKTEST` | 가상 체결, 과거 데이터 기반 |
| `real` | `REAL` + `allow-real=true` | 실계좌 (ADR 0006 이중 스위치) |

### 트러블슈팅

| 증상 | 원인 / 조치 |
|------|------------|
| `Connection refused 5432` | `docker compose up -d` 안 함. 컨테이너 헬스 확인. |
| 종목 페이지 404 (`종목을 찾을 수 없습니다`) | `instrument` 테이블 비어 있음. Flyway `V3__seed_instruments.sql`이 핵심 16개 종목을 자동 적재. 적용 안 됐다면 `flyway_schema_history` 확인. |
| Bean `AiClient` not found | `ANTHROPIC_API_KEY` 빈 상태에서 NoOpAiClient 주입 실패. `ai/src/main/resources/application-ai.yml` 확인. |
| 포트 8080 충돌 | `lsof -i :8080` → kill, 또는 `server.port=8081`. |
| Husky 훅 차단 | 시크릿 또는 ktlint 위반. **`--no-verify`는 금지** (ADR 0010). |

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
