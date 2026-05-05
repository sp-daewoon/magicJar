# ADR 0012: 매매일지(Trading Journal) 아키텍처

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: strategy-engine, ai-engineer, backend-core, frontend)

## 맥락

사용자가 강조한 필수 기능: **모든 매매 자동 기록 + AI 복기 + 사용자 메모**. 매매일지는 단순 로그가 아니라 "왜 이 매매를 했는가"를 나중에 되짚어볼 수 있는 1급 도메인.

## 결정

### 데이터 모델 위치
`core` 모듈에 `TradeJournalEntry` 도메인 타입과 `TradeJournalRepository` 포트. 구현체는 `strategy` 모듈의 JPA 저장소.

### 기록 경로
- **백테스트 모드:** `strategy` 모듈이 시뮬레이션 루프 내에서 직접 엔트리를 생성.
- **모의/실전 모드:** `OrderSender`가 반환한 `OrderResult.FILLED` 이벤트를 Spring `ApplicationEvent`로 발행 → `strategy`의 `TradeJournalRecorder`가 구독하여 엔트리 upsert.
- **진입/청산 묶기:** `tradeId` (UUID)로 동일 종목 연속 포지션을 한 묶음으로 취급. 추가 매수(평단 갱신)는 같은 `tradeId`에 누적.

### AI 복기 경로
- 청산 완료 시점에 `ai` 모듈이 Spring Application Event로 복기 요청 수신 → LLM 호출 → 결과를 엔트리의 `aiReview` 필드에 async 업데이트.
- API 키 없거나 AI 비활성이면 skip, `aiReview=null`로 남김.

### 스키마 (요약)
필수 컬럼: `id`, `trade_id`, `mode`, `code`, `name`, `side`, `entry_at/price/qty`, `exit_at/price/qty`, `pnl`, `pnl_rate`, `strategy_id`, `entry_snapshot(JSONB)`, `exit_snapshot(JSONB)`, `entry_reason`, `exit_reason`, `ai_explanation`, `ai_review`, `tags(text[])`, `user_note`, `created_at`, `updated_at`, `deleted_at`.

인덱스: `(code, entry_at)`, `(strategy_id, entry_at)`, `(mode)`.

### 사용자 상호작용
- `frontend`가 `/journal`, `/journal/{id}`, `/journal/reports` 페이지 제공 (`magicjar-trading-journal` 스킬).
- `userNote`, `tags`만 수정 가능. 기록 자체는 불변.
- 잘못된 기록은 soft-delete (`deleted_at`).

## 결과

- 긍정: 진입·청산·AI·사용자 메모가 한 곳에 모이고, 전략 성능 분석의 단일 원천 소스
- 부정: JSONB 스냅샷이 쌓이면 저장량 증가 → 월간 크기 모니터링, 장기엔 주 단위 압축 검토
- 후속: strategy-engine이 `magicjar-trading-journal` 스킬로 구현. Flyway 마이그레이션 `V2__trade_journal.sql`.
