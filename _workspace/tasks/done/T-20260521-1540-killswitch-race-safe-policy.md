---
id: T-20260521-1540-killswitch-race-safe-policy
agent: architect
model: opus
branch: agent/architect/killswitch-race-safe-policy
status: DONE
startedAt: 2026-05-21T15:40:09.895+09:00
completedAt: 2026-05-21T15:47:46.402+09:00
pr: #647
parentPhase: ?
---

# 목표
KillSwitch race-safe policy (A+B+C) — ADR 0043·0070 amend

## 결과물 체크리스트
- [x] ADR 0043 amend (§2-11) — A+B+C 본문 + dogfooding evidence + 트레이드오프 매트릭스 + 구현 위치
- [x] ADR 0070 amend (§2-11) — step 5·6 정정 + 권위 위임 cross-ref
- [x] README index amend row — §10-1 0043 Amended mark + §14 변경 이력 row

## 의존성
- ADR 0043 §2-11 권위 SoT (가드 규칙 본문)
- ADR 0070 §2-11 흐름 step (권위 위임 수신)
- 사용자 dogfooding 2026-05-21 09:43~11:05 false positive evidence

## 마지막 활동
2026-05-21T15:40:09.904+09:00 — 작업 시작
2026-05-21T16:00 — ADR 0043 §2-11 신설 (A+B+C 본문 + 트레이드오프 4 옵션 + 구현 위치 매트릭스)
2026-05-21T16:05 — ADR 0070 §2-11 step 5·6 amend + 권위 위임 cross-ref
2026-05-21T16:10 — README §10-1 0043 Amended mark + §14 변경 이력 row 추가

## 비고
사용자 본인 dogfooding(2026-05-21 09:43 매수 → 11:05 KillSwitch 자동 ON false positive) 결함 root cause 차단. A+B+C 3중 게이트 사용자 직접 정책 LOCK. 구현은 후속 strategy-engine T-RECON Phase 1 baseline 컴포넌트 책임 확장으로 dispatch.
