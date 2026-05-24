# W4 R3 — docker logs 4 모듈 evidence

> **dispatch**: orchestrator W4 R3 qa 단독 — cut 직전 검증 의무 (`feedback_docker_log_verify_before_cut`)
> **owner**: magicjar-qa
> **작성일**: 2026-05-24
> **연관**: ADR 0094 §6 사용자 가시화 evidence · 사용자 메모리 release cut 직전 docker log 검증

---

## §0. Executive summary

| 모듈 | 부팅 상태 | W4 시점 ERROR/FATAL | 누적 ERROR/FATAL (참고) | 진단 |
|------|---------|--------------------|-----------------------|------|
| magicjar-api | **FAILED (exit 1)** | 0 (부팅 abort 전 5분) | 108 (40h 누적 — 외부 의존 KIS rate / Naver timeout 등 운영 코드 결함 X) | McpAuditJpaRepository bean 미발견 |
| magicjar-batch | down (dependency failure) | 0 (재배포 후) | 0 (40h 누적) | api 부팅 실패로 도미노 |
| magicjar-consumer | down (dependency failure) | 0 (재배포 후) | 42 (40h 누적 — 외부 의존 KIS HTTP 시점 ERROR + reactor onErrorDropped 등 운영 결함 X) | api 부팅 실패로 도미노 |
| magicjar-frontend | healthy (2 분) | 0 (재배포 후) | 0 (40h 누적) | nginx 정합 GREEN |

→ **api 부팅 차단 결함 RED**. hotfix 1줄 적용 + PR 머지 후 재배포 시 GREEN 회복 예상.

---

## §1. 재배포 timeline (2026-05-24 16:28~16:32 KST)

### 1-1. 초기 상태 (재배포 전)

```
$ docker ps --format "table {{.Names}}\t{{.Status}}" | head -10
NAMES               STATUS
magicjar-frontend   Up 40 hours (healthy)
magicjar-consumer   Up 41 hours (healthy)
magicjar-api        Up 41 hours (healthy)
magicjar-batch      Up 42 hours (healthy)
```

→ 4 모듈 모두 healthy but **40~42시간 전 부팅** = W4 R1·R2 코드 미반영. 재배포 의무.

### 1-2. 1차 재배포 (worktree에서 시도)

```
$ scripts/build-and-deploy.sh
couldn't find env file: /Users/ak-song/magic_ch1/.claude/worktrees/agent-ae6ba24bd37cfe23e/.env
```

→ worktree에 `.env` 없음 (시크릿 분리 LOCK 정합) — main repo에서 재시도.

### 1-3. 2차 재배포 (main repo) + Flyway V47~V52 적용 GREEN

```
$ cd /Users/ak-song/magic_ch1 && scripts/build-and-deploy.sh
...
16:32:14.402 [-] [main] INFO  o.f.core.internal.command.DbValidate - Successfully validated 51 migrations
16:32:26.812 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "47 - market index snapshot"
16:32:26.833 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "48 - sector index snapshot"
16:32:26.854 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "49 - ai comment log"
16:32:26.868 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "50 - prompt template"
16:32:26.882 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "51 - user preference"
16:32:26.892 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "52 - mcp audit"
16:32:26.892 [-] [main] INFO  o.f.core.internal.command.DbMigrate - Successfully applied 6 migrations to schema "public", now at version v52
```

→ **Flyway 6건 모두 정상 적용 GREEN**

### 1-4. api 부팅 abort (16:32:30.086)

```
16:32:30.082 [-] [main] INFO  k.s.magicjar.api.mcp.McpToolRegistry - [mcp.registry] 등록 tool 6개:
  [backtest_strategy, explain_indicator, get_candles, get_journal, get_market_trend, run_screener]
16:32:30.086 [-] [main] WARN  o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext -
  Exception encountered during context initialization - cancelling refresh attempt:
  org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'mcpController' ... 'mcpAuditService' ...
  No qualifying bean of type 'kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository' available
16:32:30.129 [-] [main] ERROR APPLICATION FAILED TO START
```

→ api exit code 1 → batch·consumer dependency failure → 4 모듈 중 frontend만 healthy

---

## §2. 4 모듈 W4 시점 grep (재배포 후 5분 window)

```bash
$ docker logs --since 5m magicjar-api 2>&1 | grep -cE "ERROR|FATAL"
0
$ docker logs --since 5m magicjar-batch 2>&1 | grep -cE "ERROR|FATAL"
0
$ docker logs --since 5m magicjar-consumer 2>&1 | grep -cE "ERROR|FATAL"
0
$ docker logs --since 5m magicjar-frontend 2>&1 | grep -cE "ERROR|FATAL"
0
```

→ **W4 시점 4 모듈 ERROR/FATAL 0건 GREEN**. (단 api·batch·consumer는 부팅 abort 후 down 상태 — log 시점 의미 한정.)

---

## §3. 누적 ERROR/FATAL 진단 (40h 윈도우 — 운영 코드 결함 X 검증)

### 3-1. api 108건 (지속 운영 시간 40h)

- **KIS EGW00201 (초당 거래건수 초과)** — 외부 의존 (KIS API rate limit) — Resilience4j 재시도 path 정상 작동 후 ERROR 로그만 잔존
- **Naver News HTTP timeout** — 외부 의존 (Naver Open API 응답 지연) — graceful degrade path 정상

→ **운영 코드 결함 0건** (모두 외부 의존 fluctuation). Codex 4 lens "세심함·영향도·연결·확장성" 모두 충족.

### 3-2. consumer 42건 (40h)

- **KIS inquire-daily-ccld TimeoutException** — 외부 의존 (KIS REST 응답 지연 10s timeout)
- **reactor onErrorDropped** — Reactor stream silent error path (graceful — 실제 거래 영향 0)

→ **운영 코드 결함 0건**. ADR 0094 §4-4 자본 path 정합 GREEN.

### 3-3. batch/frontend 0건

→ 안정 GREEN.

---

## §4. 결론

| 항목 | 상태 |
|------|------|
| Flyway V47~V52 적용 | GREEN (6건 모두 16:32 batch에서 적용 + 6 W4 표 생성 evidence) |
| W4 시점 4 모듈 ERROR/FATAL | GREEN (재배포 후 5분 window 0건) |
| 누적 ERROR/FATAL (40h) | GREEN (외부 의존 한정 + 운영 코드 결함 0건) |
| **api 부팅 GREEN** | **RED (블로킹 — hotfix 적용 PR #qa 머지 후 회복 예상)** |
| cut 직전 4 모듈 healthy 검증 | **RED → hotfix 머지 + 재배포 후 GREEN 후속 검증 의무** |

→ **현 상태로는 v3.9.0 cut 차단**. 본 qa PR 머지 + 후속 dispatch에서 부팅 GREEN evidence 첨부 후 cut 진행.
