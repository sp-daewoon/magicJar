package kr.spdaewoon.magicjar.domain.redis

import kr.spdaewoon.magicjar.domain.user.AccountType

/**
 * Redis key registry.
 *
 * This object is intentionally dependency-free so api, consumer, batch, and library modules
 * can share Redis key construction without pulling Spring/Redis clients into domain.
 *
 * Current functions return the standard production wire keys. Legacy wire keys are intentionally
 * not generated from application paths after the Redis naming cutover.
 */
object RedisKeyRegistry {
    object Kis {
        fun token(
            userId: String,
            accountType: AccountType,
        ): String = token(userId, accountType.name)

        fun token(
            userId: String,
            accountType: String,
        ): String = Standard.token(userId, accountType)

        fun tokenLock(
            userId: String,
            accountType: AccountType,
        ): String = tokenLock(userId, accountType.name)

        fun tokenLock(
            userId: String,
            accountType: String,
        ): String = Standard.tokenLock(userId, accountType)

        fun approval(
            userId: String,
            accountType: AccountType,
        ): String = approval(userId, accountType.name)

        fun approval(
            userId: String,
            accountType: String,
        ): String = Standard.approval(userId, accountType)

        fun approvalLock(
            userId: String,
            accountType: AccountType,
        ): String = approvalLock(userId, accountType.name)

        fun approvalLock(
            userId: String,
            accountType: String,
        ): String = Standard.approvalLock(userId, accountType)

        fun keyChangedLock(
            userId: String,
            accountType: String,
        ): String = Standard.keyChangedLock(userId, accountType)

        object Standard {
            fun token(
                userId: String,
                accountType: String,
            ): String = "kis:oauth:token:user:$userId:account:$accountType"

            fun tokenLock(
                userId: String,
                accountType: String,
            ): String = "kis:oauth:token:user:$userId:account:$accountType:lock"

            fun approval(
                userId: String,
                accountType: String,
            ): String = "kis:ws:approval:user:$userId:account:$accountType"

            fun approvalLock(
                userId: String,
                accountType: String,
            ): String = "kis:ws:approval:user:$userId:account:$accountType:lock"

            fun keyChangedLock(
                userId: String,
                accountType: String,
            ): String = "kis:credential:changed:user:$userId:account:$accountType"
        }
    }

    object Account {
        fun balance(
            userId: String,
            accountType: AccountType,
        ): String = balance(userId, accountType.name)

        fun balance(
            userId: String,
            accountType: String,
        ): String = Standard.balance(userId, accountType)

        fun balanceLock(
            userId: String,
            accountType: AccountType,
        ): String = balanceLock(userId, accountType.name)

        fun balanceLock(
            userId: String,
            accountType: String,
        ): String = Standard.balanceLock(userId, accountType)

        fun availability(
            mode: String,
            userId: String,
        ): String = Standard.availability(userId, mode)

        fun currentPosition(userId: String): String = Standard.currentPosition(userId)

        object Standard {
            fun balance(
                userId: String,
                accountType: String,
            ): String = "account:balance:user:$userId:account:$accountType"

            fun balanceLock(
                userId: String,
                accountType: String,
            ): String = "account:balance:user:$userId:account:$accountType:lock"

            fun availability(
                userId: String,
                mode: String,
            ): String = "account:availability:user:$userId:mode:$mode"

            fun currentPosition(userId: String): String = "account:position:current:user:$userId"
        }
    }

    object Market {
        fun quoteLast(code: String): String = Standard.quoteLast(code)

        fun orderbookTop(code: String): String = Standard.orderbookTop(code)

        fun candleCurrent(
            code: String,
            interval: String,
        ): String = Standard.candleCurrent(code, interval)

        fun candleToday1d(code: String): String = Standard.candleToday1d(code)

        fun candleToday1dPattern(): String = "market:candle:session:code:*:interval:1d"

