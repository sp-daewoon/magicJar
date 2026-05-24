# spec: Symbol Detail Trendline Tool Contract

- 작성일: 2026-05-25
- 영역: Symbol Detail chart / LWC overlay / trendline persistence
- 관련 결정: ADR 0025(LWC 확장 + 자체 overlay layer, TradingView Advanced 폐기), ADR 0085(chart-curator), ADR 0093(chart candle history)
- 구현 기준 commit: `41eaeae7 feat(frontend): add persistent trendline tools`
- 권위 파일:
  - `frontend/src/pages/Symbol/TrendlineOverlay.tsx`
  - `frontend/src/pages/Symbol/SymbolChart.tsx`
  - `frontend/src/pages/Symbol/LightweightChart.tsx`
  - `frontend/src/api/trendlines.ts`
  - `frontend/src/api/hooks/useTrendlines.ts`
  - `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/TrendlineController.kt`
  - `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/trendline/TrendlineService.kt`
  - `api/src/main/resources/db/migration/V53__trendline.sql`
  - `api/src/main/resources/db/migration/V54__trendline_extend_left.sql`

> 본 문서는 후속 작업자가 추세선 기능을 "보기 좋게 정리"한다며 TradingView와 다른 UX로 되돌리거나, 봉 전환 보존·익스텐션·단축키 동작을 깨는 일을 막기 위한 회귀 방지 계약이다. 코드 변경 시 본 문서의 §2~§8을 먼저 확인한다.

---

## §1. 범위와 비목표

### 1-1. 현재 제공 기능

| 구분 | 기능 |
|------|------|
| 저장 | 종목별·사용자별 추세선 DB 영속 (`trendline`) |
| 수동 작성 | 차트 위 2점 클릭으로 추세선 생성 |
| 자동 작성 | 현재 interval의 최근 캔들 피벗 2개를 이어 support/resistance 각 1개 생성 |
| 표시 | LWC chart 위 SVG overlay로 line·hit area·anchor handle 렌더링 |
| 봉 전환 | sourceInterval이 달라도 같은 종목의 추세선은 가능한 모든 interval에서 표시 |
| 편집 | 선 이동, 양 끝점 이동, 더블클릭 설정 패널, 색상·확장 수정 |
| 단축키 | Esc, Delete/Backspace, Ctrl/Cmd+C, Ctrl/Cmd+V |
| 스냅 | Ctrl/Cmd를 누른 상태로 점 선택·이동 시 가장 가까운 캔들의 OHLC에 스냅 |

### 1-2. 명시적 비목표

- strategy DSL 연결, 자동매매 진입 조건 연결, 주문 실행은 **아직 비목표**다.
- 자동 추세선은 현재 "근거리 시각 보조선"이며 자동매매 트리거로 쓰면 안 된다.
- TradingView Advanced Charting Library 도입은 ADR 0025에 따라 폐기 상태다. LWC v4 + 자체 overlay를 유지한다.
- localStorage fallback은 사용하지 않는다. 영속은 backend API + DB가 권위다.
- 추세선 price label, middle point, 템플릿 저장, 다중 선택은 후속 범위다.

---

## §2. Backend API 계약

Base path: `/api/symbols/{code}/trendlines`

| Method | Path | 의미 | 회귀 금지 |
|--------|------|------|-----------|
| GET | `/` | 현재 사용자 + 종목의 모든 추세선 조회 | interval 필터를 추가하지 않는다. |
| POST | `/` | 수동 추세선 생성 | `source=MANUAL`, anchor 시각은 서로 달라야 한다. |
| PUT | `/{id}` | 좌표·스타일 수정 | `sourceInterval`·`source`는 보존한다. |
| DELETE | `/{id}` | 추세선 삭제 | userId+code scope 밖 삭제 금지. |
| POST | `/auto?interval={interval}&lookback={n}` | 자동 추세선 생성 | 같은 code+interval의 AUTO만 교체하고 MANUAL은 절대 삭제하지 않는다. |

