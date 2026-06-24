# spec — 발굴 추천 후보 → 전략 universe 담기 (Discovery → Strategy Universe)

- **날짜**: 2026-06-24
- **작성**: architect
- **권위 ADR**: [ADR 0134](../arch/adr/0134-discovery-to-strategy-universe.md)
- **롤백 앵커**: v3.14.0
- **범위**: owner=USER 전략 신설 흐름 · frontend 운반 배선 + 기존 backend 재사용(신규 backend 0)
- **자본 경로**: 중위험 (universe 결정 — 단 저장 시 사용자 명시 확인 액션, 자동 진입·주문 0)

---

## 0. 한 줄 요약

발굴 결과에서 **다중선택한 N종을 빌더 draft `universeCodes`로 운반** → 빌더 저장모달에 **프리필** → 사용자 확인·저장(`POST /api/strategies`, owner=USER). **신규 backend 엔드포인트 0** — 유일한 단절(발굴 다중선택 → 빌더 다종목 운반) 한 곳만 잇는다.

---

## 1. 문제 (P0 갭)

`docs/operations/guides/2026-06-24-strategy102-recommendation-recreate.html` 실증: 발굴 추천 후보 → 전략 universe codes로 담는 경로가 UI·백엔드 모두 없음. 기존 3 갈래(watchlist polling / 페이퍼 / 빌더 1종)는 모두 universe codes 다건 운반 불가. autopilot worker(ADR 0131)는 owner=USER 전략을 재현 못 함(격리).

## 2. 핵심 진단 (자원 audit)

main 기준 다종목 universe 저장 인프라는 거의 완비 — 빈 슬롯은 **발굴 다중선택 → 빌더 draft 다종목 운반** 한 곳뿐.

| 자원 | 위치 | 현황 |
|------|------|------|
| 발굴 다중선택 상태 `selectedCodes` | `DaytradeDiscoverPage.tsx` | 이미 존재(ADR 0122) — 재사용 |
| 다중선택 UI `selectionMode`/`onToggleSelect` | `DaytradeMatchTable.tsx` | 이미 존재 — 재사용 |
| draft 운반 `StrategyResearchBuilderDraft` | `builderDraft.ts` | **`code` 단일만** ← 빈 슬롯 |
| 저장모달 `initialCodes: string[]` + 다건 UI | `StrategySaveModal.tsx` | 이미 완비 — 재사용 |
| 저장 API `codes: List<String>` + 존재 lookup | `StrategiesController.create` | 이미 완비 — 신규 0 |

## 3. 설계 결정 (ADR 0134 §2)

- **옵션 A 채택**: 발굴 다중선택 → "빌더로 N종 보내기" → draft `universeCodes` → 빌더 저장모달 프리필.
- draft에 **`universeCodes?: string[]` add-only**(optional). 미존재 시 기존 `[code]` fallback(zero-regression).
- `code`(평가 기준 단일)는 유지 — 백테스트 단일 기준 종목 거동 byte-동일. `code = universeCodes[0]` 미러.
- owner=USER 한정. autopilot/SYSTEM 영향 0. 저장 확인 단계 보존(자동 저장·주문 0).

## 4. 라운드별 구현 plan

### R1 — frontend: draft 다종목 운반 토대

**책임**: frontend
**산출물**:
- `frontend/src/lib/strategyResearch/builderDraft.ts`
  - `StrategyResearchBuilderDraft`에 `universeCodes?: string[]` 추가(optional, add-only).
  - `buildDaytradePresetDraft(candidate, code, interval)` 시그니처를 `buildDaytradePresetDraft(candidate, codes: string[], interval)`로 확장: 첫 종목 = 평가 기준 `code`, 전체(dedup) = `universeCodes`. 빈 배열 또는 첫 종목 없으면 기존 reason 가드 유지.
  - `consumeStrategyResearchDraft` 검증: `universeCodes`는 optional이므로 필수 검증 미추가(기존 검증 byte-동일).
  - 기존 단일 호출부(`DaytradeDiscoverPage` 현행 `topCode` 호출)는 `[topCode]`로 정합 — 거동 동일.
- `frontend/src/pages/Strategy/StrategyBuilderPage.tsx`
  - draft 소비부(`consumeStrategyResearchDraft` 후): `draft.universeCodes`가 있으면 저장모달 `initialCodes={draft.universeCodes}` 전달, 없으면 기존 `[draft.code]`/`loadedStrategy?.codes` fallback.
  - `evalForm.code`는 `draft.code`(=universeCodes[0]) 유지 — 평가 단일 기준 종목.
  - 리서치 draft 메타 패널에 "universe N종 프리필됨"(N = universeCodes.length) 노출.

