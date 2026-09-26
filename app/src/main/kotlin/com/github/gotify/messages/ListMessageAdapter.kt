package com.github.gotify.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.target.Target
import com.github.gotify.MarkwonFactory
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.Message
import com.github.gotify.databinding.MessageDayHeaderBinding
import com.github.gotify.databinding.MessageItemBinding
import com.github.gotify.databinding.MessageItemCompactBinding
import com.github.gotify.messages.provider.MessageWithImage
import com.google.android.material.snackbar.Snackbar
import io.noties.markwon.Markwon
import java.text.DateFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date

/**
 * 消息列表中的一行：按天分组头，或一条消息。
 *
 * [id] 供 DiffUtil 稳定比较，也用作「哪一条被展开」的标识。
 */
internal sealed interface MessageRow {
    val id: String
}

internal data class DayHeaderRow(val label: String) : MessageRow {
    override val id: String = "day:$label"
}

internal data class MessageRowItem(val item: MessageWithImage) : MessageRow {
    override val id: String = "message:${item.message.id}"
}

/**
 * 消息列表 Adapter（对照 gotify-messages-concept.html 改造）。
 *
 * 相对原实现的变化：
 * - item 类型变为「按天分组头 / 消息」两种 viewType（[MessageRow]），DiffUtil 仍可用；
 * - 卡片信息层级改为：来源行（图标 + 应用名 + “应用消息” + 时间）/ 可点击标题 /
 *   正文摘要 / meta 行（优先级标签 + 消息 #id）/ 可展开的详情面板；
 * - 点击标题展开或收起详情，同一时刻只展开一条；暴露 [itemAt] 供滑动删除做
 *   position → 消息 的正确换算。
 */
