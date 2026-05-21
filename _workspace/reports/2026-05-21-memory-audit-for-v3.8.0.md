# 메모리 audit 보고서 (사용자 직접 amend 결재 의제) — v3.8.0 cut 전 LOCK

> **위치**: `_workspace/reports/2026-05-21-memory-audit-for-v3.8.0.md`
> **발행자**: architect (Stage 2 종합 정리 사이클 — 2026-05-21)
> **권위**: 본 보고서는 architect가 사용자 메모리 (`/Users/ak-song/.claude/projects/-Users-ak-song-magic-ch1/memory/`) **read만** 수행한 결과 보고서다. **memory write는 사용자 직접 영역**이므로, architect는 (1) 그대로 OK / (2) amend 권장 (본문 제안) / (3) archive 의제 3 분류만 제공한다.
> **대상**: 38 memory files (`feedback_*.md`·`project_*.md`·`user_*.md` + `MEMORY.md` index)
> **트리거**: 사용자 명시 "v3.8.0 cut 전에 Phase C + qa 셋업 완료 + ★★★ ADR·헌법 정리를 한번 했으면 좋겠어. 지금 인덱스, 헌법 adr 많이 변화가 있었을 수도 있고 코드에만 있고 정리안된 것들을 찾아서 싹 정리해서 다시 토큰, 메모리 정제한다음 다시 깨끗한 마음으로 3.8.0 릴리즈부터는 토큰도 적절하게 읽어드릴 수 있을 수 있잖아? 그렇지?"

---

## 0. 요약 (사용자 결재 의제 매트릭스)

| 분류 | 메모리 수 | 결재 의무 |
|------|----------|----------|
| **그대로 OK** (변경 0) | 19 | - |
| **amend 권장** (본 보고서 제안 본문 인용) | 6 | 사용자 직접 amend 결재 의무 |
| **archive 의제** (오래된·중복·v3.0.1 시점 stale) | 2 | 사용자 결재 의제 |
| 신규 추가 권장 (Stage 1 + Stage 2 evidence baseline) | 3 | 사용자 결재 의제 |

합계: 38 - 2 archive = 36 활성 메모리 (사용자 결재 후).

---

## 1. amend 권장 6건 (★ 사용자 직접 amend 의무)

### 1-1. `feedback_3tier_hierarchy.md` — 7 에이전트 amend 의무

**현행 본문 evidence** (line 22):
```
[backend-core · market-data · strategy-engine · ai-engineer · frontend · qa]
        (실무 병렬 실행)
```

**문제**: 6 에이전트만 명시 (chart-curator·qa 정합 부재). ADR 0085 chart-curator 영입(2026-05-19) → ADR 0094 chart-curator 해고 + qa Visual 격상(2026-05-21) 양쪽 evidence 반영 0.

**amend 제안 본문** (사용자가 직접 수정):
```
[architect](수석 설계자 — 설계·구상·코드 방향)
        │
        ▼
[backend-core · market-data · strategy-engine · ai-engineer · frontend · qa(Visual "품질 헌터")]
        (실무 병렬 실행 · 7 actor 매트릭스 — ADR 0094 amend 2026-05-21)
```

추가 amend 사유 row:
```
## v3.8.0 baseline amend (2026-05-21)

- chart-curator 영입(ADR 0085 · 2026-05-19) → 해고(ADR 0094 · 2026-05-21) — 캔들 SLA → market-data + LWC → frontend 책임 이관
- qa: 단순 게이트 → **Visual "품질 헌터" 격상** (Playwright + DR drill + 사용자 dogfooding 변환)
- 7 actor 최종 매트릭스: architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa
- sre 영입 거절 (P0 OPS 6건은 backend-core 인수)
```

### 1-2. `feedback_orchestrator_active_parallel.md` — qa 추가 dispatch 의무 추가

**현행 본문** — sub-agent 디스패치 룰 명시되어 있으나 qa 의무 dispatch 룰(헌법 §9-2 — frontend 핵심 화면 변경 PR Visual 자동 dispatch) 부재.

