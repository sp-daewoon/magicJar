# magicJar QA — 누적 이슈 카탈로그

본 파일은 **인덱스**다. 신 양식 ISSUE-008부터는 디렉토리 양식 (`_workspace/qa/issues/ISSUE-NNN/`).
legacy 양식 ISSUE-001~007은 본 파일 하단에 단일 본문으로 보존.

- 신규 이슈 채번: `node scripts/qa-run.mjs new --symptom <s> --target <url>`
- 라이프사이클: OPEN → INVESTIGATING → FIXED (또는 WONTFIX/DEFERRED)
- 신 양식 가이드: `_workspace/qa/issues/README.md`
- ADR: 0011 (스크린샷 게이트, 2026-05-01 개정) · 0016 (On-Demand) · 0030 (오케스트레이터=architect)

## 신 양식 인덱스 (ISSUE-008~)

`qa-run.mjs close` / `wontfix` 호출 시 아래 표가 자동 갱신된다.

<!-- AUTO-INDEX:START -->

| ID | 상태 | 증상 | PR |
|---|---|---|---|
| [ISSUE-008](issues/ISSUE-008/card.md) | FIXED | dogfooding — pyworker /screenshot/scenario 인프라 동작 검증 + presigned URL SignatureDoesNotMatch (hotfix 완료) | |

<!-- AUTO-INDEX:END -->

---

## Legacy 양식 (ISSUE-001~007 — 보존)

> 상태 갱신은 본문 안에서 수동. ISSUE-008부터는 디렉토리 양식.

## ISSUE-001 (P2): frontend ↔ backend backtest 경로 mismatch
- **관측**:
  - frontend `frontend/src/api/client.ts:55`: `apiClient.post('/backtest/run', params)` → 실제 호출은 `POST /api/backtest/run`
  - backend `api/.../controller/BacktestController.kt:36,58`: `@RequestMapping("/api/backtest")` + `@PostMapping`(root) → 실제 경로는 `POST /api/backtest`
  - 동적 검증: `POST /api/backtest/run` → **405**, `POST /api/backtest` → 400(body invalid)
- **영향**: 사용자가 BacktestPage에서 "백테스트 실행" 누르면 동작 안 함 (405 Method Not Allowed)
- **사용자 보고**: "차트도 그렇고 안되는게 많아" — 차트(F-R1-002)는 D안에서 해결, 이 항목이 잔여 미동작 후보
- **수정 방향 (택1)**: frontend를 `/backtest`로 변경 (간단) / backend에 `/run` 서브패스 alias 추가 (호환)
- **담당 제안**: frontend 또는 backend-core (frontend가 한 줄 수정이 가벼움)
- **상태**: OPEN

## ISSUE-002 (P1): Docker 운영 함정 — host gradle build 강제 부재
- **관측**:
  - api/batch/consumer Dockerfile은 `COPY build/libs/api-*.jar /app/app.jar` 단순 복사 (gradle 빌드 단계 없음)
  - host에서 `./gradlew :api:bootJar` 안 돌리면 옛 jar가 컨테이너에 들어감
  - README.md:224에 명시 있음, **scripts/smoke.sh에는 사전 build 강제 없음**
  - 사용자가 docker compose build만 한 줄 알고 진행 → V7 미적용 + candles 404 발현 (오늘 2026-05-01)
- **영향**: 모든 코드 변경 후 재빌드 시 재현 가능. 사용자 가시성 매우 큰 footgun.
- **수정 방향 (택1)**:
  - (a) Dockerfile multi-stage로 gradle 빌드 포함 (재현성↑, 빌드시간↑)
  - (b) scripts/smoke.sh 시작 부분에 `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` 추가 + jar 타임스탬프 검사
  - (c) docker-compose.yml에 init container로 gradle 빌드 1회 실행 (gradle wrapper 마운트)
  - (d) README "빌드·실행" 섹션 1순위로 강조 + 사용자가 build 누락 시 가시적 경고 출력
- **담당 제안**: architect (정책) + backend-core (Dockerfile 수정 시)
- **상태**: OPEN — **즉시 hotfix 강력 권고**

