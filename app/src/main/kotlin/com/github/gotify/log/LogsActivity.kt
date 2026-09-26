package com.github.gotify.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.gotify.R
import com.github.gotify.Utils
import com.github.gotify.Utils.launchCoroutine
import com.github.gotify.databinding.ActivityLogsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.kotlin.Logger

private const val TYPE_DAY = 0
private const val TYPE_ENTRY = 1

// 三列宽度与间距（原型 .log-entry：grid-template-columns:46px 43px minmax(0,1fr); gap:6px）
private const val TIME_COLUMN_WIDTH_DP = 46
private const val LEVEL_COLUMN_WIDTH_DP = 43
private const val COLUMN_GAP_DP = 6
private const val ROW_VERTICAL_PADDING_DP = 10
private const val MIN_TIME_TEXT_SIZE_SP = 8
private const val MAX_TIME_TEXT_SIZE_SP = 10

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun Context.monoText(): TextView = TextView(this).apply {
    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Gotify_Mono)
    includeFontPadding = false
}

/** 列表行的根容器。本页不允许新增 item 布局文件，因此条目视图在代码中构建。 */
private fun logRow(context: Context): LinearLayout = LinearLayout(context)

/** 日志列表的一行：日期分隔（原型 .day）或一条日志（原型 .log-entry）。 */
internal sealed interface LogListItem {
    data class Day(val label: String) : LogListItem

    data class Entry(val entry: LogEntry) : LogListItem
}

/**
 * 结构化日志列表。条目视图在代码里构建，因为本页不允许新增 item 布局文件。
 */
internal class LogEntryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<LogListItem> = emptyList()
    private var query = ""

    fun submit(newItems: List<LogListItem>, newQuery: String) {
        items = newItems
        query = newQuery
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LogListItem.Day -> TYPE_DAY
        is LogListItem.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return if (viewType == TYPE_DAY) DayViewHolder(context) else EntryViewHolder(context)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LogListItem.Day -> (holder as DayViewHolder).bind(item)
            is LogListItem.Entry -> (holder as EntryViewHolder).bind(item.entry, query)
        }
    }

    // 与消息列表相同的修复：复用后重新启用可选择文本
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is EntryViewHolder) {
            holder.message.isEnabled = false
            holder.message.isEnabled = true
        }
    }

    private class DayViewHolder(context: Context) : RecyclerView.ViewHolder(logRow(context)) {
        private val label = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Gotify_Meta)
            includeFontPadding = false
        }

        init {
            val root = itemView as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(0, context.dp(ROW_VERTICAL_PADDING_DP), 0, context.dp(9))
            root.addView(label)
            val line = View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.gotify_divider))
            }
            root.addView(
                line,
                LinearLayout.LayoutParams(0, context.dp(1), 1f).apply {
                    marginStart = context.dp(9)
                }
            )
        }

        fun bind(item: LogListItem.Day) {
            label.text = item.label
        }
    }

    private class EntryViewHolder(context: Context) : RecyclerView.ViewHolder(logRow(context)) {
        private val time = context.monoText().apply {
            setTextColor(ContextCompat.getColor(context, R.color.gotify_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                MIN_TIME_TEXT_SIZE_SP,
                MAX_TIME_TEXT_SIZE_SP,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }

        private val level = context.monoText().apply {
            setTypeface(typeface, Typeface.BOLD)
        }

        val message = context.monoText().apply {
            setTextColor(ContextCompat.getColor(context, R.color.gotify_text_primary))
            breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            setTextIsSelectable(true)
        }

        init {
            val root = itemView as LinearLayout
            root.orientation = LinearLayout.VERTICAL
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    0,
                    context.dp(ROW_VERTICAL_PADDING_DP),
                    0,
                    context.dp(ROW_VERTICAL_PADDING_DP)
                )
            }
            row.addView(
                time,
                LinearLayout.LayoutParams(
                    context.dp(TIME_COLUMN_WIDTH_DP),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            row.addView(
                level,
                LinearLayout.LayoutParams(
                    context.dp(LEVEL_COLUMN_WIDTH_DP),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = context.dp(COLUMN_GAP_DP) }
            )
            row.addView(
                message,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(COLUMN_GAP_DP)
                }
            )
            root.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val divider = View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.gotify_divider))
            }
            root.addView(
                divider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(1)
                )
            )
        }

        fun bind(entry: LogEntry, query: String) {
            time.text = entry.time
            level.text = if (entry.level == LogLevel.UNKNOWN) "" else entry.level.name
            level.setTextColor(ContextCompat.getColor(itemView.context, levelColor(entry.level)))
            message.text = highlight(entry.message, query)
        }

        private fun levelColor(level: LogLevel): Int = when (level) {
            LogLevel.INFO -> R.color.gotify_success
            LogLevel.WARN -> R.color.gotify_warning
            LogLevel.ERROR -> R.color.gotify_danger
            else -> R.color.gotify_text_secondary
        }

        private fun highlight(text: String, query: String): CharSequence {
            if (query.isEmpty()) return text
            val highlighted = SpannableString(text)
            val background = ContextCompat.getColor(itemView.context, R.color.gotify_brand_soft)
            var index = text.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                highlighted.setSpan(
                    BackgroundColorSpan(background),
                    index,
                    index + query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
            return highlighted
        }
    }
}