**amend 제안 본문** (말미에 추가):
```
## v3.8.0 amend (운영 단계 진입 — 2026-05-21)

- **qa Visual 의무 dispatch** (헌법 §9-2 정합): frontend 핵심 화면 (Dashboard·Orders·Positions·Settings·Watchlist) 변경 PR 발견 시 orchestrator는 머지 직전 qa Visual dispatch 의무 trigger.
- **chart-curator dispatch 0 LOCK** (ADR 0094 해고): chart-curator는 dispatch 매트릭스에서 영구 제외. 본 메모리 매트릭스에서도 chart-curator 신규 row 추가 0.
- **DR drill 분기 dispatch** (ADR 0096 §6-2): 분기 1회 qa Visual 단독 dispatch + 사용자 결재 의무.
```

### 1-3. `feedback_qa_on_demand.md` — qa Visual 격상 amend 의무

**현행 본문** (line 6~7): "QA 게이트(스크린샷 + APPROVED 보고서)는 **모든 PR에 강제하지 않는다.** 사용자 또는 Claude가 필요하다 판단할 때만 호출한다."

**문제**: ADR 0094 (2026-05-21) qa Visual 격상으로 본 메모리와 부분 모순. 본질 룰(QA on-demand)은 유지하되, qa Visual 격상 사실 + 의무 dispatch 조건 추가 의무.

**amend 제안 본문** (말미에 추가):
```
## v3.8.0 amend (운영 단계 진입 — 2026-05-21)

- 본 "QA on-demand" 룰은 **보존**한다 (사용자 메모리 LOCK).
- 추가로 qa는 **Visual "품질 헌터" 격상**(ADR 0094 amend) — 도구 매트릭스: Playwright + 스크린샷 + 시각 회귀 + E2E + DR drill 자동 재현.
- **on-demand 트리거 4건 (v3.8.0 baseline)**:
  1. 사용자 명시 dispatch ("qa, X 화면 Visual 회귀 검사해줘")
  2. **frontend 핵심 화면 변경 PR (헌법 §9-2 의무 dispatch)** — Dashboard·Orders·Positions·Settings·Watchlist
  3. 분기 DR drill (ADR 0096 §6-2 의무)
  4. Incident response (R-1 runbook 검증 단계)
- Sunset 조건은 **보존** — 실서비스 진입 시(MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true) 본 메모리는 sunset + 전 PR qa Visual 강제 모드로 회귀.
```

### 1-4. `project_v3_baseline.md` — v3.8.0 진입 amend

**현행 본문** — v3.0.1 시점 (2026-05-07) 47 ADR + W1 부트스트랩 시점 baseline. 현 시점 v3.7.0 마감 + v3.8.0 cut 직전 + 93+ ADR + 운영 단계 진입 → drift.

**amend 제안 본문** (말미에 v3.8.0 row 추가):
```
## v3.8.0 baseline (2026-05-21 — 운영 단계 진입)

- main HEAD `b1aab9e9` + v3.7.0 마감 + v3.8.0 cut 직전
- 총 93+ ADR (Accepted ~70 + Proposed ~15 + Superseded 3) — v3.0.1 시점 47 대비 47건 추가
- JVM 7 모듈 (domain · api · batch · consumer · persistence-shared · indicator-impl · strategy-engine)
- 7 actor (architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa) — ADR 0094 amend
- 운영 헌법 §1~§10 (운영 단계 §9 신설 — SLO/SLA + on-call + DR/backup + ADR 양식 + 7 hierarchy LOCK)
- P0 OPS 6건 완료 (logback JSON + Actuator/Prometheus + Grafana SLO dashboard + SLO/SLA + DR/backup + runbook 5 시나리오)
- qa Visual 첫 셋업 (Playwright + 5 시나리오 baseline)
- KillSwitch race-safe (A+B+C 3중 게이트 — PR #647·#648)

## 다음 사이클 (v3.8.x patch baseline)

- P0 OPS 후속 wiring (Micrometer counter + alert rule yml + Alertmanager + backup Slack alert)
- W4 진입 trigger (사용자 명시 시점 — ai-engineer dispatch active)
- DR drill 첫 분기 (사용자 결재 + qa Visual 자동 재현)
```

