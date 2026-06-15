#!/usr/bin/env python3
"""FINAL evidence — #86 vs the two chosen canary strategies on identical REAL DB candles (2026-03-01..now, regular session).

REPRODUCE (mock 0 — 실 DB candle 덤프 후 결정론 시뮬):
  mkdir -p /tmp/canary_sim
  for cv in "005930:1m" "005930:5m" "000660:1m" "000660:5m"; do
    code="${cv%%:*}"; iv="${cv##*:}";
    docker exec magicjar-postgres psql -U magicjar -d magicjar -At -F',' -c \
      "SELECT to_char(ts AT TIME ZONE 'Asia/Seoul','YYYY-MM-DD\"T\"HH24:MI'), open_raw, high_raw, low_raw, close_raw, volume \
       FROM candle WHERE code='$code' AND interval='$iv' AND ts >= '2026-03-01' ORDER BY ts;" \
      > /tmp/canary_sim/${code}_${iv}.csv;
  done
  python3 _workspace/strategy/sample-backtest/canary-2-sim.py
"""
exec(open('/Users/ak-song/magic_ch1/.claude/worktrees/agent-a6d6841d3cf80ca3c/_workspace/strategy/sample-backtest/canary-2-sim-lib.py').read().split('# ---- Strategy definitions ----')[0])
data={
 ('005930','1m'): load('/tmp/canary_sim/005930_1m.csv'),
 ('005930','5m'): load('/tmp/canary_sim/005930_5m.csv'),
 ('000660','1m'): load('/tmp/canary_sim/000660_1m.csv'),
 ('000660','5m'): load('/tmp/canary_sim/000660_5m.csv'),
}

# === #86 exact shape (ma10 entry == ma10 exit, TP1.0 SL2.0 noMinHold) ===
def s86_entry(x):
    i=x['i']; ma=sma(x['closes'][:i+1],10)
    if ma is None: return False
    return x['lows'][i] <= ma*1.002 and x['closes'][i] >= ma
def s86_exit(x):
    i=x['i']; ma=sma(x['closes'][:i+1],10)
    if ma is None: return False
    return x['closes'][i] < ma
S86=dict(entry=s86_entry, exit_dsl=s86_exit, tp_pct=1.0, sl_pct=2.0, min_hold_bars=0)

# === CHOSEN 1m: VWAP-uptrend + donchian(20) support reclaim entry; buffered ema20 break exit; TP2.2 SL2.0 hold8 ===
def n1m_entry(x):
    i=x['i']; c=x['c']; e=x['ema20'][i]; r=x['rsi14'][i]
    dl=donchian_lower(x['lows'][:i+1],20)
    if dl is None or e is None or i<21: return False
    if not (x['lows'][i] <= dl*1.003 and c > dl): return False  # support reclaim (entry indicator = donchian)
    if c < e*0.99: return False                                  # broad uptrend filter
    if r is None or r>75: return False                           # not overbought
    return True
def n1m_exit(x):
    i=x['i']; c=x['c']; e=x['ema20'][i]
    if e is None: return False
    return c < e*0.988                                           # exit indicator = ema20 buffered (DISTINCT from entry)
N1M=dict(entry=n1m_entry, exit_dsl=n1m_exit, tp_pct=2.2, sl_pct=2.0, min_hold_bars=8)

# === CHOSEN 5m: donchian(20) support reclaim + ema20 uptrend entry; buffered ema20 break exit; TP3.0 SL2.5 hold4 ===
def n5m_entry(x):
    i=x['i']; c=x['c']; e=x['ema20'][i]
    dl=donchian_lower(x['lows'][:i+1],20)
    if dl is None or e is None or i<21: return False
    if not (x['lows'][i] <= dl*1.005 and c > dl): return False
    if c < e: return False                                       # uptrend filter
    return True
def n5m_exit(x):
    i=x['i']; c=x['c']; e=x['ema20'][i]
    if e is None: return False
    return c < e*0.985
N5M=dict(entry=n5m_entry, exit_dsl=n5m_exit, tp_pct=3.0, sl_pct=2.5, min_hold_bars=4)

def row(label, trades):
    if not trades:
        print(f"{label:40s} | NO TRADES"); return
    n=len(trades); wins=sum(1 for t in trades if t[0]>0)
    tp=sum(1 for t in trades if t[1]=='TP'); sl=sum(1 for t in trades if t[1]=='SL')
    dsl=sum(1 for t in trades if t[1]=='DSL'); churn=sum(1 for t in trades if t[1]=='DSL' and t[0]<0)
    comp=1.0
    for t in trades: comp*=(1+t[0]/100)
    avgh=sum(t[2] for t in trades)/n
    print(f"{label:40s} | n={n:5d} win={wins/n*100:3.0f}% comp={((comp-1)*100):+7.1f}% TP%={tp/n*100:3.0f}% SL={sl:4d} DSL={dsl:4d} churn={churn:4d} hold={avgh:4.0f}")

print("HEADER: label | trades / win% / compounded% / TP-hit% / SL count / DSL-sell count / churn(DSL-loss) / avg hold bars\n")
print("--- BASELINE #86 shape (ma10==ma10, TP1.0 SL2.0, no min-hold) ---")
row("#86-shape 005930 1m", simulate(data[('005930','1m')], S86))
row("#86-shape 000660 1m", simulate(data[('000660','1m')], S86))
print("\n--- CHOSEN 1m (donchian-entry / ema20-buffered-exit, TP2.2 SL2.0 hold8) ---")
row("NEW-1m 005930", simulate(data[('005930','1m')], N1M))
row("NEW-1m 000660", simulate(data[('000660','1m')], N1M))
print("\n--- CHOSEN 5m (donchian+ema20-entry / ema20-buffered-exit, TP3.0 SL2.5 hold4) ---")
row("NEW-5m 005930", simulate(data[('005930','5m')], N5M))
row("NEW-5m 000660", simulate(data[('000660','5m')], N5M))
