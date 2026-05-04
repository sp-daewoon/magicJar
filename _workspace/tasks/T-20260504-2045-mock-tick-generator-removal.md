---
id: T-20260504-2045-mock-tick-generator-removal
agent: architect
model: opus
branch: agent/architect/mock-tick-generator-removal
status: RUNNING
startedAt: 2026-05-04T11:45:37.544Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
MockTickGenerator 영구 제거 — 운영 main src의 mock 데이터 합성 영역 폐기 (사용자 정책 feedback_no_mock_data.md)

## 결과물 체크리스트
- [x] MockTickGenerator·Properties·Test 삭제
- [x] ConsumerApp import 정리
- [x] application-mock.yml mock-tick 섹션 제거
- [x] docker-compose env·default profile dev 정합
- [x] build-and-deploy.sh 정리
- [x] swagger 주석 정정
- [x] 컨테이너 재부팅 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T11:45:37.551Z — 작업 시작

## 비고
