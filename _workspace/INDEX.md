# magicJar — 단일 진입점 인덱스

> **이 문서가 magicJar의 모든 자원을 찾는 첫 번째 위치**.
> "지금 무엇이 권위인가" → policies, "왜 그렇게 결정했나" → ADR, "지금 진행 중" → backlog, "그때는?" → baseline.

---

## 🧭 의도별 진입 경로

### "지금 X가 어떻게 동작하는가?" (현행 권위)

→ **`docs/architecture/policies/`** ([인덱스](../docs/architecture/policies/README.md))

영역별 단일 권위 문서. 본 문서가 변경 시 직접 update. ADR은 결정 이력만.

| 정책 | 위치 |
|------|------|
| KIS Credential 식별 규약 | [policies/kis-credential-identity.md](../docs/architecture/policies/kis-credential-identity.md) |
| 로그 레벨 정책 | [policies/logging-levels.md](../docs/architecture/policies/logging-levels.md) |
| Docker 배포 토폴로지 | [policies/deployment-topology.md](../docs/architecture/policies/deployment-topology.md) |
| KIS-first raw fetch | [policies/kis-first-raw-fetch.md](../docs/architecture/policies/kis-first-raw-fetch.md) |
| 자동매매 RiskPlan | [policies/autotrading-risk-plan.md](../docs/architecture/policies/autotrading-risk-plan.md) |
| Scenario Replay Sandbox | [policies/scenario-replay-sandbox.md](../docs/architecture/policies/scenario-replay-sandbox.md) |
| Task 사이클 | [policies/task-cycle.md](../docs/architecture/policies/task-cycle.md) |
| Mermaid 작성 규약 | [policies/mermaid-conventions.md](../docs/architecture/policies/mermaid-conventions.md) |
| (추가는 policies/README.md) | |

### "왜 X로 결정했나?" (결정 이력)

→ **`_workspace/arch/adr/`** ([인덱스](arch/adr/README.md))

총 건수·상태·최신 번호 범위는 [adr/README.md](arch/adr/README.md) 도입부가 단일 권위(본 문서에 수치 복제 금지 — stale drift 방지, 2026-07-03 감사 정정). ADR 파일은 append-only이며 각 결정 이유·대안·트레이드오프를 보존한다. README §0 빠른 lookup(최근 건 + 주제별 jump)이 첫 진입점.

### "지금 진행 중인 의제" (TODO)

→ **`_workspace/backlog.md`** — 진행 중 §만 보유

처리 완료 §은 `_workspace/backlog/done/v3.X.x.md`로 archive (minor release마다).

### "그때 시스템이 어떻게 생겼었나?" (시점별 snapshot)

→ **`_workspace/baseline/<version>/`** ([최신 v3.14.0](baseline/v3.14.0/README.md) — 최신 버전은 `_workspace/baseline/` 폴더 실측이 권위)

immutable. 변경 X. 새 버전 = 새 폴더. `/baseline init <version>` slash command.

### "지금 작업 중" (활성 task)

→ **`_workspace/tasks/`**

ApplicationRunner 4단계 (start·check·done·archive). 머지 후 `tasks/done/` 으로 archive.

### "이 변경이 언제 들어왔나?"

→ **`CHANGELOG.md`** (시간역순 표)

PR 머지마다 row 추가. anchor 기반 슬롯 (ADR 0050).

---

## 🗺 모듈·기능 진입 경로

### 시스템 아키텍처
- **마스터 SoT**: [docs/architecture/master-architecture.md](../docs/architecture/master-architecture.md)
- **트랙 로드맵**: [docs/architecture/tracks-roadmap.md](../docs/architecture/tracks-roadmap.md)
- **개발 룰**: [docs/architecture/development-rules.md](../docs/architecture/development-rules.md)
- **운영 헌법**: [_workspace/arch/agent-charter.md](arch/agent-charter.md)

