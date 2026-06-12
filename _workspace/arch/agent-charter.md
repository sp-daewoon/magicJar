# magicJar 운영 헌법 (Agent Charter)

> **위치**: `_workspace/arch/agent-charter.md`
> **신설일**: 2026-05-08 (PR-26 — 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게")
> **권위**: 본 헌법은 7 sub-agent SKILL + orchestrator SKILL + 메타 ADR(0033·0036·0046·0049·0050·0080·0094·0095·0096)에서 산재된 운영 룰을 단일 출처로 통합한 **참조 가능한 헌법**이다.
> **의무 정독 대상**: architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa · orchestrator (7 sub-agent + orchestrator — ADR 0094 amend 2026-05-21: chart-curator 해고 + qa Visual 격상 + sre 영입 거절)
> **변경 정책**: 본 헌법은 architect 단독 PR로만 갱신. sub-agent SKILL은 본 헌법을 참조만 하고 룰 본문 중복 금지.

본 헌법은 **운영 룰의 단일 출처**다. 본문에 명시된 핵심 § 룰(§1~§7 개발 룰 + §9 운영 단계 룰 + §11 모듈 경계 빈 합류·silent fallback 금지)을 위반하면 운영 부채(hotfix·재작업·사용자 비판) 누적 risk. 각 § 본문은 30~40줄 max — 헌법은 실행 매뉴얼이 아닌 참조 룰북.

---

## §1. task.mjs 4단계 의무 (ADR 0036 + PR-18·PR-22 + 안정성 Cycle 2)

agent/* 브랜치 작업 시 `scripts/task.mjs` CLI 4단계 호출 의무. 누락 시 task 데이터 무결성 깨짐 → done/ 누적·체크리스트 미반영·completedAt null 같은 사용자 보고 발생.

### 1-A. worktree 진입 의무 (안정성 Cycle 2 신설 — 2026-05-18)

sub-agent는 worktree 진입 시점에 **워크트리 로컬 git config**를 본인 author로 강제 설정. global·인근 worktree default(예: `bot+frontend@magicjar.local`)를 그대로 둔 채 작업하면 commit author가 **다른 에이전트로 attribution**되어 git blame·CHANGELOG 작성자 표기·운영 audit가 깨짐.

```bash
git config user.email "bot+<agent>@magicjar.local"
git config user.name "magicjar-<agent>"
```

- `<agent>` ∈ {architect, backend-core, market-data, strategy, ai, frontend, qa, chart-curator(archived), orchestrator}
- **chart-curator는 2026-05-21 ADR 0094 amend로 해고**되었으나, `.husky/pre-commit` ALLOWED_AUTHOR regex는 historical commit author 보존 + 사용자 force commit risk 회피를 위해 유지. dispatch 매트릭스는 0(orchestrator는 chart-curator dispatch 발사 금지).
- `.claude/agents/<agent>.md` frontmatter `git-author:` 는 **선언 수준**일 뿐 강제력 0 — 본 절차로만 보장됨
- 검증: 첫 commit 직전 `git config user.email` 출력으로 본인 email 확인 의무
- **위반 시 sub-agent는 작업 진행 거부 + 즉시 orchestrator/사용자 보고** (commit 후 rewrite하면 force-push 발생 risk)

**역사 evidence** (안정성 Cycle 1 — 2026-05-18): PR #473(backend-core 영역) + PR #475(market-data 영역) commit author 모두 `magicjar-frontend` attribution. 본 결함 누적 시 운영 audit·CHANGELOG `(작성: <agent>)` 마커 부정확 → 사용자 신뢰 손실.

### 1-A-extended. `extensions.worktreeConfig=true` 의무 (안정성 Cycle 3 신설 — 2026-05-19, 8 sub-agent system audit evidence)

**root cause** (5 sub-agent 동일 evidence: architect·frontend·backend-core·ai-engineer·market-data 2026-05-19 cross-validation 보고서):

- git default 상태에서 `extensions.worktreeConfig=false` → worktree의 `.git/config` 는 **main repository의 `.git/config` 와 공유**됨.
- 따라서 한 worktree에서 `git config --local user.email "bot+<X>@magicjar.local"` 설정 시 **main `.git/config` 가 덮어쓰여** 다른 worktree·sub-agent 작업에 휘발성 전파.
- §1-A의 `git config user.email "bot+<agent>@magicjar.local"` 호출만으로는 정합 보장 불가 — **본 옵션 활성화가 root cause 차단의 본질**.
- 결과: 본 author 결함 10+ PR 누적 (PR #514·#525·#535·#537·#541·#546·#551·#552·#553·#555). 안정성 Cycle 1(PR #473·#475) + Cycle 2(2026-05-18 §1-A 신설) 두 사이클 대응 후에도 결함 재발.

**필수 적용** (사용자 또는 orchestrator가 main repo에 1회):

```bash
git config extensions.worktreeConfig true
```

또는 헬퍼 스크립트 호출 (★ 라운드 3 보강 — 기존 worktree config.worktree 일괄 발행 포함):

```bash
scripts/setup-worktree-config.sh
```

본 옵션 활성 후:
- 각 worktree의 `.git/worktrees/<name>/config.worktree` 파일이 활성화됨.
- worktree-local config가 main `.git/config` 와 격리됨 → 다른 worktree·sub-agent에 전파 0.
- §1-A의 `git config user.email` 호출이 본 worktree에 한정 적용됨 (격리 GREEN).
- sub-agent author 정합 LOCK.

**필수 worktree-local 발행 의무** (★ 2026-05-19 라운드 3 evidence — extensions.worktreeConfig=true 단독으로는 부족):

각 sub-agent는 worktree 진입 즉시 다음 4 명령 모두 실행 의무:

```bash
git config user.email "bot+<agent>@magicjar.local"
git config user.name "magicjar-<agent>"
git config --worktree user.email "bot+<agent>@magicjar.local"
git config --worktree user.name "magicjar-<agent>"
```

`--worktree` 옵션 사용 시 `.git/worktrees/<name>/config.worktree` 파일이 자동 생성되어 본 worktree만의 author LOCK. main `.git/config` 흡수 회피.

검증 (첫 commit 직전 의무):
```bash
git config --get --worktree user.email   # 활성 worktree author
```

**역사 evidence**:
- 안정성 Cycle 1 (2026-05-18) — PR #473·#475 author `magicjar-frontend` attribution. §1-A 본문 신설로 1차 대응.
- 안정성 Cycle 2 (2026-05-18) — §1-A 의무 강제 시도. PR #514·#525·#535·#537·#541·#546·#551·#552·#553·#555 동일 결함 재발. 헌법 §1-A 매트릭스 갱신만으로는 root cause 차단 안 됨 (config 활성화가 본질).
- 안정성 Cycle 3 (2026-05-19 라운드 1) — 8 sub-agent system audit 5 evidence(architect·frontend·backend-core·ai-engineer·market-data) 동일 root cause 진단. 본 §1-A-extended 신설로 1차 대응.
- 안정성 Cycle 4 (2026-05-19 라운드 3) — `extensions.worktreeConfig=true` 활성 상태에서도 PR #562(ai-engineer)·#564(architect)·#565(backend-core) 라운드 2 dispatch 모두 author 결함 재발 evidence. root cause: 옵션 활성만으로는 각 worktree에 `config.worktree` 파일이 자동 발행되지 않음 → `git config --worktree`가 fallback path 모호함. 헬퍼 스크립트 보강 (`scripts/setup-worktree-config.sh` Step 2 신설 — 기존 모든 worktree에 config.worktree 일괄 발행 idempotent) + 본 §1-A-extended 필수 worktree-local 발행 의무 추가(`git config --worktree user.email/name` 명시 호출 LOCK)로 영구 차단.

**위반 시 risk**: §1-A 의무 호출 후에도 worktree 간 author config 휘발성 전파 재발 → CHANGELOG `(작성: <agent>)` 마커 부정확 + git blame audit 실패 + 사용자 신뢰 손실 누적.

### 1-B. task.mjs 4단계 본체

1. **start** — 작업 시작 즉시 `node scripts/task.mjs start --agent <a> --goal "..." --deliverables "..., ..."` (deliverables는 쉼표/세미콜론/줄바꿈 분리)
2. **check** — **각 결과물 완료 시 즉시** `node scripts/task.mjs check "<항목명>"` (deliverables 1건씩, 부분 문자열 매칭). 작업 종료 직전 일괄 호출 금지.
3. **done** — PR 생성 후 `node scripts/task.mjs done --pr <N>` (status=DONE + completedAt 자동 + pr 번호 기록). **`done` 호출 시 task 파일 자동 `git add` 수행됨** (안정성 Cycle 2 — 본 commit이 아닌 차기 commit에 자연 stage). add 실패해도 done 자체는 성공(graceful).
4. **archive** — orchestrator 영역. 머지 직후 `node scripts/task.mjs archive T-<id>` (status=DONE 검증 강제 — DONE 아니면 거부, `--force` 우회). sub-agent는 호출 금지.

### 1-C. task 파일 commit 의무 (안정성 Cycle 2 신설 — 2026-05-18)

`node scripts/task.mjs start` 직후 생성된 `_workspace/tasks/T-<id>-*.md` 는 **반드시 머지 대상 PR에 포함**되어야 한다. worktree 로컬에만 존재하고 commit 누락 시 → main 흔적 0 → orchestrator가 머지 후 `task.mjs archive` 호출 시 파일 없음 → `--force` 우회 또는 archive 누락 → 운영 부채 누적.

**처리 방법** (택1, sub-agent 자율):
- **권장**: `task.mjs start` 직후 즉시 `git add _workspace/tasks/T-<id>-*.md` 수행 → 첫 코드 commit에 자연 stage
- 또는 `task.mjs done --pr <N>` 호출이 자동 `git add` 수행하므로 done 직후 commit이 있으면 자연 stage

**역사 evidence** (안정성 Cycle 1 — 2026-05-18): PR #473·#474·#476 task 파일 commit 0건. PR #475만 task 파일이 main 흔적 보유. orchestrator archive 시 절반은 파일 부재로 누락.

### 1-D. 시간 표기

`nowIso()`는 KST(+09:00) ISO 8601. UTC 표기 금지(PR-22 정정).

### 1-E. 위반 시 risk

- PR-14·PR-18에서 누적 28~30 done 파일 일괄 정정 발생.
- sub-agent SKILL 호출 의무 흐려짐 → 사용자 보고 ("시간도 현시간들이랑 이상하고", "정보들이 명확히 갱신안되는거 같아").
- 안정성 Cycle 1 (2026-05-18) — author 결함 4 PR + task 파일 commit 누락 3 PR.

---

## §2. 머지 후 정리 4단계 (ADR 0033 §92·§112 + ADR 0036 §47·§57·§79 + PR-14)

PR 머지 직후 즉시 4단계. 누락 시 운영 부채 누적(worktree 잔재·옛 task 누적·로컬 브랜치 누적).

1. **main pull (root worktree 동기화)** — `cd <repo-root> && git pull --ff-only origin main`
2. **task 파일 archive** — `node scripts/task.mjs archive T-<id>` (§1 4단계 정합)
3. **worktree 제거** — `git worktree unlock <path> 2>/dev/null && git -C <path> restore _workspace/tasks/ 2>/dev/null && git worktree remove <path>` (force 없이 시도)
3-extended. **빈 wrapper 디렉토리 정리** — `git worktree remove`는 git 추적 파일만 제거하므로 `.gradle/`·`.idea/`·`node_modules/` 같은 build/IDE 캐시가 빈 디렉토리로 남음. 다음 명령 동반:
   ```bash
   rmdir <path>/.gradle 2>/dev/null
   rmdir <path>/.idea 2>/dev/null
   rmdir <path>/node_modules 2>/dev/null
   rmdir <path> 2>/dev/null   # 빈 경우만 (rm -rf 위험 회피)
   ```
   비어 있지 않으면 사용자에게 보고 (`ls <path>` 결과 인용).
4. **agent/* 로컬 브랜치 삭제** — `git branch -d agent/<agent>/<task>` (`-d` 안전 모드 — origin 머지 인식 시 허용)
4-extended. **worktree-agent-* 임시 브랜치 삭제** — Agent isolation 도구가 사용자 명시 `agent/<agent>/<task>` 외에 `worktree-agent-<id>` ref를 별도 생성. 동반 정리:
   ```bash
   git branch -D worktree-agent-<id>   # `-D` force (Agent isolation은 isolation 정합으로 force OK)
   ```
   누락 시 git branch 잔재 누적 → 사용자가 진행 중 작업으로 오인할 risk.

**자동 점검 (사이클 시작 시)** — 4 항목 모두 검증 의무:
1. `ls _workspace/tasks/T-*.md 2>/dev/null | wc -l` 5건 이상이면 일괄 archive
2. `git worktree list` main 외 잔재 검증
3. `ls .claude/worktrees/agent-*/ 2>/dev/null | wc -l = 0` 검증 (빈 wrapper 디렉토리 잔재)
4. `git branch | grep -c worktree-agent- = 0` 검증 (Agent isolation 임시 브랜치)

위반 시 사용자에게 즉시 보고 + 일괄 정리 권장.

**역사 evidence** (2026-05-19): worktree 잔재 9 디렉토리 + worktree-agent-* 23 브랜치 누적 → 사용자 명시 비판("작업이 다 끝났다고 하면서 남아있으면 내가 오해하잖아"). 본 §2 갱신 hotfix.

**worktree-agent-* 누적 정리**: Agent 도구 isolation worktree 부산물은 누적 15+ 시 사용자 직접 승인 후 일괄 정리(backlog §4).

**위반 시 risk**: PR-14 누적 28 task 일괄 archive 발생 + 사용자 명시 비판("이 adr? 룰? 을 잊은거 같은데").

---

## §3. KIS-first raw fetch 의무

**단일 권위**: [`docs/architecture/policies/kis-first-raw-fetch.md`](../../docs/architecture/policies/kis-first-raw-fetch.md) — Living Policy(ADR-0053)로 이관됨.

본 §은 charter 내 ref 슬롯만 보유 — 의무 절차 5단계·발동 조건·위반 baseline 본문은 위 policy 문서가 단독 권위. 갱신은 policy 본문만 update + ADR 후속 발행 (본 § 본문은 갱신 대상 아님).

**영역별 적용**: architect(spec 발행 시 raw 출처 인용 의무 전파) · market-data(KIS 어댑터·DSL·DART/Naver 모두 본 정책 적용) · backend-core(KIS 데이터 영속 시 raw 필드 보존 의무).

**역사 baseline**: PR-11 instrument 14 컬럼 vs KIS 73 필드 = 91.7% 갭 → PR-21에서 70 필드 add-only로 정정 (사용자 강한 비판 응답).

---

## §4. CHANGELOG 슬롯 채번룰 (ADR 0050)

architect spec/plan PR이 sub-agent 디스패치를 만들 때 **그 spec PR diff에 sub-agent별 CHANGELOG 슬롯 row를 사전 발행** + sub-agent는 본인 row만 in-place edit.

**슬롯 row 형식**: `| <date> | **<PREFIX>: PR-Na ...** — TBD (작성: <agent>) | ... |` + HTML 주석 anchor `<!-- pr-N-slot-<agent> -->` (hunk 분리, PR-10에서 도입).

**룰**:

1. spec/plan PR 발행 시 — **anchor + slot row prepend 의무** (CHANGELOG 표 직후, 최신 row 먼저). sub-agent마다 row 1건 + anchor 1건 + 끝 anchor `<!-- pr-N-slot-end -->`.
2. sub-agent 작업 시 — **본인 row만 in-place edit** (`(작성: <agent>)` 마커로 식별, `TBD` 자리표시자만 실제 사유로 교체). 다른 row·다른 anchor 절대 수정 금지(영역 침범).
3. 슬롯 row 외 추가 산출물 — 별도 정정 commit으로 row 보강.
4. **architect 미개입 사이클** (hotfix·결함 정정·작은 묶음 — 사용자 메모리 `feedback_spec_plan_scope.md`) — 슬롯 row 0 → sub-agent 평소대로 add-only insert. 두 번째 머지자가 git rebase 시 자체 해결.

**위반 시 risk**: 병렬 sub-agent 머지 시 modify-shared 충돌 발생 → hotfix PR + rebase 부담.

---

## §5. PR 생성 직전 rebase 의무

PR 생성 직전 main 정합. base 분기점(divergence) 누적 시 머지 충돌 risk.

```bash
git fetch origin main && git rebase origin/main
```

- rebase conflict 발생 시: 자체 해결 (본인 변경 + main 변경 모두 보존). 해결 불가 시 사용자 보고.
- rebase 후 force-push 필요 — 본인 agent/* 브랜치는 OK (main 또는 release/* 절대 force-push 금지 — pre-push hook).
- **머지 정책 = `--rebase`** (squash 금지 — author 보존 위반 risk). pre-commit author whitelist와 충돌 회피.

**위반 시 risk**: PR-23·PR-25 사례 — 통합 빌드 fail → orchestrator hotfix 강제 발생.

---

## §6. JPA Repository default fun 함정 (PR-23·PR-25 + ADR 0051)

**Spring Data JPA Repository interface**의 body 가진 fun은 **query inference에 걸려 부팅 실패**(`No property 'X' found`). default 키워드 없이도 동일 함정.

**금지 패턴**:

```kotlin
interface MyRepository : JpaRepository<MyEntity, Long> {
    // ❌ 절대 금지 — Spring Data가 'upsertWrapper' 메서드명을 query로 해석
    fun upsertWrapper(code: String, name: String) {
        upsertFull(code, name, /* default null 70건 */)
    }
}
```

**허용 패턴** (택1):

- ✅ **caller class 내부 private fun helper** (PR-25 정정 — `InstrumentSeedRunner.upsertSeed` private fun)
- ✅ **extension fun** (Repository 외부, 별도 파일)
- ✅ **Repository interface 내부 abstract fun만** (body 0 — Spring Data가 query inference로 SQL 생성)

**검증 절차** (architect spec/plan + sub-agent 코드 발행 시):

1. Repository interface 안에 body 가진 fun 존재 검증
2. 부팅 smoke 또는 통합 빌드로 `No property 'X' found` 에러 부재 확인
3. 의심 시 ADR 0051 본문 정독

**역사 사례**: PR-23 `BatchInstrumentJpaRepository.upsert(7파)` wrapper → 부팅 실패 → PR-25에서 `InstrumentSeedRunner.upsertSeed` private helper로 이동.

---

## §7. sub-agent fail 시 분기 룰 (orchestrator 영역)

sub-agent가 0 tool uses 종료, 환경 결함(Bash 실패), 또는 명세 위반 산출물 발행 시 — orchestrator는 **재디스패치 OR 자율 진행 둘 중 하나만**, 동시 진행 금지.

**판단 기준**:

- **재디스패치**: 명세 누락·sub-agent 환경 일시 결함·재시도로 회복 가능 → background 발사 후 결과 대기 (사용자 보고 후 결정 권장)
- **자율 진행**: 명세 충분 + 단순 결함 + orchestrator가 영역 침범 없이 정정 가능 → 즉시 main 브랜치 + orchestrator 모자(`bot+orchestrator@magicjar.local`) + 작업 시작

**금지**: 재디스패치 발사한 상태에서 자율 진행 동시 시작 → 작업 중복·영역 침범·머지 충돌 risk.

**보고 의무**: 사용자에게 분기 결정 사유 명시 + 결과(PR URL 또는 fail 원인) 보고.

**역사 사례**: PR-23·PR-25 통합 빌드 fail → orchestrator 자율 정정 진행. 사용자 명시 없이 hotfix 발행 → 사용자 사후 인지.

---

## §8. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-06-10 | **§11 신설 — 모듈 경계 빈 합류 검증 + silent fallback 금지 (ADR 0115 §2-P2 / 최고 관리자 전체 승인)** | 입력=spec `_workspace/specs/2026-06-10-execution-ledger-integrity-architecture.md` §5-1(C4·C5 메타 갭) + 2026-06-10 005930·전략8 incident G6 evidence — `RiskConfigJpaAdapter`가 api 모듈에만 `@Component` 존재 → consumer ApplicationContext 빈 부재 → `ObjectProvider.getIfAvailable()` 항상 null → `buildContext`가 `defaultFor(1M/10%)` silent 진행(사용자 5M/70% 설정인데 1M/10%로 더 좁게 평가 = 역방향 자본 위험). 부팅 성공하므로 헌법 §6(부팅 실패 배선 결함)이 못 잡음. ADR 0088이 Outbox 3개에 1회 fix했으나 룰화 안 돼 RiskConfig로 재발 → 헌법 룰화로 영구 차단. **§11-1**: domain port 구현 빈의 모듈별 합류 검증 의무(새 port 구현 시 소비 모듈 전부에 배선 테스트 — PR #888 consumer 정적 검증 2건 선례 LOCK). **§11-2**: silent fallback 금지(빈 부재·null 분기는 최소 ERROR 로그 + REAL 모드 fail-fast 또는 명시 degraded·로그 0 silent 진행 금지·12개 ObjectProvider 분기 점진 확산 backlog). 동반: ADR 0115 §2-P2-④ drain 가드 + development-rules 장중 drain 절차 + master §3-5 3중 상태 권위 매트릭스. 운영 코드 0 touch(docs only). 자본 영향 0 + KIS 호출 0 + AI 호출 0 + mock 합성 0. (architect, 사용자 전체 승인) |
| 2026-05-21 | **Stage 2 종합 정리 (v3.8.0 cut 전 LOCK) — §1-A chart-curator allowed values archived 마커 추가 + §10 운영 메모 명시 amend (architect 책임 + sub-agent 책임 + orchestrator 책임 정합)** | 사용자 명시 "v3.8.0 cut 전에 Phase C + qa 셋업 완료 + ★★★ ADR·헌법 정리를 한번 했으면 좋겠어. 지금 인덱스, 헌법 adr 많이 변화가 있었을 수도 있고 코드에만 있고 정리안된 것들을 찾아서 싹 정리해서 다시 토큰, 메모리 정제한다음 다시 깨끗한 마음으로 3.8.0 릴리즈부터는 토큰도 적절하게 읽어드릴 수 있을 수 있잖아? 그렇지?" — Stage 1 (P0 OPS 6/6 + KillSwitch race-safe + ADR 0094·0095·0096) 후속 정리 사이클. **본 cleanup PR diff** (architect 단독 11 정리 항목): (1) ADR README audit — README §9·§11·§14 정합 검증 GREEN (이미 Stage 1에서 0094·0095·0096 prepend 완료 + ADR 0080·0087 amend 마커 + ADR 0085 Superseded 마커 본문 정합) (2) 헌법 §1-§10 정합 검증 — 본 row 추가 + §1-A chart-curator archived 마커 + §10 amend (3) master-architecture §10·§13 정합 (Stage 1에서 0094·0095·0096 모두 prepend 완료 — 본 cleanup row 추가) (4) 메모리 audit 보고서 `_workspace/reports/2026-05-21-memory-audit-for-v3.8.0.md` 신규 (사용자 직접 amend 결재 의제) (5) CHANGELOG drift 검사 — Stage 1 6 P0 PR + KillSwitch PR 모두 row 존재 GREEN (6) backlog cleanup — `done/v3.7.x.md` archive 의제 (7) **qa.md 신규 발행** `.claude/agents/qa.md` — Visual QA "품질 헌터" 페르소나 본문 (Playwright + 5 시나리오 + DR drill 정기 + 사용자 dogfooding 변환 + 페르소나 매트릭스 cross-link) (8) **chart-curator.md archive** — `.claude/agents/_archive/chart-curator.md` 이동 + Superseded header amend + dispatch 0 명시 (9) CLAUDE.md amend — 8→7 + chart-curator 제거 + qa Visual 격상 + JVM 7 모듈 매트릭스 + ADR 카운트 93+ (10) **orphan decision audit 보고서** `_workspace/reports/2026-05-21-orphan-decision-audit-for-v3.8.0.md` 신규 — recent hotfix 14건 audit + 사용자 결재 의제 (11) skill SKILL.md 정합 검증 GREEN + magicjar-chart-curator skill archive 의제. **운영 코드 0 touch** (Kotlin/TS/SQL 0 변경 — docs·ADR·메모리 보고서·agent 파일·CLAUDE.md only) + **자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**. v3.8.0 cut 직전 토큰·메모리 정제 사용자 명시 의제 LOCK. (architect, opus 4.7 1M) |
| 2026-05-21 | **§9-4 backup·DR 권위 cross-link 추가 — ADR 0096 본문화** | ADR 0094 §3-7 P0-OPS-4 task 본문화 후속. ADR 0096 § PostgreSQL 매트릭스(daily 03:00 KST + 7일 retention + `_workspace/backups/postgres/daily/` LOCK) + § Redis 매트릭스(03:30 KST + 3일 retention + AOF 거부) + RPO 24h + RTO 30분 + R-4 runbook cross-link + DR drill 분기 의무 5 시나리오 + qa Visual E2E 자동 재현 의무 본문화. 본 §9-4 ref 슬롯 보강(산출물 위치·스크립트 경로·사용자 가이드 cross-link 추가) + 서두 권위 ADR 매트릭스 0096 추가. 운영 ADR 양식 3 슬롯 두 번째 적용 사례(SLO 영향·rollback plan·관측 매트릭스). 운영 코드 0 touch. backend-core P0-OPS-4 후속 wiring 5건 권고(Micrometer counter + Slack alert + persistence-shared Outbox monitoring + docker compose service 검토 + DR drill 자동화 script). 사용자 1회 작업 의무: 본 PR 머지 직후 `crontab -e` 4 라인 추가(`docs/operations/backup-setup.md` §2 본문). v3.8.0 운영 baseline P0 진척(P0 OPS 6건 중 4번째 task 완료 — P0-OPS-1·2·3·4). (architect) |
| 2026-05-21 | **§9-1 SLO 측정 매트릭스 + alert routing 권위 cross-link 추가 — ADR 0095 본문화** | ADR 0094 §3-2 P0-OPS-3 task 본문화 후속. ADR 0095 §2 측정 source(Prometheus query 1순위 + SystemEvent + Kafka 토픽 빈도) + §3 severity 4단 routing matrix(P4 INFO/P3 WARN/P2 ERROR/P1 CRITICAL — ADR 0084 정합) + §3-4 KillSwitch 자동 ON 검토 자동 액션 S-1·S-5 critical 한정(ADR 0043 race-safe 정합) 본문화. v3.8.0 baseline 5건 LOCK + Redis hit ratio·DB pool 활용률 2건은 P1 OPS amend backlog. backend-core P0-OPS-3 후속 wiring 5건 의무. (architect) |
| 2026-05-21 | **§9 신설 + §10 운영 메모 재배치 — 운영 단계 룰 6 항목 (SLO/SLA · 변경 관리 · on-call · backup/DR · ADR 양식 · 7 hierarchy LOCK)** | ADR 0094 amend Accepted (orchestrator 자율 위임 결재) — 8 → 7 hierarchy + qa Visual 격상 + ai-engineer 유지 + sre 영입 거절 + backend-core P0 OPS 6건 인수. 사용자 명시 "이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해" + "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든". (architect) |
| 2026-05-19 | §1-A-extended 보강 — 필수 worktree-local 발행 의무 + 라운드 3 evidence + 헬퍼 스크립트 Step 2 보강 | 라운드 3 evidence — `extensions.worktreeConfig=true` 활성 상태에서도 PR #562(ai-engineer)·#564(architect)·#565(backend-core) 라운드 2 dispatch 모두 author 결함 재발. root cause: 옵션 활성만으로는 각 worktree에 `config.worktree` 파일이 자동 발행되지 않음. `scripts/setup-worktree-config.sh` Step 2 신설(기존 모든 worktree에 config.worktree 일괄 발행 idempotent) + 본 §1-A-extended 필수 worktree-local 발행 의무 추가(`git config --worktree user.email/name` 4 명령 LOCK)로 영구 차단. (architect) |
| 2026-05-19 | §2-3·§2-4·§2-5 강화 — worktree 디렉토리·worktree-agent-* 잔재 누적 root cause fix | 사용자 명시 비판 (2026-05-19 KST) — 헌법 §2 4단계가 git 명령만 다루고 빈 wrapper 디렉토리·Agent isolation 임시 브랜치 검증 누락 → worktree 잔재 9 디렉토리 + worktree-agent-* 23 브랜치 누적. §2-3 빈 wrapper rmdir 명시 + §2-4 worktree-agent-* force delete 명시 + §2-5 사이클 시작 자동 점검 4 항목 신설(task 파일·worktree list·빈 wrapper·임시 브랜치). orchestrator SKILL.md 정합 갱신 동반. (architect) |
| 2026-05-19 | §1-A chart-curator author 추가 hotfix — `.husky/pre-commit` ALLOWED_AUTHOR regex + CLAUDE.md Git hook 본문 정합 흡수 | PR #514 evidence — chart-curator sub-agent commit이 frontend author로 attribution. PR #511 ADR 0085 영입 시 agent-charter §1-A allowed values는 갱신했으나 `.husky/pre-commit` 강제 regex + CLAUDE.md SoT 본문 갱신 누락. pre-commit hook이 chart-curator 거부 안 함 → sub-agent fallback으로 다른 author 사용 → git blame·CHANGELOG `(작성: chart-curator)` 마커 깨짐. (architect) |
| 2026-05-19 | §0(서문) 8 → 9 actor + §1-A `<agent>` 허용 값에 `chart-curator` 추가 (ADR 0085 영입 정합) | 사용자 dogfooding evidence(한국석유 일봉 5개월 stale) + 3 영역 교차 점유 단일 owner 부재 → 8번째 sub-agent chart-curator 영입. agent-charter.md SoT에 영입 정합 흡수. (architect) |
| 2026-05-18 | §1 강화 — 1-A worktree 진입 author 의무 + 1-C task 파일 commit 의무 신설 (안정성 Cycle 2 — backlog §59) | 안정성 Cycle 1 evidence — PR #473·#475 author=magicjar-frontend attribution + PR #473·#474·#476 task 파일 commit 0건. worktree default frontend가 다른 sub-agent에 전파되어 운영 audit 깨짐. (architect) |
| 2026-05-08 | 본 헌법 신설 — 7 § 룰 통합 | PR-26 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게". sub-agent SKILL 산재 룰을 단일 출처로 통합 + 9 SKILL.md 슬림화 동반. (architect) |

---

## §9. 운영 단계 룰 (2026-05-21 ADR 0094 amend 신설 — 사용자 위임 결재)

본 §은 ADR 0094 §4-2 본문에 권위. 본 charter는 운영 단계 진입 후 모든 sub-agent dispatch + ADR 발행 시 본 §을 의무 적용한다.

### 9-1. SLO/SLA 의무

자동매매 운영 단계 SLO 5건 (위반 시 SystemEvent P1 자동 발사 + Slack alert — ADR 0044 reuse):

- 자동매매 시그널 emit ~ 주문 발사 지연 **P95 < 5초 (P99 < 10초)**
- KIS WS 연결률 (장중 09:00~15:30) **> 99%**
- account API **p95 < 500ms**
- Outbox drain lag **P95 < 30초**
- Redis hit ratio **> 95%**
- DB connection pool 활용률 **< 80%**

**측정 매트릭스 + alert routing 권위**: **ADR 0095** (P0-OPS-3 — SLO/SLA + alert routing matrix). 본 §9-1 6건 중 v3.8.0 baseline 5건(S-1 시그널→주문 + S-2 KIS WS + S-3 account API + S-4 KIS rate limit + S-5 KillSwitch 자동 ON)은 ADR 0095 §2 측정 source(Prometheus query + SystemEvent + Kafka 토픽 빈도) 본문화. Redis hit ratio + DB pool 활용률 2건은 P1 OPS 사이클 amend (측정 source wiring backlog). severity 4단 routing matrix(P4 INFO/P3 WARN/P2 ERROR/P1 CRITICAL — ADR 0084 정합) + S-1·S-5 critical KillSwitch 자동 ON 검토 자동 액션(ADR 0043 race-safe 정합)은 ADR 0095 §3 본문화. backend-core P0-OPS-3 후속 wiring 5건(Micrometer timer + alert rule yml + Alertmanager + runbook) 의무.

### 9-2. 변경 관리 의무

- 모든 PR diff > 50 LOC 또는 자본 path 직결 시 **rollback plan 본문 명시 의무**
- canary 배포: dev → staging → REAL (자동매매 진입 전 30분 staging dwell)
- frontend 핵심 화면(Dashboard·Orders·Positions·Settings) 변경 PR은 **qa Visual 시나리오 자동 dispatch**
- 사용자 결재 가시화: PR description "운영 영향 매트릭스" 섹션 의무 (SLO 영향·예상 alert·롤백 단계 3건 + Visual 회귀 evidence)

### 9-3. on-call rotation (사용자 단독 운영자 단순화)

- **1차**: 사용자(최고 관리자) — KillSwitch 토글 권한 보유 + Slack alert 1차 수신
- **2차**: backend-core sub-agent — incident classification + runbook 적용 + DR/backup 가동
- **3차**: architect + 영역 sub-agent (market-data·strategy-engine·frontend·qa) — root cause + ADR 발행

### 9-4. 정기 backup·DR

**단일 권위**: **ADR 0096** (P0-OPS-4 — DR/backup 절차) — PostgreSQL backup 매트릭스 + Redis snapshot 매트릭스 + RPO 24h + RTO 30분 + DR drill 분기 의무 본문화. 본 §은 charter 내 ref 슬롯만 보유 — 산출물 위치·스크립트·복구 절차 본문은 ADR 0096 + R-4 runbook 단일 권위.

- postgres pg_dump cron 매일 03:00 KST + 7일 retention (`_workspace/backups/postgres/daily/YYYY-MM-DD.dump`) — **backend-core 책임** (스크립트: `scripts/backup/pg-dump-daily.sh`)
- Redis snapshot 매일 03:30 KST + 3일 retention (`_workspace/backups/redis/snapshots/dump-YYYY-MM-DD-HHMM.rdb`) — **backend-core 책임** (스크립트: `scripts/backup/redis-snapshot.sh`)
- 분기별 DR drill (사용자 결재 의무) + **qa Visual E2E 시나리오 5건 자동 재현 의무** (ADR 0096 §6-2)
- **RPO 24h + RTO 30분** LOCK (ADR 0096 §4) — 복구 절차 본문은 R-4 runbook 단일 권위
- 사용자 cron 등록 가이드: `docs/operations/backup-setup.md`

### 9-5. 신규 ADR 양식 (운영 단계)

모든 신규 ADR §결과 직전에 다음 3 슬롯 의무 추가:

1. **SLO 영향** — 본 결정이 §9-1 SLO에 미치는 영향 (개선/유지/저하 + 수치 변동)
2. **rollback plan** — 본 결정이 운영에 부적합 발견 시 즉시 되돌리는 절차 (코드 revert · DB 마이그레이션 down · 설정 toggle 등)
3. **관측 매트릭스** — 본 결정 결과를 운영 단계에서 측정하는 metric 또는 alert (Prometheus query · Grafana panel · Slack alert) + **Visual 회귀 시나리오 (frontend 영향 시)**

### 9-6. sub-agent 7 hierarchy LOCK (ADR 0094 amend)

운영 단계 진입 후 sub-agent 7 actor:

| actor | 책임 | 페르소나 |
|-------|------|---------|
| architect | 시스템 설계·ADR·운영 헌법 amend·SLO/SLA 정의 | 균형 — 결정자 |
| backend-core | Spring Boot 도메인·인프라·persistence-shared·KIS adapter + **운영 인프라 단일 owner (P0 OPS 6건)** | 진보 — "리팩토링맨" |
| frontend | React/TS·STOMP UI·운영 dashboard + **TradingView LWC v4 단일 owner (chart-curator 이관)** | 보수 — "안정빌드" |
| market-data | KIS API·시세·뉴스·공시 + **캔들 SLA 정합성 단일 owner (chart-curator 이관)** | 보수 — "안정빌드" |
| strategy-engine | DSL·지표·백테스트·시그널·리스크·주문 path | 보수 — "안정빌드" |
| ai-engineer | Spring AI·프롬프트·AI 옵션 보조 (사용자 트리거만 — W4 진입 시 active dispatch) | 균형 — 보조 분석 |
| **qa (격상)** ★ | **Visual QA "품질 헌터"** — Playwright + 스크린샷 + 시각 회귀 + E2E + 사용자 시나리오 자동 검증 | 방어 — "품질 헌터" |

- **chart-curator 해고** (ADR 0094 §2-2-A) — 책임 이관 매트릭스: `_workspace/specs/2026-05-21-chart-curator-handover.md`
- **qa Visual 격상** (ADR 0094 §2-2-B) — 도구·페르소나·시나리오: `_workspace/specs/2026-05-21-qa-visual-uplift.md`
- **sre 영입 거절** (사용자 명시) — P0 OPS 6건은 backend-core 인수

---

## §10. 운영 메모

- **architect 책임**: 본 헌법 본문은 architect 단독 PR로만 갱신. 새 § 추가 시 ADR 발행 의무. ADR/master/README/CHANGELOG 정합 SoT 권위 LOCK (ADR 0030).
- **sub-agent 책임**: 본 헌법 정독 의무 + 영역별 특수 룰만 SKILL.md에 명시 (KIS-first는 architect·market-data만 관련 § 강조). **qa는 본 헌법 §9-2·§9-4 추가 정독 의무 (frontend 핵심 화면 PR Visual 자동 dispatch + DR drill 분기 의무 — ADR 0094·0096)**.
- **orchestrator 책임**: 사이클 시작 시 본 헌법 §1·§2 자동 점검 + sub-agent 디스패치 시 §4·§5 사전 검증 + **운영 단계 진입 후 §9 추가 검증 (frontend PR Visual dispatch 의무 + SLO 영향 ADR slot + rollback plan + qa 의무 dispatch trigger)**. chart-curator dispatch 발사 0 LOCK (해고 — ADR 0094 amend).
- **사용자 책임**: 본 헌법 룰 위반 발견 시 architect에게 갱신 명시 (운영 룰 흐려짐 신호). DR drill 분기 의무 결재 + cron 등록 1회 작업 의무 (`docs/operations/backup-setup.md`).

---

## §11. 모듈 경계 빈 합류 검증 + silent fallback 금지 (2026-06-10 신설 — ADR 0115 §2-P2 / 최고 관리자 전체 승인)

> **권위**: ADR 0115 §2-P2(체결-원장-리스크 체인 통합) + spec `_workspace/specs/2026-06-10-execution-ledger-integrity-architecture.md` §5-1(C4·C5 메타 갭). **입력 evidence**: 2026-06-10 005930·전략8 incident G6 — `RiskConfigJpaAdapter`가 api 모듈에만 `@Component`로 존재 → consumer ApplicationContext엔 빈 부재 → `ObjectProvider<RiskConfigRepository>.getIfAvailable()`이 항상 null → `buildContext`가 `defaultFor(1M/10%)`로 **silent 진행**. 사용자가 5M/70%로 설정을 넓혔는데 시스템은 1M/10%로 더 좁게 평가 = **역방향 자본 위험**. 부팅은 성공하므로 헌법 §6(부팅 실패하는 배선 결함)이 못 잡는다. ADR 0088이 Outbox 3개에 1회 fix했으나 룰화 안 돼 RiskConfig로 재발.
>
> 본 §은 **부팅은 성공하는데 필수 cross-module 빈이 silent null인** 결함을 영구 차단한다. 자본·리스크 결정에 쓰이는 port의 모듈 경계 누락을 정적 테스트 + log 가시화 + REAL fail-fast 3중으로 닫는다.

### 11-1. 도메인 port 구현 빈의 모듈별 합류 검증 의무

- **룰**: domain port의 구현 빈(`@Component`/`@Repository`)이 **그 port를 `ObjectProvider`로 주입받는 모든 앱 모듈(api·consumer·batch)의 ApplicationContext에 실제 합류**하는지 검증하는 정적 테스트를 의무화한다. ADR 0088 `persistence-shared` 패턴(api·consumer 양쪽 빈 합류)이 표준 경로다.
- **발동**: cross-module 필수 port — 자본·리스크 결정에 쓰이는 port(`RiskConfigRepository`·`PositionPort`·`OrderEventOutboxRepository`·`KillSwitchQueryPort`·`ExecutionStrategyPositionUpdatePort` 등) 신규/이동 PR.
- **검증 방법**: 각 소비 앱 모듈에 "이 port의 빈이 컨텍스트에 존재하는가"를 단언하는 `@SpringBootTest` 슬라이스 또는 component scan basePackages 정적 검증을 동반한다. PR #888이 추가한 consumer 배선 정적 검증 2건이 선례 — 이를 룰로 LOCK한다.
- **새 port 구현 시 의무**: 새 도메인 port 구현 빈을 추가하면 그 port를 소비하는 **모듈 전부에 배선 테스트**를 함께 추가한다(한 모듈만 통과는 불완전 — G6 재발 패턴).

### 11-2. silent fallback 금지

- **룰**: `ObjectProvider.getIfAvailable() == null` 분기(빈 부재) 또는 null·empty fallback 분기는 **반드시 최소 ERROR 로그 가시화 + REAL 모드 fail-fast 또는 명시 degraded 표기**. silent 진행(로그 0으로 default 값 사용) 금지.
- **근거**: silent fallback은 모듈 경계 결함을 숨겨 incident를 늦게 발견하게 한다(G6은 3주 잠복). 빈 부재가 자본·리스크 평가 default를 바꾸면 REAL 모드에서는 즉시 부팅·실행 실패가 더 안전하다(잘못된 한도로 실주문 발사보다 낫다).
- **적용**: PR #888이 RiskConfig에 적용한 패턴(null 분기 ERROR 로그 + REAL fail-fast)을 `SignalGeneratedKafkaListener` 등의 `ObjectProvider` graceful 분기(config·event·position·notifier·killSwitch·strategyLookup·balance 등 12개) 전체로 점진 확산(backlog). 각 분기는 부재 시 거동을 명시(killSwitch는 fail-true·position은 명시 degraded 등)하고 silent 0건으로 수렴.
- **MOCK/REAL 분기**: MOCK은 ERROR 로그 + degraded 진행 허용(개발 흐름 유지), REAL은 자본·리스크 결정 port 부재 시 fail-fast LOCK. 모드 무관 silent(로그 0) 금지.

## §12. 주문 액션 승인 매트릭스 (2026-06-11 신설 — 최고 관리자 직접 결재)

> **계기**: 2026-06-11 canary dwell 중 orchestrator가 전략 8 잔존 MOCK 포지션 청산을 위해 수동 MOCK SELL(#283)을 사전 승인 없이 발행. 모의·자본 영향 0·실시간 보고였으나 최고 관리자가 주문 내역에서 발견 후 정책 결정.

| 액션 | 승인 요건 |
|------|-----------|
| **REAL 주문 발행·취소·정정** (orchestrator/sub-agent 발의 수동 개입) | **사전 명시 승인 의무** — 발행 전 최고 관리자 확인. 승인 없는 REAL 주문 액션 절대 금지 |
| **MOCK(모의) 주문 발행·취소** | 사후 보고로 충분 — 발행 즉시 사유·주문 ID·결과를 보고에 명시 |
| **KillSwitch ON** (비상 차단) | **예외 — 즉시 실행** 후 보고 (자본 보호 방향이므로 지체 금지). OFF는 기존 룰대로 사용자 단독 결재 (R3 §3-2) |
| 전략 자동매매의 REAL 주문 (시그널 경로) | 본 매트릭스 대상 아님 — canary/운영 결재(체크리스트·runbook)가 승인 권위 |

## §13. 롱 온리 원칙 ref (2026-06-12 — 최고 관리자 결재)

> **본문 권위 = [`docs/architecture/policies/long-only-trading.md`](../../docs/architecture/policies/long-only-trading.md)** (charter는 ref만 — policies 중복 처리 룰).
> 요지: **magicJar에 공매도(숏)는 없다. 매도 = 항상 보유분 청산.** 전략 생성·매도/매수 로직·cap 설계·AI 추천 전부 이 전제를 따른다. BUY=가격 보호 / SELL=체결 보장 비대칭 원칙.
