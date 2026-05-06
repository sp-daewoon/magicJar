# ADR 0025: LWC 확장 + 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 결정(2026-05-03 default 12 §10) 정합
- master §위치: `docs/architecture/master-architecture.md` v2 §D.16, §9
- 트랙: T-LWC (LWC extension, W5)
- 선행: ADR 0022 (Frontend SPA — LWC), ADR 0020

---

## 1. 맥락

이전 ADR 0022 §"커스텀 지양 원칙"은 "TradingView Advanced Charts로 후속 전환 가능"을 명시했다. 단:

1. TradingView Charting Library는 결제만으로 안 됨 — **별도 신청·승인 절차** 필요. 무료 신청도 검토 거절 가능.
2. Charting Library 라이선스는 magicJar의 1인 사용자 단계에서 실현성 낮음.
3. magicJar 비전에 "자체 도형·주석·패턴 시각화" 요구 — TradingView Advanced 의존하면 LWC 위에 한 번 더 추상화.

사용자 결정(default 12 §10): "사전 정의 도형 1차 (추세선·수평선·박스 + Markers). 풀 free draw는 후속".

## 2. 결정

### 2-1. TradingView Advanced 폐기

- ADR 0022 §"커스텀 지양 원칙"의 "TradingView Advanced 후속 트리거" 부분 무효
- 대신 LWC v4 공식 API + **자체 overlay layer** (Canvas 또는 SVG over LWC chart)

### 2-2. LWC 확장 — 자체 overlay layer

```
frontend/src/components/chart/
├── TradingChart.tsx         # LWC v4 main (현행)
├── overlay/
│   ├── ShapeOverlay.tsx     # 추세선·수평선·박스 (사전 정의)
│   ├── AnnotationOverlay.tsx # 텍스트 주석
│   ├── PatternOverlay.tsx   # T-PAT 패턴 마커 (Bullish Engulfing 등)
│   └── MarkerOverlay.tsx    # signal·order 마커 (LWC Markers API)
├── controls/
│   ├── ShapeToolbox.tsx     # 도형 추가 toolbox
│   └── ReplayControls.tsx   # 시점 거슬러 재생
└── lib/
    ├── canvasOverlay.ts     # LWC time/price coordinate ↔ canvas pixel 변환
    └── shapeStore.ts        # Zustand store — 사용자 그린 도형 영속 (localStorage + post-MVP DB)
```

### 2-3. 1차 깊이 (W5 T-LWC R1·R2)

| 기능 | R1 | R2 | 후속 (post-W5) |
|---|---|---|---|
| 추세선 (2점 클릭) | ✓ | | |
| 수평선 (가격 클릭) | ✓ | | |
| 박스 (사각형 영역) | ✓ | | |
| 텍스트 주석 | | ✓ | |
| 패턴 마커 (T-PAT 자동) | ✓ | | |
| 시그널 마커 (T-SIG) | ✓ | | |
| 주문/체결 마커 (T-O) | ✓ | | |
| 다중 timeframe 동기 차트 (D + 60m) | | ✓ | |
| 종목 비교 overlay | | ✓ | |
| Replay (시점 시그널 재생) | | ✓ | |
| 자유 그리기 (free draw) | | | post-MVP |
| Custom indicator script | | | post-MVP |

### 2-4. 좌표 변환

LWC v4 공식 API:
- `chart.timeScale().timeToCoordinate(time)` → x pixel
- `series.priceToCoordinate(price)` → y pixel
- 역변환: `coordinateToTime`·`coordinateToPrice`

이 좌표를 canvas overlay에 사용. LWC chart resize·zoom 시 chart event 구독해 overlay 재그리기.

### 2-5. 영속

사용자가 그린 도형:
- 1차: localStorage (per-symbol) — 빠른 dogfooding
- post-MVP: `chart_drawing` 테이블 (DB) — 다기기 동기화

## 3. 결과·영향

- **LWC v4 외부 lib 0건** — 사용자 비전 정합 (PR #31에서 LWC 공식 API 한정 검증 통과 정합)
- TradingView Advanced 도입 시점 결정 부담 0
- 풀 GUI는 W5 T-LWC에서 점진 — W2 트랙(T-SB Strategy Builder UI)과는 별도

## 4. 검토 대안

- **(a) TradingView Charting Library 신청** — 라이선스 실현성 낮음, 시간·비용 부담.
- **(b) ApexCharts·Highcharts 등 다른 lib** — LWC v4 보다 무거움, 시계열 차트 특화 부족.
- **(c) LWC 확장 + 자체 overlay layer (채택)** — 사용자 비전 + 실용성 정합.

## 5. References

- master-architecture.md §D.16, §9
- ADR 0022 (Frontend SPA — LWC)
- ADR 0020 (KIS Strategy Builder — UI 분리)
- LWC v4 https://tradingview.github.io/lightweight-charts/
- TradingView Charting Library 라이선스: https://www.tradingview.com/charting-library-docs/