### 외부 통합
- **KIS API 레퍼런스**: [docs/operations/kis-api-reference.md](../docs/operations/kis-api-reference.md)
- **외부 API 정책**: [docs/architecture/external-api-policy.md](../docs/architecture/external-api-policy.md)
- **캔들 데이터 정책**: [docs/architecture/candle-data-policy-v2.md](../docs/architecture/candle-data-policy-v2.md)
- **캔들/오더블럭 스크리닝 baseline**: [_workspace/baseline/v3.10.0/](baseline/v3.10.0/) — `3m`·`4h`·`12h`, 사용자 실행형 screener, 캔들 재수집 UX
- **시나리오 리플레이 sandbox baseline**: [_workspace/baseline/v3.11.0/](baseline/v3.11.0/) — 실주문 경로와 분리된 전략 QA 리플레이 진입점

### 운영

메타 룰 3종 책임 분리 (정합 단일 출처):

| 위치 | 책임 | 양식 |
|------|------|------|
| [policies/](../docs/architecture/policies/README.md) | **시스템·도메인 사실 권위** (KIS credential·로그·KIS-first·task cycle·mermaid 등) | 표준 4섹션 |
| [agent-charter.md](arch/agent-charter.md) | **에이전트 행동 강령** (task.mjs 4단계·머지 후 정리·CHANGELOG 슬롯·rebase·Repository default fun·fail 분기) | 7 § 헌법 |
| [branching-rules.md](arch/branching-rules.md) | **실전 운영 가이드** (브랜치 흐름·네이밍·모델 차등·git author 화이트리스트) | 다이어그램·표 |

기타 운영 문서:

- **초기 셋업**: [docs/operations/initial-setup.md](../docs/operations/initial-setup.md)
- **NAS Docker 배포·DB 이관**: [docs/operations/nas-deployment.md](../docs/operations/nas-deployment.md)
- **시스템 감사 보고서**: [_workspace/audit/](audit/) — 2026-07-03 전 영역 감사(P0 1·P1 12·P2 16·P3 12) + 조치 추적 [policies/audit-remediation-tracker.md](../docs/architecture/policies/audit-remediation-tracker.md)

### 카탈로그 (전략·AI)
- **전략·지표·패턴**: [_workspace/strategy/](strategy/)
- **AI 프롬프트**: [_workspace/ai/](ai/)

### 차트·추세선
- **Symbol Detail 추세선 도구 명세**: [_workspace/specs/2026-05-25-symbol-trendline-tool-spec.md](specs/2026-05-25-symbol-trendline-tool-spec.md)

---

## ⚙️ Slash Commands

| 명령 | 용도 | 자세히 |
|------|------|--------|
| `/baseline init <ver>` | 새 baseline 사이클 진입 | [.claude/commands/baseline.md](../.claude/commands/baseline.md) |
| `/baseline render <ver>` | mermaid fix + html 재생성 | 〃 |
| `/baseline update <ver> <영역>` | 부분 갱신 | 〃 |
| `/baseline diff <v1> <v2>` | 두 버전 변경 매트릭스 | 〃 |
| `/devlog` | 개발일지 HTML 작성 | [.claude/commands/devlog.md](../.claude/commands/devlog.md) |

---

## 🔖 Cross-Reference 표준 형식

모든 문서에서 다음 형식으로 인용:

- **ADR**: `ADR-0046` 또는 `[ADR-0046](path)`
- **backlog**: `backlog §23` 또는 `[backlog §23](path)`
- **PR**: `PR #246` 또는 GitHub URL
- **코드**: `consumer/.../KisApprovalAdapter.kt:124`
- **policy**: `policy: kis-credential-identity` 또는 link

각 living policy 문서 머리에 `# ref` 한 줄 — 변경 시 본문 update + ADR 후속 발행 + 본 ref 갱신.

---

## 📐 변경 시 의무 (정합성 보장)

| 변경 종류 | 갱신 대상 |
|-----------|-----------|
| 정책 변경 | (1) 해당 policy 문서 본문 update + (2) ADR 후속 발행 + (3) backlog § close (있을 시) |
| 결정 추가 | ADR 신규 발행 + adr/README.md 인덱스 갱신 + 관련 policy 문서 ref 갱신 |
| backlog 처리 완료 | `✅ 처리 완료` 마킹 + 다음 minor release 시 done/v3.X.x.md로 archive |
| 새 baseline | `/baseline init <ver>` 사용 (자동 폴더 + slot + 디스패치) |

**룰**: append만으로 끝내지 말 것. **현행 권위 위치(policy)를 갱신하지 않으면 정합 깨짐.** 본 INDEX의 ref가 정합 검증 기준.
