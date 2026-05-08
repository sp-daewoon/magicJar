# 07 — MD 룰 정책 진화 (v3.0.1 → v3.1.0)

> **작성자**: architect (PR-30)
> **시점**: 2026-05-08 v3.1.0 태그 컷 직후
> **사용자 명시 의도**: "우리 md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록"

본 문서는 v3.1.0 사이클(2026-05-06 v3.0.1 → 2026-05-08 v3.1.0)에서 **MD 룰 정책이 어떻게 더 나아졌는지**를 사례·PR·effect 3축으로 어필한다. 추측 0 — 모든 룰은 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § + ADR 0049~0051 + backlog 처리 매트릭스로 추적 가능.

각 § 본문은 **도입 전 / 도입 후 / 효과 / 사례 PR** 4단 구조다.

---

## §1. 운영 헌법 7 § 통합 (`_workspace/arch/agent-charter.md` — PR-26)

### 도입 전

운영 룰이 9 sub-agent SKILL.md + orchestrator SKILL.md + 메타 ADR(0033·0036·0046·0049·0050)에 산재. sub-agent마다 동일 룰 본문 중복 → 갱신 누락 시 룰 흐려짐. 사용자 보고:

> "이 adr? 룰? 을 잊은거 같은데" (PR-14 직전)
> "강령 만들어야 하고", "토큰 소모 없이 가게" (PR-26)

### 도입 후

