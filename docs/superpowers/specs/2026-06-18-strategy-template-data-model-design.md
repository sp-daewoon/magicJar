# 전략 시작 화면 + 템플릿 데이터화 설계

- **작성일**: 2026-06-18
- **상태**: Draft (사용자 검토 대기)
- **영역**: domain · api · frontend · persistence(Flyway)
- **연관**: ADR 0110(전략별 포지션)·0111(분할매수/익절)·0112(AI 생성 비자동저장) · 개발룰 3(레이어 순차)
- **출발 맥락**: 사용자(최고 관리자) — "전략 만들기가 어렵다, 더 사용자 친화적으로" + "데이터화는 되어야 하지 않나, 코드로 녹이기만 해서 되려나"

---

## 1. 문제 (현재 상태)

전략 빌더(`/strategy/builder`)는 이미 네 입력 경로(블록 팔레트·자연어·AI 생성·DSL 직접)를 갖췄으나, 사용자가 겪는 두 마찰이 분명하다:

1. **시작점이 없다** — 진입 시 빈 캔버스만 뜬다. "어디서부터 놓을지" 막막하다.
2. **무슨 지표·조건을 골라야 할지 막막하다** — 지표 83 + 패턴 59개를 검색 없이 탐색해야 한다.

근본 원인은 **자산이 절반만 데이터화**돼 있다는 점이다:

| 자산 | 현재 저장 위치 | 데이터화 |
|------|---------------|---------|
| 내가 만든 전략 | DB `strategy` (26행) | ✅ |
| 검증 프리셋 11개 (스마트머니 6·미너비니 5) | **frontend TS 하드코딩** (`smartMoneyPresets.ts`·`minerviniFavorites.ts`) | ❌ 코드+빌드+배포 필요 |
| 즐겨찾기 마킹 | **브라우저 localStorage** | ❌ 디바이스 종속·휘발 |
| (선례) 단타 발굴 프리셋 | DB `strategy_research_ai_discovery_preset` | ✅ |

프리셋이 코드에 박혀 있어 검색·정렬·사용통계·동적 추가가 불가능하고, 즐겨찾기는 폰·다른 PC에서 사라진다. 단타 쪽은 이미 프리셋을 DB로 관리하는데 전략 쪽만 코드에 머물러 일관성이 깨져 있다.

### 기각된 대안 — "목표 카테고리 큐레이션"

브레인스토밍 초기에 "눌림목/돌파/과매도/추세" 같은 **고정 매매 유형 카테고리**를 사람이 정의해 진입점으로 두는 안을 검토했으나 **기각**한다. 이유: 카테고리를 누군가 계속 정의·유지·확장해야 하는 *새 관리 대상*이 생긴다 — 빌더를 쉽게 만들려다 일거리를 하나 더 얹는 꼴. (사용자 직접 지적: "결국 계속 늘려가야 하는 거고, 만드는 게 하나 더 필요하잖아.")

---

## 2. 목표 / 비목표

### 목표
- 빌더 진입 시 **빈 캔버스 대신 "전략 시작 화면"** 을 띄워 시작점을 준다.
- 시작 화면을 **새로 큐레이션·관리할 게 0인 재료**로만 채운다: ① 검증 템플릿(DB) ② 내 전략·즐겨찾기(DB) ③ AI 즉석 초안(기존 기능 연결).
- 프리셋·즐겨찾기를 **DB로 데이터화** — 코드 배포 없이 추가/수정, 검색·정렬·통계, 다중기기 공유.
- 새 매매 유형은 코드가 아니라 **데이터(사용자 저장·AI 생성·시드 INSERT)로 자라나게** 한다.

### 비목표 (이번 범위 아님)
- 빌더(BlockPalette·Canvas·BlockNode) 내부 UX 재설계 — 시작 화면만 추가, 빌더 자체는 그대로.
- 거래 이력 기반 DSL 조건(직전 매도가 대비 % 등) — **별도 트랙**(사용자가 "UX 먼저" 선택). 본 spec 범위 외.
- 지표 팔레트 검색/툴팁 보강(접근법 B) — 후속 후보로 보류.
- 즐겨찾기/템플릿의 사용자 간 공유·마켓플레이스.

---

## 3. 핵심 결정 (브레인스토밍 확정)

1. **접근법 A — "목표부터 고르기" = 전략 시작 화면.** 단, 사람이 짜는 고정 카테고리는 만들지 않는다(무큐레이션).
2. **데이터 모델 A — 한 테이블 통합.** 프리셋과 내 전략은 구조(entry·exit·리스크플랜)가 같고 차이는 *소유자*뿐이므로, 기존 `strategy` 테이블을 확장해 한 곳에서 다룬다. 별도 `strategy_template` 테이블은 만들지 않는다(중복 회피).
3. **baseline 프리셋은 Flyway 시드로 git 버전관리** + 런타임은 DB에서 읽는다. 검증된 11개가 함부로 안 바뀌도록 보존하면서 데이터로 다룬다.

---

## 4. 아키텍처 / 컴포넌트

