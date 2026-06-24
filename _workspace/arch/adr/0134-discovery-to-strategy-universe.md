# ADR 0134 — 발굴 추천 후보 → 전략 universe 담기 (Discovery → Strategy Universe)

- **상태**: Accepted (오케스트라 위임 결재 2026-06-24 — P0 갭 해소)
- **날짜**: 2026-06-24
- **작성**: architect
- **관련**: ADR 0117(발굴 레이어 — DaytradeDiscoverService) · ADR 0122(발굴 MOCK/페이퍼 dispatch — 후보 다중선택 §8-1) · ADR 0131(autopilot 일일 universe 자동 재생성 — owner=AUTOPILOT 소관) · ADR 0089(전략 userId·codes 모델) · ADR 0124(KIS WS owner 우선순위 — STRATEGY) · 실증 가이드 `docs/operations/guides/2026-06-24-strategy102-recommendation-recreate.html`
- **롤백 앵커**: v3.14.0
- **범위 게이트**: owner=USER 전략 한정 · 발굴 다중선택 → 빌더 draft 프리필 · 신규 backend 엔드포인트 0
- **자본 경로**: 중위험 (universe = 어떤 종목이 매매 평가 대상인가. 단, **저장 시점에 사용자가 확인·저장하는 명시 액션**이며 자동 진입·자동 주문 0)

---

## 1. 맥락 (왜 지금)

### 1-1. P0 갭 실증 (2026-06-24 qa 가이드)

qa가 "전략102를 추천 종목으로 재현하라"는 사용자 시나리오를 실증한 결과(`docs/operations/guides/2026-06-24-strategy102-recommendation-recreate.html`), **발굴(Discovery) 추천 후보를 전략 universe(자동매매 대상 종목 codes)로 담는 사용자 흐름이 UI·백엔드 양쪽에 모두 없음**이 확정됐다.

현재 발굴 결과(`DaytradeMatchTable` TOP N)에서 후보를 받아 다음 단계로 넘기는 길은 **3 갈래뿐**이다:

| 경로 | 운반 대상 | universe 영향 |
|------|-----------|---------------|
| ① watchlist 등록 | 종목 code | polling 시세 채널만 (ADR 0124 — watchlist는 매매 universe 아님) |
| ② 페이퍼 승격 (ADR 0122) | 다중선택 codes → MOCK 페이퍼 | 페이퍼 가상 운용 (실 전략 universe 아님) |
| ③ 빌더로 보내기 | **발굴 1위 code 1개** (`matches[0].instrumentCode`) | 빌더 평가종목 1개만 (다종목 universe 운반 0) |

> **결론**: "발굴이 추천한 N 종목을 골라 전략 universe codes 로 담아 전략을 만든다"는 경로가 **없다**.

### 1-2. autopilot 자동화는 USER 수동을 대체하지 못함

ADR 0131이 owner=AUTOPILOT 전략의 universe를 매일 08:35 자동 재생성한다. 그러나:

- ADR 0131의 owner 격리(§2-5)는 **USER/SYSTEM 전략 codes 를 절대 touch 0** 으로 LOCK한다. 즉 autopilot worker는 **owner=USER 수동 전략을 재현하지 못한다**(직관·계약 유지).
- 사용자 시나리오("전략102처럼 추천 16종을 universe로 담아 내 전략을 만든다")는 **owner=USER 수동 생성** 흐름이다. autopilot 자동화의 사각지대다.

### 1-3. 기존 자원 audit — 빈 슬롯은 단 하나

main 기준 audit 결과, **다종목 universe 저장 인프라는 이미 거의 완비**되어 있고 빠진 것은 "발굴 → 빌더 draft 의 다종목 운반" 한 곳뿐이다:

| 자원 | 위치 | 현황 |
|------|------|------|
| 발굴 다중선택 상태 | `DaytradeDiscoverPage.tsx` `selectedCodes: Set<string>` | **이미 존재** (ADR 0122 페이퍼 승격용) — 재사용 가능 |
| 발굴 테이블 다중선택 UI | `DaytradeMatchTable.tsx` `selectionMode`·`onToggleSelect` | **이미 존재** — 재사용 가능 |
| 빌더 draft 운반 객체 | `builderDraft.ts` `StrategyResearchBuilderDraft` | **`code: string` 단일만** ← ★ 빈 슬롯 (`universeCodes` 없음) |
| 빌더 → 저장모달 프리필 | `StrategyBuilderPage.tsx` `initialCodes` prop | draft에 다종목 없어 미전달 |
| 저장모달 다종목 입력 | `StrategySaveModal.tsx` `initialCodes: string[]` + 검색·다건 추가 UI | **이미 완비** — 재사용 가능 |
| 저장 API codes 다건 | `StrategiesController.create` `codes: List<String>` + `instrumentJpaRepository` 존재 lookup | **이미 완비** — 신규 0 |

> **핵심 진단**: backend는 `POST /api/strategies`가 `codes: List<String>`을 이미 받고, 저장모달은 `initialCodes`로 다종목 프리필을 이미 지원한다. **유일한 단절은 발굴 화면의 다중선택을 빌더 draft 의 다종목 슬롯으로 운반하지 못하는 것**이다. 이 한 곳만 이으면 전체 흐름이 완성된다.

---

## 2. 결정 — 옵션 A: 전략102 재현 흐름 (발굴 다중선택 → 빌더 N종 프리필)

발굴 결과에서 **다중선택한 후보를 "빌더로 N종 보내기"** → `StrategyResearchBuilderDraft`에 **`universeCodes: string[]` 추가** → 빌더 페이지가 저장모달 `initialCodes`로 **프리필** → 사용자가 확인·저장 → `POST /api/strategies`(codes 다건, owner=USER).

### 2-1. draft 모델 확장 (1 필드 add-only)

`StrategyResearchBuilderDraft`에 **`universeCodes?: string[]`** 추가(optional — 기존 단일 code 흐름 byte-동일 보존).

```ts
export interface StrategyResearchBuilderDraft {
  // ... 기존 필드 유지
  code: string                 // 평가 기준 종목(미러: universeCodes[0]) — 기존 byte-동일
  universeCodes?: string[]     // ★ 신규 — 빌더 저장모달 universe 프리필용 (optional)
}
```

- `code`(평가 기준 단일 종목)는 **그대로 유지**. 빌더 평가/백테스트는 단일 기준 종목으로 수행(현행 byte-동일). `code = universeCodes[0]` 미러.
- `universeCodes`가 있으면 빌더가 저장모달 `initialCodes`로 프리필. 없으면 기존 단일 흐름(`[code]`)로 fallback — **zero-regression**.
- `buildDaytradePresetDraft(candidate, codes, interval)` 시그니처를 다종목 수용으로 확장: 첫 종목 = 평가 기준 `code`, 전체 = `universeCodes`. 기존 단일 호출부는 `[topCode]`로 호출(거동 동일).

### 2-2. 발굴 화면 — "빌더로 N종 보내기" 액션 (다중선택 재사용)

- `DaytradeDiscoverPage`의 기존 `selectedCodes: Set<string>`(ADR 0122 페이퍼 승격용)를 **빌더 도선에도 재사용**. 선택 0개면 발굴 1위 1종(기존 거동)로 fallback.
- 다중선택 툴바에 "**빌더로 N종 보내기**" 버튼 추가(페이퍼 "모의로 굴려보기" 옆). 클릭 시 `buildDaytradePresetDraft(candidate, selectedArr, interval)` → `universeCodes = selectedArr` → `storeStrategyResearchDraft` → `navigate(/strategy/builder?researchDraftId=...)`.
- **자동 진입·자동 주문 0** (ADR 0122 §1-2 정합): 선택은 빌더 프리필의 입력일 뿐, 저장·자동매매는 빌더에서 사용자가 명시 확인.

### 2-3. 빌더 페이지 — draft universeCodes 프리필

- `StrategyBuilderPage`의 draft 소비부에서 `draft.universeCodes`가 있으면 저장모달 `initialCodes={draft.universeCodes}`로 전달. 없으면 기존 `[draft.code]`/`loadedStrategy?.codes` fallback.
- 평가종목(`evalForm.code`)은 `draft.code`(= universeCodes[0]) 유지 — 백테스트는 단일 기준 종목으로 현행 동일.
- 리서치 draft 메타 패널에 "universe N종 프리필됨" 노출(가시화 — 사용자가 몇 종이 담겼는지 인지).

