package com.mj.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun magicJarApiSpec(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("magicJar API")
                    .version("v1 (Phase E)")
                    .description(
                        """
                        magicJar(마법의항아리) — Spring Boot + Spring AI + KIS OpenAPI 기반 한국 주식 자동매매 시스템.

                        본 문서는 **api-app(8080)** 서비스의 사용자 대면 REST 엔드포인트를 다룬다.
                        백테스트/매매일지/종목 카탈로그/심볼 검색 도메인을 묶는다.

                        | 도메인 | 기본 경로 | 비고 |
                        |---|---|---|
                        | 백테스트 | `/api/backtest` | 잡 트리거(POST) → 상태 폴링(GET) |
                        | 매매일지 | `/api/journal` | 페이징 조회 + 차트 스크린샷 업로드 |
                        | 종목 카탈로그 | `/api/instruments` | KRX 상장 종목 메타 |
                        | 심볼 검색 | `/api/symbols/search` | 자동완성 (LIKE) |

                        > 인증: Phase E는 `permitAll`. JWT/OAuth2 부착은 Phase F 이후.
                        > 잡 실행 인터페이스(`/jobs/*`)는 batch-app(8081) Swagger를 별도 참조.
                        """.trimIndent(),
                    ).contact(Contact().name("sp-daewoon").email("sp-daewoon@gmail.com"))
                    .license(License().name("Internal").url("https://github.com/sp-daewoon/magicJar")),
            ).addServersItem(Server().url("http://localhost:8080").description("로컬 docker compose"))
}
