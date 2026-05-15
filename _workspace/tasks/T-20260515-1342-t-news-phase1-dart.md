---
id: T-20260515-1342-t-news-phase1-dart
agent: market-data
model: sonnet
branch: agent/market-data/t-news-phase1-dart
status: RUNNING
startedAt: 2026-05-15T13:42:39.335+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
T-NEWS Phase 1 — DART 1분 폴링 강화 + Flyway V32 disclosure 테이블 + Kafka disclosure.new.v1 + REST /disclosures (ADR 0075 Phase 1)

## 결과물 체크리스트
- [ ] V32 SQL
- [ ] DartDisclosureAdapter cron 1분
- [ ] DisclosureJpaEntity
- [ ] DisclosureJpaRepository
- [ ] DisclosureKafkaProducer
- [ ] SymbolDisclosureController
- [ ] SymbolDisclosureService
- [ ] KafkaTopicConfig 갱신
- [ ] 단위 테스트 ~5건

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-15T13:42:39.342+09:00 — 작업 시작

## 비고
