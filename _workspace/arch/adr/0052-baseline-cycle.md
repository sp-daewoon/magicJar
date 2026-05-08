# ADR 0052 — Baseline Documentation Cycle

> **Status**: Accepted (2026-05-08)
> **Deciders**: 사용자(최고 관리자), orchestrator
> **Related**: ADR 0050 (CHANGELOG 슬롯 채번룰), ADR 0031 (release/v* immutable), ADR 0035 (모델 차등), 헌법 §2 (머지 후 정리), §6 (KIS-first raw 출처)

## Context

v3.1.0 W1 마감 시점에 처음으로 종합 기능 정의서(`_workspace/baseline/v3.1.0/`) 7 파일 풀세트를 발행. 4 sub-agent 병렬 디스패치 + Mermaid 시각화 + HTML 변환까지 사이클이 약 30분 분량 8 PR을 거치며 자리 잡음. 매 release 시점(W2·W3·W4 마감) 또는 사용자 트리거 시 동일 절차를 정형화 운용 필요.

### v3.1.0 사이클 실측 결과

- 폴더 신설 + slot 발행: 1 PR (orchestrator)
- 4 sub-agent 병렬 작성: 4 PR (architect·backend-core·market-data·frontend)
- 정리: 1 PR (4 task archive)
- HTML 변환 + mermaid syntax fix: 2 PR
- 총 8 PR, ~3000 라인, Mermaid 21건, raw 인용 47+건

### 직면한 함정

1. mermaid 10.6.1에서 dotted edge label·sequence message 따옴표·participant alias 괄호 등 syntax 엄격성 — 1차 sub-agent 작성본 다수 fail
2. `task.mjs` 동일 분 호출 시 ID 충돌 (slug 분리 의무)
3. sub-agent가 `task.mjs done` 누락 → archive `--force` 우회

## Decision

magicJar baseline 사이클을 다음 3 자산 + 1 슬래시 명령으로 정형화한다:

### 1. Skill — `magicjar-baseline`

`.claude/skills/magicjar-baseline/SKILL.md`. 7단계 절차, 분배 매트릭스, dispatch 프롬프트 의무, 함정 4건 명시.

### 2. Slash command — `/baseline`

`.claude/commands/baseline.md`. 4 sub-command:
- `init <version>` — 새 사이클 시작
- `update <version> <영역>` — 부분 갱신
- `render <version>` — mermaid fix + html 재생성
- `diff <v1> <v2>` — 두 버전 비교

### 3. 헬퍼 스크립트 4종 — `scripts/baseline-*.mjs`

- `baseline-init.mjs` — 폴더 + README skeleton + CHANGELOG 4 anchor + 5 row prepend
- `baseline-to-html.mjs` — md → html (marked + mermaid CDN)
- `fix-mermaid-syntax.mjs` — diagram type별 분기 변환 (4 룰)
- `baseline-diff.mjs` — 두 버전 매트릭스 + git diff stat

### 4. 산출물 7 파일 풀세트 (LOCK)

| 파일 | 작성자 | 핵심 |
|------|--------|------|
| README.md | architect | 인덱스 + 한 장 요약 |
| 01-architecture.md | architect | Mermaid 4 모듈 |
| 02-data-flow.md | backend-core | Kafka·Redis 매트릭스 |
| 03-use-sequences.md | market-data | KIS-first raw 출처 |
| 04-feature-spec.md | backend-core | 트랙별 표준 9 항목 |
| 05-ui-inventory.md | frontend | 화면·메뉴·버튼 |
| 06-history-traceability.md | architect | PR·ADR 시계열 |
| 07-policy-evolution.md | architect | 헌법·정책 진화 |

추가: `html/` 폴더에 8 HTML (브라우저 직접 열람).

### 5. 분배 LOCK (add-add 충돌 0%)

각 sub-agent가 본인 단독 파일만 add. README는 architect 단독 책임 (다른 sub-agent 변경 0). CHANGELOG는 ADR 0050 슬롯 anchor 분리.

### 6. 이전 baseline 폴더 immutable (ADR 0031 정합)

- v3.1.0 baseline → 이후 변경 금지
- v3.2.0 진입 시 신규 폴더 생성, v3.1.0과 비교는 `/baseline diff` 사용

## Consequences

### 긍정

1. **재진입 비용 ~0** — 사용자가 `/baseline init v3.X.0` 한 줄로 사이클 진입
2. **4 sub-agent 병렬** — 직렬 대비 시간 1/3
3. **CHANGELOG 슬롯 LOCK** → 머지 충돌 0%
4. **HTML 자동 변환** → GitHub 외부 환경에서도 시각화 (devlog HTML 정책 정합)
5. **mermaid syntax fix 스크립트** → 향후 sub-agent 작성본 자동 정정

### 부정·트레이드오프

1. **분배 LOCK 변경 비용** — 영역이 sub-agent 매핑과 어긋나면 ADR 갱신 필요 (예: 신규 sub-agent 추가 시)
2. **mermaid CDN 의존** — 오프라인 환경에서는 렌더링 X (대안: `mermaid-cli`로 SVG 사전 export)
3. **HTML 단일 파일 inline embed** → 큰 baseline에서 파일 사이즈 증가 (현 시점 ~40KB 평균)

### 이행 룰

1. v3.1.0 사이클 실측 분량(~3000 라인 / 21 Mermaid / 8 PR)이 표준 베이스라인. 향후 변동 시 본 ADR 갱신.
2. baseline 작성 분량이 sub-agent 단일 영역 한계 초과 시 → 영역 추가 분할 또는 architect ADR 후속 발행.
3. mermaid syntax 룰 보강은 `scripts/fix-mermaid-syntax.mjs` 단일 파일에 누적.

## Alternatives Considered

### A. 단일 docs 파일 (master-architecture에 §추가)

**기각**: master 문서가 비대해짐. baseline은 immutable snapshot 성격이라 별도 폴더가 SoT 정합성 우월.

### B. architect 단독 디스패치 (4 sub-agent 대신)

**기각**: opus 단일 작성 시 영역별 raw inspection 깊이 부족 (frontend·KIS 통합·domain 모두 동시 정통 어려움). 4 sub-agent가 각자 본인 영역 1차 source — 3배 정확도.

### C. spec/plan PR 사전 발행 (architect 1차 + sub-agent 2차)

**기각**: 사이클 R1+R2 직렬 → 시간 2배. baseline은 회고 docs 성격이라 spec/plan 무용 (메모리 "spec/plan 발행 범위 — 신규 트랙·새 모듈·다중 ADR일 때만").

### D. PNG/SVG export (mermaid-cli 빌드 시)

**기각 (현 시점)**: HTML 클라이언트 사이드 렌더링이 의존성 0 + 빌드 시간 0. 향후 오프라인 요구 발생 시 옵션 추가 검토.

## References

- 사이클 1차 발행: PR #235·#236·#237·#238·#239·#240·#241·#242 (2026-05-08)
- 산출물: `_workspace/baseline/v3.1.0/` 7 md + `html/` 8 html
- skill: `.claude/skills/magicjar-baseline/SKILL.md`
- slash: `.claude/commands/baseline.md`
- 스크립트: `scripts/baseline-{init,to-html,diff}.mjs` + `scripts/fix-mermaid-syntax.mjs`