        fun codeFromCandleToday1dKey(key: String): String? {
            val standardPrefix = "market:candle:session:code:"
            val standardSuffix = ":interval:1d"
            if (key.startsWith(standardPrefix) && key.endsWith(standardSuffix)) {
                return key.removePrefix(standardPrefix).removeSuffix(standardSuffix).takeIf { it.isNotBlank() }
            }

            val legacyPrefix = "candle:today:"
            val legacySuffix = ":1d"
            if (key.startsWith(legacyPrefix) && key.endsWith(legacySuffix)) {
                return key.removePrefix(legacyPrefix).removeSuffix(legacySuffix).takeIf { it.isNotBlank() }
            }
            return null
        }

        fun symbolMeta(code: String): String = Standard.symbolMeta(code)

        fun sessionCurrent(): String = Standard.sessionCurrent()

        fun candleBackfillMiss(
            code: String,
            interval: String,
        ): String = Standard.candleBackfillMiss(code, interval)

        fun candleBackfillUnrecoverable(
            code: String,
            interval: String,
        ): String = Standard.candleBackfillUnrecoverable(code, interval)

        fun candleUniverseDetailRecent(): String = Standard.candleUniverseDetailRecent()

        fun candleUniverseStrategyCandidates(): String = Standard.candleUniverseStrategyCandidates()

        object Standard {
            fun quoteLast(code: String): String = "market:quote:last:code:$code"

            fun orderbookTop(code: String): String = "market:orderbook:top:code:$code"

            fun candleCurrent(
                code: String,
                interval: String,
            ): String = "market:candle:current:code:$code:interval:$interval"

            fun candleToday1d(code: String): String = "market:candle:session:code:$code:interval:1d"

            fun symbolMeta(code: String): String = "market:symbol:meta:code:$code"

            fun sessionCurrent(): String = "market:session:current"

            fun candleBackfillMiss(
                code: String,
                interval: String,
            ): String = "market:candle:backfill:miss:code:$code:interval:$interval"

            fun candleBackfillUnrecoverable(
                code: String,
                interval: String,
            ): String = "market:candle:backfill:unrecoverable:code:$code:interval:$interval"

            fun candleUniverseDetailRecent(): String = "market:candle:universe:detail-recent"

            fun candleUniverseStrategyCandidates(): String = "market:candle:universe:strategy-candidates"
        }
    }

    object Strategy {
        fun activeByCode(
            code: String,
            interval: String,
        ): String = Standard.activeByCode(code, interval)

        fun activeByCodePattern(): String = "strategy:active:code:*:interval:*"

        /**
         * ### stopLossReentryCooldown — 손절(실현 손실) 청산 후 재진입 쿨다운 (진입 가드 3종 ②, 2026-07-08)
         * key = `strategy:sl-cooldown:user:{userId}:code:{code}`
         *
         * 같은 (userId, code)에서 **손절성(실현 손실) 전량 청산**이 체결되면 그 시점을 본 키에 기록하고 TTL 을
         * 쿨다운 윈도우(yml `magicjar.strategy.entry-guard.cooldown.minutes`)로 건다. 같은 윈도우 안에서 같은 code 의
         * 신규 **BUY** 시그널이 들어오면 발행을 skip 한다 — "손절 → 같은 종목 즉시 재매수 → 재손절" 반복(2026-07-08
         * 근거 014910·037070·046940·013120)이 손실을 누적하는 통로를 닫는다. owner 무관(전 전략 공통).
         *
         * SELL/청산은 본 키를 절대 게이트로 쓰지 않는다(보유분 청산 항상 보장 — 롱온리). Redis 영속이라 consumer
         * 재기동에도 쿨다운이 생존한다(윈도우 내 재부팅 후 즉시 재진입 방지). 결함 시 fail-open(진입 skip 안 함).
         */
        fun stopLossReentryCooldown(
            userId: String,
            code: String,
        ): String = Standard.stopLossReentryCooldown(userId, code)

        object Standard {
            fun activeByCode(
                code: String,
                interval: String,
            ): String = "strategy:active:code:$code:interval:$interval"

            fun stopLossReentryCooldown(
                userId: String,
                code: String,
            ): String = "strategy:sl-cooldown:user:$userId:code:$code"
        }
    }

