---
id: T-20260501-1140-ops-jar-staleness-guard
agent: architect
model: opus
branch: hotfix/ops-jar-staleness-and-qa-report
status: DONE
startedAt: 2026-05-01T02:30:00.000Z
completedAt: 2026-05-01T04:30:00.000Z
pr: #41
parentPhase: F_R2_QA_HOTFIX
---

# 목표
Docker 운영 함정(ISSUE-002 P1) hotfix — host gradle build 누락 시 옛 jar가 컨테이너에 들어가는 footgun 해소. `scripts/smoke.sh` jar staleness 가드 + `scripts/build-and-deploy.sh` 헬퍼 + README 운영 함정 경고 + `_workspace/qa/` 정식 신설(issues.md + report-20260501-1042.md).

## 결과물 체크리스트
- [x] scripts/smoke.sh — jar mtime 검사로 stale 시 fail-fast
- [x] scripts/build-and-deploy.sh — gradle build + docker compose build + up 일체화
- [x] README "빌드·실행" 섹션 운영 함정 경고 추가
- [x] _workspace/qa/issues.md — ISSUE-001~007 누적 카탈로그 신설
- [x] _workspace/qa/report-20260501-1042.md — 통합 QA 보고서 (CHANGES_REQUESTED + 후속 hotfix 4건 처방)

## 의존성
- (없음)

## 마지막 활동
2026-05-01T04:30:00.000Z — main rebase 머지 완료. R2 hotfix 4건 모두 머지 후 9 컨테이너 healthy 재검증.

## 비고
회고 정정 — task.mjs start 누락분 사후 작성. 사용자 환경에서 candles 404로 발현된 진짜 root cause(host build 누락 → V7 미적용) 해소. boundary-qa 스킬(ADR 0016 트리거 #6+#7)로 통합 QA 실시 후 처방.
