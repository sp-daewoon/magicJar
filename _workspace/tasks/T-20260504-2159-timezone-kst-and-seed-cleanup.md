---
id: T-20260504-2159-timezone-kst-and-seed-cleanup
agent: architect
model: opus
branch: agent/architect/timezone-kst-and-seed-cleanup
status: RUNNING
startedAt: 2026-05-04T12:59:14.333Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
타임존 KST 통일 + V3·V7 시드 폐기 (instrument·candle 마스터 sync 정공법)

## 결과물 체크리스트
- [x] docker-compose 모든 컨테이너 TZ=Asia/Seoul
- [x] JVM -Duser.timezone=Asia/Seoul
- [x] postgres PGTZ + initdb timezone
- [x] V3·V7 Flyway 시드 폐기
- [x] master sync 가이드
- [x] 컨테이너 재부팅 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T12:59:14.340Z — 작업 시작

## 비고
