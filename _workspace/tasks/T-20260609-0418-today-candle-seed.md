---
id: T-20260609-0418-today-candle-seed
agent: market-data
model: opus
branch: agent/market-data/today-candle-seed
status: DONE
startedAt: 2026-06-09T04:18:47.696+09:00
completedAt: 2026-06-09T04:31:25.798+09:00
pr: #833
parentPhase: ?
---

# 목표
candle:today:{code}:1d Redis Hash를 구독 수립 시점에 KIS inquire-price 공식값(open/high/low/close/volume)으로 시드/재보정 — 늦은/간헐 구독에도 화면·전략이 정확한 당일 시가·고저·거래량을 읽도록

## 결과물 체크리스트
- [x] 구독 시점 today-Hash 시드 hook (owner 무관)
- [x] KIS inquire-price 공식 open/high/low/volume fetch
- [x] WS tick volume 이중계상 차단(공식 ACML_VOL 권위)
- [x] 핵심 단위테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-09T04:18:47.711+09:00 — 작업 시작

## 비고