### 1-5. `MEMORY.md` index — Stage 2 후속 갱신 보강

**현행 본문**: 38 메모리 file index. 마지막 row는 `project_next_session_dispatch_matrix.md` (2026-05-18).

**amend 제안 본문** (index 말미에 추가 row):
```
- [Stage 1 + Stage 2 통합 세션 (v3.8.0 cut 직전)](project_session_2026_05_21_stage1_stage2_full.md) — 사용자 결재 시점 발행 권장. P0 OPS 6/6 + KillSwitch race-safe + ADR 0094·0095·0096 + qa Visual 격상 + chart-curator 해고 + Stage 2 종합 정리 (ADR audit + 헌법 amend + qa.md + chart-curator archive + 메모리 audit + orphan decision audit). 다음 1순위 = v3.8.0 cut + W4 진입 결재.
- [v3.8.0 운영 단계 진입 baseline](project_v3_8_0_ops_stage_baseline.md) — 사용자 결재 시점 발행 권장. 7 actor LOCK + 운영 헌법 §9 + Prometheus/Grafana stack + SLO 5건 + RPO 24h + RTO 30분.
```

### 1-6. `feedback_release_policy_patch_first.md` — v3.8.0 minor cut 결재 amend

**현행 본문**: "v3.7=W4 마감, v3.8=W5 마감으로 minor 종료" 명시.

**문제**: v3.7는 W4가 아닌 운영 baseline + W3 보강으로 마감됨 (Stage 1 evidence). v3.8.0은 W5(LWC·LEARN) 마감이 아니라 **운영 단계 LOCK + v3.8.x patch baseline 시작** 의제로 재해석 의제.

**amend 제안 본문** (말미에 추가):
```
## v3.8.0 baseline amend (2026-05-21 — 운영 단계 진입)

- 본 룰("v3.7=W4 마감, v3.8=W5 마감 minor LOCK")은 사용자 명시 LOCK으로 v3.0.1 시점 baseline.
- **v3.7.0 마감 evidence** (2026-05-19 cut): W3 보강 + persistence-shared + indicator-impl + chart-curator 영입 마감 (W4 진입은 사용자 명시 보류 — `project_v3_baseline.md` cross-ref).
- **v3.8.0 cut 의제** (2026-05-21 직전): **운영 단계 진입** + P0 OPS 6건 + qa Visual 격상 + chart-curator 해고 + ADR 0094·0095·0096 baseline. W5(LWC·LEARN)는 v3.9.0+ 사이클 amend 의제.
- v3.8.0 이후도 patch only LOCK 유지 — 단 minor cut 시점은 (1) W4 진입 (2) W5 진입 (3) 운영 단계 amend(예: SLO 6건 → 8건 확장) 3건만 사용자 결재.
```

---

## 2. archive 의제 2건 (사용자 결재 의제)

### 2-1. `project_v2_baseline.md` — v2.0.1 시점 (2026-05-05)

**사유**: v3.0.0(2026-05-05)에서 PoC 코드 전체 정리 완료 + 현 시점 v3.7.0 마감 → v2 baseline 메모리 권위 0. v3.0.1 baseline (`project_v3_baseline.md`)이 cross-ref로 보존하므로 본 메모리 직접 read 의무 0.

**archive 옵션** (사용자 택1):
- **옵션 가** (권장): file delete — 토큰 정제 강화
- **옵션 나**: `memory/_archive/project_v2_baseline.md` 이동 (역사 보존)

### 2-2. `project_session_2026_05_17_v3_6_10.md` — 단일 사이클 부분 memory

**사유**: `project_session_2026_05_17_full.md`(3 사이클 통합)에 흡수 완료. 부분 memory와 통합 memory 동시 존재 → drift risk.

**archive 옵션** (사용자 택1):
- **옵션 가** (권장): file delete — 통합 메모리만 보존
- **옵션 나**: `memory/_archive/` 이동

---

## 3. 신규 추가 권장 3건 (Stage 1 + Stage 2 evidence baseline)

### 3-1. `project_session_2026_05_21_stage1_stage2_full.md` (★ 권장)