### 2-4. backend — 신규 엔드포인트 0 (기존 `POST /api/strategies` 재사용)

- 저장은 기존 `StrategiesController.create`(codes 다건 + `instrumentJpaRepository` 존재 lookup) 그대로. **신규 backend 엔드포인트·서비스·Flyway 0**.
- validation(존재 종목·중복·상한)은 §2-6 참조. 저장모달은 이미 6자리 코드/검색 선택 가드를 보유하므로 frontend 1차 가드 + backend 기존 lookup 2중.

### 2-5. owner 격리 (zero-regression LOCK)

- 본 흐름으로 만들어지는 전략은 **owner=USER**(origin=USER_DSL — `StrategiesController` default). owner=AUTOPILOT 전략에는 **영향 0**(ADR 0131 worker 소관 그대로).
- 발굴 다중선택 → 빌더 → 저장은 **사용자 명시 액션**이므로 autopilot 자동 재생성과 경로가 분리된다. 둘은 충돌하지 않는다(USER 전략은 매일 자동 덮어쓰기 대상 아님 — ADR 0131 §2-5).
- **owner=AUTOPILOT 전략 UI 수정 가드(가이드 P1 갭)**: autopilot 전략은 worker가 매일 codes를 덮어쓰므로 사용자가 빌더에서 수정해도 다음 08:35에 회귀한다. 본 트랙은 USER 흐름 신설에 집중하고, autopilot 전략 UI 수정 가드(저장 시 경고·읽기전용 배지)는 **post-MVP §6-1**로 분리(별도 작은 PR — 본 자본 경로와 결합하지 않음).

### 2-6. 자본 안전 — universe validation·확인 UX

universe = 어떤 종목이 매매 평가 대상인가 = 자본 경로. 단 본 흐름은 **저장 시점에 사용자가 확인·저장하는 명시 액션**이므로 자동 진입·자동 주문은 없다. 안전 가드:

1. **존재 종목 검증** — 저장모달 프리필 시 발굴 결과 code는 instrument master 매핑된 값(발굴이 이미 마스터 매핑). backend `instrumentJpaRepository` 존재 lookup 2중 가드. 미존재 code는 저장 시 거부.
2. **중복 제거** — `universeCodes`는 dedup(같은 종목 중복 방지 — `Array.from(Set)`).
3. **상한** — universe 상한 = WS slot 예산 정합(ADR 0124 — 40 slot 매매 전용). 발굴 TOP N(default 16)은 상한 내. 저장모달에 상한 경고(post-MVP 보강 가능, 1차는 N≤발굴 TOP N으로 자연 상한).
4. **확인 UX** — 저장모달이 "자동매매 대상 종목 N개"를 명시 표시(기존 UI). 사용자가 빼거나 추가 후 저장. **draft 프리필 ≠ 자동 저장** — 저장 버튼은 항상 사용자 클릭.
5. **저장 후 전략 status** — 신규 전략은 비활성(자동매매 OFF) default(기존 거동). 사용자가 명시 ACTIVE 토글해야 매매 평가 진입.

---

## 3. 대안 비교

### 3-1. 진입 흐름

| 대안 | 내용 | 장점 | 단점 | 판정 |
|------|------|------|------|------|
| **A. 발굴 다중선택 → 빌더 N종 프리필 (채택)** | draft에 `universeCodes` 추가, 빌더 저장모달 프리필 | 기존 빌더·draft·저장모달·저장 API 전부 재사용 · 신규 backend 0 · 사용자 확인 단계 보존(자본 안전) · 전략102 재현 시나리오 1:1 | 빌더를 1회 경유(직접 전략 생성 아님) | **✓ 채택** |
| B. 기존 전략에 universe 추가/교체 | 발굴 다중선택 → "전략 선택 → universe 추가/교체" + `POST /api/strategy/{id}/universe` | 기존 전략에 빠르게 추가 | **신규 backend 엔드포인트 + owner 가드 + 자본경로 직접 변경**(저장된 전략의 매매 universe를 즉시 바꿈 — confirm 신중) · ADR 0131과 owner 경합 가능 | ✗ (post-MVP §6-2 — confirm·owner 가드 갖춘 뒤) |
| C. 발굴에서 전략 직접 생성 | 발굴 화면에서 universe 채워 바로 `POST /api/strategies` | 최단 경로 | DSL·손익·status 없이 전략 생성 → 불완전 전략 양산 · 빌더 검증 우회(자본 위험) | ✗ |

