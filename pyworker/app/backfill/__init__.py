"""Yahoo Finance 분봉 backfill 패키지.

ADR 0048 candle-architecture v2 §C 영역.
KIS 분봉 API는 당일 분봉만 제공하므로 과거 분봉은 Yahoo Finance에서 backfill.

flow:
    POST /backfill/yahoo {code, interval, days}
    → YahooBackfillService.fetch_and_publish()
    → yfinance.Ticker(`{code}.KS|.KQ`).history()
    → DataFrame → list[CandleMinuteAggregatedEvent]
    → Kafka topic candle.minute.aggregated.v1 publish (Kotlin 호환 schema)
    → api CandleMinuteEventListener가 candle 테이블 INSERT (interval=M1)
"""
