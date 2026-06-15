package kr.spdaewoon.magicjar.domain.redis

import kr.spdaewoon.magicjar.domain.user.AccountType

/**
 * Redis key registry.
 *
 * This object is intentionally dependency-free so api, consumer, batch, and library modules
 * can share Redis key construction without pulling Spring/Redis clients into domain.
 *
 * Current functions return the existing production wire keys. Standard functions expose the
 * recommended convention for gradual copy/dual-read migrations.
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
        ): String = "kis:token:$userId:$accountType"

        fun tokenLock(
            userId: String,
            accountType: AccountType,
        ): String = tokenLock(userId, accountType.name)

        fun tokenLock(
            userId: String,
            accountType: String,
        ): String = "kis:token:lock:$userId:$accountType"

        fun legacyAccessToken(
            userId: String,
            accountType: String,
        ): String = "access_token:$userId:$accountType"

        fun approval(
            userId: String,
            accountType: AccountType,
        ): String = approval(userId, accountType.name)

        fun approval(
            userId: String,
            accountType: String,
        ): String = "kis:approval:$userId:$accountType"

        fun approvalLegacyUserOnly(userId: String): String = "kis:approval:$userId"

        fun approvalLock(
            userId: String,
            accountType: AccountType,
        ): String = approvalLock(userId, accountType.name)

        fun approvalLock(
            userId: String,
            accountType: String,
        ): String = "kis:approval:lock:$userId:$accountType"

        fun keyChangedLock(
            userId: String,
            accountType: String,
        ): String = "kis:key-changed-lock:$userId:$accountType"

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
        ): String = "account:balance:$userId:$accountType"

        fun balanceLock(
            userId: String,
            accountType: AccountType,
        ): String = balanceLock(userId, accountType.name)

        fun balanceLock(
            userId: String,
            accountType: String,
        ): String = "account:balance:lock:$userId:$accountType"

        fun availability(
            mode: String,
            userId: String,
        ): String = "account:availability:$mode:$userId"

        fun currentPosition(userId: String): String = "position:current:$userId"

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
        fun quoteLast(code: String): String = "quote:last:$code"

        fun orderbookTop(code: String): String = "orderbook:top:$code"

        fun candleCurrent(
            code: String,
            interval: String,
        ): String = "candle:current:$code:$interval"

        fun candleToday1d(code: String): String = "candle:today:$code:1d"

        fun candleToday1dPattern(): String = "candle:today:*:1d"

        fun codeFromCandleToday1dKey(key: String): String? {
            val prefix = "candle:today:"
            val suffix = ":1d"
            if (!key.startsWith(prefix) || !key.endsWith(suffix)) return null
            return key.removePrefix(prefix).removeSuffix(suffix).takeIf { it.isNotBlank() }
        }

        fun symbolMeta(code: String): String = "meta:$code"

        fun sessionCurrent(): String = "session:cache:current"

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
        }
    }

    object Strategy {
        fun activeByCode(
            code: String,
            interval: String,
        ): String = "strategy:active:by_code:$code:$interval"

        fun activeByCodePattern(): String = "strategy:active:by_code:*"

        object Standard {
            fun activeByCode(
                code: String,
                interval: String,
            ): String = "strategy:active:code:$code:interval:$interval"
        }
    }

    object Signal {
        fun dedupe(
            strategyId: Long,
            code: String,
            interval: String,
            tsEpochSeconds: Long,
        ): String = "signal:dedupe:$strategyId:$code:$interval:$tsEpochSeconds"

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
        ): String = "risk:trade-freq:$strategyId:$userId:$window"

        fun dailyPnl(
            userId: String,
            date: String,
        ): String = "daily_pnl:$userId:$date"

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

    object Instrument {
        fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

        fun searchPrefixPattern(): String = "instrument:search:prefix:*"

        fun byCode(code: String): String = "instrument:by-code:$code"

        fun byCodePattern(): String = "instrument:by-code:*"

        fun list(market: String): String = "instrument:list:$market"

        fun listPattern(): String = "instrument:list:*"

        fun syncRunning(): String = "instrument:sync:running"

        fun syncLastAt(): String = "instrument:sync:lastAt"

        object Standard {
            fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

            fun byCode(code: String): String = "instrument:summary:code:$code"

            fun list(market: String): String = "instrument:list:market:$market"

            fun syncRunning(): String = "instrument:sync:lock"

            fun syncLastAt(): String = "instrument:sync:last-at"
        }
    }

    object Indicator {
        fun resultPattern(code: String): String = "indicator:cache:$code:*"

        fun result(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
        ): String = "indicator:cache:$code:$interval:$name:$paramsHash"

        fun resultWithRange(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
            rangeHash: String,
        ): String = "indicator:cache:$code:$interval:$name:$paramsHash:$rangeHash"

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
        ): String = "pattern:cache:$code:$interval:$patternsHash"

        object Standard {
            fun result(
                code: String,
                interval: String,
                patternsHash: String,
            ): String = "pattern:result:code:$code:interval:$interval:set:$patternsHash"
        }
    }

    object Performance {
        fun summaryPattern(userId: String): String = "perf:summary:$userId:*"

        fun summary(
            userId: String,
            period: String,
            periodStart: String,
            periodEnd: String,
        ): String = "perf:summary:$userId:$period:$periodStart:$periodEnd"

        fun equityPattern(userId: String): String = "perf:equity:$userId:*"

        fun equity(
            userId: String,
            from: String,
            to: String,
        ): String = "perf:equity:$userId:$from:$to"

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
        ): String = "screener:lock:$userId:$strategyId"

        fun daytrade(
            preset: String,
            filterHash: String,
            interval: String,
            limit: Int,
            tradingDay: String,
        ): String = "screener:daytrade:$preset:$filterHash:$interval:$limit:$tradingDay"

        object Standard {
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
        fun naverRateLimit(): String = "news:ratelimit:naver"

        fun urlDedup(urlHash: String): String = "news:dedup:url:$urlHash"

        fun disclosureDedup(rceptNo: String): String = "news:dedup:rcept:$rceptNo"

        object Standard {
            fun naverRateLimit(): String = "news:ratelimit:provider:naver"

            fun urlDedup(urlHash: String): String = "news:dedup:url:sha256:$urlHash"

            fun disclosureDedup(rceptNo: String): String = "disclosure:dedup:rcept:$rceptNo"
        }
    }

    object Notification {
        fun eventDedup(eventId: String): String = "notification:dedup:$eventId"

        fun orderDedup(
            userId: String,
            orderId: String,
        ): String = "notification:dedup:order:$userId:$orderId"

        fun rateLimit(
            userId: String,
            topicHash: String,
        ): String = "notification:ratelimit:$userId:$topicHash"

        fun once(
            userId: String,
            topicHash: String,
        ): String = "notification:once:$userId:$topicHash"

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
        fun realtimeStatus(): String = "admin:realtime-status"

        fun watchlistSource(userId: String): String = "watchlist:source:$userId"

        fun watchlistSourcePattern(): String = "watchlist:source:*"

        object Standard {
            fun realtimeStatus(): String = "ops:realtime:status"

            fun watchlistSource(userId: String): String = "ops:watchlist:source:user:$userId"
        }
    }

    object Reconcile {
        fun lock(userId: String): String = "reconcile:lock:$userId"

        fun lastRun(userId: String): String = "reconcile:lastrun:$userId"

        object Standard {
            fun lock(userId: String): String = "reconcile:user:$userId:lock"

            fun lastRun(userId: String): String = "reconcile:user:$userId:last-run"
        }
    }

    /**
     * Copy-only migration candidates. Critical trading keys are deliberately excluded even if
     * they have no TTL; a missing TTL on those keys is an incident to investigate, not a key
     * naming migration to automate.
     */
    val noTtlCopyMigrationRules: List<RedisKeyMigrationRule> =
        listOf(
            RedisKeyMigrationRule(
                name = "instrument.by-code",
                legacyPattern = Instrument.byCodePattern(),
                legacyRegex = Regex("""^instrument:by-code:([^:]+)$"""),
                standardKey = { Instrument.Standard.byCode(it.groupValues[1]) },
            ),
            RedisKeyMigrationRule(
                name = "instrument.list",
                legacyPattern = Instrument.listPattern(),
                legacyRegex = Regex("""^instrument:list:([^:]+)$"""),
                standardKey = { Instrument.Standard.list(it.groupValues[1]) },
            ),
            RedisKeyMigrationRule(
                name = "instrument.sync.last-at",
                legacyPattern = Instrument.syncLastAt(),
                legacyRegex = Regex("""^instrument:sync:lastAt$"""),
                standardKey = { Instrument.Standard.syncLastAt() },
            ),
            RedisKeyMigrationRule(
                name = "market.symbol.meta",
                legacyPattern = "meta:*",
                legacyRegex = Regex("""^meta:([^:]+)$"""),
                standardKey = { Market.Standard.symbolMeta(it.groupValues[1]) },
            ),
            RedisKeyMigrationRule(
                name = "market.session.current",
                legacyPattern = Market.sessionCurrent(),
                legacyRegex = Regex("""^session:cache:current$"""),
                standardKey = { Market.Standard.sessionCurrent() },
            ),
            RedisKeyMigrationRule(
                name = "ops.realtime.status",
                legacyPattern = Ops.realtimeStatus(),
                legacyRegex = Regex("""^admin:realtime-status$"""),
                standardKey = { Ops.Standard.realtimeStatus() },
            ),
            RedisKeyMigrationRule(
                name = "ops.watchlist.source",
                legacyPattern = Ops.watchlistSourcePattern(),
                legacyRegex = Regex("""^watchlist:source:([^:]+)$"""),
                standardKey = { Ops.Standard.watchlistSource(it.groupValues[1]) },
            ),
        )
}

data class RedisKeyMigrationRule(
    val name: String,
    val legacyPattern: String,
    val legacyRegex: Regex,
    val standardKey: (MatchResult) -> String,
) {
    fun targetFor(legacyKey: String): String? = legacyRegex.matchEntire(legacyKey)?.let(standardKey)
}