**테스트**: R1 0건 OK(개발룰 4) — 단, `builderDraft.test.ts`에 다종목 시그니처 회귀 케이스는 R3와 함께(또는 R1 동반 가능). 기존 단일 호출 회귀 깨짐 0 확인 의무.

**라운드 경계 LOCK**: backend 변경 0. DSL 파서·평가 로직 무변경.

### R2 — frontend: 발굴 화면 "빌더로 N종 보내기" 액션

**책임**: frontend (R1 머지 후)
**산출물**:
- `frontend/src/pages/Strategy/DaytradeDiscoverPage.tsx`
  - 기존 다중선택 툴바(페이퍼 "모의로 굴려보기" 옆)에 "**빌더로 N종 보내기**" 버튼 추가.
  - 클릭 핸들러: `selectedArr`(선택 codes, dedup) → `buildDaytradePresetDraft(candidate, selectedArr, interval)` → `storeStrategyResearchDraft` → `navigate(/strategy/builder?researchDraftId=...)`.
  - **선택 0개 fallback**: 발굴 1위 1종(`[matches[0].instrumentCode]`)로 호출(기존 "빌더로 보내기" 거동 보존).
  - 버튼 라벨에 선택 수 노출(`빌더로 N종 보내기`). 선택 0이면 "빌더로 보내기(1위)" 등 명확 표기.
  - 자동 진입·자동 주문 0(ADR 0122 §1-2): 버튼은 빌더 프리필 navigate일 뿐.
- (선택, §6-3 post-MVP 후보를 R2에 흡수 검토) 저장모달 codes 다건 붙여넣기 — 본 R2 범위 외(별도 결정 가능). 1차 R2는 "N종 보내기"에 집중.

**테스트**: R2 0건 OK. R3 일괄.

### R3 — qa: E2E + 데이터 흐름 시나리오 일괄 + Visual 회귀

**책임**: qa (R1·R2 머지 후)
**산출물**:
- E2E 시나리오 (Playwright):
  1. 발굴 실행 → TOP N 결과
  2. 다중선택 N종(예: 3종) → "빌더로 N종 보내기" 클릭
  3. 빌더 페이지 저장모달에 N종 프리필 확인(자동매매 대상 종목 N개 표시)
  4. 저장 → `POST /api/strategies` codes 다건 영속 → `GET /api/strategies/{id}` codes = N종 확인
  5. owner=USER + 자동매매 OFF default 확인
  6. **선택 0개 fallback**: 발굴 1위 1종만 보내짐(기존 거동) 회귀 확인
  7. owner=AUTOPILOT 전략 codes 무변경 확인(격리)
- `builderDraft.test.ts` 다종목 시그니처 유닛(dedup·평가기준=첫종목·빈배열 가드·단일 fallback).
- Visual 회귀: 발굴 툴바 "N종 보내기" 버튼 + 저장모달 universe 프리필 스크린샷.

## 5. owner 격리 & 자본 안전 (ADR 0134 §2-5·§2-6)

- owner=USER 전략 신설 흐름. AUTOPILOT/SYSTEM codes byte-동일.
- 저장 확인 단계 보존: draft 프리필 ≠ 자동 저장. 모든 저장 = 사용자 명시 클릭.
- validation: 발굴 code는 instrument master 매핑값 + backend `instrumentJpaRepository` 존재 lookup 2중. universeCodes dedup. 신규 전략 비활성(자동매매 OFF) default.

## 6. 라운드 의존 그래프

```
R1 (frontend: draft 토대) ──→ R2 (frontend: 발굴 액션) ──→ R3 (qa: E2E·Visual)
                            (R1·R2 순차 — 같은 frontend 영역, draft 토대 선행)
```

- R1·R2는 같은 frontend 영역 순차(draft 토대 → 발굴 액션). 병렬 불가(R2가 R1 시그니처 의존).
- R3는 R1·R2 머지 후.
- **backend-core dispatch 0** (신규 backend 0).

## 7. CHANGELOG 슬롯

본 spec PR diff에 R1·R2(frontend) + R3(qa) 슬롯 row + anchor 사전 발행(헌법 §4). sub-agent는 본인 row만 in-place edit.

## 8. 검증 의무

ADR 0134 §8 정합. 단위 테스트로 종결 금지 — 화면·DB·API 실증(feedback_verification_strict). 라이브 발굴 N종 → 빌더 프리필 → 저장 → codes 영속 화면·API evidence 인용.