**근거**: 1차 목표는 "발굴↔전략 universe 단절 해소"이고, 빌더는 이미 DSL·손익·검증·저장모달을 갖춘 완성된 전략 생성 관문이다. 옵션 A는 **그 관문에 다종목 universe만 추가로 흘려보내면 끝**난다. 신규 backend 0, 자본 확인 단계 보존, 전략102 재현 시나리오와 1:1. 옵션 B(기존 전략 직접 변경)는 자본경로 직접 mutate라 confirm·owner 가드가 무르익은 뒤 post-MVP로 분리한다.

### 3-2. draft code vs universeCodes 관계

| 대안 | 내용 | 판정 |
|------|------|------|
| **code 유지 + universeCodes 추가 (채택)** | 평가 기준은 단일 code, universe는 별도 다종목 | **✓** — 백테스트 단일 기준 종목 거동 byte-동일 + 다종목 프리필 동시 |
| code를 string[]로 변경 | code 자체를 배열화 | ✗ — 빌더 평가/백테스트가 단일 종목 전제(파괴적 변경·회귀 위험) |

---

## 4. owner 격리 & 롤아웃 (zero-regression LOCK)

- **owner=USER 흐름 신설.** owner=AUTOPILOT/SYSTEM 전략 codes byte-동일(ADR 0131 worker 소관 불변).
- **단일 종목 흐름 byte-동일.** `universeCodes` 미존재(optional) 시 기존 `[code]` fallback — 기존 "빌더로 보내기"(1종) 거동 100% 보존.
- **저장 확인 단계 보존.** draft 프리필 ≠ 자동 저장. 모든 저장은 사용자 명시 클릭.
- **신규 전략 비활성 default.** 저장 후 자동매매 OFF, 사용자 ACTIVE 토글 필요(기존 거동).
- **롤백**: frontend revert(draft `universeCodes` 필드 + 발굴 "N종 보내기" 버튼 + 빌더 프리필 배선) → 기존 단일 흐름 복귀. backend 변경 0이라 DB·API 롤백 불요. 즉시.

---

## 5. 자원 audit (feedback_spec_audit 의무 — 5단계)

검증 시점 main 기준. **신규 backend 자원 0 — 본 결정은 frontend 운반 배선 + 기존 backend 재사용**.

### 신규 (frontend only, 최소)

| 자원 | 종류 | 비고 |
|------|------|------|
| `StrategyResearchBuilderDraft.universeCodes?` | frontend type | optional add-only. 미존재 시 기존 `[code]` fallback. |
| `buildDaytradePresetDraft` 시그니처 확장 | frontend lib | `code: string` → `codes: string[]`(첫=평가기준, 전체=universe). 기존 호출부 `[topCode]`로 정합. |
| "빌더로 N종 보내기" 버튼 | frontend `DaytradeDiscoverPage` | 다중선택 툴바에 add. `selectedCodes` 재사용. |
| 빌더 draft universeCodes 프리필 배선 | frontend `StrategyBuilderPage` | `initialCodes={draft.universeCodes}` 전달. |

### 재사용 (LOCK — 신규 생성 금지)

| 자원 | 위치 | 본 ADR 활용 |
|------|------|-------------|
| `selectedCodes: Set<string>` | `DaytradeDiscoverPage` | 다중선택 상태 — 페이퍼·빌더 양 도선 공유. |
| `selectionMode`·`onToggleSelect` | `DaytradeMatchTable` | 다중선택 UI — 재사용. |
| `StrategySaveModal` `initialCodes` + 다종목 검색추가 UI | frontend | universe 프리필·편집 — 재사용. |
| `POST /api/strategies` (`StrategiesController.create`, codes 다건) | api/controller | 저장 — **신규 0**. |
| `instrumentJpaRepository.findAllById` | api/controller | 존재 종목 lookup — 기존 가드. |
| `consumeStrategyResearchDraft`·`storeStrategyResearchDraft` | frontend `builderDraft.ts` | draft 세션 운반 — 재사용. |

### 점유 충돌 검증