`_workspace/arch/agent-charter.md` 단일 출처로 7 § 룰 통합 (PR-26 #224, commit `432eaaf`):

| § | 제목 | 정합 ADR |
|---|------|----------|
| §1 | task.mjs 4단계 의무 | ADR 0036 + PR-18·PR-22 |
| §2 | 머지 후 정리 4단계 | ADR 0033·0036 |
| §3 | KIS-first raw fetch 의무 | 사용자 메모리 `feedback_kis_first_policy.md` |
| §4 | CHANGELOG 슬롯 채번룰 | ADR 0050 |
| §5 | PR 생성 직전 rebase 의무 | ADR 0034 |
| §6 | JPA Repository default fun 함정 | ADR 0051 |
| §7 | sub-agent fail 시 분기 룰 | orchestrator 영역 |

sub-agent SKILL은 `구두 정독 의무 + 영역별 특수 룰만` 명시 (총 9 SKILL `579 → 506 LOC, -12.6%` 슬림화).

### 효과

- **단일 출처**: 갱신 1회 → 전체 sub-agent 정합 (이전: 9 SKILL 모두 갱신 필요)
- **architect 단독 PR로만 갱신** — 권위 명확
- **새 § 추가 시 ADR 발행 의무** (메타 진화 추적성)
- 사용자 보고 "룰 흐려짐" 신호 발생 시 단일 location에서 확인 가능

### 사례 PR

- **PR-26 (#224)** `docs(arch): PR-26 운영 헌법(agent-charter) 통합 + ADR 0051 + backlog 처리 완료`
- 9 sub-agent SKILL 슬림화 동반 (commit `cbca639`·`156e7f9`)

---

## §2. task.mjs 4단계 (start·check·done·archive)

### 도입 전

agent/* 브랜치 작업 시 task 파일 호출 의무 흐려짐. 사용자 보고:

> "시간도 현시간들이랑 이상하고", "정보들이 명확히 갱신안되는거 같아"

PR-14 시점 누적 28 task 파일이 `_workspace/tasks/`에 RUNNING 상태로 누적, 체크리스트 미반영, completedAt null 다수.

### 도입 후

헌법 §1 의무화 + `scripts/task.mjs` 강화:

| 단계 | 명령 | 시점 | 책임 |
|------|------|------|------|
| **start** | `node scripts/task.mjs start --agent <a> --goal "..." --deliverables "..."` | 작업 시작 즉시 | sub-agent |
| **check** | `node scripts/task.mjs check "<항목>"` | 결과물 1개 완료 즉시 | sub-agent |
| **done** | `node scripts/task.mjs done --pr <N>` | PR 생성 후 | sub-agent |
| **archive** | `node scripts/task.mjs archive T-<id>` | 머지 직후 | orchestrator (sub-agent 호출 금지) |

**보강사항**:
- KST 시간 표기 (PR-22 `f34d264` `nowIso()` UTC → KST +09:00)
- archive 시 status=DONE 검증 강제 (PR-18 `5222f6d`) + `--force` 우회 옵션
- `--force` flag parsing 보강 (PR-26 `2ed00ac` parseArgs boolean 인식)

### 효과

- **누적 task 0건**: PR-14에서 28건, PR-18에서 30건 일괄 정정 후 PR-26·PR-29까지 누적 0건
- **체크리스트 명시화**: PR-30(본 사이클) sub-agent 5 결과물 모두 check 호출 확인 가능
- **시간 정합**: KST ISO 8601 표기로 사용자 보고 해소

### 사례 PR

- **PR-14 (#210)** `chore(meta): PR-14 머지 후 정리 4단계 룰 + 누적 28 task archive + Dozzle`
- **PR-18 (#214)** `chore(meta): PR-18 task 파일 무결성 보강 + 옛 done 일괄 정정`
- **PR-22 (#218)** `fix(script): PR-22 task.mjs 시간 KST 정정`
- **PR-26 (#224)** `--force` flag parsing 보강

---

## §3. 머지 후 정리 4단계 (worktree·task·로컬 브랜치)

### 도입 전

PR 머지 후 정리 룰 흐려짐 → worktree 잔재 누적, 옛 task done/ 미archive, 로컬 agent/* 브랜치 누적.

### 도입 후

헌법 §2 의무화:

```bash
# 1. main pull (root worktree 동기화)
cd <repo-root> && git pull --ff-only origin main

# 2. task 파일 archive
node scripts/task.mjs archive T-<id>

# 3. worktree 제거
git worktree unlock <path> 2>/dev/null
git -C <path> restore _workspace/tasks/ 2>/dev/null
git worktree remove <path>

# 4. agent/* 로컬 브랜치 삭제
git branch -d agent/<agent>/<task>
```

**자동 점검 (사이클 시작 시)**:
- `ls _workspace/tasks/T-*.md | wc -l` 누적 5건 이상 → 일괄 archive 의무
- `git worktree list` main 외 잔재 검증

### 효과

- **PR-29 본격 머지 후 정리**: 4 task 일괄 archive(`T-20260508-{0241,0946,0947,0948}-*` — CHANGELOG 2026-05-08 row 18)
- **PR-29d 머지 후 정리**: 단일 worktree `/Users/ak-song/magic-pr-29d` 제거 + agent/backend-core/pr-29d-r3-batch 로컬 브랜치 삭제 (CHANGELOG row 17)
- **worktree-agent-* 누적**: backlog §4로 별도 의제. 사용자 직접 승인 후 일괄 정리 (다중 worktree 허용 + 정리 의무는 그대로 — 사용자 메모리 `feedback_worktree_cleanup.md`)

### 사례 PR

- **PR-14 (#210)** — 룰 명시화 + 28 task 일괄 archive
- **#232 PR-29 cleanup** `chore(meta): PR-29 W1 본격 머지 후 정리 — task 4건 archive + backlog §21 V7·V8 검토 close`
- **#234 v3.1.0 release-cut** — 정리 4단계 GREEN 후 태그 컷

---

## §4. KIS-first raw fetch 의무 (`open-trading-api` 레포 정합)

### 도입 전

KIS API · 외부 데이터 규격 결정 시 자체 추론 가능. PR-11 (#205~#207) T-IM1 instrument 테이블 14 컬럼만 spec → 사용자 강한 비판:

> "kis 데이터 규격 확인 안하고 막 만들어서 꾸겨넣은거잖아" (PR-21 직전 — 2026-05-07)

fitness gap 91.7% (KIS 73 필드 - 14 instrument 컬럼 = 59 누락 / 64 KIS = 91.7%).

### 도입 후

헌법 §3 의무화 + 사용자 메모리 `feedback_kis_first_policy.md` 정합:

**5단계 절차**:
1. **raw fetch 직접 다운로드** — `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/<path>`
2. **자체 추론 0 정책** — tr_id·endpoint·필드·byte offset·DSL 자체 결정 금지. 모든 결정은 raw 출처 인용 의무
3. **검증 보고서 산출 권장** — 새 endpoint·새 데이터 규격 결정 시 `_workspace/specs/<pr>-kis-raw-verification.md`
4. **sub-agent에 raw 출처 인용 의무 전파** — dispatch §에 "코드 inline 주석에 raw URL 명시" 의무
5. **MCP 활용** — `mcp__kis-code-assistant__search_domestic_stock_api` 결과 인용 권장

### 효과

- **PR-21 fitness gap 해소** — 14 → 83 필드 add-only (PR-21a #219 KOSPI 70 + KOSDAQ 64 widths · PR-21b #220 InstrumentJpaEntity 83 필드)
- **PR-29 spec/plan 사이클**: T-MS1 KIS chk-holiday CTCA0903R + T-CA1 KIS ksdinfo 6 endpoint(HHKDB669100C0~106C0) + DART OPENDART pblntf_ty=B+D 모두 raw 출처 인용 의무 적용
- **자체 추론 0건**: PR-21a market-data 산출물 코드 inline 주석에 11회 raw URL 인용 (`KisInstrumentRaw.kt`·`KisInstrumentMasterAdapter.kt`)
- **KisChkHolidayAdapter** 1:1 정합: `chk_holiday.py` raw 1:1 정합 검증 (CHANGELOG row 21)

### 사례 PR

- **PR-21 사이클** (#217 spec → #219 PR-21a market-data → #220 PR-21b backend-core)
- **PR-29 사이클** spec(#228) → 본격 구현(#230 #231 #229 #233)
- 검증 보고서 baseline: `_workspace/specs/pr-21-kis-header-raw-verification.md`

---

## §5. CHANGELOG 슬롯 채번룰 (ADR 0050)

### 도입 전

병렬 sub-agent 디스패치 시 CHANGELOG 동시 prepend → 머지 시 `modify-shared` 충돌 빈발. PR-9 시점 첫 인지.

### 도입 후

ADR 0050 (PR-8 #197) + 헌법 §4:

**룰**:
1. spec/plan PR 발행 시 — **anchor + slot row prepend 의무** (CHANGELOG 표 직후, 최신 row 먼저)
2. sub-agent 작업 시 — **본인 row만 in-place edit** (`(작성: <agent>)` 마커로 식별, `TBD` 자리표시자만 실제 사유로 교체)
3. 슬롯 row 외 추가 산출물 — 별도 정정 commit으로 row 보강
4. **architect 미개입 사이클** (hotfix·결함 정정·작은 묶음) — 슬롯 row 0 → sub-agent 평소대로 add-only insert

**HTML 주석 anchor 도입** (PR-10 #201 — hunk 분리 강화):
```
<!-- pr-N-slot-architect -->
| TBD — PR-N architect: ... |
<!-- pr-N-slot-backend-core -->
| TBD — PR-N backend-core: ... |
...
<!-- pr-N-slot-end -->
```

### 효과

- **modify-shared 충돌 0%**: PR-10·PR-11·PR-21·PR-29·PR-30 모두 병렬 sub-agent 머지 시 충돌 0건
- **CHANGELOG 정합**: 사이클별 PR row 시계열 추적 가능 (06-history-traceability §2)
- **architect 미개입 사이클 폐기**: hotfix·결함 정정 시 sub-agent 자유롭게 row insert (사용자 메모리 `feedback_spec_plan_scope.md`)

### 사례 PR

- **PR-8 (#197)** ADR 0050 발행
- **PR-9 (#198)** 첫 적용 (slot 3건)
- **PR-10 (#201)** HTML 주석 anchor 도입 (sub-agent 4 slot)
- **PR-21 (#217)** 슬롯 row 4 anchor + 3 row 사전 발행 (4번째 적용)
- **PR-29 (#228)** 5 anchor 발행 (architect + backend-core + market-data + frontend + end)
- **PR-30 (#235 본 사이클)** 4 anchor 발행 (architect + backend-core + market-data + frontend + end)

---

## §6. rebase 의무 (ADR 0034 + 헌법 §5)

### 도입 전

머지 정책에 squash 사용 가능. squash가 web user(`git config user.email`이 web user로 변경)를 author로 덮어쓰는 risk → pre-commit author whitelist와 충돌.

### 도입 후

ADR 0034 (PR-1 #181 정정 squash → rebase) + 헌법 §5:

**룰**:
- **PR 생성 직전 rebase 의무**: `git fetch origin main && git rebase origin/main`
- **머지 정책 = `--rebase`** (squash 금지)
- **agent/* 브랜치 force-push OK** (main·release/* 절대 force-push 금지 — pre-push hook)
- rebase conflict 발생 시 자체 해결 (본인 변경 + main 변경 모두 보존)

### 효과

- **author 보존**: pre-commit author whitelist 정합 (`bot+<agent>@magicjar.local`) — PR-21·PR-26·PR-29 모두 올바른 author 보존
- **PR-23·PR-25 통합 빌드 fail 사례**: 베이스 분기점 누적 → orchestrator hotfix 강제 발생 — 이후 헌법 §5 rebase 의무 강조 보강
- **head ref 청결**: agent/* 브랜치 누적 미발생 (CHANGELOG row 18 — 4건 일괄 archive)

### 사례 PR

- **PR-1 (#181)** ADR 0034 squash → rebase 정정
- **PR-23 (#221)** 통합 빌드 hotfix
- **PR-25 (#223)** 후속 hotfix
- **헌법 §5** PR-26 (#224) 통합

---

## §7. Repository default fun 함정 회피 (ADR 0051)

### 도입 전

Spring Data JPA Repository interface body fun 작성 가능. `default fun upsertWrapper(...)` 패턴이 query inference에 걸려 부팅 실패. 헌법 §6:

> Spring Data JPA Repository interface의 body 가진 fun은 query inference에 걸려 부팅 실패 (`No property 'X' found`). default 키워드 없이도 동일 함정.

PR-23(#221) `BatchInstrumentJpaRepository.upsert(7파)` wrapper → 부팅 실패.

### 도입 후

ADR 0051 (PR-26 #224 — `1ada3d5`) + 헌법 §6:

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
- ✅ caller class 내부 private fun helper (PR-25 정정 패턴 — `InstrumentSeedRunner.upsertSeed`)
- ✅ extension fun (Repository 외부, 별도 파일)
- ✅ Repository interface 내부 abstract fun만 (body 0)

**검증 절차** (architect spec/plan + sub-agent 코드 발행 시):
1. Repository interface 안에 body 가진 fun 존재 검증
2. 부팅 smoke 또는 통합 빌드로 `No property 'X' found` 에러 부재 확인
3. 의심 시 ADR 0051 본문 정독

### 효과

- **PR-25 정정 패턴 정합**: `InstrumentSeedRunner.upsertSeed` private fun helper로 이동
- **헌법 §6 통합**: sub-agent 디스패치 시 spec에서 검증 절차 의무
- **재발 0건**: PR-26 이후 Repository default fun 함정 사례 0

### 사례 PR

- **PR-23 (#221)** 통합 빌드 fail (upsert wrapper)
- **PR-25 (#223)** 정정 (Repository wrapper → Runner helper)
- **PR-26 (#224)** ADR 0051 신규 발행 + 헌법 §6 통합

---

## §8. sub-agent fail 분기 룰 (헌법 §7)

### 도입 전

sub-agent가 0 tool uses 종료, 환경 결함, 또는 명세 위반 산출물 발행 시 — orchestrator 행동 모호. 재디스패치 + 자율 진행 동시 발사 risk → 작업 중복·영역 침범·머지 충돌.

### 도입 후

헌법 §7 + orchestrator SKILL 보강 (PR-26 `156e7f9`):

**판단 기준**:
- **재디스패치**: 명세 누락·sub-agent 환경 일시 결함·재시도로 회복 가능 → background 발사 후 결과 대기 (사용자 보고 후 결정 권장)
- **자율 진행**: 명세 충분 + 단순 결함 + orchestrator 영역 침범 없이 정정 가능 → 즉시 main 브랜치 + orchestrator 모자(`bot+orchestrator@magicjar.local`) + 작업 시작

**금지**: 재디스패치 발사한 상태에서 자율 진행 동시 시작 → 작업 중복·영역 침범·머지 충돌 risk.

**보고 의무**: 사용자에게 분기 결정 사유 명시 + 결과(PR URL 또는 fail 원인) 보고.

### 효과

- **사용자 사후 인지 회피**: 자율 진행 시점에 사용자 명시 보고 의무 (PR-23·PR-25 사례 — 사용자 사후 인지 baseline)
- **PR-29 사이클**: 4 sub-agent 병렬 디스패치 모두 정상 완료. 재디스패치 0건 (CHANGELOG row 18)

### 사례 PR

- **PR-23·PR-25 (#221·#223)** orchestrator 자율 정정 — 사용자 사후 인지
- **PR-26 (#224)** 헌법 §7 통합 — 다음 사이클부터 명시 보고

---

## §9. v3.0.1 → v3.1.0 정책 진화 누적

| 룰 | v3.0.1 시점 | v3.1.0 시점 | 진화 종류 |
|----|-------------|-------------|----------|
| 운영 헌법 단일 출처 | 미존재 (9 SKILL 산재) | `agent-charter.md` 7 § (PR-26 #224) | **신설** |
| task.mjs 4단계 | start만 권고 | 4단계 의무 (헌법 §1) | **강화** |
| 머지 후 정리 4단계 | 흐려짐 | 헌법 §2 의무 + 자동 점검 | **강화** |
| KIS-first raw fetch | 권고 (사용자 메모리만) | 헌법 §3 5단계 절차 의무 + 검증 보고서 권장 | **강화** |
| CHANGELOG 슬롯 채번 | 미존재 | ADR 0050 + 헌법 §4 + HTML 주석 anchor | **신설** |
| 머지 정책 squash | 허용 | rebase 의무 (ADR 0034 정정 + 헌법 §5) | **폐기 + 신설** |
| Repository default fun | 인지 부재 | ADR 0051 + 헌법 §6 검증 절차 | **신설** |
| sub-agent fail 분기 | orchestrator 자유재량 | 헌법 §7 양자택일 + 사용자 보고 의무 | **신설** |
| GitHub Actions 일상 검증 | `.github/workflows/ci.yml` 일상 검증 | `release.yml` 단독 (release tag 트리거만) | **폐기 + 신설** (ADR 0049) |
| 단일 worktree | ADR 0033 단일 worktree (옛) | 다중 허용 + 정리 의무 (PR-0 #180 정정) | **갱신** |
| architect-as-orchestrator | 1인 2역 | 3단 hierarchy (오케스트라 → architect → 실무 sub-agents) (PR-0 #180 §2-3 정정) | **갱신** |
| Phase A/B/C/D 트랙 표현 | T1~T17 옛 트랙 ID | T-W1·T-W2·T-S1·T-MS1·T-IM1·T-CA1·T-ONBOARD 신규 ID | **갱신** (PR-1b #182) |
| `/api/watchlist` (단수) | 옛 endpoint (ADR 0040) | `/api/watchlists` (복수 RESTful) | **갱신** (PR-8 #197) |
| `MAGICJAR_AI_OPTION_ENABLED` | 옛 변수명 | `MAGICJAR_AI_ENABLED` 통일 | **갱신** (PR-2 #183) |
| 옛 트랙 ID grep | T1~T17 잔재 | grep 8회 검증 — 실질 0건 | **정리 완료** (PR-4 #187) |

**총 13건의 정책 진화** (신설 6 + 강화 3 + 폐기+신설 2 + 갱신 2). 모두 architect 단독 PR + ADR 발행 + 헌법 § 통합 절차로 baseline.

---

## §10. 다음 Wave 정책 후보 (W2 진입 시 추가 검토 대상)

W2(80지표·57패턴·DSL·Strategy Builder) 진입 시 추가 검토할 정책 후보. 본 § 자체는 v3.1.0 시점 미발행 — `_workspace/backlog.md` 의제 흡수.

| 후보 | 사유 | 진입 시점 |
|------|------|----------|
| **§ DSL safety 룰북** (실행 sandbox·timeout·CPU budget) | ADR 0047 정합 — DSL 발행 시 spec/plan 의무 | T-DSL (W2) |
| **§ Indicator Source plug-in 통과 의무** | ADR 0021 정합 — 새 IndicatorSource 추가 시 의무 절차 | T-IND (W2) |
| **§ KisRestPort 분리** | backlog §2 — 인터페이스 책임 분리 | T-O·T-RECON (W3) |
| **§ STOMP relay broker 도입** | backlog §8 — post-MVP | post-MVP |
| **§ AI 영역 침범 ADR** | backlog §10 — 장기. PR-26 헌법 통합으로 사실상 흡수 | 사용자 명시 트리거 시 |
| **§ frontend 모바일 반응형** | backlog §9 | post-W3 |
| **§ Vault·Secret Manager** | sub-agent 메모리 `project_kis_key_management.md` — 본격 운영 진입 시 재논의 | 실서비스 진입 |
| **§ Multi-tenant 인증** | master §9 — 1인 사용자 단계 끝나면 | 실서비스 진입 |
| **§ Observability** (Prometheus·Grafana) | master §9 | post-MVP |
| **§ TimescaleDB** | master §9 — 일봉 ≥1억 행 도달 시 | post-MVP |

---

## §11. 결론 — "MD 룰 정책의 변화로 더 나아지고 있다"

본 v3.1.0 사이클 (2026-05-06 → 2026-05-08, 56 PR + 4 ADR) 결과로 **사용자 명시 비판이 0회 신규 발생** (PR-21 KIS-first 비판 1회는 PR-21 사이클 내 정정 — 같은 사이클에서 폐기). 핵심:

- **헌법 단일 출처**: `agent-charter.md` 7 § — 갱신 1회 → 전체 sub-agent 정합
- **task.mjs 4단계 의무화**: 누적 task 0건 (PR-14 후 28건 → PR-26 후 0건)
- **CHANGELOG 슬롯 + HTML 주석 anchor**: 병렬 sub-agent 충돌 0%
- **KIS-first raw fetch**: 자체 추론 0건 — fitness gap 91.7% 해소 (PR-21)
- **rebase 의무**: author 보존, squash 폐기
- **Repository default fun 함정**: ADR 0051 신규 + 검증 절차 — 재발 0건

**다음 Wave**: W2 진입 시 §10 후보 정책 검토 + W2 트랙별 spec/plan 발행 시 헌법 §3·§4 사전 검증 + DSL safety 룰북 신설 우선.

→ `06-history-traceability.md` §6 정합 — v3.0.1 → v3.1.0 누적 결과 표.
