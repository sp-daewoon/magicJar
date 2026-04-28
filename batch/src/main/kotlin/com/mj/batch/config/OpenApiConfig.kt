package com.mj.batch.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun magicJarBatchApiSpec(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("magicJar Batch Job Trigger")
                    .version("v1 (Phase E)")
                    .description(
                        """
                        magicJar **batch-app(8081)** — Spring Batch 잡 HTTP 트리거.

                        api-app(8080)의 `BacktestService`가 내부적으로 호출하지만, 운영자가 직접 잡을 돌리려면
                        본 Swagger의 엔드포인트를 사용한다.

                        | 엔드포인트 | 잡 | 동작 |
                        |---|---|---|
                        | `POST /jobs/backtest` | DailyCandleBackfillJob → BacktestRunJob | 캔들 적재 후 백테스트 |
                        | `POST /jobs/backfill` | DailyCandleBackfillJob 단독 | 캔들만 채움 |

                        > KIS 어댑터는 `stub-kis` Profile(기본)에서는 fixture 응답, `real-kis` Profile에서는 실 KIS REST 호출.
                        """.trimIndent(),
                    ),
            ).addServersItem(Server().url("http://localhost:8081").description("로컬 docker compose"))
}
