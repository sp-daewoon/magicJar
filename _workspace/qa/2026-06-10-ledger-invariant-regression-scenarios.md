# 원장 키 사슬 불변식 회귀 시나리오 LOCK (qa)

- **작성**: qa "품질 헌터" (2026-06-10)
- **권위 SoT**: `_workspace/specs/2026-06-10-execution-ledger-integrity-architecture.md` §2-B-2 불변식 5건 · ADR 0115 §2-P2
- **트리거**: ADR 0115 §2-P2 키 사슬 불변식 #5 — 회귀 시나리오 LOCK (최고 관리자 승인 2026-06-10)
- **incident 근거**: 2026-06-10 주문 #219(15주 부분체결 6건 중 5주 미귀속)·#220(취소-체결 race)·#223 — `order_request → order_execution → strategy_position` 흐름
- **테스트 산출물**: `consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/persistence/LedgerInvariantScenarioTest.kt` (7 케이스, 전부 GREEN)

---

## 1. 최고 관리자 "실격" 정의 → 검증 가능 불변식

> 최고 관리자 원문(2026-06-10): *"매도했다는데 포지션이 남아있고 매수했다는데 포지션이 안 보이면 자동매매 시스템 실격."*

본 회귀 시나리오는 그 두 "실격" 상태를 자동 재현 테스트로 못박는다. 핵심 통찰: 두 실격 모두 `order_execution`(immutable append-only 진실원) → `strategy_position`(mutable 증분 projection) 사이의 **증분 누락**으로 발생하며, **rebuild**(대안 B — order_execution 진실원 전량 재계산)로 회복된다. 따라서 각 시나리오는 **(결함 재현) → (rebuild 회복)** 의 완전 사이클로 LOCK 한다.

---

## 2. 시나리오 ↔ 불변식 매핑 표

spec §2-B-2 합격 불변식 5건과 본 회귀 테스트의 매핑:

| 불변식 (spec §2-B-2) | 요지 | S-A 커버 | S-B 커버 | 검증 테스트 |
|---------------------|------|:-------:|:-------:|------------|
| **#1** 모든 주문 키 완전성 | 0-sentinel 폐지 + OrderTrigger enum | — | — | **직교** (R2 OrderTrigger·Flyway V80 트랙 — 본 회귀 범위 밖) |
| **#2** 모든 체결 정확히 1회 귀속 | 부분체결 N건이면 귀속도 N회, eventId 멱등 | ✅ | ✅ | `S-B 정상경로`(6건→15주) · `S-B 이중경로 멱등`(같은 eventId 1회) · `S-A 정상경로`(SELL 분할 전량) |
| **#3** 검산 가능성 | `Σ(strategy_position 귀속) == KIS 권위 position` | ✅ | ✅ | `S-A 체결유실 변형`(rebuild→authority 0 일치) · `S-B replay-skip 결함`(rebuild→authority 15 일치) · `S-A 교차검산 가드`(불일치 시 미적용) |
| **#4** 화면 추적성 | 시그널→주문→체결→보유 키 join 추적 | — | — | **직교** (frontend 후속 트랙 — UI 계약) |
| **#5** 회귀 시나리오 LOCK | qa E2E 의무 2종 | ✅ | ✅ | **본 문서 전체 — S-A·S-B 7 케이스** |

**결론**: 본 회귀는 불변식 **#2·#3·#5** 를 직접 LOCK 한다. #1·#4 는 spec §2-B-3 직교 매트릭스대로 별도 트랙(R2 키 스키마 / frontend UI).

---

## 3. 시나리오 S-A — "매도 체결 후 전략 포지션 잔존 0건"

> incident: 주문 #220 (취소-체결 race) / 일반 SELL 체결통보 유실. "매도했는데 포지션 남음" 실격.

| 케이스 | 단계 | 기대 | 불변식 |
|--------|------|------|--------|
| `S-A 정상경로` | BUY 10주 → SELL 부분체결 4+3+3=10 전량 청산 | strategy_position **0주** · 전량청산 reset(평단·filledLots 0) · realized 29,000 보존 | #2 |
| `S-A 체결유실 변형` | BUY 10 → SELL 4+3 반영, 마지막 3주 SELL **유실**(잔존 3주 = 실격) → 진실원(SELL 10 전체)·KIS 권위 0주로 **rebuild** | rebuild applied · strategy_position **0주 회복** · realized 29,000 정합 | #2·#3·#5 |
| `S-A 교차검산 가드` | 잔존 3주 + 진실원 자체도 SELL 7주만 보존(rebuild=3주) vs KIS 권위 0주 → 불일치 | rebuild **미적용**(false) · 기존 상태 유지 (없는 물량 인식 risk 0 — KIS 권위 우선 LOCK) | #3 |

**핵심**: 체결 유실로 실격 상태(잔존 3주)가 *발생할 수 있음*을 재현한 뒤, rebuild 가 진실원에서 전량 재계산 + KIS 권위 교차검산을 통과할 때만 0주로 회복함을 LOCK. 진실원 자체가 불완전하면(교차검산 불일치) 함부로 덮어쓰지 않는 보수 가드도 동반 LOCK.

