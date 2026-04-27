package com.mj.domain.journal.port

import com.mj.domain.common.NewsItem
import java.time.Instant

/**
 * 뉴스·공시 저장소 포트. 구현체는 market-data 모듈이 제공한다.
 * `code`가 null이면 전체 종목을 대상으로 검색한다.
 */
interface NewsRepository {
    fun save(items: List<NewsItem>)

    fun search(
        code: String?,
        from: Instant,
        to: Instant,
    ): List<NewsItem>
}