### 2-1. DTO 필드 의미

| 필드 | 의미 |
|------|------|
| `sourceInterval` | 생성 당시 기준 봉. 표시 필터가 아니라 provenance metadata다. |
| `type` | `SUPPORT` 또는 `RESISTANCE` |
| `source` | `MANUAL` 또는 `AUTO` |
| `anchor1Ts`, `anchor2Ts` | 두 anchor 시각. service는 시간 오름차순으로 정렬해 저장한다. |
| `anchor1Price`, `anchor2Price` | 두 anchor 가격. 문자열 decimal로 응답해 정밀도를 보존한다. |
| `extendLeft`, `extendRight` | 화면 좌우 방향 외삽 여부 |
| `label`, `color` | 표시 metadata. label은 null 허용, color는 hex 문자열을 기대한다. |

### 2-2. 자동 생성 알고리즘 현재 계약

현재 자동 추세선은 다음 보수적 알고리즘이다.

1. `lookback`은 `30..1000` 범위로 clamp한다. 기본값은 240이다.
2. 캔들은 최신 N개를 조회한 뒤 시간 오름차순으로 뒤집어 사용한다.
3. `PIVOT_WING=2`로 중심 봉 좌우 2개보다 낮은 low를 pivot low, 높은 high를 pivot high로 본다.
4. 마지막 pivot 2개를 이어 support 1개, resistance 1개 후보를 만든다.
5. 후보가 있으면 기존 같은 `code + sourceInterval + source=AUTO` 추세선을 삭제하고 새 AUTO를 저장한다.

이 알고리즘은 짧은 구간 피벗 확인용이다. 자동매매에 연결하려면 별도 major trendline score가 필요하다:

- 최소 anchor 거리
- touch count
- line break 검증
- 주봉/일봉/4시간/1시간 multi-timeframe 우선순위
- 현재가와의 거리 및 최근 반응 횟수
- false breakout 필터

---

## §3. 표시와 봉 전환 계약

### 3-1. sourceInterval은 표시 필터가 아니다

주봉에서 그린 선은 일봉·분봉에서도 보일 수 있어야 한다. 일봉에서 그린 선도 주봉·월봉에서 가능한 한 보인다.

구현 규칙:

1. frontend는 `useTrendlines(code)`로 종목의 전체 추세선을 가져온다.
2. 현재 interval과 `trendline.sourceInterval`이 달라도 렌더 후보에서 제외하지 않는다.
3. `sourceInterval`은 badge와 provenance 표시용이다.
4. anchor 시각이 현재 로드된 캔들 사이에 있으면 `timeToCoordinate` 직접값 또는 인접 캔들 보간으로 x좌표를 만든다.
5. anchor가 현재 로드 범위 밖이라 coordinate를 만들 수 없으면 해당 선은 숨길 수 있다. 이 경우 해결책은 interval 필터가 아니라 더 많은 캔들 로드다.

### 3-2. 오른쪽 익스텐션 회귀 금지

`extendRight=true`인 선은 마지막 캔들 x좌표에서 끊기면 안 된다.

정확한 규칙:

- 오른쪽 확장 끝점 `x2`는 `overlayWidth`다.
- 왼쪽 확장 시작점 `x1`은 `0`이다.
- 확장 y값은 두 anchor의 화면 좌표 slope로 외삽한다.
- anchor handle은 외삽 끝점이 아니라 실제 anchor 위치에 남아야 한다.

금지 구현:

```text
lastCandleTime으로 endTime을 clamp한 뒤 그 좌표를 x2로 쓰는 방식
```

이 방식은 사용자가 보고한 "오른쪽 익스텐션이 마지막 끝 캔들에서 끊김" 회귀를 재발시킨다.

---

## §4. 수동 작성 UX 계약

### 4-1. 진입