---

## 4. 시나리오 S-B — "매수 체결 후 전략 포지션 미표시 0건"

> incident: 주문 #219·#223 (15주 부분체결 6건 중 5주 replay-skip 미귀속). "매수했는데 포지션 안 보임" 실격.

| 케이스 | 단계 | 기대 | 불변식 |
|--------|------|------|--------|
| `S-B 정상경로` | BUY 부분체결 6건(1+3+5+1+2+3) 모두 귀속 | strategy_position **15주** · filledLots 6 | #2 |
| `S-B replay-skip 결함 재현` | 6건 중 4건(1+3+5+1=10)만 귀속, 2+3주 **미귀속**(미표시 = 실격) → 진실원(6건 전체)·KIS 권위 15주로 **rebuild** | rebuild applied · strategy_position **15주 전량 회복** · 평단 70,000 · filledLots 6 | #2·#3·#5 |
| `S-B 이중경로 멱등` | 진실원에 같은 eventId 중복 보존(WS+backfill) → rebuild | eventId distinct dedup → 5주 1회만 반영(이중계상 0) · 기존과 동일 → over-write 0 | #2 |
| `S-B rebuild 토글 off` | `magicjar.position.ledger-rebuild.enabled=false` | rebuild **no-op**(false) · 미표시 10주 그대로 (롤백 경로 — drift 보정이 별도 회복) | rollback LOCK |

**핵심**: 부분체결 분할 수가 늘수록 미귀속 확률이 비례 증가하는 G4 구조 결함(spec §2-B-2 #2)을 재현한 뒤, rebuild 가 진실원 6건을 빠짐없이 fold 해 미표시 0건으로 회복함을 LOCK. 이중경로 멱등·토글 롤백까지 가드.

---

## 5. 레벨 결정 (qa 판단 근거)

| 레벨 | 담당 | 본 회귀의 위치 |
|------|------|---------------|
| **rebuild 순수 함수** | `StrategyPositionRebuildTest` (strategy-engine, backend-core R1) | fold 수식·멱등·clamp 단위 검증 — *기존* |
| **어댑터 upsert 수식** | `ConsumerExecutionStrategyPositionAdapterTest` (consumer, 기존) | applyBuy/applySell 평단·realized 단위 — *기존* |
| **★ incident 시나리오 통합** | `LedgerInvariantScenarioTest` (consumer, 본 PR) | **증분 결함 재현 → rebuild 회복** 의 완전 사이클을 어댑터 코드 경로 그대로 통과 — *신규* |

실제 DB 컨테이너 대신 어댑터의 port·repository 의존을 in-memory fake([InMemoryStrategyPositionPort]) + mock([StrategyRebuildExecutionRow] interface projection fake)으로 주입해 **어댑터 실제 코드 경로**(`applyExecution` 증분 + `rebuildStrategyPositionFromLedger` 회복)를 통과시킨다. JVM 단위·CI 무인프라로 매 `./gradlew check` 에서 항상 GREEN 보장 — mock 합성 0(실제 어댑터 로직·실제 rebuild 순수 함수 통과, fake 는 DB read 대체일 뿐).

**R2 충돌 회피**: consumer main 코드 0 touch — `consumer/src/test/` 디렉토리 + `_workspace/qa/` 문서만. R2(OrderTrigger enum·Flyway V80)와 파일 충돌 0.

---

## 6. Playwright UI 정합 시나리오 (skeleton — 후속)

불변식 #4(화면 추적성)는 frontend 후속 트랙이나, qa Visual 의무(헌법 §9-2) 정합을 위해 장중 전제 E2E skeleton 을 후속으로 발행한다:

- **시그널 내역 → 주문 → 체결 → 보유** 키 join 화면 추적: 사인 1건이 발사한 주문 수량·체결 수량·현재 그 전략 보유 수량이 화면에서 끊김 없이 추적되는지.
- **Positions 화면 정합**: strategy_position 표시 수량 == KIS 권위 position (실격 상태 화면 노출 0건).
- 장중 실제 백엔드 응답 전제(mock 합성 0 — `feedback_no_mock_data` 정합)라 **본 사이클은 데이터 키 사슬 회귀(S-A·S-B)까지 LOCK**, UI E2E 는 R2 OrderTrigger 키 완성 후 화면 join 가능 시점에 발행한다.

---

## 7. 검증 결과

```
./gradlew :consumer:test --tests "...LedgerInvariantScenarioTest"
BUILD SUCCESSFUL — 7 cases GREEN
  S-A 정상경로 / S-A 체결유실 변형 / S-A 교차검산 가드
  S-B 정상경로 / S-B replay-skip 결함 재현 / S-B 이중경로 멱등 / S-B rebuild 토글 off
```

전체 `./gradlew check` GREEN · ktlint GREEN. consumer main 0 touch.