### 4.1 데이터 흐름
```
[전략 시작 화면]  ──GET /api/strategies/start──▶  [api]  ──▶  [strategy + strategy_favorite 조회]
       │                                                              │
       │  카드 클릭(템플릿/내전략)                                      └─ SYSTEM 템플릿 + 내 전략 + 즐겨찾기 플래그
       ▼
[기존 빌더]  ◀── 선택한 전략의 entry/exit/리스크플랜을 BlockTree로 로드 (기존 parseDslTextToTree 재사용)
       │
       │  "말로 설명" 클릭
       ▼
[기존 AI 생성 모달]  ── 변경 없음, 입구만 시작 화면에 노출
```

### 4.2 컴포넌트 (단위와 책임)

| 단위 | 책임 | 의존 |
|------|------|------|
| **StrategyStartScreen** (신규, frontend) | 진입 시 재료 3종 렌더·검색·"빈 캔버스로 직접" 탈출구. 카드 클릭 시 빌더로 로드 | `useStrategyStart` hook |
| **useStrategyStart** (신규 hook) | `GET /api/strategies/start` 조회·캐시 | strategy API |
| **즐겨찾기 토글** (frontend) | localStorage → `POST/DELETE /api/strategies/{id}/favorite` 전환 | favorite API |
| **StrategyStartController** (신규, api) | 시작 화면 조회 엔드포인트 — SYSTEM 템플릿 + 내 전략 + 즐겨찾기 합성 | StrategyQueryService |
| **StrategyFavorite** (신규, domain + persistence) | (userId, strategyId) 즐겨찾기 모델·포트·어댑터 | — |
| **strategy 확장** (domain + Flyway) | `owner`·`is_template` 추가, 프리셋 시드 | — |

기존 `BlockPalette`의 즐겨찾기 탭은 시작 화면과 데이터 소스를 공유(localStorage 제거)하되 UI는 유지한다.

---

## 5. 데이터 모델 (확정: A 통합)

### 5.1 `strategy` 테이블 확장
```
ALTER TABLE strategy
  ADD COLUMN owner varchar(16) NOT NULL DEFAULT 'USER';   -- 'SYSTEM' | 'USER'
ALTER TABLE strategy ALTER COLUMN user_id DROP NOT NULL;
```
- **owner**: `SYSTEM`(시드 프리셋·시작 화면 "검증 템플릿") / `USER`(사용자 전략). 이 한 컬럼이 ① 시작 화면 분류 ② 자동매매 대상 제외(SYSTEM 행은 평가/주문 비대상)를 모두 담당한다. 별도 `is_template` 컬럼은 두지 않는다(YAGNI — "사용자가 자기 전략을 템플릿화"는 비목표이며, 필요해질 때 additive로 추가).
- 기존 `origin` 컬럼은 "어떻게 생성됐나"(MANUAL/AI 등)로 의미가 다른 직교 축이므로 **건드리지 않고 공존**. (R2에서 `origin`에 SYSTEM/SEED 류 값이 이미 쓰이는지 grep 확인.)
- `user_id`: SYSTEM 템플릿은 소유 사용자가 없으므로 **nullable 허용**, SYSTEM 행은 NULL(sentinel 0보다 조회 필터가 명확).
- 기존 26행: `owner='USER'` DEFAULT 적용 → **무손실**.

### 5.2 `strategy_favorite` 신설
```
CREATE TABLE strategy_favorite (
  id          bigserial PRIMARY KEY,
  user_id     bigint      NOT NULL,
  strategy_id bigint      NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, strategy_id)
);
```
- localStorage의 `customFavorites`/`hiddenDefaultFavoriteIds`를 대체.

### 5.3 프리셋 시드 (Flyway)
- `smartMoneyPresets.ts`(6) + `minerviniFavorites.ts`(5)의 entry/exit **BlockTree → DSL 텍스트**로 변환해 `strategy`에 `owner='SYSTEM', is_template=true` 행으로 INSERT.
- DSL 텍스트는 프론트 serializer와 동치여야 한다 — 시드 작성 시 **각 프리셋을 빌더에 로드→직렬화한 DSL을 채취**해 SQL에 박는다(수기 추정 금지). R2에서 1:1 대조 검증.
- 시드 행은 `name`(식별자)·`name_ko`·`tf`(suggestedInterval)·`take_profit_pct`·`fixed_stop_loss_pct`를 합리적 기본값으로 채운다. 종목(`codes`)은 비움(템플릿은 종목 비귀속).

---

## 6. API 계약

### 6.1 시작 화면 조회
```
GET /api/strategies/start
200 {
  templates:  [ { id, name, nameKo, tf, dslEntry, dslExit, takeProfitPct, fixedStopLossPct, ... } ],  -- owner=SYSTEM
  myStrategies:[ { id, name, ..., favorite: boolean } ],                                              -- owner=USER, 내 것
  favorites:  [ strategyId, ... ]                                                                     -- 빠른 표시용
}
```
- 단일 호출로 시작 화면을 채운다(왕복 최소화).
- 데이터 0건이면 빈 배열 — mock 합성 금지(메모리: mock 데이터 사용 금지). 프론트는 "아직 없음 + 빈 캔버스로 직접" 안내.