    object Signal {
        fun dedupe(
            strategyId: Long,
            code: String,
            interval: String,
            tsEpochSeconds: Long,
        ): String = Standard.dedupe(strategyId, code, interval, tsEpochSeconds)

        object Standard {
            fun dedupe(
                strategyId: Long,
                code: String,
                interval: String,
                tsEpochSeconds: Long,
            ): String = "signal:dedupe:strategy:$strategyId:code:$code:interval:$interval:ts:$tsEpochSeconds"
        }
    }

    object Risk {
        fun tradeFrequency(
            strategyId: Long,
            userId: String,
            window: String,
        ): String = Standard.tradeFrequency(userId, strategyId, window)

        fun dailyPnl(
            userId: String,
            date: String,
        ): String = Standard.dailyPnl(userId, date)

        object Standard {
            fun tradeFrequency(
                userId: String,
                strategyId: Long,
                window: String,
            ): String = "risk:trade-frequency:user:$userId:strategy:$strategyId:window:$window"

            fun dailyPnl(
                userId: String,
                date: String,
            ): String = "risk:daily-pnl:user:$userId:date:$date"
        }
    }

    /**
     * magicjar-autopilot 자율 루프 전용 Redis 키 (개선 C — 종목 충돌 dedup, 2026-06-22).
     *
     * ### buyCodeLock — owner=AUTOPILOT BUY 종목 분산 lock
     * key = `autopilot:buy:code:user:{userId}:code:{code}`
     *
     * owner=AUTOPILOT 전략 N개가 같은 발굴 후보(같은 code)에 동시 BUY 신호를 내면 슬롯 1개를 다투고 나머지는
     * REJECTED 로 버려져 종목 분산 효과가 0이 된다. 이 lock 은 **code 단위(strategyId 제외)** SETNX 로 같은 평가
     * cycle 안에서 같은 code 의 두 번째 이후 autopilot BUY 를 suppress 한다 → N전략이 서로 다른 종목으로 분산.
     *
     * TTL 은 단일 평가 cycle(boundary 동시 신호 폭)보다 길고 다음 cycle 전에 만료될 만큼 짧게 둔다(한 cycle 1 슬롯).
     * 격리: 키 prefix 와 호출 경로(applyAutopilotBuyGuard owner=AUTOPILOT 분기)로 USER/SYSTEM·SELL 은 구조적 제외.
     *
     * ### reentryCooldown — owner=AUTOPILOT 재진입 churn 억제 (follow-up I, 2026-06-22)
     * key = `autopilot:reentry:user:{userId}:code:{code}`
     *
     * 한 code 를 **전량 청산(SELL 체결로 quantity→0)** 하면 그 시점 KST epoch-millis 를 본 키에 기록하고 TTL 을
     * 쿨다운 윈도우로 건다. 같은 윈도우 안에서 같은 code 의 owner=AUTOPILOT **BUY** 가 들어오면 suppress 한다 —
     * buy→TP/exit→분 뒤 재매수 반복(churn)이 매 왕복마다 거래비용(매도세 0.18% + 수수료)을 누적해 gross 알파를
     * 깎는 손실원이기 때문이다. SELL/청산은 본 키를 절대 게이트로 쓰지 않는다(보유분 청산 항상 보장 — 롱온리).
     *
     * dedup(buyCodeLock)이 *같은 cycle 동시* 중복을 막는다면, 본 키는 *시간차 재진입*(분 단위 churn)을 막는
     * 직교 보강이다. TTL=쿨다운 윈도우(default 보수)라 윈도우 경과 후 정당 추세추종 재진입은 자연 허용된다.
     *
     * ### reentryDayCount — owner=AUTOPILOT 종목당 1일 왕복 cap (planned-entry churn 저감, 2026-06-24)
     * key = `autopilot:reentry:count:user:{userId}:code:{code}:{kstDate}`
     *
     * 쿨다운(reentryCooldown)은 *청산 직후 N분* 의 즉시 재진입만 막는다. 하지만 윈도우(15분) 경과 후엔 같은 code 를
     * 하루 종일 반복 왕복할 수 있어(실측 2026-06-23 122640 ~8왕복 — 거래비용 46% 잠식) **누적 왕복 횟수**는
     * 묶이지 않았다. 본 키는 한 code 의 owner=AUTOPILOT **전량 청산(왕복 완료)** 횟수를 KST 날짜 단위로 카운트하고
     * (INCR + 첫 카운트 시 KST EOD 까지 TTL), 그 횟수가 cap 에 도달하면 같은 날 같은 code 의 신규 BUY 재진입을
     * suppress 한다. 시간 축(쿨다운)·가격 축(추격가드)과 직교한 **누적 축** 보강이다. 날짜 경계에 키 자연 소멸 → 익일 리셋.
     * SELL/청산은 본 키를 절대 게이트로 쓰지 않는다(보유분 청산 항상 보장 — 롱온리).
     */
    object Autopilot {
        fun buyCodeLock(
            userId: String,
            code: String,
        ): String = Standard.buyCodeLock(userId, code)

