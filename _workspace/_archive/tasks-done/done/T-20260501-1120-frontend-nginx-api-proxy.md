---
id: T-20260501-1120-frontend-nginx-api-proxy
agent: frontend
model: sonnet
branch: hotfix/frontend-nginx-proxy
status: DONE
startedAt: 2026-05-01T02:15:00.000Z
completedAt: 2026-05-01T02:30:00.000Z
pr: #40
parentPhase: F_R2_QA_HOTFIX
---

# 목표
frontend nginx에 `/api`·`/actuator`·`/ws` upstream proxy 추가. SPA fallback이 `/api/*` 요청을 가로채 HTML 응답하던 문제(차트·시세 미동작 진짜 원인) 해소 + backtest 경로 mismatch(`/backtest/run`→`/backtest`, ISSUE-001) 정정.

## 결과물 체크리스트
- [x] nginx.conf 에 `/api`·`/actuator`·`/ws` location upstream → api:8080
- [x] frontend client.ts `/backtest/run` → `/backtest`
- [x] docker compose 재빌드 후 candles 89봉 응답 + STOMP 핸드셰이크 정상 검증

## 의존성
- (없음)

## 마지막 활동
2026-05-01T02:30:00.000Z — main rebase 머지 완료.

## 비고
회고 정정 — task.mjs start 누락분 사후 작성. ISSUE-001 + 통합 QA에서 발견된 진짜 P1(차트 미동작) 해소. 사용자 검증 todo는 README 운영 함정 경고와 함께 안내.
