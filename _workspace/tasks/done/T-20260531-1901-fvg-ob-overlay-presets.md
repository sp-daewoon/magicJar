---
id: T-20260531-1901-fvg-ob-overlay-presets
agent: orchestrator
model: opus
branch: codex/fvg-ob-overlay-presets
status: DONE
startedAt: 2026-05-31T19:01:41.027+09:00
completedAt: 2026-05-31T19:21:01+09:00
pr: null
parentPhase: ?
---

# 목표
FVG/OrderBlock strategy presets and TradingView-like chart zone overlay

## 결과물 체크리스트
- [x] largest 1m data baseline
- [x] strategy preset candidates
- [x] FVG/OB chart overlay
- [x] focused tests and docs
- [x] v3.10.1 patch release notes

## 의존성
- 1분봉 실데이터 기준: watchlist 최다 보유 종목은 `000660` SK하이닉스 21,392 rows (`2026-04-09 08:00+09` ~ `2026-05-29 19:59+09`).
- 최신 1,000개 1분봉 단순 FVG 후보: bullish 79건, bearish 44건.

## 마지막 활동
2026-05-31T19:21:01+09:00 — v3.10.1 patch 1 문서 표기 및 검증 정리

## 비고
- 방향 영향: 기존 KIS-first/DB candle/indicator endpoint/strategy DSL 흐름 위에 보수적으로 확장. 주문·자본 흐름 변경 없음.
- UI 검증: Playwright mock API로 `/strategy/builder` 프리셋 후보와 `/symbol/000660` FVG/OB overlay 렌더 확인 (`FVG BULL` 1, `OB BEAR` 1).
