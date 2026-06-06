# ADR 0113: 캔들 retention 계층화 케이스 분석 (결정 보류)

- 상태: **Proposed** (사용자 지시 2026-06-06 — "분석만 문서화, 결정 보류")
- 작성일: 2026-06-06
- 결정자: architect (지시: 사용자/최고 관리자, 협의: orchestrator)
- 관련: ADR 0062 (합산 precomputed) · ADR 0105 (1m 6개월/1년 reconciliation) · ADR 0057 (KIS-only)
- evidence: `docs/assessments/2026-06-06-db-usage-contention-audit.html` + `docs/assessments/2026-06-06-candle-storage-capacity.html` + `docs/assessments/2026-06-06-candle-operating-policy-options.md`

## 맥락(Context)

실매매 진입 전 DB 전수 감사(2026-06-06)에서 candle 테이블이 DB 973MB 중 849MB(87%)를 점유하고, 그중 561MB(66%)가 인덱스이며, update가 insert의 2.4배(446만 vs 186만)·HOT update 3.3%로 쓰기 증폭이 확인되었다. 현재 20종목 1m 6개월 기준 240만 행이며, 유니버스 확장 시 candle이 시스템 장기 운영 가능성을 좌우하는 1순위 경합점이다. ADR 0105가 "1m 기본 6개월"을 선언했으나 cleanup 집행 코드는 0건이고, 합산봉(3m~12h)의 보관 기간은 결정된 바 없다.

**전제 사실 (재확인)**: KIS OpenAPI는 국내주식 중간 interval(3m~12h)을 제공하지 않는다 (1m + 1d/1wk/1mo/1y만 제공, 1m 서버 보관 최대 1년). 따라서 "1m 수집 → 백엔드 precomputed 합산"(ADR 0062)은 유일하게 합리적인 source 전략이며 **본 ADR은 이를 재확인하고 유지한다**. 결정 대상은 source가 아니라 **보관(retention) 계층**이다.

## 용량 기초 수치

- row 단가: 인덱스 정리 후 ~215B/row (테이블 120B + pk/uq 95B)
- 종목 1개·1년(NXT 720bar/일·252거래일): 1m 181,440행(60%) + 합산 8종 120,960행(40%) + 일봉계열 ~310행 ≈ **30.3만 행 ≈ 65MB/종목·년**

| 유니버스 | 전부 영구 (5년) | 1m 6개월+합산 영구 (5년) | 계층 retention (Case C) |
|---|---:|---:|---:|
| 20종목 | ~6.5GB | ~3.2GB | ~1.6GB |
| 100종목 | ~32GB | ~16GB | ~8GB |
| 200종목 | ~65GB | ~32GB | ~16GB |
| 500종목 | ~163GB | ~80GB | ~40GB |

핵심: 한계는 디스크보다 **인덱스 hot page의 메모리 이탈**(조회·upsert 동시 열화)이 먼저 오며, 200종목 × 1~2년 시점으로 추정된다. retention은 디스크 절약이 아니라 조회 성능 유지 장치다.

## 검토한 대안(Options)

| 케이스 | 내용 | 장점 | 단점 |
|------|------|------|------|
| **A. 현상 유지** | retention 없이 전부 영구 | 코드 변경 0. ~30종목이면 5년 무난 | 유니버스 확장 시 실매매 한창 시점에 한계 도달 — 그때 마이그레이션 비용 최대 |
| **B. 일괄 6개월** | 1m+합산봉 전부 6개월 삭제 | 규칙 단순, 정합("합산봉=항상 재생성 가능") 유지 | 6개월 이전 중간봉 백테스트 불가 — 전략 검증 lookback(1~3년)이 인질 |
| **C. 계층 retention** ★권고 | 1m 6개월(admin 1년) / 3m·5m 2년 / 15m~12h 영구 / 일봉계열 영구 | 500종목에도 ~40GB 수렴. 백테스트 lookback: 1m 6개월·3/5m 2년·15m↑ 무제한. ADR 0105 "1년 초과 복구 불가 인정" 원칙의 자연 연장 | "1m 부재 구간 합산봉 = 재생성 불가, 보관본이 권위" 규칙 신설 필요. cleanup cron + readiness retention 경계 인지 |
| **D. 합산봉 비영속** | 조회 시마다 1m 합산 | 저장 40% 절약 | 스크리너/백테스트가 장중 1m 풀스캔 — 읽기 경합으로 전이. ADR 0062 정면 반전. 가장 싼 자원(디스크) 아끼고 가장 비싼 자원(장중 CPU) 지불. **기각** |
| **E. ts 월별 파티셔닝 (+Timescale 옵션)** | declarative range partition, retention=DROP PARTITION | 100M+행에서도 파티션별 인덱스 소형 유지. 삭제 무부하 | 테이블 재작성 마이그레이션 + JPA 검증 + 운영 학습 비용. **현 규모에선 과잉** |

## 결정(Decision)

**결정 보류** — 사용자(최고 관리자) 지시 2026-06-06. 본 ADR은 케이스 분석의 기록이며, retention 채택 시점에 본 ADR을 Accepted로 갱신하거나 후속 ADR을 발행한다.

단, 케이스 선택과 무관한 **무위험 결함 정정 1건은 즉시 집행**한다 (사용자 승인 2026-06-06):

- **V69**: `idx_candle_code_interval_ts` drop — V1에서 UNIQUE 제약과 함께 태어난 중복 인덱스, 실측 scan 0회, 167MB. btree 양방향 스캔으로 uq가 DESC 조회 커버.

## 결과(Consequences)

- 긍정: V69로 디스크 167MB 회수 + candle 쓰기 인덱스 유지 비용 4→3. 케이스 분석이 기록으로 남아 유니버스 확장 결정 시 즉시 참조 가능.
- 부정: retention 미결정 상태 지속 — cleanup 집행 코드는 여전히 0건 (stomp_event 7일·market_index_snapshot 90일 포함, 감사 보고서 P3).
- 후속 과제:
  1. `idx_candle_code_interval_ts_v8` drop 여부 — uq(23.2M scans)와 기능 중복이나 외부 리뷰(candle-operating-policy-options.md)는 keep 의견. **1~2주 pg_stat 관찰 후** v8 scan이 uq로 수렴하면 V70에서 drop (추가 167MB).
  2. retention 케이스 결정 (권고: Case C) → cleanup 스케줄러 구현.
  3. `StrategyController.kt` `findByCodeAndInterval().takeLast(n)` 전량 fetch → `LIMIT n` 쿼리 교체 (보류 승인됨).
  4. candle `fillfactor=90` — HOT 3.3% 회복 (보류 승인됨).
  5. Case E(파티셔닝)는 **유니버스 100종목 돌파 가시화 시점**에 재검토 트리거.