| 자원 | 신규 점유 | main 충돌 | 결론 |
|------|-----------|-----------|------|
| Flyway | 0 | — | 충돌 0 (스키마 변경 0) |
| REST 엔드포인트 | 0 (기존 `/api/strategies` 재사용) | — | 충돌 0 |
| Kafka/STOMP/ENV | 0 | — | 신규 0 |
| frontend draft type | `universeCodes?` add-only | optional | 충돌 0 (기존 흐름 byte-동일) |

---

## 6. post-MVP (후속 트랙 후보)

1. **owner=AUTOPILOT 전략 UI 수정 가드** (가이드 P1 갭) — autopilot 전략을 빌더에서 열 때 "매일 08:35 자동 재생성됨 — 수동 codes 변경은 회귀함" 경고 배지/읽기전용. 본 트랙과 자본경로 비결합 작은 PR.
2. **옵션 B (기존 전략에 universe 추가/교체)** — `POST /api/strategy/{id}/universe` + confirm·owner 가드. 자본경로 직접 mutate라 가드 무르익은 뒤.
3. **universe 일괄 입력 보강** (가이드 P1) — 저장모달에 codes 다건 붙여넣기/CSV 가져오기. 본 트랙 R3에 포함 검토(§7).
4. **universe 상한 경고 UI** — N > 권장(예: 40 — WS slot 예산) 시 저장모달 경고.

---

## 7. 라운드 경계 (구현 plan 요약 — 상세는 spec)

| 라운드 | 책임 에이전트 | 내용 |
|--------|---------------|------|
| **R1** | frontend | `builderDraft.ts` `universeCodes?` 추가 + `buildDaytradePresetDraft` 시그니처 다종목화(기존 호출부 정합) + 빌더 draft 소비부 `initialCodes` 프리필 배선 |
| **R2** | frontend | `DaytradeDiscoverPage` "빌더로 N종 보내기" 버튼(`selectedCodes` 재사용) + 선택0 fallback + draft 메타 패널 "universe N종" 가시화 + (선택) 저장모달 다건 붙여넣기(§6-3) |
| **R3** | qa | E2E 시나리오 — 발굴 다중선택 → N종 보내기 → 빌더 프리필 확인 → 저장 → `/api/strategies` codes 다건 영속 확인 + owner=USER 격리 + 단일종목 fallback 회귀 + Visual 회귀(발굴 툴바·저장모달 프리필) |

> **개발 룰 정합**: R1·R2는 frontend 운반 배선(테스트 0건 OK — 개발룰 4), R3에서 데이터 흐름 시나리오 일괄. backend 변경 0이라 backend-core dispatch 없음.

## 8. 검증·증명 의무 (feedback_verification_strict)

단위 테스트로 끝내지 않는다:
1. 발굴 화면 다중선택 N종 → "빌더로 N종 보내기" → 빌더 저장모달에 N종 프리필됐는지 화면 확인
2. 저장 후 `GET /api/strategies/{id}` codes가 N종 전부 영속됐는지 DB·API 확인
3. owner=USER + 자동매매 OFF default 확인 (저장 직후 비활성)
4. 선택 0개 시 발굴 1위 1종 fallback(기존 거동) 회귀 확인
5. owner=AUTOPILOT 전략 codes 무변경 확인(격리)

### 운영 단계 슬롯 (헌법 §9-5)

- **SLO 영향**(§9-1): 발굴→빌더 운반은 frontend 세션 로컬(KIS·Kafka·Outbox 무관). 저장은 기존 `/api/strategies` p95 무변경. SLO 영향 0.
- **rollback plan**(§9-2): frontend revert(draft 필드 + 버튼 + 프리필 배선). backend 0 변경이라 DB·API 롤백 불요. 즉시.
- **관측 매트릭스**(§9-3): 신규 전략 생성 시 기존 전략 생성 로그(`전략 등록 요청`)로 가시화 + **Visual 회귀 시나리오**(발굴 툴바 "N종 보내기" 버튼 + 저장모달 universe 프리필) qa R3 자동 재현.

## 9. 롤백

frontend revert 3건(draft `universeCodes` + 발굴 "N종 보내기" 버튼 + 빌더 프리필 배선) → 기존 단일종목 흐름 byte-동일 복귀. backend·DB·API 변경 0(down SQL 불요). 롤백 앵커 v3.14.0.
