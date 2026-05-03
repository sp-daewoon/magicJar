-- V15: 백테스트 trade·equity_point 영속 (track9 R1)
--
-- backtest_run.summary_json은 metrics만 담는다. 상세는 별도 테이블에 적재.
--
-- - backtest_trade: 라운드트립 거래. (run_id, sequence) PK 대신 자체 trade_id 사용.
-- - backtest_equity_point: 봉 단위 자산곡선. 시계열 데이터.
--
-- 두 테이블 모두 run 삭제 시 CASCADE로 함께 삭제.

CREATE TABLE IF NOT EXISTS backtest_trade (
    trade_id          VARCHAR(64)   PRIMARY KEY,
    run_id            VARCHAR(64)   NOT NULL,
    symbol            VARCHAR(16)   NOT NULL,
    sequence          INTEGER       NOT NULL,
    entry_at          TIMESTAMPTZ   NOT NULL,
    exit_at           TIMESTAMPTZ   NOT NULL,
    entry_price       NUMERIC(18, 4) NOT NULL,
    exit_price        NUMERIC(18, 4) NOT NULL,
    shares            INTEGER       NOT NULL,
    pnl               NUMERIC(18, 4) NOT NULL,
    pnl_percent       NUMERIC(10, 8) NOT NULL,
    entry_commission  NUMERIC(18, 4) NOT NULL DEFAULT 0,
    exit_commission   NUMERIC(18, 4) NOT NULL DEFAULT 0,
    exit_tax          NUMERIC(18, 4) NOT NULL DEFAULT 0,
    exit_reason       VARCHAR(32)   NOT NULL DEFAULT 'SIGNAL_EXIT',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_backtest_trade_run
        FOREIGN KEY (run_id) REFERENCES backtest_run(run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_backtest_trade_run_seq
    ON backtest_trade (run_id, sequence);

CREATE INDEX IF NOT EXISTS idx_backtest_trade_run_entry
    ON backtest_trade (run_id, entry_at);


CREATE TABLE IF NOT EXISTS backtest_equity_point (
    run_id          VARCHAR(64)    NOT NULL,
    sequence        INTEGER        NOT NULL,
    at              TIMESTAMPTZ    NOT NULL,
    equity          NUMERIC(20, 4) NOT NULL,
    cash            NUMERIC(20, 4) NOT NULL,
    position_value  NUMERIC(20, 4) NOT NULL,
    PRIMARY KEY (run_id, sequence),
    CONSTRAINT fk_backtest_equity_run
        FOREIGN KEY (run_id) REFERENCES backtest_run(run_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_backtest_equity_run_at
    ON backtest_equity_point (run_id, at);


COMMENT ON TABLE backtest_trade IS
    '백테스트 라운드트립 거래. 1 run = N trades.';
COMMENT ON COLUMN backtest_trade.exit_reason IS
    'SIGNAL_EXIT | STOP_LOSS | TAKE_PROFIT | END_OF_DATA';
COMMENT ON COLUMN backtest_trade.pnl IS
    '비용(수수료·거래세) 차감 후 순손익(원).';

COMMENT ON TABLE backtest_equity_point IS
    '백테스트 자산곡선 시계열. 1 run = N points (봉 단위).';
COMMENT ON COLUMN backtest_equity_point.equity IS
    'mark-to-market 평가액 = cash + position_value.';
