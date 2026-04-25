package kr.spdaewoon.magicjar.web.service

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `_workspace/` 의 폴더별 파일 수 스냅샷. /harness 페이지의 "산출물 트리" 위젯에서 사용.
 */
@Service
class WorkspaceTreeReader {
    data class Node(
        val path: String,
        val fileCount: Int,
    )

    fun snapshot(root: String = "_workspace"): List<Node> {
        val base = Paths.get(root)
        if (!Files.isDirectory(base)) return emptyList()
        return Files
            .walk(base, 2)
            .use { stream ->
                stream
                    .filter { Files.isDirectory(it) && it != base }
                    .map { dir -> Node(base.relativize(dir).toString(), countFiles(dir)) }
                    .toList()
            }.sortedBy { it.path }
    }

    private fun countFiles(dir: Path): Int =
        runCatching {
            Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.count().toInt() }
        }.getOrElse { 0 }
}