internal class ListMessageAdapter(
    private val context: Context,
    private val settings: Settings,
    private val imageLoader: ImageLoader,
    private val delete: Delete
) : ListAdapter<MessageRow, RecyclerView.ViewHolder>(DiffCallback) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val markwon: Markwon = MarkwonFactory.createForMessage(context, imageLoader)

    private val timeFormatRelative =
        context.resources.getString(R.string.time_format_value_relative)
    private val timeFormatPrefsKey = context.resources.getString(R.string.setting_key_time_format)

    private var messageLayout = 0
    private var applicationNames: Map<Long, String> = emptyMap()
    private var expandedId: String? = null

    init {
        val messageLayoutPrefsKey = context.resources.getString(R.string.setting_key_message_layout)
        val messageLayoutNormal = context.resources.getString(R.string.message_layout_value_normal)
        val messageLayoutSetting = prefs.getString(messageLayoutPrefsKey, messageLayoutNormal)

        messageLayout = if (messageLayoutSetting == messageLayoutNormal) {
            R.layout.message_item
        } else {
            R.layout.message_item_compact
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (currentList[position] is DayHeaderRow) {
            TYPE_DAY_HEADER
        } else if (messageLayout == R.layout.message_item) {
            TYPE_MESSAGE_REGULAR
        } else {
            TYPE_MESSAGE_COMPACT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DAY_HEADER ->
                DayHeaderViewHolder(MessageDayHeaderBinding.inflate(inflater, parent, false))

            TYPE_MESSAGE_COMPACT ->
                CompactMessageViewHolder(MessageItemCompactBinding.inflate(inflater, parent, false))

            else ->
                RegularMessageViewHolder(MessageItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = currentList[position]) {
            is DayHeaderRow ->
                (holder as DayHeaderViewHolder).bind(row)

            is MessageRowItem -> {
                val messageHolder = holder as MessageViewHolder
                messageHolder.bindMessage(row)
                messageHolder.bindExpansion(row.id)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val row = currentList[position]
        if (payloads.contains(PAYLOAD_EXPANSION) &&
            holder is MessageViewHolder &&
            row is MessageRowItem
        ) {
            holder.bindExpansion(row.id)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemId(position: Int): Long {
        return currentList[position].id.hashCode().toLong()
    }

    fun setApplications(applications: List<Application>) {
        applicationNames = applications.associate { it.id to it.name }
        notifyDataSetChanged()
    }

    /** 折叠当前展开的条目（切换筛选 / 搜索 / 刷新时调用）。 */
    fun collapseExpanded() {
        val id = expandedId ?: return
        expandedId = null
        findPosition(id)?.let { notifyItemChanged(it, PAYLOAD_EXPANSION) }
    }

    /** 供滑动删除把 adapter position 换算回真实消息；分组头或越界返回 null。 */
    fun itemAt(position: Int): MessageWithImage? {
        return (currentList.getOrNull(position) as? MessageRowItem)?.item
    }

    /** 计算分组头，返回带分组信息、可直接提交的列表。 */
    fun group(messages: List<MessageWithImage>): List<MessageRow> {
        val rows = ArrayList<MessageRow>(messages.size + 2)
        var lastLabel: String? = null
        messages.forEach { message ->
            val label = dayLabelOf(context, message.message.date)
            if (label != lastLabel) {
                rows.add(DayHeaderRow(label))
                lastLabel = label
            }
            rows.add(MessageRowItem(message))
        }
        return rows
    }

    private fun findPosition(rowId: String): Int? {
        val index = currentList.indexOfFirst { it.id == rowId }
        return if (index >= 0) index else null
    }

    private fun toggleExpansion(rowId: String) {
        val previous = expandedId
        if (previous == rowId) {
            expandedId = null
            findPosition(rowId)?.let { notifyItemChanged(it, PAYLOAD_EXPANSION) }
            return
        }
        expandedId = rowId
        if (previous != null) {
            findPosition(previous)?.let { notifyItemChanged(it, PAYLOAD_EXPANSION) }
        }
        findPosition(rowId)?.let { notifyItemChanged(it, PAYLOAD_EXPANSION) }
    }

    // Fix for message not being selectable (https://issuetracker.google.com/issues/37095917)
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is MessageViewHolder) {
            holder.message.isEnabled = false
            holder.message.isEnabled = true
        }
    }

    private fun appNameOf(message: Message): String = applicationNames[message.appid].orEmpty()

    /** 应用名首字母的配色（对齐原型 deploy / home / system 三套）。 */
    private fun appIconColors(message: Message): Pair<Int, Int> {
        val raw = message.appid.hashCode()
        val index = if (raw < 0) -raw else raw
        val palette = APP_ICON_PALETTE[index % APP_ICON_PALETTE.size]
        return ContextCompat.getColor(context, palette.first) to
            ContextCompat.getColor(context, palette.second)
    }

    private inner class DayHeaderViewHolder(private val binding: MessageDayHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: DayHeaderRow) {
            binding.dayLabelText.text = row.label
        }
    }

    private abstract inner class MessageViewHolder(
        root: View,
        private val appInitial: TextView,
        private val appName: TextView,
        val image: ImageView,
        val message: TextView,
        private val title: TextView,
        private val sourceLabel: TextView,
        private val priority: TextView,
        private val messageId: TextView,
        private val date: TextView,
        private val details: View,
        private val detailsText: TextView,
        private val copy: View,
        private val deleteButton: View
    ) : RecyclerView.ViewHolder(root) {
        private val iconContainer: View = root.findViewById(R.id.message_image_container)
        private var relativeTimeFormat = true
        private lateinit var dateTime: OffsetDateTime
        private var current: Message? = null
        private var currentRowId: String? = null

        init {
            title.setOnClickListener { currentRowId?.let { toggleExpansion(it) } }
            copy.setOnClickListener { copyToClipboard() }
            deleteButton.setOnClickListener { current?.let { delete.delete(it) } }
            itemView.setOnLongClickListener {
                copyToClipboard()
                true
            }
        }

        fun bindMessage(row: MessageRowItem) {
            val model = row.item.message
            current = model
            currentRowId = row.id

            val name = appNameOf(model)
            appName.text = name
            appInitial.text = name.trim().take(1).uppercase()
            bindIcon(row)

            title.text = model.title
            sourceLabel.visibility = View.VISIBLE

            val summary = model.message.orEmpty()
            val preview = stripMarkdown(summary)
            message.text = preview
            message.visibility = if (preview.isBlank()) View.GONE else View.VISIBLE

            bindPriority(model.priority)
            messageId.text = context.getString(R.string.messages_message_id, model.id)

            val timeFormat = prefs.getString(timeFormatPrefsKey, timeFormatRelative)
            setDateTime(model.date, timeFormat == timeFormatRelative)
            date.setOnClickListener { switchTimeFormat() }
        }

        fun bindExpansion(rowId: String) {
            val isExpanded = expandedId == rowId
            details.visibility = if (isExpanded) View.VISIBLE else View.GONE
            if (isExpanded) {
                current?.let { detailsText.text = markwon.toMarkdown(it.message) }
            } else {
                detailsText.text = ""
            }
        }

        private fun bindPriority(priority: Long?) {
            val value = priority ?: 0L
            val label: Int
            val background: Int
            val textColor: Int
            when {
                value >= HIGH_PRIORITY -> {
                    label = R.string.messages_priority_high
                    background = R.drawable.gotify_messages_priority_high
                    textColor = R.color.gotify_warning
                }

                value <= LOW_PRIORITY -> {
                    label = R.string.messages_priority_low
                    background = R.drawable.gotify_messages_priority_low
                    textColor = R.color.gotify_text_secondary
                }

                else -> {
                    label = R.string.messages_priority_normal
                    background = R.drawable.gotify_messages_priority_normal
                    textColor = R.color.gotify_brand_deep
                }
            }
            this.priority.text = context.getString(label, value)
            this.priority.setBackgroundResource(background)
            this.priority.setTextColor(ContextCompat.getColor(context, textColor))
        }

        private fun bindIcon(row: MessageRowItem) {
            val (background, foreground) = appIconColors(row.item.message)
            iconContainer.background?.mutate()?.setTint(background)
            appInitial.setTextColor(foreground)

            val url = Utils.resolveAbsoluteUrl("${settings.url}/", row.item.image)
            if (url == null) {
                showInitial()
                return
            }
            image.setImageDrawable(null)
            appInitial.visibility = View.VISIBLE
            image.visibility = View.VISIBLE
            image.load(url, imageLoader) {
                crossfade(false)
                target(AppIconTarget(appInitial, image))
            }
        }

        private fun showInitial() {
            image.setImageDrawable(null)
            image.visibility = View.GONE
            appInitial.visibility = View.VISIBLE
        }

        fun switchTimeFormat() {
            relativeTimeFormat = !relativeTimeFormat
            updateDate()
        }

        fun setDateTime(dateTime: OffsetDateTime, relativeTimeFormatPreference: Boolean) {
            this.dateTime = dateTime
            relativeTimeFormat = relativeTimeFormatPreference
            updateDate()
        }

        private fun updateDate() {
            date.text = if (relativeTimeFormat) {
                Utils.dateToRelative(dateTime)
            } else {
                val time = dateTime.toInstant().toEpochMilli()
                val date = Date(time)
                if (DateUtils.isToday(time)) {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
                }
            }
        }

        private fun copyToClipboard() {
            val value = current?.message ?: return
            val clipboard = itemView.context
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("GotifyMessageContent", value))
            Snackbar.make(
                itemView,
                itemView.context.getString(R.string.messages_copied),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        /** 只有加载成功才隐藏首字母：加载中 / 加载失败都保留首字母，避免出现空白图标。 */
        private inner class AppIconTarget(
            private val fallback: TextView,
            private val target: ImageView
        ) : Target {
            override fun onError(error: Drawable?) {
                target.setImageDrawable(null)
                target.visibility = View.GONE
                fallback.visibility = View.VISIBLE
            }

            override fun onSuccess(result: Drawable) {
                // 自定义 Target 需要自己把图片交给 ImageView（Coil 只对 ImageViewTarget 自动设置）
                target.setImageDrawable(result)
                target.visibility = View.VISIBLE
                fallback.visibility = View.GONE
            }
        }
    }

    private inner class RegularMessageViewHolder(binding: MessageItemBinding) :
        MessageViewHolder(
            binding.root,
            binding.messageAppInitial,
            binding.messageAppName,
            binding.messageImage,
            binding.messageText,
            binding.messageTitle,
            binding.messageSourceLabel,
            binding.messagePriority,
            binding.messageId,
            binding.messageDate,
            binding.messageDetails,
            binding.messageDetailsText,
            binding.messageCopy,
            binding.messageDelete
        )

    private inner class CompactMessageViewHolder(binding: MessageItemCompactBinding) :
        MessageViewHolder(
            binding.root,
            binding.messageAppInitial,
            binding.messageAppName,
            binding.messageImage,
            binding.messageText,
            binding.messageTitle,
            binding.messageSourceLabel,
            binding.messagePriority,
            binding.messageId,
            binding.messageDate,
            binding.messageDetails,
            binding.messageDetailsText,
            binding.messageCopy,
            binding.messageDelete
        )

    object DiffCallback : DiffUtil.ItemCallback<MessageRow>() {
        override fun areItemsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
            oldItem == newItem
    }

    fun interface Delete {
        fun delete(message: Message)
    }

    companion object {
        const val TYPE_DAY_HEADER = 0
        const val TYPE_MESSAGE_REGULAR = 1
        const val TYPE_MESSAGE_COMPACT = 2

        private const val PAYLOAD_EXPANSION = "gotify:expansion"
        private const val HIGH_PRIORITY = 8L
        private const val LOW_PRIORITY = 3L

        private val APP_ICON_PALETTE = listOf(
            R.color.gotify_brand_soft to R.color.gotify_brand_deep,
            R.color.gotify_success_soft to R.color.gotify_success,
            R.color.gotify_surface_soft to R.color.gotify_text_secondary
        )

        private val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\([^)]*\)""")
        private val MARKDOWN_LINK = Regex("""\[([^]]*)]\([^)]*\)""")
        private val MARKDOWN_MARKS = Regex("""[*_`>#~]""")
        private val WHITESPACE = Regex("""\s+""")

        /** 日标签：今天 / 昨天 用本地化文案，更早的日期退回相对时间。 */
        fun dayLabelOf(context: Context, dateTime: OffsetDateTime): String {
            val date = dateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            val today = LocalDate.now()
            return when (date) {
                today -> context.getString(R.string.messages_day_today)
                today.minusDays(1) -> context.getString(R.string.messages_day_yesterday)
                else -> Utils.dateToRelative(dateTime)
            }
        }

        /** 折叠态显示的纯文本摘要：去掉 Markdown 标记并压平空白。 */
        fun stripMarkdown(source: String?): String {
            if (source.isNullOrBlank()) return ""
            return source
                .replace(MARKDOWN_IMAGE, " ")
                .replace(MARKDOWN_LINK, "$1")
                .replace(MARKDOWN_MARKS, " ")
                .replace(WHITESPACE, " ")
                .trim()
        }
    }
}
