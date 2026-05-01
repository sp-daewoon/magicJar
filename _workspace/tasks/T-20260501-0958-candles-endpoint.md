---
id: T-20260501-0958-candles-endpoint
agent: backend-core
model: sonnet
branch: agent/backend-core/candles-endpoint
status: RUNNING
startedAt: 2026-05-01T00:58:19.267Z
completedAt: null
pr: null
parentPhase: F_R1_DONE_VERIFICATION
---

# 목표
F-R1-002: GET /api/instruments/{code}/candles 신규 + 시드 검증 — 005930 차트 실 데이터 표시

## 결과물 체크리스트
- [x] InstrumentController.candles GET 엔드포인트
- [x] 응답 DTO(CandleResponse) + 매퍼 (Candle.toResponse — KST 'YYYY-MM-DD' 변환)
- [x] CandleQueryService (application layer + DAY/D1 alias 매핑)
- [x] 통합 테스트 (InstrumentControllerTest 7 PASS — 신규 4건)
- [x] V7 Flyway 시드(005930 일봉 ~90 영업일, generate_series 동적 생성)
- [x] OpenAPI 어노테이션 (Operation/Parameter/ApiResponses + @Schema on DTO)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-01T01:02:24.670Z — 전체 결과물 완료 — :api:test 회귀 통과(InstrumentControllerTest 7 PASS, BacktestSmokeTest 3 PASS, JournalControllerTest 4 PASS 등)

## 비고
