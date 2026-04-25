package kr.spdaewoon.magicjar.ai.prompt

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

// resources/prompts/*.st 를 읽고 {{var}} 단순 치환만 수행하는 경량 로더.
// Spring AI의 PromptTemplate이 있지만, 모듈 간 결합을 최소화하기 위해 직접 구현.
@Component
class PromptLoader {
    private val cache = mutableMapOf<String, String>()

    fun load(path: String): String =
        cache.getOrPut(path) {
            val resource = ClassPathResource(path)
            require(resource.exists()) { "prompt not found: $path" }
            resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        }

    fun render(
        path: String,
        vars: Map<String, String>,
    ): String {
        var text = load(path)
        for ((k, v) in vars) {
            text = text.replace("{{" + k + "}}", v)
        }
        return text
    }
}
