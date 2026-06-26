ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS trailing_stop_pct NUMERIC(9, 4);

COMMENT ON COLUMN strategy.trailing_stop_pct IS 'Trailing-stop exit threshold in percent (high-watermark drawdown). Position-aware exit lever — peer of take_profit_pct/fixed_stop_loss_pct. nullable: null = trailing disabled (zero-regression for existing strategies). ADR 0135.';
