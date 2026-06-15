#!/usr/bin/env python3
"""
Deterministic offline backtest for canary strategy design validation.

Replicates magicJar live signal semantics on REAL DB candles:
- boundary-close evaluation per candle (close-only signals, like RealtimeSignalGenerator)
- regular session only (09:00<=t<15:30 KST) — matches market-hours gate
- entry on BUY DSL; while in position evaluate, in order:
    1. fixed stop-loss (current vs avgPrice) -> MARKET sell  [always, ignores min_hold]
    2. take-profit (current vs avgPrice >= TP%) -> LIMIT sell [always, ignores min_hold]
    3. DSL sell -> sell, but SKIP if held bars < min_hold_bars (jan-ikjeol guard B)
- one position per code, all-capital-ish single share-count irrelevant (we measure % outcomes)
- indicators computed from RAW close series (ma, ema, donchian, vwap-session, rsi, volume_ma)

We measure for each strategy:
- trades, win rate, avg return %, total compounded return %
- TP-hit rate (exits via take-profit)
- churn = exits via DSL-sell that closed at a LOSS (the #86 disease: oscillation chop)
- avg holding bars
"""
import csv, sys, math
from collections import defaultdict

def load(path):
    rows=[]
    with open(path) as f:
        for line in f:
            p=line.rstrip("\n").split(",")
            if len(p)<6 or p[1]=="": continue
            ts=p[0]; o=float(p[1]); h=float(p[2]); l=float(p[3]); c=float(p[4]); v=float(p[5] or 0)
            rows.append((ts,o,h,l,c,v))
    return rows

def in_session(ts):
    # ts = 'YYYY-MM-DDTHH:MM'
    hm=ts[11:16]
    return "09:00" <= hm < "15:30"

def sma(vals, n):
    if len(vals)<n: return None
    return sum(vals[-n:])/n

def ema_series(closes, n):
    out=[None]*len(closes)
    k=2/(n+1)
    e=None
    for i,c in enumerate(closes):
        if i+1<n:
            continue
        if e is None:
            e=sum(closes[i-n+1:i+1])/n
        else:
            e=c*k+e*(1-k)
        out[i]=e
    return out

def rsi_series(closes, n):
    out=[None]*len(closes)
    if len(closes)<n+1: return out
    gains=[]; losses=[]
    for i in range(1,len(closes)):
        ch=closes[i]-closes[i-1]
        gains.append(max(ch,0)); losses.append(max(-ch,0))
    # gains index i corresponds to closes i (i>=1)
    avg_g=sum(gains[:n])/n; avg_l=sum(losses[:n])/n
    for i in range(n, len(closes)):
        if i> n:
            avg_g=(avg_g*(n-1)+gains[i-1])/n
            avg_l=(avg_l*(n-1)+losses[i-1])/n
        rs= (avg_g/avg_l) if avg_l>0 else float('inf')
        out[i]= 100 - 100/(1+rs) if avg_l>0 else 100.0
    return out

def donchian_lower(lows, n):
    if len(lows)<n: return None
    return min(lows[-n:])
def donchian_upper(highs, n):
    if len(highs)<n: return None
    return max(highs[-n:])

# Session VWAP: resets each trading day, cumulative typical*vol / cumulative vol
def session_vwap(rows):
    out=[None]*len(rows)
    cur_day=None; cpv=0.0; cv=0.0
    for i,(ts,o,h,l,c,v) in enumerate(rows):
        day=ts[:10]
        if day!=cur_day:
            cur_day=day; cpv=0.0; cv=0.0
        tp=(h+l+c)/3
        cpv+=tp*v; cv+=v
        out[i]= (cpv/cv) if cv>0 else c
    return out

def volume_ma(vols, n):
    if len(vols)<n: return None
    return sum(vols[-n:])/n

def simulate(rows, strat):
    """strat: dict with entry(fn), exit_dsl(fn or None), tp_pct, sl_pct, min_hold_bars"""
    closes=[r[4] for r in rows]
    highs=[r[2] for r in rows]
    lows=[r[3] for r in rows]
    vols=[r[5] for r in rows]
    opens=[r[1] for r in rows]
    vwap=session_vwap(rows)
    ema20=ema_series(closes,20)
    rsi14=rsi_series(closes,14)

    pos=None  # dict avg, opened_idx
    trades=[]  # (ret_pct, exit_kind, hold_bars)
    for i in range(len(rows)):
        ts=rows[i][0]
        if not in_session(ts):
            continue
        c=closes[i]
        ctx=dict(i=i, closes=closes, highs=highs, lows=lows, vols=vols, opens=opens,
                 vwap=vwap, ema20=ema20, rsi14=rsi14, c=c)
        if pos is None:
            if strat['entry'](ctx):
                pos=dict(avg=c, opened=i)
            continue
        # in position
        held=i-pos['opened']
        ret=(c-pos['avg'])/pos['avg']*100
        # 1. stop loss
        if ret <= -strat['sl_pct']:
            trades.append((ret,'SL',held)); pos=None; continue
        # 2. take profit
        if ret >= strat['tp_pct']:
            trades.append((ret,'TP',held)); pos=None; continue
        # 3. DSL sell (with min_hold guard)
        if strat['exit_dsl'] is not None and strat['exit_dsl'](ctx):
            if held < strat['min_hold_bars']:
                continue  # min-hold skip
            trades.append((ret,'DSL',held)); pos=None; continue
    # force close at end
    if pos is not None:
        i=len(rows)-1; c=closes[i]; ret=(c-pos['avg'])/pos['avg']*100
        trades.append((ret,'EOD',i-pos['opened']))
    return trades

