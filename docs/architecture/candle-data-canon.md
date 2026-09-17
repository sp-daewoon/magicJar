# 캔들 데이터 캐논 (Canonical) — 지금 무엇이 권위인가

> **이 문서 = 캔들/가격/차트 데이터에 대한 "지금 옳은 답"의 단일 위치.**
> ADR 94건·`candle-data-policy-v2.md` 17개 amend 노트에 흩어진 결정을 여기 1곳으로 응축한다.
> **권위 순서**: 본 캐논 > candle-data-policy-v2(운영 상세) > 개별 ADR(결정 이력).
> 충돌 시 본 캐논이 이긴다. 본 캐논이 침묵한 세부는 v2 → ADR 순으로 참조.
> **결정 이력**: 감사 [`2026-09-17-chart-data-integrity-audit.md`](../../_workspace/audit/2026-09-17-chart-data-integrity-audit.md) · ADR 0057·0105·0118·0119·0124 · [candle-data-policy-v2.md](candle-data-policy-v2.md)
> **마지막 갱신**: 2026-09-17 (신설 — 하네스 재편 캐논 LOCK)

---

## §0. 왜 이 문서가 생겼나

캔들 데이터 하나에 ADR 136건 중 **94건(68%)**이 얽혔고, `candle-data-policy-v2.md`는 헤더에만 **17개 amend 노트**가 쌓였다(감사 §2). 그 결과 "이 봉의 진짜 값이 무엇인가"의 답이 6곳(WS/Redis/DB/REST 3종)에서 나오고, 같은 봉을 쓰는 경로가 8개이며, 최종값이 실행 순서에 의존하게 되었다(감사 §1).

본 캐논은 그 혼란을 **단일 권위 표 5개**로 응축한다. 세부 흐름·mermaid·estimate는 v2가 계속 보유하고, 본 캐논은 "무엇이 이기는가"만 규정한다.

---

## §1. 진실원 우선순위 (Source Precedence) — LOCK

같은 `(code, interval, ts)` 봉에 대해 값이 충돌하면 **신뢰도 높은 source가 이긴다.** 이 순서가 곧 P0-B 가드(remediation)의 근거다.

| 우선순위 | source 등급 | 예시 값 | 덮어쓰기 규칙 |
|---:|---|---|---|
| 1 (최고) | 공식 마감 확정 | `KIS_DAILY_CLOSE`, `KIS-MKT-DAILY-SNAP`(마감 후) | 무엇이든 덮을 수 있음 |
| 2 | REST 과거 확정 | `KIS-BACKFILL`, `KIS-RANGE-GAPFILL`, `KIS-MKT-DAILY-BF` | 등급 3 이하만 덮음 |
| 3 | WS 실시간 마감봉 | `KIS`(tick 집계 closing) | 등급 3만 덮음(동일 등급 최신) |
| 4 (최저) | polling/fallback | `KIS-POLLING`, `KIS-NOTICK-FALLBACK` | 아무것도 덮지 못함(빈 칸만 채움) |

> **현행 코드는 이 순서를 강제하지 않는다** (감사 §1-②: 무가드 DO UPDATE). remediation **P0-B**가 `source_priority` 컬럼 + `WHERE EXCLUDED.source_priority >= candle.source_priority`로 강제할 대상. 그 전까지는 "마지막 writer 승리"가 실제 동작임을 인지하고 운영.

---

## §2. 캔들 쓰기 경로 대장 (Writer Registry) — LOCK

같은 봉을 쓰는 경로는 아래 8개가 전부다. **새 writer 추가 금지** — 신규 수집원은 반드시 이 표에 등재 + 단일 arbiter(remediation P0-A) 경유.

| # | writer | source 라벨 | 등급 | UPSERT | 트리거 |
|---|--------|-------------|-----:|--------|--------|
| 1 | `LiveCandleUpdater`/`TickToMinuteAggregator` | `KIS` | 3 | INSERT | WS tick 매분 마감 |
| 2 | `BoundaryAggregator`/`CandleRefreshService.upsertAggregateCandles` | `KIS` | 3 | DO UPDATE | 1m closing 직후 상위봉 합산 |
| 3 | `CandleBackfillWorker` | `KIS-BACKFILL` | 2 | DO NOTHING | admin 수동/gap |
| 4 | `MarketDailyBackfillCampaign` | `KIS-MKT-DAILY-BF` | 2 | DO NOTHING | 전종목 10년 캠페인 |
| 5 | `MarketDailyNightlySnapshotService` | `KIS-MKT-DAILY-SNAP` | 1 | DO UPDATE | 야간/장전 snapshot |
| 6 | `MarketDailyDateRepairService` | `KIS-MKT-DAILY-BF` | 2 | DO UPDATE/DELETE | date repair |
| 7 | `DailyFamilyRollupRunner` | (rollup) | 2 | DO UPDATE/DELETE | 1wk/1mo/1y 합산 |
| 8 | `NoTickMinuteFallbackService`/`SymbolDetailMinuteGapFillService` | `KIS-NOTICK-FALLBACK`/`KIS-RANGE-GAPFILL` | 2~4 | DO NOTHING | WS silent/차트 진입 |

> **P0-A**: 8경로가 DB를 직접 UPSERT하는 대신 `CandleWriteArbiter` 단일 진입점 경유로 통합. **P0-C**: #6 date repair의 DELETE는 KIS 일시 미보유를 영구삭제로 오판(감사 §1-②) → tombstone 전환.

---

## §3. 진실원 계층 (Source-of-truth by lifecycle) — LOCK

ADR 0105 §3-1을 캐논으로 승격. 데이터 생애주기별 권위.

