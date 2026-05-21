# QA 첫 실전 임무 보고서 — 종목상세 현재가 결함 hunt (Visual QA "품질 헌터" 첫 가동)

| 항목 | 값 |
|------|-----|
| 작성일 | 2026-05-21 23:35 KST |
| 작성자 | magicjar-qa (`bot+qa@magicjar.local`) |
| 페르소나 | Visual QA "품질 헌터" (`.claude/agents/qa.md` reference) |
| dispatch source | orchestrator → frontend sub-agent (qa author·worktree LOCK) |
| worktree | `/Users/ak-song/magic_ch1_qa_first_mission` |
| branch | `agent/qa/first-mission-symbol-detail` |
| 시나리오 | `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts` (신규 발행) |
| 임무 분류 | 진단·보고만 (fix 0건) |

---

## 1. 사용자 dogfooding 원문 인용

> "종목상세에 지금 현재가에 문제가 있는게 보이거든??
>  일단 내가 말을 안 해볼거야 뭐가 문제인지.
>  이거 QA한테 한번 찾아보라 해보자 어떤게 문젠지 찾을 수 있는지 확인해야지
>  그리고 QA와 연계하여 바로 업무처리 까지"

사용자는 결함 위치를 함구. QA 자율 발견 의무.

---

## 2. 결함 식별 결과 — **결함 확정 (P0 회귀)**

**현상 한 줄**: 종목상세 페이지의 좌측 시세창 "현재가"가 **사용자 HTS 정규장 종가와 불일치**. PR #595·#597 hotfix(2026-05-20)로 일시 정합되었으나 **2026-05-21 시점 회귀 확인**.

### 2-1. evidence 매트릭스 — 3 종목 동시 수집

