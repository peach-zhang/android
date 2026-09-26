package com.github.gotify.log

import android.content.Context
import java.io.File
import org.tinylog.kotlin.Logger

/** 日志级别。解析不出级别的行归入 [UNKNOWN]，并按原样展示。 */
internal enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    UNKNOWN
}

/**
 * 一条（可能多行的）日志记录。[message] 保留原始正文，异常堆栈等延续行以换行符拼接在其中。
 * [date] / [time] 为空字符串表示该行没有可识别的时间戳。
 */
internal data class LogEntry(
    val date: String,
    val time: String,
    val level: LogLevel,
    val message: String
)

class LoggerHelper {
    companion object {
        private const val LEVEL_PATTERN = "TRACE|DEBUG|INFO|WARN|ERROR"

        // tinylog 文件 writer 的格式：{date: yyyy-MM-dd HH:mm:ss.SSS} {level}: {message}
        private val datedLine = Regex(
            "^\\s*(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(?:[.,]\\d{1,6})?\\s*" +
                "(?:\\[[^\\]]*]\\s*)?($LEVEL_PATTERN)\\b\\s*:?\\s?(.*)$"
        )

        // 只带时间的形式：HH:mm:ss.SSS INFO: message
        private val timedLine = Regex(
            "^\\s*(\\d{2}:\\d{2}:\\d{2})(?:[.,]\\d{1,6})?\\s*" +
                "(?:\\[[^\\]]*]\\s*)?($LEVEL_PATTERN)\\b\\s*:?\\s?(.*)$"
        )

        // 只带级别的形式：INFO: message
        private val levelLine = Regex("^\\s*($LEVEL_PATTERN)\\b\\s*:?\\s?(.*)$")

        fun read(ctx: Context): String = folder(ctx)
            .listFiles()
            .orEmpty()
            .flatMap { it.readLines() }
            .fold(mutableListOf<String>()) { newLines, line -> groupExceptions(newLines, line) }
            .takeLast(200)
            .reversed()
            .joinToString(separator = "\n")

        private fun groupExceptions(
            newLines: MutableList<String>,
            line: String
        ): MutableList<String> {
            if (newLines.isNotEmpty() && (line.startsWith('\t') || line.startsWith("Caused"))) {
                newLines[newLines.lastIndex] += '\n' + line
            } else {
                newLines.add(line)
            }
            return newLines
        }

        fun clear(ctx: Context) {
            folder(ctx).listFiles()?.forEach { it.writeText("") }
            Logger.info("Logs cleared")
        }

        fun init(ctx: Context) {
            val file = folder(ctx)
            file.mkdirs()
            System.setProperty("tinylog.directory", file.absolutePath)
        }

        private fun folder(ctx: Context): File = File(ctx.filesDir, "log")

        /**
         * 只读解析：把 [read] 返回的原始文本拆成结构化条目，供日志页三列展示使用。
         * 解析不出级别的行按延续行（缩进 / Caused）并入上一条，其余原样保留为 [LogLevel.UNKNOWN]，
         * 因此不会丢弃任何一行。此方法不参与日志写入，不影响 [read] / [clear] 行为。
         */
        internal fun parse(raw: String): List<LogEntry> {
            val entries = mutableListOf<LogEntry>()
            raw.split('\n').forEach { rawLine ->
                val line = rawLine.trimEnd('\r')
                val parsed = parseLine(line)
                when {
                    parsed != null -> entries.add(parsed)

                    isContinuation(line) && entries.isNotEmpty() -> {
                        val last = entries.removeAt(entries.lastIndex)
                        entries.add(last.copy(message = last.message + '\n' + line))
                    }

                    else -> entries.add(LogEntry("", "", LogLevel.UNKNOWN, line))
                }
            }
            return entries
        }

        private fun parseLine(line: String): LogEntry? {
            datedLine.matchEntire(line)?.let {
                return LogEntry(
                    it.groupValues[1],
                    it.groupValues[2],
                    levelOf(it.groupValues[3]),
                    it.groupValues[4]
                )
            }
            timedLine.matchEntire(line)?.let { match ->
                val level = levelOf(match.groupValues[2])
                return LogEntry("", match.groupValues[1], level, match.groupValues[3])
            }
            levelLine.matchEntire(line)?.let {
                return LogEntry("", "", levelOf(it.groupValues[1]), it.groupValues[2])
            }
            return null
        }

        private fun isContinuation(line: String): Boolean {
            val indented = line.startsWith('\t') || line.startsWith(' ')
            return line.isEmpty() || indented || line.startsWith("Caused")
        }

        private fun levelOf(value: String): LogLevel = when (value) {
            "TRACE" -> LogLevel.TRACE
            "DEBUG" -> LogLevel.DEBUG
            "INFO" -> LogLevel.INFO
            "WARN" -> LogLevel.WARN
            "ERROR" -> LogLevel.ERROR
            else -> LogLevel.UNKNOWN
        }
    }
}
