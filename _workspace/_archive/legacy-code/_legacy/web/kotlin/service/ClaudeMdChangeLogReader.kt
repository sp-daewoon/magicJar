package kr.spdaewoon.magicjar.web.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

/**
 * `CLAUDE.md` 의 "변경 이력" 테이블을 파싱해 최근 N건을 반환한다.
 * 포맷: `| 날짜 | 변경 내용 | 대상 | 사유 |` 헤더 이후의 파이프 테이블 행.
 */
@Service
class ClaudeMdChangeLogReader(
    @Value("\${magicjar.harness.claude-md-file:CLAUDE.md}")
    private val claudeMdFile: String,
) {
    data class Entry(
        val date: String,
        val change: String,
        val target: String,
        val reason: String,
    )

    fun recent(limit: Int = 10): List<Entry> {
        val path = Paths.get(claudeMdFile)
        if (!Files.exists(path)) return emptyList()
        val lines = runCatching { Files.readAllLines(path) }.getOrElse { return emptyList() }
        val rows = mutableListOf<Entry>()
        var inTable = false
        for (raw in lines) {
            val line = raw.trim()
            if (!inTable) {
                if (line.startsWith("| 날짜 |")) inTable = true
                continue
            }
            if (line.isBlank() || !line.startsWith("|")) {
                if (rows.isNotEmpty()) break else continue
            }
            // 헤더 구분선 `|----|----|` 무시
            if (line.matches(Regex("""^\|\s*-+.*"""))) continue
            val cells = line.trim('|').split("|").map { it.trim() }
            if (cells.size >= 4) {
                rows +=
                    Entry(
                        date = cells[0],
                        change = cells[1],
                        target = cells[2],
                        reason = cells[3],
                    )
            }
        }
        return rows.takeLast(limit).reversed()
    }
}
