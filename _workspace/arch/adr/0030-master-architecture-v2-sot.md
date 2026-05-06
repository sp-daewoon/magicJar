# ADR 0030: 새 v2 마스터 = 단일 진실원 (Source of Truth)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §1·§13
- 트랙: 메타 운영
- 선행: ADR 0036 (orchestrator-meta — architect-as-orchestrator 흡수)

---

## 1. 맥락

magicJar는 다수의 spec/plan/ADR/회고/dogfooding 문서가 누적됐다. 1차안 v1 master(폐기) + 트랙별 spec/plan + 30개 기존 ADR + 1차안 인라인 ADR 14건 등 — 어느 문서가 "현재 진실"인지 모호한 시점이 발생했다.

v2 베이스라인 진입 시점에 **단일 진실원 메타 규약**을 박는다.

## 2. 결정

### 2-1. 단일 진실원 (SoT)

**`docs/architecture/master-architecture.md` v2** = magicJar 시스템 비전·컴포넌트·데이터 플로우·외부 연동·도메인 경계·모듈 매트릭스·Profile 매트릭스·트랙 로드맵 요약·핵심 ADR 세트의 단일 진실원.

본 master와 충돌하는 어떤 문서도 무효 — master 개정이 선행돼야 함.

### 2-2. 문서 계층

```
docs/architecture/master-architecture.md (v2)        ← SoT (메타)
   ↓ 인용
docs/architecture/tracks-roadmap.md (v2)             ← 트랙 14개 outline
   ↓ 인용
docs/architecture/development-rules.md               ← 개발 룰북 (코드 진행 정책)
docs/architecture/cleanup-plan.md                    ← legacy 청소 계획
docs/architecture/code-convention.md                 ← 코드 컨벤션
   ↓
_workspace/arch/adr/00XX-*.md                        ← ADR (0001~0040, 파일명 = 본문 헤더 번호)
   ↓
_workspace/tasks/T-YYYYMMDD-HHMM-<slug>.md           ← agent/* 작업 단위 task 파일 (ADR 0036)
```

### 2-3. 운영 규약

| 규약 | 내용 |
|---|---|
| **트랙 진입 시** | spec/plan에 master §X 위치 명시 의무. master에 없는 컴포넌트면 master 선 개정. |
| **ADR 발행 시** | master §10에 한 줄 추가 + tracks-roadmap §3에 트랙 매핑. |
| **충돌 시** | master 개정이 선행. spec/plan/ADR이 master 개정 전에 머지되지 않음. |
| **legacy 청소** | master §9 (비범위·후속) + cleanup-plan에 명시. release/v0 외 git history 정리 시점은 사용자 결정. |
| **변경 이력** | master §13 + 각 ADR §변경 이력 + CLAUDE.md 변경 이력 모두 갱신 의무. |

### 2-4. 기존 ADR과의 정합

- ADR 0001~0040 → master §X 위치에 인용 의무
- Superseded/Deprecated 항목은 헤더에 명시 표시 (예: "선행 폐기: ...")

### 2-5. release branch와의 관계

- `release/v0`·`release/v1`·`release/v1.1.0`·`release/v1.2.0_notwork` 4개는 immutable 보존 (사용자 결정 default 12 §3)
- v2 베이스라인 컷 시점에 새 release branch 결정 (별도 ADR 발행)
- main은 v2 진행, release/* 는 손대지 않음

### 2-6. 사용자 메모리와의 정합

`~/.claude/projects/.../memory/` 항목과 master는 **동시 갱신** 의무:
- 사용자 비전 정정 → master §1 + ADR 0001 + 사용자 메모리 일관성
- AI 옵션 정책 → master §3-3 + ADR 0026 + 사용자 메모리 정합

## 3. 결과·영향

- 새 트랙 진입 시점부터 master §X 위치 명시 의무 — 누락 시 spec/plan review reject.
- 충돌 발견 시 architect가 master 개정 PR 1건 발행 후 트랙 진행.
- legacy 청소·release 컷·사용자 메모리 갱신 모두 master 정합 검증.

## 4. 검토 대안

- **(a) master 없이 ADR + spec 합산** (1차안) — 비전·플로우·매트릭스가 분산. 진실원 모호.
- **(b) master 없이 README** — 깊이 부족.
- **(c) master = SoT + ADR/spec/plan 서브 (채택)** — 단일 진실원 + 트랙별 상세 정합.

## 5. References

- master-architecture.md 전체
- tracks-roadmap.md
- development-rules.md
- cleanup-plan.md
- ADR 0036 (orchestrator-meta — architect-as-orchestrator 흡수)