**사유**: Stage 1 (P0 OPS 6/6 + KillSwitch race-safe + ADR 0094·0095·0096) + Stage 2 (ADR audit + 헌법 + qa.md + chart-curator archive + 메모리 audit + orphan decision audit) 통합 세션 evidence. 다음 세션 진입 시점 즉시 baseline read 의무.

**제안 본문** (사용자 직접 write):
```
- 2026-05-21 KST 통합 세션 (v3.7.0 마감 → v3.8.0 cut 직전)
- Stage 1: P0 OPS 6/6 (PR #649·#650·#651·#652·#653·#654) + KillSwitch race-safe (PR #647·#648) + ADR 0094·0095·0096
- Stage 2: ADR audit + 헌법 §1-A·§8·§10 amend + master §13 amend + qa.md 신규 발행 + chart-curator archive + CLAUDE.md amend + 메모리 audit 보고서 + orphan decision audit 보고서
- 다음 1순위: v3.8.0 cut + W4 진입 결재 + P0 OPS 후속 wiring (Micrometer counter + alert rule yml + Alertmanager)
- main HEAD: Stage 2 cleanup PR 머지 직후 시점
```

### 3-2. `project_v3_8_0_ops_stage_baseline.md` (★ 권장)

**사유**: 운영 단계 진입 = v3.0.1 baseline 시점 이후 가장 큰 paradigm shift. project_v3_baseline.md amend로 처리 가능하지만, 독립 memory가 토큰 효율적.

**제안 본문**: 위 §1-4 amend 본문 그대로 활용 + 운영 헌법 §9 inline.

### 3-3. `feedback_chart_curator_handover.md` (선택)

**사유**: chart-curator 해고 + 책임 이관 (캔들 SLA → market-data + LWC → frontend) 룰을 sub-agent dispatch 시 lens로 활용할 수 있도록 memory 보존 검토. 권위 본문은 ADR 0094 + `_workspace/specs/2026-05-21-chart-curator-handover.md`이므로 cross-ref만 짧게.

**제안 본문**:
```
- chart-curator 해고 (ADR 0094 · 2026-05-21) + 책임 이관 매트릭스: 캔들 SLA → market-data + LWC v4 컴포넌트 → frontend + 보수 페르소나 lens → frontend·market-data 흡수 (ADR 0087 amend).
- orchestrator·architect는 chart-curator dispatch 0 LOCK. 자본 path 직결 차트·캔들 dispatch 시 market-data + frontend 합작 의무. cross-review 자본 path 직결 lens는 market-data·strategy-engine 1차 + backend-core·architect 2차.
- 권위 ADR: 0094 §1-2·§2-2-A·§9-6 / 0085 Superseded / 0087 amend / spec _workspace/specs/2026-05-21-chart-curator-handover.md.
```

---

## 4. 그대로 OK 19건 (변경 0)

다음 메모리는 현행 본문이 v3.8.0 시점에도 정합 — 변경 0:

1. `user_language.md` — 한국어 소통 (영구 LOCK)
2. `project_overview.md` — magicJar 비전 + KIS OpenAPI baseline (v3.0.1 시점 본문 그대로 유효)
3. `feedback_autonomy.md` — 자율 실행 + 3 사용자 확인 룰 (영구 LOCK)
4. `feedback_swagger_pattern.md` — 4단 어노테이션 + 한국어 (영구 LOCK)
5. `feedback_secret_handling.md` — 평문 키 처리 (영구 LOCK — KIS 키 재발급 룰)
6. `project_system_vision.md` — 룰 본체 + AI 옵션 (v3.0.1 시점 본문 그대로 유효)
7. `feedback_honorific.md` — 존댓말 의무 (영구 LOCK)
8. `feedback_devlog_html.md` — `/devlog` 7 표준 섹션 (영구 LOCK)
9. `project_kis_key_management.md` — SettingsModal + AES 암호화 (영구 LOCK)
10. `feedback_kis_key_realonly_for_data.md` — 시세·캔들 실전 키 (영구 LOCK)
11. `feedback_no_github_actions.md` — GHA 폐기 (영구 LOCK — qa.md 본문에도 cross-ref 본문화)
12. `feedback_worktree_cleanup.md` — worktree 다중 + 정리 의무 (영구 LOCK — 헌법 §2 정합)
13. `feedback_no_mock_data.md` — mock 0 (영구 LOCK — qa.md 본문에도 cross-ref 본문화)
14. `feedback_spec_plan_scope.md` — spec/plan 발행 범위 (영구 LOCK)
15. `feedback_orchestrator_nickname.md` — "오케스트라" 호칭 (영구 LOCK)
16. `feedback_v3_skepticism_no_regression.md` — v1/v2 잔재 의심 lens (영구 LOCK)
17. `feedback_kis_first_policy.md` — KIS-only (영구 LOCK — 헌법 §3 정합)
18. `feedback_conflict_resolution_autonomy.md` — git 충돌 자율 (영구 LOCK)
19. `feedback_dev_priority_qa_batch.md` — 개발 진도 우선 (영구 LOCK)
20. `feedback_pr_rebase_sync.md` — rebase 머지 sync (영구 LOCK)
21. `feedback_verification_strict.md` — 할루시네이션 금지 + evidence (영구 LOCK)
22. `feedback_docker_log_verify_before_cut.md` — cut 직전 docker logs (영구 LOCK)
23. `project_master_html.md` — `/master-index` (영구 LOCK)
24. `project_session_2026_05_17_full.md` — 통합 세션 (보존 — 역사 evidence)
25. `project_session_2026_05_16_progress.md` — ADR 0078 (보존)
26. `feedback_force_push_obligation.md` — force-push 룰 (영구 LOCK)
27. `project_session_2026_05_18_full.md` — 통합 세션 (보존)
28. `project_next_session_dispatch_matrix.md` — 다음 세션 dispatch (Stage 2 직후 amend 의제로 보내거나 archive)
29. `project_real_account_cash_3.md` — 실계좌 cash (보존 — 자본 path 직결)
30. `feedback_codex_audit_worktree.md` — 협업 lens (보존)

(중복 카운트 — `project_session_2026_05_17_v3_6_10.md`는 archive 의제이므로 본 표 미포함)

---

## 5. architect의 결재 권고

### 권고 1: ★ 즉시 amend 6건 — 사용자 직접 write 의무

- 위 §1의 6건 amend 본문은 architect가 read 결과 + ADR/헌법 정합 검증을 거친 제안.
- 사용자가 1회 read 후 수긍 시 본문 그대로 amend 또는 본인 표현으로 다듬어서 write.
- write 후 본 보고서는 archive 또는 보존 자율.

### 권고 2: archive 2건 — 사용자 결재 의제

- `project_v2_baseline.md`·`project_session_2026_05_17_v3_6_10.md` 옵션 가(delete) 또는 옵션 나(_archive 이동) 결재.
- 권장 옵션 가 — 토큰 정제 강화 사용자 의제 정합.

### 권고 3: 신규 추가 3건 — 사용자 결재 의제

- §3-1·§3-2은 강력 권장 (다음 세션 진입 토큰 효율 + baseline 본문).
- §3-3은 선택 (cross-ref만 보존하므로 ADR/spec read로 대체 가능).

### 권고 4: 본 보고서 사후 처리

- 사용자가 §1·§2·§3 결재 완료 후 본 보고서는 `_workspace/reports/_archive/2026-05-21-memory-audit-for-v3.8.0.md` 이동 또는 그대로 보존 (history evidence).

---

## 6. 검증

- architect read만 수행 — 메모리 directory에 write 0 (사용자 직접 영역).
- 본 보고서 본문은 사용자 read 후 결재 의제 매트릭스로만 활용.
- ADR/헌법/master에 본 보고서 cross-link 추가 0 (memory는 사용자 개인 영역 — SoT 권위 영역 아님).
- Stage 2 cleanup PR 머지 후 사용자 본 보고서 read + 결재 + 직접 amend 의무.

**상태**: 사용자 결재 대기 (Stage 2 cleanup PR 머지 후 1회 사용자 의무 작업).