        fun reentryCooldown(
            userId: String,
            code: String,
        ): String = Standard.reentryCooldown(userId, code)

        fun reentryDayCount(
            userId: String,
            code: String,
            kstDate: String,
        ): String = Standard.reentryDayCount(userId, code, kstDate)

        object Standard {
            fun buyCodeLock(
                userId: String,
                code: String,
            ): String = "autopilot:buy:code:user:$userId:code:$code"

            fun reentryCooldown(
                userId: String,
                code: String,
            ): String = "autopilot:reentry:user:$userId:code:$code"

            fun reentryDayCount(
                userId: String,
                code: String,
                kstDate: String,
            ): String = "autopilot:reentry:count:user:$userId:code:$code:$kstDate"
        }
    }

    object Instrument {
        fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

        fun searchPrefixPattern(): String = "instrument:search:prefix:*"

        fun byCode(code: String): String = Standard.byCode(code)

        fun byCodePattern(): String = "instrument:summary:code:*"

        fun list(market: String): String = Standard.list(market)

        fun listPattern(): String = "instrument:list:market:*"

        fun syncRunning(): String = Standard.syncRunning()

        fun syncLastAt(): String = Standard.syncLastAt()

        object Standard {
            fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

            fun byCode(code: String): String = "instrument:summary:code:$code"

            fun list(market: String): String = "instrument:list:market:$market"

            fun syncRunning(): String = "instrument:sync:lock"

            fun syncLastAt(): String = "instrument:sync:last-at"
        }
    }

    object Indicator {
        fun resultPattern(code: String): String = "indicator:result:code:$code:*"

        fun result(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
        ): String = Standard.result(code, interval, name, paramsHash)

        fun resultWithRange(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
            rangeHash: String,
        ): String = Standard.resultWithRange(code, interval, name, paramsHash, rangeHash)

        object Standard {
            fun result(
                code: String,
                interval: String,
                name: String,
                paramsHash: String,
            ): String = "indicator:result:code:$code:interval:$interval:name:$name:params:$paramsHash"

            fun resultWithRange(
                code: String,
                interval: String,
                name: String,
                paramsHash: String,
                rangeHash: String,
            ): String = "indicator:result:code:$code:interval:$interval:name:$name:params:$paramsHash:range:$rangeHash"
        }
    }

    object Pattern {
        fun result(
            code: String,
            interval: String,
            patternsHash: String,
        ): String = Standard.result(code, interval, patternsHash)

        object Standard {
            fun result(
                code: String,
                interval: String,
                patternsHash: String,
            ): String = "pattern:result:code:$code:interval:$interval:set:$patternsHash"
        }
    }

    object Performance {
        fun summaryPattern(userId: String): String = "performance:summary:user:$userId:*"

        fun summary(
            userId: String,
            period: String,
            periodStart: String,
            periodEnd: String,
        ): String = Standard.summary(userId, period, periodStart, periodEnd)

        fun equityPattern(userId: String): String = "performance:equity:user:$userId:*"

