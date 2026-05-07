# magicJar 운영 헌법 (Agent Charter)

> **위치**: `_workspace/arch/agent-charter.md`
> **신설일**: 2026-05-08 (PR-26 — 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게")
> **권위**: 본 헌법은 9 sub-agent SKILL + orchestrator SKILL + 메타 ADR(0033·0036·0046·0049·0050)에서 산재된 운영 룰을 단일 출처로 통합한 **참조 가능한 헌법**이다.
> **의무 정독 대상**: architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa · orchestrator (8 actor 모두)
> **변경 정책**: 본 헌법은 architect 단독 PR로만 갱신. sub-agent SKILL은 본 헌법을 참조만 하고 룰 본문 중복 금지.

본 헌법은 **운영 룰의 단일 출처**다. 본문에 명시된 7개 § 룰을 위반하면 운영 부채(hotfix·재작업·사용자 비판) 누적 risk. 각 § 본문은 30~40줄 max — 헌법은 실행 매뉴얼이 아닌 참조 룰북.

---

## §1. task.mjs 4단계 의무 (ADR 0036 + PR-18·PR-22)

agent/* 브랜치 작업 시 `scripts/task.mjs` CLI 4단계 호출 의무. 누락 시 task 데이터 무결성 깨짐 → done/ 누적·체크리스트 미반영·completedAt null 같은 사용자 보고 발생.

1. **start** — 작업 시작 즉시 `node scripts/task.mjs start --agent <a> --goal "..." --deliverables "..., ..."` (deliverables는 쉼표/세미콜론/줄바꿈 분리)
2. **check** — **각 결과물 완료 시 즉시** `node scripts/task.mjs check "<항목명>"` (deliverables 1건씩, 부분 문자열 매칭). 작업 종료 직전 일괄 호출 금지.
3. **done** — PR 생성 후 `node scripts/task.mjs done --pr <N>` (status=DONE + completedAt 자동 + pr 번호 기록).
4. **archive** — orchestrator 영역. 머지 직후 `node scripts/task.mjs archive T-<id>` (status=DONE 검증 강제 — DONE 아니면 거부, `--force` 우회). sub-agent는 호출 금지.

**시간 표기**: `nowIso()`는 KST(+09:00) ISO 8601. UTC 표기 금지(PR-22 정정).

**위반 시 risk**: PR-14·PR-18에서 누적 28~30 done 파일 일괄 정정 발생. sub-agent SKILL 호출 의무 흐려짐 → 사용자 보고 ("시간도 현시간들이랑 이상하고", "정보들이 명확히 갱신안되는거 같아").

---

## §2. 머지 후 정리 4단계 (ADR 0033 §92·§112 + ADR 0036 §47·§57·§79 + PR-14)

PR 머지 직후 즉시 4단계. 누락 시 운영 부채 누적(worktree 잔재·옛 task 누적·로컬 브랜치 누적).

1. **main pull (root worktree 동기화)** — `cd <repo-root> && git pull --ff-only origin main`
2. **task 파일 archive** — `node scripts/task.mjs archive T-<id>` (§1 4단계 정합)
3. **worktree 제거** — `git worktree unlock <path> 2>/dev/null && git -C <path> restore _workspace/tasks/ 2>/dev/null && git worktree remove <path>` (force 없이 시도)
4. **agent/* 로컬 브랜치 삭제** — `git branch -d agent/<agent>/<task>` (`-d` 안전 모드 — origin 머지 인식 시 허용)

**자동 점검 (사이클 시작 시)**: `ls _workspace/tasks/T-*.md 2>/dev/null | wc -l` 누적 5건 이상이면 일괄 archive 의무. `git worktree list` main 외 잔재 검증.

**worktree-agent-* 누적 정리**: Agent 도구 isolation worktree 부산물은 누적 15+ 시 사용자 직접 승인 후 일괄 정리(backlog §4).

**위반 시 risk**: PR-14 누적 28 task 일괄 archive 발생 + 사용자 명시 비판("이 adr? 룰? 을 잊은거 같은데").

---

## §3. KIS-first raw fetch 의무 (PR-21 사용자 비판 응답 + 사용자 메모리 `feedback_kis_first_policy.md`)

KIS API·외부 데이터 규격 결정 시 자체 추론 0 정책. 위반 시 fitness gap(PR-11 instrument 14 컬럼 vs KIS 73 필드 = 91.7% 갭) 발생.

**발동 조건**: 새 KIS endpoint 도입 (REST/WS — tr_id·필드·byte offset) · 새 KIS 데이터 규격 결정 (종목 마스터·시세·체결·호가·잔고·주문) · DSL·tr_id·payload 구조 결정 · 외부 API 스키마 변경 인식 (Yahoo·DART·Naver).

**의무 절차 5단계**:

1. **raw fetch 직접 다운로드** — `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/<path>` 또는 KIS 공식 문서 URL
2. **자체 추론 0 정책** — tr_id·endpoint·필드·byte offset·DSL 자체 결정 금지. 모든 결정은 raw 출처 인용 의무
3. **검증 보고서 산출 권장** — 새 endpoint·새 데이터 규격 결정 시 `_workspace/specs/<pr>-kis-raw-verification.md` 권장 (예: PR-21 `pr-21-kis-header-raw-verification.md` baseline)
4. **sub-agent에 raw 출처 인용 의무 전파** — dispatch §에 "코드 inline 주석에 raw URL 명시" 의무. sub-agent 자체 추론 0 (architect spec 인용으로 입증)
5. **MCP 활용** — `mcp__kis-code-assistant__search_domestic_stock_api(query=...)` 결과 spec 또는 PR 본문에 인용 권장

**위반 baseline**: 사용자 강한 비판 — "kis 데이터 규격 확인 안하고 막 만들어서 꾸겨넣은거잖아" (PR-11 V4). PR-21에서 70 필드 add-only로 정정.

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
| 2026-05-08 | 본 헌법 신설 — 7 § 룰 통합 | PR-26 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게". sub-agent SKILL 산재 룰을 단일 출처로 통합 + 9 SKILL.md 슬림화 동반. (architect) |

---

## §9. 운영 메모

- **architect 책임**: 본 헌법 본문은 architect 단독 PR로만 갱신. 새 § 추가 시 ADR 발행 의무.
- **sub-agent 책임**: 본 헌법 정독 의무 + 영역별 특수 룰만 SKILL.md에 명시 (KIS-first는 architect·market-data만 관련 § 강조).
- **orchestrator 책임**: 사이클 시작 시 본 헌법 §1·§2 자동 점검 + sub-agent 디스패치 시 §4·§5 사전 검증.
- **사용자 책임**: 본 헌법 룰 위반 발견 시 architect에게 갱신 명시 (운영 룰 흐려짐 신호).