### 6.2 즐겨찾기
```
POST   /api/strategies/{id}/favorite   → 201 (UNIQUE 충돌은 멱등 200)
DELETE /api/strategies/{id}/favorite   → 204
```

### 6.3 템플릿 → 빌더 로드
- 신규 엔드포인트 불필요. 카드 클릭 시 이미 받은 `dslEntry/dslExit`를 기존 `parseDslTextToTree`로 로드하고, 리스크플랜은 저장 모달 기본값으로 전달(ADR 0112 — **자동 저장 안 함**, 사용자가 검토 후 저장).

---

## 7. 마이그레이션 전략

1. **컬럼 추가**(DEFAULT 포함)로 기존 26행 자동 채움 — 다운타임/백필 불필요.
2. **프리셋 시드** INSERT — 멱등(이미 있으면 skip, `name` UNIQUE 활용).
3. **localStorage 즐겨찾기**: 프론트가 최초 로드 시 localStorage에 잔존 데이터가 있으면 1회 `POST favorite`로 흡수 후 localStorage 정리(베스트에포트, 실패해도 무해).
4. **frontend 하드코딩 제거**: `smartMoneyPresets.ts`·`minerviniFavorites.ts`는 R3에서 제거하되, **시드 채취가 끝난 뒤**(R2 완료 후) 제거 — 순서 LOCK.

---

## 8. 구현 단계 (개발룰 3: 레이어 순차, 한 PR 4레이어 금지)

- **R1 — 도메인·시그니처** (테스트 0 OK)
  - `Strategy` 도메인에 `owner` 필드. `StrategyFavorite` 모델 + 포트.
  - `GET /start`·favorite API 시그니처(컨트롤러 스텁/DTO).
- **R2 — 영속·시드·백엔드 구현**
  - Flyway: `strategy` 컬럼 추가 + `user_id` nullable + `strategy_favorite` + 프리셋 시드(채취한 DSL).
  - JPA 어댑터, 시작 화면 조회 서비스, 즐겨찾기 CRUD 구현.
  - **자원 audit**(메모리: spec 시 main 자원 audit) — Flyway 최신 버전 번호 확인 후 채번, REST 경로 충돌 확인.
- **R3 — UI·일괄 검증**
  - `StrategyStartScreen` + `useStrategyStart`, 빌더 진입 분기(시작 화면 ↔ 캔버스).
  - 즐겨찾기 localStorage→API 전환 + 1회 흡수 마이그레이션.
  - 하드코딩 프리셋 제거.
  - 데이터 흐름 시나리오 테스트(시작 화면 조회→템플릿 로드→저장→즐겨찾기 토글), qa Visual(시작 화면 신규 = 헌법 §9-2 대상).

---

## 9. 에러 처리 / 엣지 케이스

- `GET /start` 실패 → 시작 화면이 "불러오지 못함 + 빈 캔버스로 직접" 폴백(빌더 접근을 막지 않음).
- 템플릿 DSL이 파서 버전과 어긋나 로드 실패 → 해당 카드에 경고 표시, 나머지는 정상(현 AI 생성의 `dslValid=false` 패턴 재사용).
- SYSTEM 템플릿 삭제/수정 API는 일반 사용자에게 비노출(읽기 전용). 시드 갱신은 Flyway로만.
- 즐겨찾기 대상 전략이 삭제되면 CASCADE로 정리.

## 10. 테스트 전략 (R3 일괄)
- 백엔드: `/start` 합성(템플릿+내전략+즐겨찾기 플래그), favorite 멱등, SYSTEM 행 user_id NULL 조회.
- 프론트: 시작 화면 렌더(3종)·검색·빈 상태·템플릿 로드·즐겨찾기 토글 + localStorage 흡수.
- qa Visual: 시작 화면 스냅샷.

## 11. 리스크 / 롤백
- **리스크**: 시드 DSL과 프론트 직렬화 불일치 → 채취 방식으로 차단 + R2 1:1 대조.
- **리스크**: `user_id` nullable 전환이 기존 NOT NULL 가정 코드에 영향 → R1에서 조회 경로 grep 검증.
- **롤백**: 컬럼·테이블은 additive라 코드 롤백만으로 무해(시작 화면 미노출 시 빌더는 기존 빈 캔버스로 동작). Flyway down 불요.

## 12. ADR 필요성
- 데이터 모델 변경(전략·템플릿 통합, owner 도입)은 아키텍처 결정이므로 **R2 진입 전 ADR 1건 발행** 권장(통합 vs 분리 근거·origin과의 관계·SYSTEM 소유 user_id NULL 규약 고정).

## 13. 미해결 / 후속
- 접근법 B(팔레트 검색·툴팁): 후속 후보.
- 거래 이력 기반 DSL 조건: 별도 트랙(표현력 확장) — 본 작업 후 재논의.
- 템플릿 사용 통계(어느 프리셋이 많이 쓰이나) → 데이터화 완료 후 자연 확장 가능.
