---
name: magicjar-trading-journal
description: magicJar의 매매일지(Trading Journal)를 설계·구현·확장·수정한다. 모든 매매(백테스트·모의·실전) 발생 시 진입/청산 근거·지표 스냅샷·AI 복기 설명·사용자 코멘트를 영속화하고, 월간/전략별 리포트와 검색을 제공한다. "매매일지 만들어줘", "거래 기록 남겨", "복기 노트", "매매 복기", "저널 페이지 추가", "일지 기능 확장"도 포함.
---

# magicjar-trading-journal — 매매일지

모든 주문과 청산은 **자동으로 일지에 쌓인다.** 사용자는 기록을 읽고, 감정/반성 코멘트를 달고, AI 복기를 요청한다. 매매일지는 "왜 이 매매를 했는가"를 미래에 알려주는 자산이다.

## 핵심 요구사항

1. **자동 기록** — 주문 체결(Order 상태 FILLED) 이벤트가 발생하면 **어느 모드이든** 일지 엔트리가 생성된다.
2. **복기 가능성** — 진입 시점의 지표 스냅샷·적용 전략 ID·AI 전략 설명이 함께 저장된다.
3. **사용자 메모** — 감정·교훈을 사용자가 자유 텍스트로 추가할 수 있다.
4. **AI 복기** — 청산 완료 후 ai-engineer가 "예상 vs 실제" 비교 문장을 자동 생성(선택).
5. **검색/집계** — 기간·종목·전략·결과(수익/손실)·태그로 필터. 월간 리포트 생성.

## 도메인 모델

`core/src/main/java/kr/spdaewoon/magicjar/core/domain/journal/`

```java
record TradeJournalEntry(
    UUID id,
    String tradeId,              // 내부 거래 체인 ID (진입~청산 묶음)
    Mode mode,                   // BACKTEST | MOCK | REAL
    String code, String name,
    Side side,                   // BUY | SELL (진입 방향)
    Instant entryAt, BigDecimal entryPrice, int entryQty,
    Instant exitAt,  BigDecimal exitPrice, int exitQty,  // 미청산은 null
    BigDecimal pnl, BigDecimal pnlRate,                  // 청산 후에만
    String strategyId,                                   // 예: "rsi_oversold_v1"
    Map<String, BigDecimal> entrySnapshot,               // {"RSI14": 28.3, "SMA20": 71200, ...}
    Map<String, BigDecimal> exitSnapshot,
    String entryReason,                                  // 조건식 or 수동 사유
    String exitReason,
    @Nullable String aiExplanation,                      // 진입 직후 생성된 ai-engineer 설명
    @Nullable String aiReview,                           // 청산 후 ai-engineer 복기
    List<String> tags,                                   // ["breakout","news_driven","mistake",...]
    @Nullable String userNote                            // 사용자 수기 메모
) {}
```

## 저장소

- 테이블 `trade_journal`: 주요 컬럼 인덱스(`code`, `strategy_id`, `mode`, `entry_at`)
- `entrySnapshot`/`exitSnapshot`은 JSONB 컬럼
- `tags`는 별도 `trade_journal_tag` 조인 테이블 또는 PostgreSQL `text[]`
- Flyway 마이그레이션: `V2__trade_journal.sql`

## 기록 트리거

- **백테스트 모드:** strategy-engine이 시뮬레이션 루프 안에서 각 진입/청산에 대해 엔트리 생성.
- **모의/실전 모드:** Order 체결 이벤트(Kafka/Spring Application Event)를 구독하여 엔트리 upsert.
- AI 설명 생성은 비동기(별도 큐) — 주문 경로를 막지 않는다.

## REST API (frontend 소비)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/journal` | 목록 (페이지·필터) |
| GET | `/api/journal/{id}` | 상세 |
| PATCH | `/api/journal/{id}` | `userNote`, `tags` 수정 |
| POST | `/api/journal/{id}/ai-review` | AI 복기 재요청 |
| GET | `/api/journal/reports/monthly?month=YYYY-MM` | 월간 집계 |

요청/응답 DTO는 `_workspace/strategy/trading-journal-api.md` 에 정리.

## UI (frontend, magicjar-dashboard와 연동)

- `/journal` — 테이블 뷰(날짜, 종목, 전략, 손익, 태그), 필터 사이드바
- `/journal/{id}` — 상세(진입/청산 차트 마커, 지표 스냅샷, AI 설명/복기, 사용자 메모 편집)
- `/journal/reports` — 월간 수익률·승률·전략별 성과 차트

## AI 복기 프롬프트 (ai-engineer 연계)

입력: `TradeJournalEntry`의 진입·청산 정보 + 해당 기간 뉴스 샘플.
출력 스키마:
```json
{
  "expectationReview": "진입 시 가정한 시나리오 vs 실제 전개 비교 (2~4문장)",
  "lessonLearned": "교훈 한 줄",
  "tagsSuggested": ["noise_trade","news_reaction",...],
  "reasoning": "..."
}
```

프롬프트 파일: `resources/prompts/journal/review-v1.st` (ai-engineer가 관리).

## 매매일지가 아닌 것

- 주문 라우팅 로그 (→ 별도 `order_audit`)
- 시스템 디버그 로그 (→ 로깅 인프라)

## 재실행/업데이트 지침

- 기존 엔트리는 불변. 수정은 `userNote`/`tags`만. 잘못된 기록 삭제는 soft-delete (`deleted_at`).
- 스키마 변경은 하위호환 필드 추가 우선, 제거는 마이그레이션 스크립트 동반.
- AI 복기 프롬프트 버전은 엔트리의 `aiReviewPromptVersion` 필드로 추적.