        fun equity(
            userId: String,
            from: String,
            to: String,
        ): String = Standard.equity(userId, from, to)

        object Standard {
            fun summary(
                userId: String,
                period: String,
                periodStart: String,
                periodEnd: String,
            ): String = "performance:summary:user:$userId:period:$period:from:$periodStart:to:$periodEnd"

            fun equity(
                userId: String,
                from: String,
                to: String,
            ): String = "performance:equity:user:$userId:from:$from:to:$to"
        }
    }

    object Screener {
        fun lock(
            userId: String,
            strategyId: Long,
        ): String = Standard.lock(userId, strategyId)

        fun result(
            dslHash: String,
            universeFilterHash: String,
        ): String = Standard.result(dslHash, universeFilterHash)

        fun daytrade(
            preset: String,
            filterHash: String,
            interval: String,
            limit: Int,
            tradingDay: String,
        ): String = Standard.daytrade(preset, filterHash, interval, limit, tradingDay)

        object Standard {
            fun result(
                dslHash: String,
                universeFilterHash: String,
            ): String = "screener:result:dsl:$dslHash:universe:$universeFilterHash"

            fun lock(
                userId: String,
                strategyId: Long,
            ): String = "screener:run:user:$userId:strategy:$strategyId:lock"

            fun daytrade(
                preset: String,
                filterHash: String,
                interval: String,
                limit: Int,
                tradingDay: String,
            ): String = "screener:daytrade:preset:$preset:filter:$filterHash:interval:$interval:limit:$limit:date:$tradingDay"
        }
    }

    object News {
        fun naverRateLimit(): String = Standard.naverRateLimit()

        fun urlDedup(urlHash: String): String = Standard.urlDedup(urlHash)

        fun disclosureDedup(rceptNo: String): String = Standard.disclosureDedup(rceptNo)

        object Standard {
            fun naverRateLimit(): String = "news:ratelimit:provider:naver"

            fun urlDedup(urlHash: String): String = "news:dedup:url:sha256:$urlHash"

            fun disclosureDedup(rceptNo: String): String = "disclosure:dedup:rcept:$rceptNo"
        }
    }

    object Ai {
        fun promptCache(promptHash: String): String = Standard.promptCache(promptHash)

        object Standard {
            fun promptCache(promptHash: String): String = "ai:prompt:cache:sha256:$promptHash"
        }
    }

    object Notification {
        fun eventDedup(eventId: String): String = Standard.eventDedup(eventId)

        fun orderDedup(
            userId: String,
            orderId: String,
        ): String = Standard.orderDedup(userId, orderId)

        fun rateLimit(
            userId: String,
            topicHash: String,
        ): String = Standard.rateLimit(userId, topicHash)

        fun once(
            userId: String,
            topicHash: String,
        ): String = Standard.once(userId, topicHash)

        object Standard {
            fun eventDedup(eventId: String): String = "notification:dedup:event:$eventId"

            fun orderDedup(
                userId: String,
                orderId: String,
            ): String = "notification:dedup:order:user:$userId:order:$orderId"

            fun rateLimit(
                userId: String,
                topicHash: String,
            ): String = "notification:ratelimit:user:$userId:topic:$topicHash"

            fun once(
                userId: String,
                topicHash: String,
            ): String = "notification:once:user:$userId:topic:$topicHash"
        }
    }

    object Ops {
        fun realtimeStatus(): String = Standard.realtimeStatus()

        fun watchlistSource(userId: String): String = Standard.watchlistSource(userId)

        fun watchlistSourcePattern(): String = "ops:watchlist:source:user:*"

        object Standard {
            fun realtimeStatus(): String = "ops:realtime:status"

            fun watchlistSource(userId: String): String = "ops:watchlist:source:user:$userId"
        }
    }

    object Reconcile {
        fun lock(userId: String): String = Standard.lock(userId)

        fun lastRun(userId: String): String = Standard.lastRun(userId)

        object Standard {
            fun lock(userId: String): String = "reconcile:user:$userId:lock"

            fun lastRun(userId: String): String = "reconcile:user:$userId:last-run"
        }
    }
}
