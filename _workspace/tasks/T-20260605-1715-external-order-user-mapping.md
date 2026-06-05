---
id: T-20260605-1715-external-order-user-mapping
agent: backend-core
model: opus
branch: agent/backend-core/external-order-user-mapping
status: DONE
startedAt: 2026-06-05T17:15:56.528+09:00
completedAt: 2026-06-05T17:34:30.088+09:00
pr: #752
parentPhase: ?
---

# 목표
외부 HTS 주문 흡수 user 매핑·mode·source 오기록 fix

## 결과물 체크리스트
- [x] htsId→내부 userId 매핑
- [x] mode 계좌타입 정합
- [x] source EXTERNAL 분류
- [x] 데이터 보정 SQL
- [x] 알림 문구 개선
- [x] 회귀 테스트
- [x] CHANGELOG row

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-05T17:15:56.540+09:00 — 작업 시작

## 비고