- 사용자가 `추세선` 버튼을 누르면 drawing mode가 켜진다.
- `지지`/`저항` segmented control로 생성 type을 선택한다.
- 시작점이 없을 때는 상태 badge가 "지지 시작점 대기" 또는 "저항 시작점 대기"를 표시한다.

### 4-2. 미리보기

TradingView 감각을 맞추기 위해 미리보기는 필수다.

| 상태 | 표시 |
|------|------|
| 첫 점 클릭 전 | cursor 위치에 target ring 표시 |
| 첫 점 클릭 후 | anchor ring + cursor target ring + dashed rubber-band line 표시 |
| Ctrl/Cmd 누른 상태 | 가장 가까운 캔들의 OHLC 중 cursor에 가까운 가격으로 스냅 |

첫 점 클릭 전 "시작점 대기" 텍스트만 보이고 target ring이 없는 상태는 회귀다.

### 4-3. 클릭 저장

1. 첫 번째 차트 클릭은 pending anchor로만 저장한다.
2. 두 번째 차트 클릭은 `POST /trendlines`를 호출한다.
3. 두 anchor의 timestamp가 같으면 저장하지 않고 사용자에게 다른 봉을 선택하게 한다.
4. 수동 생성 기본값은 `extendRight=true`, `extendLeft=false`다.

### 4-4. Esc 동작

| 현재 상태 | Esc 결과 |
|-----------|----------|
| drawing mode on + 첫 점 있음 | 첫 점만 취소하고 drawing mode는 유지 |
| drawing mode on + 첫 점 없음 | drawing mode 자체를 해제 |
| input/textarea/select/contenteditable focus | Esc를 가로채지 않는다 |

---

## §5. 선택·편집·삭제 계약

### 5-1. 선택과 drag

- 선을 클릭하면 선택된다.
- 선택된 선은 양 끝 anchor handle을 보여준다.
- 선 본체 drag는 두 anchor의 시간·가격 delta를 같이 이동한다.
- 시작/끝 handle drag는 해당 anchor만 이동한다.
- Ctrl/Cmd를 누른 상태의 drag는 가장 가까운 캔들의 OHLC에 스냅한다.
- pointer up 시에만 PUT을 호출한다. 이동 중 매 프레임 PUT을 호출하지 않는다.

### 5-2. 더블클릭 설정 패널

선 또는 handle을 더블클릭하면 설정 패널을 연다.

현재 패널 계약:

| 탭 | 동작 |
|----|------|
| 모습 | color swatch, extension select (`none`, `right`, `left`, `both`) |
| 좌표 | anchor 시각·가격 read-only 표시 |
| 보임 | `extendLeft`, `extendRight` checkbox |

확장 select와 checkbox는 같은 `extendLeft`/`extendRight` 상태를 수정해야 한다.

### 5-3. 삭제

- 선택된 추세선이 있을 때 Delete 또는 Backspace를 누르면 DELETE API를 호출한다.
- 삭제 후 selected id와 draft 상태를 정리한다.
- 설정 패널이 열려 있을 때는 Delete/Backspace 전역 삭제를 실행하지 않는다.

---

## §6. 복사·붙여넣기 계약

### 6-1. 단축키

- Ctrl/Cmd+C: 선택된 추세선을 overlay 내부 copy buffer에 저장한다.
- Ctrl/Cmd+V: copy buffer가 있으면 그것을, 없으면 현재 선택된 선을 복제한다.
- input/textarea/select/contenteditable focus에서는 단축키를 가로채지 않는다.

### 6-2. 복제 결과

복제는 "같은 길이 그대로"가 핵심이다.

보존해야 하는 값:

- `anchor1Ts`
- `anchor2Ts`
- 두 anchor 사이의 price delta
- `sourceInterval`
- `type`
- `extendLeft`
- `extendRight`
- `label`
- `color`

변경하는 값:

- `anchor1Price`, `anchor2Price`는 같은 금액만큼 위로 shift한다.

현재 shift 규칙:

1. visible candles의 high-low range가 있으면 `range * 0.035`
2. 없으면 두 anchor price 차이의 `0.2`
3. 그것도 없으면 `anchor1Price * 0.01`, 최소 1

복제는 반드시 backend `POST /trendlines`로 영속한다. 화면에만 임시로 그리는 복제는 금지한다.

---

## §7. Symbol Detail 레이아웃 계약

차트 영역은 종목 상세의 주 작업면이다.

- Symbol Detail에서 차트는 별도 전체 폭 row를 가져야 한다.
- 현재가 카드와 차트를 같은 좁은 grid cell에 계속 나눠 넣으면 안 된다.
- 우측 패널(호가·뉴스·공시)은 차트 아래 또는 별도 영역으로 배치할 수 있지만, 차트 높이와 폭을 희생시키면 안 된다.
- desktop 기준 chart wrapper는 `xl:h-[620px]` 수준의 충분한 높이를 유지한다.

사용자가 보고한 "현재가랑 나눠서 쓰다 보니 차트가 너무 작다"는 결함을 재발시키지 않는다.

---

## §8. 검증 체크리스트

추세선 관련 변경 PR은 최소 아래를 확인한다.

### 8-1. 정적 검증

```bash
cd frontend && npm run typecheck
./gradlew :api:compileKotlin
./gradlew :api:ktlintMainSourceSetCheck
```

### 8-2. 빌드·배포 검증

```bash
scripts/build-and-deploy.sh frontend
```

### 8-3. 브라우저 회귀 검증

SK하이닉스 `000660` 또는 충분한 캔들 데이터가 있는 종목으로 확인한다.

| 항목 | 기대 |
|------|------|
| 추세선 버튼 클릭 | 첫 점 전 cursor ring 표시 |
| 첫 점 클릭 후 mouse move | anchor ring + dashed preview line + cursor ring 표시 |
| Esc 첫 회 | pending anchor만 취소 |
| Esc 두 번째 | drawing mode 해제 |
| 수동 저장 후 일/주/월/분봉 전환 | `sourceInterval`과 무관하게 가능한 interval에서 선 유지 |
| 더블클릭 | 설정 패널 열림 |
| extension right | SVG line `x2`가 overlay width와 같음 |
| Ctrl/Cmd drag | 가까운 캔들 OHLC에 스냅 |
| Delete/Backspace | 선택 선 삭제 |
| Ctrl/Cmd+C/V | 같은 시간 길이의 평행선 생성, API에 새 row 생김 |

### 8-4. 2026-05-25 evidence

- `npm run typecheck` GREEN
- `./gradlew :api:compileKotlin` GREEN
- `scripts/build-and-deploy.sh frontend` GREEN
- Playwright 확인:
  - SK하이닉스 차트 overlay width = `1214`
  - `extendRight=true` line `x2 = 1214`
  - Ctrl/Cmd+C/V 후 trendline count `4 -> 5`
  - 테스트 생성 id `65` cleanup 후 DB id `[7, 6, 5, 4]` 복구
  - console error 0

---

## §9. 후속 설계 메모

자동매매 연결은 별도 spec으로 다룬다. 연결 전 필수 조건:

1. major trendline score를 만든다.
2. 주봉/일봉/4시간/1시간 multi-timeframe 우선순위를 명시한다.
3. 현재가가 긴 추세선에 닿는 조건을 "거리/터치/반등/돌파 실패"로 수치화한다.
4. 수동 추세선과 자동 추세선을 strategy DSL에서 서로 다른 source로 구분한다.
5. 주문 전 risk guard, kill switch, market session gate를 반드시 통과한다.

현재 구현의 AUTO는 이 후속 설계의 seed일 뿐, 주문 조건으로 직결하지 않는다.