def report(name, trades):
    if not trades:
        print(f"  {name}: 0 trades"); return
    n=len(trades)
    wins=[t for t in trades if t[0]>0]
    tp=[t for t in trades if t[1]=='TP']
    sl=[t for t in trades if t[1]=='SL']
    dsl=[t for t in trades if t[1]=='DSL']
    dsl_loss=[t for t in dsl if t[0]<0]
    avg=sum(t[0] for t in trades)/n
    comp=1.0
    for t in trades: comp*=(1+t[0]/100)
    avg_hold=sum(t[2] for t in trades)/n
    print(f"  {name}: trades={n} win%={len(wins)/n*100:.0f} avgRet={avg:+.2f}% comp={((comp-1)*100):+.1f}% avgHold={avg_hold:.0f}bars")
    print(f"     exits: TP={len(tp)}({len(tp)/n*100:.0f}%) SL={len(sl)} DSL={len(dsl)} | churn(DSL-loss)={len(dsl_loss)}")

# ---- Strategy definitions ----
def s86_entry(x):
    i=x['i']; lows=x['lows']; closes=x['closes']
    ma=sma(closes[:i+1],10)
    if ma is None: return False
    return lows[i] <= ma*1.002 and closes[i] >= ma
def s86_exit(x):
    i=x['i']; closes=x['closes']
    ma=sma(closes[:i+1],10)
    if ma is None: return False
    return closes[i] < ma

# NEW 1m strategy: VWAP-reclaim entry (above session VWAP + above EMA20), exit = trend break below EMA20*0.992 (buffered, slower line). TP 2.2, SL 1.2, min_hold 5
def n1m_entry(x):
    i=x['i']; c=x['c']; vw=x['vwap'][i]; e=x['ema20'][i]
    if vw is None or e is None: return False
    # buy when reclaiming/holding above session VWAP and above ema20 (momentum up, not at a pivot we also exit on)
    return c > vw and c > e
def n1m_exit(x):
    i=x['i']; c=x['c']; e=x['ema20'][i]
    if e is None: return False
    return c < e*0.992  # buffered slower-line break (distinct from entry's vwap+ema cross)

# NEW 5m strategy: Donchian(20) lower-band bounce entry distinct from RSI-rollover exit. TP 2.5, SL 1.5, min_hold 3
def n5m_entry(x):
    i=x['i']; lows=x['lows']; closes=x['closes']; c=x['c']
    dl=donchian_lower(lows[:i+1],20)
    if dl is None: return False
    # bounce: low touched lower band but close recovered above it (support reclaim)
    return lows[i] <= dl*1.005 and c > dl
def n5m_exit(x):
    i=x['i']; r=x['rsi14'][i]
    if r is None: return False
    return r < 45  # momentum rollover (distinct indicator from donchian entry)

defs={
 '#86 (ma10/ma10, TP1.0 SL2.0 noMinHold)': dict(entry=s86_entry, exit_dsl=s86_exit, tp_pct=1.0, sl_pct=2.0, min_hold_bars=0),
 '#86-shape on 005930 1m':                  dict(entry=s86_entry, exit_dsl=s86_exit, tp_pct=1.0, sl_pct=2.0, min_hold_bars=0),
 'NEW-1m vwap+ema / ema-buffer (TP2.2 SL1.2 hold5)': dict(entry=n1m_entry, exit_dsl=n1m_exit, tp_pct=2.2, sl_pct=1.2, min_hold_bars=5),
 'NEW-5m donchian / rsi (TP2.5 SL1.5 hold3)':        dict(entry=n5m_entry, exit_dsl=n5m_exit, tp_pct=2.5, sl_pct=1.5, min_hold_bars=3),
}

data={
 ('005930','1m'): load('/tmp/canary_sim/005930_1m.csv'),
 ('005930','5m'): load('/tmp/canary_sim/005930_5m.csv'),
 ('000660','1m'): load('/tmp/canary_sim/000660_1m.csv'),
 ('000660','5m'): load('/tmp/canary_sim/000660_5m.csv'),
}

print("=== #86 reproduction (ma10/ma10 churn) ===")
report("#86-shape 005930 1m", simulate(data[('005930','1m')], defs['#86 (ma10/ma10, TP1.0 SL2.0 noMinHold)']))
report("#86-shape 000660 1m", simulate(data[('000660','1m')], defs['#86 (ma10/ma10, TP1.0 SL2.0 noMinHold)']))

print("\n=== NEW 1m (005930) ===")
report("NEW-1m 005930", simulate(data[('005930','1m')], defs['NEW-1m vwap+ema / ema-buffer (TP2.2 SL1.2 hold5)']))

print("\n=== NEW 5m (000660) ===")
report("NEW-5m 000660", simulate(data[('000660','5m')], defs['NEW-5m donchian / rsi (TP2.5 SL1.5 hold3)']))
report("NEW-5m 005930", simulate(data[('005930','5m')], defs['NEW-5m donchian / rsi (TP2.5 SL1.5 hold3)']))
