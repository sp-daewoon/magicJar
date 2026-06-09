---
id: T-20260610-0930-backtest-sizing-alignment-adr
agent: architect
model: opus
branch: agent/architect/backtest-sizing-alignment-adr
status: DONE
startedAt: 2026-06-10T09:30:00.000+09:00
completedAt: 2026-06-10T09:55:00.000+09:00
pr: TBD
parentPhase: ?
---

# 목표
백테스트 사이징 정합 ADR + G3 게이트 재정의 + (부속) 세션함수 DSL 확장 spec

## 결과물 체크리스트
- [x] ADR 0121 — 백테스트 사이징 정합 + G3 재정의
- [x] fractional sizing 계약 (SizingPolicy.FixedFractional 필드·타입·default)
- [x] G3 새 정의 (FixedFractional 10%/5 기준 MDD ≤ 12% + 근거)
- [x] V78 재검증 계획 + 판정 규칙 LOCK
- [x] 세션함수 DSL 확장 spec (VWAP·ORB·tod_RVOL — R-later)
- [x] CHANGELOG 슬롯 사전 발행 (ADR row + Wave 2/3 anchor)

## 의존성
- 입력: _workspace/strategy/2026-06-10-v77-preset-revalidation-report.md (#865)
- 후속 구현: strategy-engine (Wave 2 fractional sizing · Wave 3 V78)

## 마지막 활동
2026-06-10 — ADR 0121 + 세션함수 spec + README/CHANGELOG 갱신 완료. md only.

## 비고
- task.mjs start가 classifier 일시 불가로 실행 불가 → 본 task 파일 수동 생성 (헌법 §1-C 머지 PR 포함 의무 충족).
- 자원 audit: Flyway/REST/ENV/Kafka/STOMP 신규 0. V77 미점유 확인(최신 V76).
