---
id: T-20260612-1554-admin-realtime-status-ui
agent: frontend
model: opus
branch: agent/frontend/admin-realtime-status-ui
status: RUNNING
startedAt: 2026-06-12T15:54:49.099+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
실시간 구독 상태 관리자 화면 /admin/realtime 구축 — GET /api/admin/realtime-status 소비, WS·slot·구독·캔들신선도 + 5초 auto-refresh + 장애 판독 힌트

## 결과물 체크리스트
- [x] api 클라이언트+타입
- [x] useRealtimeStatus hook
- [x] RealtimeStatusPage + 하위 컴포넌트
- [x] 네비 운영 그룹
- [x] 컴포넌트 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T16:35:00+09:00 — 진행분 검토·완성. 컴포넌트 테스트 16건 신규(realtime-status.test.tsx). pnpm typecheck·build·전체 vitest(99 파일 649 tests) GREEN. CHANGELOG row + PR.

## 비고
- 소비 계약 LOCK 1:1 매핑(RealtimeStatus 필드명 추측 0): updatedAt·stale·ws·slots·subscriptions·executionNotice·candleFreshness.
- /admin/realtime 라우트 + AppHeader 운영 그룹 신설(실시간 상태·시스템 이벤트). /system은 기존 알림센터 드롭다운 링크에 더해 NAV에도 노출(add-only).
- 5초 auto-refresh(refetchIntervalInBackground=false) + 수동 새로고침. mock 0 — 빈/에러 정직 표기.
- backend 0 touch(GET /api/admin/realtime-status는 market-data 병렬 구현). 기존 화면 불변.