## ISSUE-003 (P2): pyworker .dockerignore 부재 — 잠재 frontend와 동일 문제
- **관측**:
  - `pyworker/.dockerignore` 미존재
  - 호스트 `pyworker/.venv` 디렉토리 존재 — docker build 시 빌드 컨텍스트로 들어가 컨테이너 안 .venv를 덮어쓸 위험
  - 현재까지 발현 안 했으나 frontend(.dockerignore 부재로 PR #38 hotfix 발생)와 동일 패턴
- **영향**: 사용자 환경에서 pyworker 재빌드 시 모듈 충돌 가능
- **수정 방향**: pyworker 표준 .dockerignore 추가 — `.venv`, `__pycache__`, `.pytest_cache`, `.ruff_cache`, `*.pyc`, `.git`, `.env*`
- **담당 제안**: ai-engineer (pyworker 담당)
- **상태**: OPEN

## ISSUE-004 (P3): /strategy 페이지 placeholder — 호출 API 없음
- **관측**: `frontend/src/pages/Strategy/index.tsx`에 `apiClient`/`fetch`/`axios` 호출 0건. nav link만 존재.
- **영향**: 사용자가 "전략" 메뉴 누르면 빈 페이지. 비기능 노출.
- **수정 방향**: 별도 phase에서 strategy API + UI 구현. 그때까지 nav에서 숨김 또는 "준비 중" 안내 권장.
- **담당 제안**: frontend (UX 개선) 또는 strategy-engine (API 구현)
- **상태**: OPEN

## ISSUE-005 (P3): V7 시드 범위 ↔ CandleQueryService default lookback mismatch
- **관측**:
  - V7 시드: 125일치 generate_series 중 영업일 = ~89봉
  - CandleQueryService.defaultFrom(D1) = 180일 lookback
  - 결과: 응답 89봉(시드 전체) — 사용자 차트 잘 표시되지만 lookback 범위 안에 빈 영역(시드 이전 ~55일) 존재
- **영향**: 차트 좌측이 V7 시드 시작점에서 끊김. UX적으로는 정상으로 보임 (빈 영역 자동 무시).
- **수정 방향**: 향후 backfill job 정상 가동 시 자연 해소. 즉시 조치 불요.
- **담당 제안**: market-data (KIS 키 발급 후 backfill)
- **상태**: DEFERRED

## ISSUE-006 (P3): WebSecurity permitAll — 인증 부재
- **관측**: `WebSecurityConfig.kt:14` `auth.anyRequest().permitAll()` — 모든 /api/** 공개
- **영향**: 알려진 결정 (Phase F+에서 JWT/OAuth2 추가 예정). 그러나 매매일지·KIS 계좌·실 매매 기록이 누구나 GET 가능. 실 운영 진입 직전 반드시 보강.
- **수정 방향**: Phase F+에서 Spring Security + JWT 또는 OAuth2 도입. ADR 0028 후보.
- **담당 제안**: backend-core + architect
- **상태**: DEFERRED — **실 매매 진입 게이트로 PIN**

## ISSUE-007 (P3): /api/strategy controller 부재
- **관측**: phaseE.P1 기록에 "/api/strategy" 컨트롤러 명시되어 있으나 실제 코드 없음. `curl /api/strategy → 404`.
- **영향**: 기록과 실제 코드 mismatch. 회귀 아님 (처음부터 미구현).
- **수정 방향**: harness-state.json phaseE.P1 summary 정정 또는 컨트롤러 신설.
- **담당 제안**: backend-core (정책) + architect (기록 정리)
- **상태**: OPEN

## (참고) 회귀 검증 결과 — 모두 정상
- `/api/instruments` 16개 ✅ (V3 시드 정합)
- `/api/instruments/005930` 200 + `삼성전자/KOSPI` ✅
- `/api/instruments/005930/candles?interval=DAY` 89봉 ✅ (D안 신규)
- `/api/journal` 200 ✅
- `/api/symbols/search` 라우트 존재 (실호출 미검증)
- frontend `/`, `/symbol/005930`, `/garbage` 모두 SPA fallback 200 ✅
- STOMP `/ws` GET 400 = WebSocket upgrade 미수행이라 정상 (실 STOMP 핸드셰이크는 wscat 등으로 별도 검증)
- 9 컨테이너 모두 healthy, V7 Flyway 적용 확인