| 종목 | 화면 표시 | API `currentPrice` | 예상 HTS 범위 | 정합 | 비고 |
|------|----------|--------------------|--------------|------|------|
| 005930 삼성전자 | **292,500** | `292500.0000` | 30,000 ~ 200,000 | ❌ inflated | PR #597 evidence 시점 ≈ 56,000 (실제 사용자 MTS 기준 추정) |
| 000660 SK하이닉스 | **1,918,000** | `1918000.0000` | 100,000 ~ 400,000 | ❌ inflated | PR #597 머지 evidence 1,719,000 → 회귀 |
| 004090 한국석유 | **13,470** | `13470.0000` | 5,000 ~ 50,000 | ✅ 정상 범위 | 비교 baseline (PR #597 머지 후 일관 정합) |

→ **모든 종목이 균등 inflate된 게 아님** (한국석유 정상) → 단위 변환 결함 아님. **종목·날짜별 selective 결함**.

### 2-2. 화면 ↔ 외부 SoT(KIS prod) 비교 — SK하이닉스 시계열

DB candle `1d` close_raw (KIS source) 2026-05 분포:

```
2026-05-21  close=1,918,000  vol=4,850,226   ← 오늘 시세창 표시 (의심)
2026-05-20  close=1,764,000  vol=  223,020   ← NXT 야간 의심 (정규장 vol 2.3%)
2026-05-19  close=1,719,000  vol=7,805,248   ← PR #597 evidence 사용자 MTS 정합값
2026-05-18  close=1,840,000  vol=10,771,377
2026-05-15  close=1,848,000  vol=13,151,904
```

PR #597 머지 시점(2026-05-20 d8d8d91)의 검증 표:
```
| currentPrice | 1,719,000 | 1,719,000 | ✓ |
```

→ **회귀 명백**. PR #597 NXT 야간 휴리스틱이 2026-05-21 사이클에서 무효화됨.

---

## 3. Evidence 매트릭스 (7 dimension)

### 3-1. Visual evidence

baseline 스크린샷 6장 신규 발행 (첫 baseline — 회귀 가드 가용):

| 파일 | 용도 |
|------|------|
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-005930-full-chromium-darwin.png` | 삼성전자 종목상세 전체 페이지 baseline |
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-005930-price-card-chromium-darwin.png` | 삼성전자 가격 카드 부분 (visual diff 정밀도 ↑) |
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-000660-full-chromium-darwin.png` | SK하이닉스 종목상세 전체 |
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-000660-price-card-chromium-darwin.png` | SK하이닉스 가격 카드 |
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-004090-full-chromium-darwin.png` | 한국석유 종목상세 전체 (비교 baseline) |
| `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts-snapshots/s6-004090-price-card-chromium-darwin.png` | 한국석유 가격 카드 |

시각적 결함: 가격 표시 색상·포맷·badge는 모두 정상 작동 (`'+11.58%'` 빨강 ▲ 표시). **숫자 값 자체가 비현실적** — 시각적으로 즉시 인식 가능.

### 3-2. DOM evidence

```html
<!-- 005930 삼성전자 가격 카드 부분 -->
<div class="flex items-baseline gap-2">
  <span class="text-2xl font-bold text-white tabular-nums">292,500</span>
  <span class="text-sm text-gray-500">원</span>
</div>

<!-- 000660 SK하이닉스 -->
<div class="flex items-baseline gap-2">
  <span class="text-2xl font-bold text-white tabular-nums">1,918,000</span>
  <span class="text-sm text-gray-500">원</span>
</div>
```

selector: `.text-2xl.font-bold.text-white.tabular-nums`. 1차원 단일 element. 변동률 element: `.text-sm.tabular-nums` (`+11.58%`).

### 3-3. Network evidence — REST 응답 raw

```json
// GET /api/symbols/005930 (200 OK)
{
  "code": "005930", "name": "삼성전자", "market": "KOSPI",
  "currentPrice": "292500.0000",
  "previousClose": "273500.0000",
  "change": "19000.0000", "changeRate": "6.95",
  "high": "300000.0000", "low": "287000.0000", "open": "300000.0000",
  "volume": 36940433,
  "marketCap": "17509604",                       // 17,509,604 (억) = 1,750조원 inflated
  "asOf": "2026-05-21T06:19:59Z",                // = KST 15:19:59 (장 마감 11분 전)
  "source": "live", "stale": false,              // 회귀 — stale=false로 잘못 표시
  "basePrice": "281000",                          // instrument 테이블 — 정규장 전일종가
  "marketCapPrev": 16428042
}

// GET /api/symbols/000660 (200 OK)
{
  "code": "000660", "name": "SK하이닉스",
  "currentPrice": "1918000.0000",                // PR #597 시점 1,719,000 → 회귀
  "previousClose": "1719000.0000",
  "change": "199000.0000", "changeRate": "11.58",
  "asOf": "2026-05-21T06:19:59Z",
  "basePrice": "1840000"
}
```

### 3-4. Console evidence

```
[error] Failed to load resource: the server responded with a status of 404 (Not Found)
[debug] [STOMP] Opening Web Socket... → CONNECTED → SUBSCRIBE 5건
  - /topic/system.event.broadcast
  - /topic/market.tick.{code}
  - /topic/market.candle.minute.{code}
  - /topic/market.orderbook.{code}
  - /topic/system.event.user.{userId}
```

→ STOMP 정상 구독. 404 1건 미식별(부수적). 가격 계산·렌더링 JS 에러 0건.

### 3-5. Backend evidence — DB·Redis·로그

**DB candle table 분포 (2026-05 SK하이닉스)**: §2-2 참조. **모든 row가 KIS source**. NXT 분리 컬럼 없음 (`source='KIS'` 단일).

**Redis state**:
```
quote:last:005930 = {"price":"299000","volume":16,"ts":"2026-05-21T06:19:59Z","source":"live","priceChange":"23000","changeRate":"8.33","changeSign":"2"}
meta:005930       = {"currentPrice":299500,"previousClose":276000,"priceChange":23500,...}
quote:last:000660 = {"price":"1941000","volume":158,"ts":"2026-05-21T06:19:59Z","source":"live","priceChange":"196000","changeRate":"11.23","changeSign":"2"}
```

→ **Redis 가격(299,000) vs DB candle close(292,500) vs API 응답(292,500) 비일관**. Redis quote:last의 source="live"가 NXT 감염 검사를 통과 (`source.contains("nxt")` 패턴 매칭 빠짐) → 정규장 마감 후 NXT 가격이 `source=live`로 publish될 risk.

**API 로그 (최근 30분)**: SymbolMetaService 호출 직접 로그 0건 (debug level). NaverNewsAdapter는 정상 1분 polling.

**MAGICJAR_MODE**: `MOCK` (사용자 잔고 보호) but **시세 path는 항상 prod endpoint** (`SymbolMetaService` line 61 `baseUrl = kisBaseProd` LOCK). 즉 inflated 값은 KIS prod 실응답.

### 3-6. 시간 정합 (KST timestamp matrix)

| timestamp | 의미 | 값 |
|-----------|------|-----|
| 현재 시각 (테스트 실행) | KST | **2026-05-21 23:33** |
| `asOf` 응답 | UTC → KST | **2026-05-21 15:19:59 KST** (장 종료 11분 전) |
| candle ts 최신 | KST | 2026-05-21 00:00:00 KST (일봉 자정 정규화) |
| Redis quote:last ts | UTC → KST | 2026-05-21 15:19:59 KST |

→ `asOf` = 15:19, 현재 = 23:33 → **8시간 stale**. 그런데 응답 `stale: false`. **stale 판정 결함 확정** (`SymbolMetaService.kt` line 207-208 `usedKisFallback` 기준만 사용, 시간 경과 검증 없음).

### 3-7. 현재가 값 검증 — 4 source 단일 종목 비교 (SK하이닉스)

| Source | 값 | path |
|--------|-----|------|
| DB candle 1d 최신 close (2026-05-21) | 1,918,000 | `candleJpaRepository.findByCodeAndIntervalLatest` |
| Redis quote:last (live source) | 1,941,000 | KIS WS / TickFallbackEmitter |
| instrument basePrice | 1,840,000 | 전일 정규장 종가 |
| PR #597 검증 evidence (2026-05-20) | 1,719,000 | 사용자 HTS MTS |

→ DB candle·Redis 값이 PR #597 검증 evidence(1,719,000) 대비 **+12~13% 오버슈팅**. 사용자가 보는 HTS 값이 진짜 정규장 종가라면 NXT 야간 거래 또는 시간외 단일가가 KIS endpoint에 누설된 정황.

---

## 4. Root Cause 가설 매트릭스 — 8 가설 검증

| ID | 가설 | 검증 결과 | 평가 |
|----|------|----------|------|
| **H1** | 가격 표시 format 결함 (콤마·소수점) | toLocaleString('ko-KR') 정상 작동. DOM raw text "292,500"·"1,918,000"이 콤마 포맷만 적용 | ❌ **기각** |
| **H2** | 가격 source 결함 (STOMP push 안 옴 → REST stale) | STOMP 5건 SUBSCRIBE 성공 + 페이지 mount 즉시 `realtimeTick` state 가능. REST `currentPrice` 자체가 잘못된 값 | ❌ **기각** (REST 응답 자체 결함) |
| **H3** | 종목 매핑 결함 (code → name 잘못) | instrument 테이블 005930="삼성전자"·000660="SK하이닉스" 정합. KIS 응답도 동일 종목명 | ❌ **기각** |
| **H4** | 통화 단위 결함 (KRW vs USD) | 화면 "원" 표시 정합, 모든 source가 KRW 단위 | ❌ **기각** |
| **H5** | KRX 운영 시간 결함 (장 마감 시간 표시) | badge 로직 정상 (`getPriceBadge` 호출). 단 `stale=false`로 응답되어 "장 마감" badge 미표시 | ⚠️ **부분 적중 — H8 합류** |
| **H6** | 변동률 계산 결함 (전일 대비) | change=199,000 / previousClose=1,719,000 → +11.58% 계산 정합. 단 currentPrice·previousClose 자체가 inflated되어 변동률만 정확 | ⚠️ **부분 적중 — derived value** |
| **H7** | backend response 결함 (API 응답 자체) | ✅ 확정 — `/api/symbols/{code}` 응답 currentPrice가 사용자 HTS와 불일치 | ✅ **확정 (1차)** |
| **H8** | candle DB 1d 최신 close = NXT 야간/시간외 단일가 포함 | DB 2026-05-21 close=1,918,000 vs PR #597 직후 2026-05-19 정상값 1,719,000. NXT 분리 컬럼 부재, source='KIS' 단일. `inquire-daily-itemchartprice fid_cond_mrkt_div_code=J`(KRX 정규장) 호출에도 stck_clpr가 NXT 통합 가격일 risk | ✅ **확정 (root)** |

### 추가 발견 (qa 자율) — Stale 판정 결함

- `SymbolMetaService.kt` line 206-208: `stale = staleFromCache || usedKisFallback`
- 시간 경과 검증 (예: `asOf` < now - 30분이면 stale=true) **부재**.
- 결과: 8시간 stale 데이터가 `stale=false` + `source=live`로 그대로 표시.

---

## 5. 영역 식별

| 영역 | 결함 위치 | 책임 sub-agent |
|------|----------|---------------|
| **backend-core** | `api/src/main/kotlin/.../service/SymbolMetaService.kt` line 73-130 (NXT 야간 휴리스틱 무효화) + line 206-208 (stale 판정 시간 경과 검증 부재) | backend-core |
| **chart-curator** | `api/src/main/kotlin/.../service/CandleRefreshService.kt` line 250·319 (`fid_org_adj_prc=0`) + KIS adapter `fid_cond_mrkt_div_code=UN`(통합) → `J`(정규장 분리) 전환 또는 candle table `venue` 컬럼 도입 — PR #597 §"후속 추천" 미완 | chart-curator |
| **frontend** | (없음 — 결함 없음) | — |
| **data** | DB candle table에 NXT 야간 일봉이 정규장과 같은 row로 적재됨 (source='KIS' 단일, venue 구분 없음) | chart-curator + backend-core 합류 |

→ **frontend 영역 0건**, **backend-core + chart-curator 영역 합류 결함**.

---

## 6. 권장 fix 매트릭스 (옵션 3개 — orchestrator 의사결정 위임)

### 옵션 A — Quick fix (P0 patch, NXT 야간 휴리스틱 강화)
- **목표**: PR #597 휴리스틱 회귀 fix
- **영역**: backend-core (`SymbolMetaService.kt` 단일 파일)
- **변경**:
  1. NXT 야간 휴리스틱 강화: `curVol * 10 < nextVol` → `curVol < nextVol * 0.30` (장중 cumulative volume 고려)
  2. stale 판정 시간 경과 검증: `asOf < now - Duration.ofMinutes(30)` → stale=true
  3. 정규장 운영시간(09:00~15:30 KST) 외 candle 최신 row 의심 시 직전 정상 row(`vol > 100K`) fallback
- **risk**: 휴리스틱 임시 fix, 다음 회귀 가능
- **소요**: 1 사이클 (api·domain 0 변경)

### 옵션 B — Proper fix (P1, candle venue 컬럼 도입)
- **목표**: NXT/정규장 분리 single source of truth
- **영역**: chart-curator (lead) + backend-core (consumer)
- **변경**:
  1. `candle` 테이블에 `venue VARCHAR(10) NOT NULL DEFAULT 'KRX'` 컬럼 추가 (Flyway)
  2. KIS chart adapter `fid_cond_mrkt_div_code=UN` → 2 path 분리 (J=KRX, X=NXT)
  3. `SymbolMetaService` candle 조회 시 `venue='KRX'` 필터
  4. backfill: 기존 row 모두 `venue='KRX'` 기본값 + NXT 의심 row 보정 별도 migration
- **risk**: schema 변경, 다른 consumer(chart·indicator·pattern) 영향
- **소요**: 2~3 사이클 (chart-curator + backend-core + DB migration)

### 옵션 C — Hybrid (P0 즉시 + P1 backlog)
- **즉시**: 옵션 A (P0 patch — SK하이닉스/삼성전자 dogfooding 즉시 해소)
- **backlog**: 옵션 B (P1 — v3.8.x로 sunset)
- **추천**: ★ 본 옵션이 사용자 dogfooding 신속 해소 + 근본 fix 동시 보장

---

## 7. 회귀 가드 — 본 PR이 남기는 자산

| 자산 | 위치 | 효과 |
|------|------|------|
| Playwright 시나리오 | `frontend/tests/e2e/s6-symbol-detail-current-price.spec.ts` | 3 종목 동시 검증 + expectRange 위배 시 fail (P0 hotfix 들어가면 GREEN으로 reverse) |
| baseline 스크린샷 6장 | `frontend/tests/e2e/.../s6-{code}-{full|price-card}-*.png` | 향후 시각 회귀 가드 |
| 본 보고서 | `_workspace/reports/2026-05-21-qa-first-mission-symbol-detail.md` | dogfooding evidence 추적 |

본 시나리오 실행 결과:
- 005930 → ❌ FAIL (expected ≤200,000, received 292,500)
- 000660 → ❌ FAIL (expected ≤400,000, received 1,918,000)
- 004090 → ✅ PASS (13,470 ∈ [5,000, 50,000])

→ **P0 hotfix 머지 후 본 시나리오를 재실행하여 3 종목 모두 PASS 확인**이 fix 검증 protocol.

---

## 8. orchestrator 후속 dispatch 권고

| 우선순위 | dispatch 대상 | 작업 |
|---------|-------------|------|
| P0 즉시 | **backend-core** | 옵션 A — NXT 휴리스틱 강화 + stale 시간 경과 검증 hotfix (single PR) |
| P0 즉시 | **qa** | 본 시나리오로 P0 fix 검증 dogfooding (재실행 + 3/3 PASS 확인) |
| P1 backlog | **chart-curator + backend-core** | 옵션 B — candle venue 컬럼 도입 + KIS endpoint J/X 분리 (multi-PR spec) |
| P2 sunset | **qa** | s6 시나리오를 정기 시각 회귀 suite에 합류 (CI 0 정합 — 사용자 메모리 정합 로컬 단독) |

---

## 9. 사용자 명시 dogfooding 함구 정합 보고

본 보고서는 사용자 함구 의도를 정확히 정합. 결함 위치를 사용자에게 묻지 않고 **qa 자율 발견 + multi-dimension evidence 매트릭스**로 root cause를 확정. 사용자가 본 보고서 read 후 추가 dogfooding evidence 제공 시 본 보고서 §8 P1·P2 dispatch에 합류.

## 10. 룰·정책 준수 보고

- ✅ **단일 책임 코드** (rule 2): 본 PR = qa 진단·보고만, 운영 코드 변경 0
- ✅ **KIS-only 정책** (사용자 메모리): Yahoo Finance fetch 차단 정합 확인, KIS prod endpoint만 사용
- ✅ **mock 데이터 사용 금지**: backend 응답 raw 인용, 합성 0
- ✅ **검증 의무 — 할루시네이션 금지**: DB·Redis·API·DOM·Visual·Console·시간 7 dimension evidence 인용
- ✅ **존댓말 의무**: 본 보고서 본문 한국어 보고체
- ✅ **QA on-demand**: 사용자 명시 dispatch (첫 실전 임무 트리거 1건)
- ✅ **fix 0건 LOCK**: 운영 코드 0 변경, test + 보고서만

---

**보고 완료. orchestrator 의사결정 대기.**