internal class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = LogEntryAdapter()
    private var rawLog = ""
    private var entries: List<LogEntry> = emptyList()
    private var levelFilter = LevelFilter.ALL
    private val refreshLogs =
        object : Runnable {
            override fun run() {
                updateLogs()
                handler.postDelayed(this, REFRESH_INTERVAL_MILLIS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Logger.info("Entering ${javaClass.simpleName}")

        binding.appBar.toolbar.apply {
            title = getString(R.string.logs_title)
            setNavigationIcon(R.drawable.gotify_logs_back)
            setNavigationOnClickListener { finish() }
        }

        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = adapter
        binding.logList.itemAnimator = null

        binding.copyLogs.setOnClickListener { copyFullLog() }
        binding.clearLogs.setOnClickListener { confirmClearLogs() }
        binding.searchLogs.doOnTextChanged { _, _, _, _ -> renderEntries() }
        binding.logsFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            levelFilter = LevelFilter.of(checkedIds.firstOrNull())
            renderEntries()
        }

        updateActionState()
        renderEntries()
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshLogs)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshLogs)
        super.onStop()
    }

    private fun updateLogs() {
        launchCoroutine {
            val log = LoggerHelper.read(this)
            val parsed = LoggerHelper.parse(log)
            withContext(Dispatchers.Main) {
                // 内容没变就不重建列表，避免打断用户正在进行的文本选择
                if (log != rawLog) {
                    rawLog = log
                    entries = parsed
                    renderEntries()
                }
                updateActionState()
            }
        }
    }

    private fun renderEntries() {
        val query = binding.searchLogs.text?.toString().orEmpty().trim()
        val visible = entries.filter { levelFilter.matches(it) && matchesQuery(it, query) }

        val items = mutableListOf<LogListItem>()
        var currentDate: String? = null
        visible.forEach { entry ->
            if (entry.date.isNotEmpty() && entry.date != currentDate) {
                currentDate = entry.date
                items.add(LogListItem.Day(dayLabel(entry.date)))
            }
            items.add(LogListItem.Entry(entry))
        }
        adapter.submit(items, query)

        binding.logsCounter.text = getString(R.string.logs_counter, visible.size, entries.size)
        val isEmpty = visible.isEmpty()
        binding.logEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.logList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        val hasLogs = entries.isNotEmpty()
        binding.logEmptyTitle.setText(
            if (hasLogs) R.string.logs_no_match_title else R.string.logs_empty_title
        )
        binding.logEmptyBody.setText(
            if (hasLogs) R.string.logs_no_match_body else R.string.logs_empty_body
        )
    }

    private fun matchesQuery(entry: LogEntry, query: String): Boolean {
        if (query.isEmpty()) return true
        val fields = listOf(entry.message, entry.time, entry.date, entry.level.name)
        return fields.any { it.contains(query, ignoreCase = true) }
    }

    private fun dayLabel(date: String): String {
        return if (date == LocalDate.now().toString()) getString(R.string.logs_day_today) else date
    }

    /** 复制始终针对未筛选的完整原文（原型：复制不受筛选影响）。 */
    private fun copyFullLog() {
        if (rawLog.isEmpty()) return
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("GotifyLog", rawLog))
        Utils.showSnackBar(this, getString(R.string.logs_copied))
    }

    private fun confirmClearLogs() {
        if (rawLog.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_clear_confirm_title)
            .setMessage(R.string.logs_clear_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logs_clear_confirm_positive) { _, _ -> clearLogs() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(this, R.color.gotify_danger)
            )
        }
        dialog.show()
    }

    private fun clearLogs() {
        LoggerHelper.clear(this)
        rawLog = ""
        entries = emptyList()
        renderEntries()
        updateActionState()
    }

    private fun updateActionState() {
        val hasLogs = rawLog.isNotEmpty()
        val alpha = if (hasLogs) 1f else DISABLED_ALPHA
        binding.copyLogs.isEnabled = hasLogs
        binding.copyLogs.alpha = alpha
        binding.clearLogs.isEnabled = hasLogs
        binding.clearLogs.alpha = alpha
    }

    private enum class LevelFilter(private val level: LogLevel?) {
        ALL(null),
        INFO(LogLevel.INFO),
        WARN(LogLevel.WARN),
        ERROR(LogLevel.ERROR);

        fun matches(entry: LogEntry): Boolean = level == null || level == entry.level

        companion object {
            fun of(chipId: Int?): LevelFilter = when (chipId) {
                R.id.filter_info -> INFO
                R.id.filter_warn -> WARN
                R.id.filter_error -> ERROR
                else -> ALL
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 5000L
        const val DISABLED_ALPHA = 0.45f
    }
}
