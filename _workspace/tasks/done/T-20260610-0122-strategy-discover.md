---
id: T-20260610-0122-strategy-discover
agent: strategy
model: opus
branch: agent/strategy-engine/discover-perf-atr-fix
status: DONE
startedAt: 2026-06-10T01:22:10.403+09:00
completedAt: 2026-06-10T01:32:23.583+09:00
pr: #856
parentPhase: 발굴 스크리너 성능·ATR
---

# 목표
발굴 스크리너 2건 — (A) `POST /api/screener/daytrade-discover` 응답 ~28s 성능 개선 + (B) ATR 점수 0.0 점검·수정. 둘 다 스크리너 scoring 경로라 한 PR.

## 결과물 체크리스트
- [x] (B) ATR 점수 0.0 원인 규명 (버그 여부 단정 + 실측 근거)
- [x] (B) ATR scoring 수정 (smoothed NATR + 단타 적합 sweet-spot 재보정)
- [x] (A) perf 진단 + 캐싱 최적화 (presetKey·filter·interval·limit·tradingDay 키, userId 무관)
- [x] 테스트 (캐시 동작 + ATR 비-0 검증)

## 의존성
- ScreenerEngineImpl / DaytradeDiscoverService / MultiCriteriaRanker / IndicatorVolatility 읽음
- RedisScreenerCacheAdapter 패턴 재사용

## 마지막 활동
2026-06-10 — 작업 시작 + 코드 파악 + DB 실측 ATR 진단 완료

## 비고
실측: 089030(테크윙) 마지막 일봉 (high-low)/close = 0.224 > ATR_MAX(0.20) → sweet-spot 0.0 clamp. 단일 캔들 proxy 노이즈 = 근본 원인. 14일 smoothed NATR = 0.0986로 정상.
