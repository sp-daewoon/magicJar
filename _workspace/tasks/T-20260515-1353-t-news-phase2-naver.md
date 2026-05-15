---
id: T-20260515-1353-t-news-phase2-naver
agent: backend-core
model: sonnet
branch: agent/backend-core/t-news-phase2-naver
status: RUNNING
startedAt: 2026-05-15T13:53:57.920+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
T-NEWS Phase 2 — NaverNewsAdapter + Flyway V33 + NewsKafkaProducer + REST /news + WatchlistNewsScheduler (ADR 0075 Phase 2)

## 결과물 체크리스트
- [ ] V33__t_news_article_table.sql
- [ ] NaverNewsAdapter.kt
- [ ] NaverNewsRateLimiter.kt
- [ ] NewsArticleJpaEntity.kt
- [ ] NewsArticleJpaRepository.kt
- [ ] NewsKafkaProducer.kt
- [ ] SymbolNewsController.kt
- [ ] SymbolNewsService.kt
- [ ] WatchlistNewsScheduler.kt
- [ ] .env.example NAVER 항목 추가
- [ ] KafkaTopicConfig news.collected.v1 추가
- [ ] 단위 테스트 ~6건 GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-15T13:53:57.927+09:00 — 작업 시작

## 비고
