---
id: T-20260622-1611-autopilot-dormant-strategies
agent: strategy
model: opus
branch: agent/strategy/autopilot-dormant-strategies
status: DONE
startedAt: 2026-06-22T16:11:57.440+09:00
completedAt: 2026-06-22T16:14:50.549+09:00
pr: #1101
parentPhase: ?
---

# 목표
autopilot 휴면 전략 101/102/103 진단

## 결과물 체크리스트
- [x] 진단표
- [x] hypotheses 레지스트리 동기화

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-22T16:11:57.446+09:00 — 작업 시작

## 비고

### 진단 결론 (실데이터 기반 — 추측 0)
오늘(2026-06-22) 5m 5m·3m 라이브 캔들 500봉 replay + signal/risk_event/order_request DB 실증.

**핵심: 101·102·103 DSL entry 조건은 건강하다. 휴면은 DSL이 아니라 신호 시점 지연 + risk gateway 슬롯/풀 경합 때문이다.**

| 전략 | entry 발화? | signal BUY | risk_event 차단 | 진짜 원인 | 조치 |
|------|------------|-----------|----------------|----------|------|
| 101 pullback | 거의 안 함(today 1건) | 1 | BLOCKED | 약세장(7/8 종목 하락) → ma20>ma50 상승추세 조건 정당 미충족 | **보존**(정당 휴면) |
| 102 fvg | 빈번(005930 9·000660 36 today true) | 10 | MAX_CONCURRENT_POSITIONS 10 BLOCKED | 신호 13:50+ 발생 시 슬롯3 이미 선점 | **보존**(DSL 정상, 슬롯 경합) |
| 103 ob | 빈번(005930 32·000660 54 today true) | 14 | POSITION_LIMIT/MAX_CONCURRENT BLOCKED | 신호 13:40+ 발생 시 슬롯3 이미 선점 | **보존**(DSL 정상, 슬롯 경합) |

근거:
- risk_event: 101·102·103 BUY signal **전량 BLOCKED**(MAX_CONCURRENT_POSITIONS 10·POSITION_LIMIT 15). order_request 0건.
- 발화군 100·104·105가 11:57~13:00에 슬롯3 선점 → 102·103 첫 신호(13:40/13:50)는 그 후라 전량 차단.
- ob lookback 단축(sw20→10) replay: 첫 발화 시점 불변(13:40) → warmup 아닌 BOS 시점(약세장 늦은 상승돌파)이 결정. 파라미터 수정 무효 + 005930 0건화 위험 → **반려**.
- DSL 임계 완화는 약세장 칼받기 매수만 유발 → 억지 발화 회피 원칙으로 **반려**.

**구조적 근본(strategy-engine 영역 밖)**: 장중 intraday 캔들 backfill이 10:25에야 시작(09:00 개장 후 1.5h 지연) + 5m/3m 6/13~6/19 데이터 갭 → 오전 내내 fvg/ob zone 미형성. market-data backfill 스케줄·autopilot 슬롯 정책(오케스트라/backend-core)에 인계.

→ **코드/DSL/파라미터 변경 0건**. hypotheses 레지스트리에 진단 §추가만(다양성 손실은 구조적이며 정당, 억지 발화 회피).