| 데이터 | 권위 위치 |
|---|---|
| 현재가 tick | KIS WS `H0UNCNT0` (KRX+NXT 통합 단일 채널 — ADR 0124) |
| 호가 | KIS WS `H0UNASP0` (통합) |
| 진행 중 봉 (1m~1y) | Redis `candle:current:{code}:{interval}` (1개만 오버레이 — P1-A) |
| 확정 봉 | PostgreSQL `candle` (단일 권위 — 조회 시 재조립 금지, P1-A) |
| 지나간 1m gap 복구 | KIS REST `FHKST03010230` (최대 1년 보관) |
| 당일 짧은 replay | KIS REST `FHKST03010200`/`03010230` |
| 1d·1wk·1mo·1y 확정 | KIS REST `FHKST03010100` |
| today 1d 최종 권위 | 공식 마감값 snapshot(`FHKST11300006`) > WS tick 집계 (ADR 0118) |

**KST 경계 계산 = 백엔드 단일 권위** (P1-B): 봉 시작 ts는 `LiveCandleUpdater.liveCandleStart`(백엔드)가 계산해 API/STOMP payload에 담는다. 프론트(`LightweightChart.barBoundaryTimestamp`)는 **재계산 금지**, 받은 ts 사용.

---

## §4. 시장구분·세션·수집 정책 — LOCK

| 축 | 현행 권위 | 근거 |
|---|---|---|
| REST 시장구분 | 전종목 `J`(KRX 본시장) 단일. `UN`/`NX` deprecated | ADR 0118 amend (KIS측 stale 동결) |
| WS 채널 | 체결가 `H0UNCNT0` + 호가 `H0UNASP0` 통합 단일 | ADR 0124 |
| 세션 cover | NXT 통합 08:00~20:00 KST (720 bar/일) | ADR 0063 |
| 1m 수집 | 실시간 WS(구독 종목만) + on-demand(차트 진입·admin). **proactive cold-start 폐지** | ADR 0119 |
| 1d/1wk/1mo/1y | 전종목 일봉 universe(R-BF/R2) 야간 snapshot. `MARKET_DAILY` ≠ active universe | ADR 0118 |
| 3m~12h | 1m 기반 백엔드 precomputed 합산(`CandleAggregator`). 직접 수정 금지 | ADR 0062 |
| 합성 | **Mock/근사 합성 전면 금지**. 데이터 없으면 "없음" 표시 | `feedback_no_mock_data.md` |
| data readiness | `READY` 아니면 전략 평가 skip·주문 fail-closed | ADR 0105 §3-6 |

---

## §5. 전략/청산에서의 가격 사용 — LOCK

| 용도 | 현행 권위 | 상태 |
|---|---|---|
| DSL 캔들 접근(`close`/`open`/`high`/`low`) | **원주가(raw) 고정** (지표와 소스 통일) | 완료(과거 adj 혼합 교정) |
| 지표(ma·rsi 등) | 원주가(raw) 기본 | 완료 |
| ADJUSTED 모드 양측 threading | 지표+DSL 동시 adj 미지원 | **미해결 — remediation P2-A** (선행 audit P1-④ 이연분) |
| 손절/익절 수준 | 진입가/평단 대비 고정 %(현행 실동작) | 작동 |
| 추세선 기반 손절(ADR 0140) | `planned-entry.enabled=false` → **프로덕션 미작동** | **remediation P2-B** |

> **핵심 인지**: DSL/패턴/추세선은 "언제 사고팔지" 신호로만 쓰이고, "어디서 자를지"는 항상 % 지배. 차트 기반 매매 복원은 P2-A(소스 통일) → P2-B(추세선 canary) 순서로만 가능. **데이터 신뢰 확보 전 차트 손절 활성 금지** (ADR 0137 휩쏘 손실 실증).

---

## §6. ADR 상태 정합 (Superseded 명시) — 하네스 재편

"ADR 폐기 금지" 원칙(사용자)은 유지하되, **실제로 대체된 결정은 상태줄에 `Superseded by`를 명시**한다(폐기 아님, 이력 보존). 아래는 캐논 기준 대체 관계.

| ADR | 실제 상태 | 대체 | 현재 상태줄 | 조치 |
|---|---|---|---|---|
| 0063 (NXT `UN` 통합 cover) | deprecated | ADR 0118 (전종목 `J`) | `Accepted` (불일치) | P2-D: `Superseded by 0118`로 정정 |
| 0091 (NXT `UN`/`J` resolver) | deprecated | ADR 0118 | `Accepted` (불일치) | P2-D |
| 0048 §2-4 (30초 polling fallback) | 폐지 | ADR 0124 amend | 본문만 폐지 | P2-D: 상태 명시 |
| 0019 (합성 폐기↔부활 공존) | 부분 재통합 | ADR 0062/0064 | 헤더 노트 누적 | 캐논 §2/§4가 현행 권위(ADR은 이력) |

---

## §7. 변경 시 의무

1. 캔들 관련 결정 변경 → **본 캐논 해당 § 먼저 갱신** (현행 권위)
2. candle-data-policy-v2.md 운영 상세 갱신 (흐름·mermaid·estimate)
3. ADR 후속 발행 (이력) — 기존 ADR 대체 시 상태줄 `Superseded by`
4. INDEX.md ref 갱신

**룰**: append로 끝내지 말 것. 캐논(§1~§6)을 갱신하지 않으면 "지금 무엇이 권위인가"가 다시 흩어진다.

## §8. 변경 이력

- 2026-09-17: 신설 — 하네스 재편. 진실원 우선순위·writer 대장·생애주기 권위·시장/세션 정책·전략 가격 사용·ADR Superseded 정합을 단일 캐논으로 LOCK. candle-data-policy-v2의 17 amend 노트는 운영 상세로 잔존, 본 캐논이 "무엇이 이기는가"의 권위.
