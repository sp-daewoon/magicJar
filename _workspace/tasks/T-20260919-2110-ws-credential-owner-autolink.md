---
id: T-20260919-2110-ws-credential-owner-autolink
agent: backend-core
model: opus
branch: agent/backend-core/ws-credential-owner-autolink
status: RUNNING
startedAt: 2026-09-19T21:10:30.127+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
WS credential owner 자동연결 — resolvedCredentialOwnerId(env) 비면 등록된 REAL credential 유일 시 자동 사용. 시세 approval은 REAL 고정 유지. env 의존 제거, '키 넣었는데 시세 안 옴' 해소. 다수 REAL이면 모호 → 기존처럼 skip(안전).

## 결과물 체크리스트
- [x] resolveWsCredentialOwnerId 헬퍼(유일 REAL 자동선택)
- [x] getApprovalKey/resolveSeedUserId 적용
- [x] 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-19T21:10:30.136+09:00 — 작업 시작

## 비고
