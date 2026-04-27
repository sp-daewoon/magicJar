package kr.spdaewoon.magicjar.web.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.spdaewoon.magicjar.core.domain.AgentStatus
import kr.spdaewoon.magicjar.core.domain.HarnessState
import kr.spdaewoon.magicjar.core.domain.PhaseEntry
import kr.spdaewoon.magicjar.core.port.HarnessStateReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * [HarnessStateReader] 의 파일 시스템 구현.
 *
 * 입력 소스 (ADR 0008):
 *  - `_workspace/_harness-state.json`  : 현재/과거 Phase, 에이전트 활동 스냅샷
 *  - `.claude/agents/{name}.md`        : 에이전트 이름/역할 요약 (frontmatter 파싱)
 *  - `_workspace/qa/issues.md`         : QA 이슈(OPEN/FIXED) 카운트
 *
 * 민감정보 마스킹 책임은 해당 원본 파일을 렌더링해 보여줄 때(/harness 페이지) 별도로 처리한다.
 * 이 서비스는 메타데이터만 추출하므로 키/토큰이 담길 일이 없다.
 */
@Service
class HarnessStateReaderImpl(
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.harness.state-file:_workspace/_harness-state.json}")
    private val stateFile: String,
    @Value("\${magicjar.harness.agents-dir:.claude/agents}")
    private val agentsDir: String,
    @Value("\${magicjar.harness.qa-issues-file:_workspace/qa/issues.md}")
    private val qaIssuesFile: String,
) : HarnessStateReader {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun read(): HarnessState {
        val root =
            runCatching { readStateJson(Paths.get(stateFile)) }
                .getOrElse {
                    log.warn("harness-state.json 읽기 실패: {}", it.message)
                    null
                }

        val phaseHistory = parsePhaseHistory(root)
        val agentActivity = parseActiveAgents(root)
        val blockedAgents = parseBlockedAgents(root)
        val currentPhase = (root?.get("currentPhase")?.asText()) ?: "0"
        val generatedAt =
            (root?.get("updatedAt")?.asText())
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now()

        val agents =
            buildAgentList(
                agentsDirectory = Paths.get(agentsDir),
                activeAgents = agentActivity,
                blockedAgents = blockedAgents,
            )

        val (open, fixed) = parseQaIssues(Paths.get(qaIssuesFile))

        return HarnessState(
            currentPhase = currentPhase,
            phaseHistory = phaseHistory,
            agents = agents,
            issuesOpen = open,
            issuesFixed = fixed,
            generatedAt = generatedAt,
        )
    }

    private fun readStateJson(path: Path): ObjectNode? =
        if (Files.exists(path)) objectMapper.readTree(Files.readAllBytes(path)) as? ObjectNode else null

    private fun parsePhaseHistory(root: ObjectNode?): List<PhaseEntry> {
        val array = root?.get("phaseHistory") ?: return emptyList()
        return array.mapNotNull { node ->
            runCatching {
                PhaseEntry(
                    phase = node.get("phase").asText(),
                    startedAt = Instant.parse(node.get("startedAt").asText()),
                    finishedAt =
                        node
                            .get("finishedAt")
                            ?.takeUnless { it.isNull }
                            ?.asText()
                            ?.let(Instant::parse),
                    mode = node.get("mode")?.asText() ?: "unknown",
                    agents = node.get("agents")?.map { it.asText() } ?: emptyList(),
                )
            }.getOrNull()
        }
    }

    private fun parseActiveAgents(root: ObjectNode?): Set<String> = root?.get("activeAgents")?.map { it.asText() }?.toSet() ?: emptySet()

    private fun parseBlockedAgents(root: ObjectNode?): Set<String> = root?.get("blockedAgents")?.map { it.asText() }?.toSet() ?: emptySet()

    private fun buildAgentList(
        agentsDirectory: Path,
        activeAgents: Set<String>,
        blockedAgents: Set<String>,
    ): List<AgentStatus> {
        if (!Files.isDirectory(agentsDirectory)) return fallbackAgentList(activeAgents, blockedAgents)
        return Files
            .list(agentsDirectory)
            .use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".md") }
                    .map { parseAgentFile(it) }
                    .toList()
            }.map { parsed ->
                val status =
                    when {
                        blockedAgents.contains(parsed.name) -> "BLOCKED"
                        activeAgents.contains(parsed.name) -> "RUNNING"
                        else -> "IDLE"
                    }
                AgentStatus(
                    name = parsed.name,
                    roleSummary = parsed.roleSummary,
                    skills = parsed.skills,
                    lastActivity = null,
                    lastArtifacts = emptyList(),
                    status = status,
                )
            }.sortedBy { it.name }
    }

    private fun fallbackAgentList(
        active: Set<String>,
        blocked: Set<String>,
    ): List<AgentStatus> {
        val names = (active + blocked + defaultAgents).distinct().sorted()
        return names.map { n ->
            AgentStatus(
                name = n,
                roleSummary = "(에이전트 정의 파일 미발견)",
                skills = emptyList(),
                lastActivity = null,
                lastArtifacts = emptyList(),
                status =
                    when {
                        blocked.contains(n) -> "BLOCKED"
                        active.contains(n) -> "RUNNING"
                        else -> "IDLE"
                    },
            )
        }
    }

    private data class AgentFrontmatter(
        val name: String,
        val roleSummary: String,
        val skills: List<String>,
    )

    /**
     * `.claude/agents/<name>.md` 는 YAML frontmatter(---) 로 시작하며 name/description 을 포함한다.
     * skills 는 description 이후 본문에서 `magicjar-*` 패턴을 훑어 수집한다.
     */
    private fun parseAgentFile(path: Path): AgentFrontmatter {
        val text = runCatching { Files.readString(path) }.getOrElse { "" }
        val lines = text.lines()
        var name: String = path.fileName.toString().removeSuffix(".md")
        var description: String = "-"
        // frontmatter 파싱
        if (lines.firstOrNull() == "---") {
            val end = lines.drop(1).indexOf("---")
            if (end >= 0) {
                lines.subList(1, end + 1).forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        when (key) {
                            "name" -> name = value.ifBlank { name }
                            "description" -> description = value.ifBlank { description }
                        }
                    }
                }
            }
        }
        val skillRegex = Regex("""magicjar-[a-z0-9-]+""")
        val skills =
            skillRegex
                .findAll(text)
                .map { it.value }
                .toSet()
                .sorted()
        return AgentFrontmatter(name = name, roleSummary = description, skills = skills)
    }

    /**
     * `_workspace/qa/issues.md` 포맷(ADR 0011 QA 게이트 산출물). 파일이 없으면 모두 0.
     * 간단 규칙: 라인에 `[OPEN]` 또는 `[FIXED]` 토큰이 있으면 카운트.
     */
    private fun parseQaIssues(path: Path): Pair<Int, Int> {
        if (!Files.exists(path)) return 0 to 0
        val text = runCatching { Files.readString(path) }.getOrElse { return 0 to 0 }
        val open = Regex("""\[OPEN]""").findAll(text).count()
        val fixed = Regex("""\[FIXED]""").findAll(text).count()
        return open to fixed
    }

    companion object {
        val defaultAgents =
            listOf(
                "architect",
                "backend-core",
                "market-data",
                "strategy-engine",
                "ai-engineer",
                "frontend",
                "qa",
            )
    }
}
