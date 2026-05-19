# magicJar 운영 헌법 (Agent Charter)

> **위치**: `_workspace/arch/agent-charter.md`
> **신설일**: 2026-05-08 (PR-26 — 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게")
> **권위**: 본 헌법은 9 sub-agent SKILL + orchestrator SKILL + 메타 ADR(0033·0036·0046·0049·0050·0085)에서 산재된 운영 룰을 단일 출처로 통합한 **참조 가능한 헌법**이다.
> **의무 정독 대상**: architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa · chart-curator · orchestrator (9 actor 모두 — chart-curator 영입 2026-05-19 ADR 0085)
> **변경 정책**: 본 헌법은 architect 단독 PR로만 갱신. sub-agent SKILL은 본 헌법을 참조만 하고 룰 본문 중복 금지.

본 헌법은 **운영 룰의 단일 출처**다. 본문에 명시된 7개 § 룰을 위반하면 운영 부채(hotfix·재작업·사용자 비판) 누적 risk. 각 § 본문은 30~40줄 max — 헌법은 실행 매뉴얼이 아닌 참조 룰북.

---

## §1. task.mjs 4단계 의무 (ADR 0036 + PR-18·PR-22 + 안정성 Cycle 2)

agent/* 브랜치 작업 시 `scripts/task.mjs` CLI 4단계 호출 의무. 누락 시 task 데이터 무결성 깨짐 → done/ 누적·체크리스트 미반영·completedAt null 같은 사용자 보고 발생.

### 1-A. worktree 진입 의무 (안정성 Cycle 2 신설 — 2026-05-18)

sub-agent는 worktree 진입 시점에 **워크트리 로컬 git config**를 본인 author로 강제 설정. global·인근 worktree default(예: `bot+frontend@magicjar.local`)를 그대로 둔 채 작업하면 commit author가 **다른 에이전트로 attribution**되어 git blame·CHANGELOG 작성자 표기·운영 audit가 깨짐.

```bash
git config user.email "bot+<agent>@magicjar.local"
git config user.name "magicjar-<agent>"
```

- `<agent>` ∈ {architect, backend-core, market-data, strategy, ai, frontend, qa, chart-curator, orchestrator}
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
| 2026-05-19 | §1-A-extended 보강 — 필수 worktree-local 발행 의무 + 라운드 3 evidence + 헬퍼 스크립트 Step 2 보강 | 라운드 3 evidence — `extensions.worktreeConfig=true` 활성 상태에서도 PR #562(ai-engineer)·#564(architect)·#565(backend-core) 라운드 2 dispatch 모두 author 결함 재발. root cause: 옵션 활성만으로는 각 worktree에 `config.worktree` 파일이 자동 발행되지 않음. `scripts/setup-worktree-config.sh` Step 2 신설(기존 모든 worktree에 config.worktree 일괄 발행 idempotent) + 본 §1-A-extended 필수 worktree-local 발행 의무 추가(`git config --worktree user.email/name` 4 명령 LOCK)로 영구 차단. (architect) |
| 2026-05-19 | §2-3·§2-4·§2-5 강화 — worktree 디렉토리·worktree-agent-* 잔재 누적 root cause fix | 사용자 명시 비판 (2026-05-19 KST) — 헌법 §2 4단계가 git 명령만 다루고 빈 wrapper 디렉토리·Agent isolation 임시 브랜치 검증 누락 → worktree 잔재 9 디렉토리 + worktree-agent-* 23 브랜치 누적. §2-3 빈 wrapper rmdir 명시 + §2-4 worktree-agent-* force delete 명시 + §2-5 사이클 시작 자동 점검 4 항목 신설(task 파일·worktree list·빈 wrapper·임시 브랜치). orchestrator SKILL.md 정합 갱신 동반. (architect) |
| 2026-05-19 | §1-A chart-curator author 추가 hotfix — `.husky/pre-commit` ALLOWED_AUTHOR regex + CLAUDE.md Git hook 본문 정합 흡수 | PR #514 evidence — chart-curator sub-agent commit이 frontend author로 attribution. PR #511 ADR 0085 영입 시 agent-charter §1-A allowed values는 갱신했으나 `.husky/pre-commit` 강제 regex + CLAUDE.md SoT 본문 갱신 누락. pre-commit hook이 chart-curator 거부 안 함 → sub-agent fallback으로 다른 author 사용 → git blame·CHANGELOG `(작성: chart-curator)` 마커 깨짐. (architect) |
| 2026-05-19 | §0(서문) 8 → 9 actor + §1-A `<agent>` 허용 값에 `chart-curator` 추가 (ADR 0085 영입 정합) | 사용자 dogfooding evidence(한국석유 일봉 5개월 stale) + 3 영역 교차 점유 단일 owner 부재 → 8번째 sub-agent chart-curator 영입. agent-charter.md SoT에 영입 정합 흡수. (architect) |
| 2026-05-18 | §1 강화 — 1-A worktree 진입 author 의무 + 1-C task 파일 commit 의무 신설 (안정성 Cycle 2 — backlog §59) | 안정성 Cycle 1 evidence — PR #473·#475 author=magicjar-frontend attribution + PR #473·#474·#476 task 파일 commit 0건. worktree default frontend가 다른 sub-agent에 전파되어 운영 audit 깨짐. (architect) |
| 2026-05-08 | 본 헌법 신설 — 7 § 룰 통합 | PR-26 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게". sub-agent SKILL 산재 룰을 단일 출처로 통합 + 9 SKILL.md 슬림화 동반. (architect) |

---

## §9. 운영 메모

- **architect 책임**: 본 헌법 본문은 architect 단독 PR로만 갱신. 새 § 추가 시 ADR 발행 의무.
- **sub-agent 책임**: 본 헌법 정독 의무 + 영역별 특수 룰만 SKILL.md에 명시 (KIS-first는 architect·market-data만 관련 § 강조).
- **orchestrator 책임**: 사이클 시작 시 본 헌법 §1·§2 자동 점검 + sub-agent 디스패치 시 §4·§5 사전 검증.
- **사용자 책임**: 본 헌법 룰 위반 발견 시 architect에게 갱신 명시 (운영 룰 흐려짐 신호).
