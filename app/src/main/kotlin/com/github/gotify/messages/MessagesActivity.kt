package com.github.gotify.messages

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import com.github.gotify.BuildConfig
import com.github.gotify.CoilInstance
import com.github.gotify.MissedMessageUtil
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.Utils.launchCoroutine
import com.github.gotify.api.Api
import com.github.gotify.api.ApiException
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.AuthApi
import com.github.gotify.client.api.ClientApi
import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.Message
import com.github.gotify.databinding.ActivityMessagesBinding
import com.github.gotify.init.InitializationActivity
import com.github.gotify.log.LogsActivity
import com.github.gotify.login.LoginActivity
import com.github.gotify.messages.provider.MessageState
import com.github.gotify.messages.provider.MessageWithImage
import com.github.gotify.service.WebSocketService
import com.github.gotify.settings.SettingsActivity
import com.github.gotify.sharing.ShareActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.kotlin.Logger

internal class MessagesActivity :
    AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMessagesBinding
    private lateinit var viewModel: MessagesModel
    private var isLoadMore = false
    private var updateAppOnDrawerClose: Long? = null
    private var loadedMessages: List<MessageWithImage> = emptyList()
    private var applicationNames: Map<Long, String> = emptyMap()
    private lateinit var listMessageAdapter: ListMessageAdapter
    private lateinit var onBackPressedCallback: OnBackPressedCallback

    /** 上一次真正生效的筛选/搜索词，用于判断是否需要重置展开态。 */
    private var lastFilterKey: String? = null
    private var lastQuery: String? = null

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val messageJson = intent.getStringExtra("message")
            val message = Utils.JSON.fromJson(
                messageJson,
                Message::class.java
            )
            launchCoroutine {
                addSingleMessage(message)
            }
        }
    }

    /** 新增：WebSocket 连接状态广播（不改动既有 NEW_MESSAGE_BROADCAST 逻辑）。 */
    private val connectionReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(WebSocketService.EXTRA_CONNECTED, false)
            val url = intent.getStringExtra(WebSocketService.EXTRA_URL)
            updateConnectionStatus(connected, url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, MessagesModelFactory(this))[MessagesModel::class.java]
        Logger.info("Entering " + javaClass.simpleName)
        initTopbar()
        initDrawer()

        val layoutManager = LinearLayoutManager(this)
        val messagesView: RecyclerView = binding.messagesView
        listMessageAdapter = ListMessageAdapter(
            this,
            viewModel.settings,
            CoilInstance.get(this)
        ) { message ->
            scheduleDeletion(message)
        }
        addBackPressCallback()
        binding.sendMessage.setOnClickListener {
            startActivity(Intent(this, ShareActivity::class.java))
        }
        binding.appBarDrawer.messageSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    applyMessageSearch()
                }

                override fun afterTextChanged(text: Editable?) = Unit
            }
        )

        messagesView.setHasFixedSize(true)
        messagesView.layoutManager = layoutManager
        messagesView.addOnScrollListener(MessageListOnScrollListener())
        messagesView.adapter = listMessageAdapter

        val appsHolder = viewModel.appsHolder
        appsHolder.onUpdate { onUpdateApps(appsHolder.get()) }
        if (appsHolder.wasRequested()) onUpdateApps(appsHolder.get()) else appsHolder.request()

        val itemTouchHelper = ItemTouchHelper(SwipeToDeleteCallback(listMessageAdapter))
        itemTouchHelper.attachToRecyclerView(messagesView)

        val swipeRefreshLayout = binding.swipeRefresh
        swipeRefreshLayout.setOnRefreshListener { onRefresh() }
        binding.drawerLayout.addDrawerListener(
            object : SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    onBackPressedCallback.isEnabled = true
                }

                override fun onDrawerClosed(drawerView: View) {
                    updateAppOnDrawerClose?.let { selectApp ->
                        updateAppOnDrawerClose = null
                        viewModel.appId = selectApp
                        selectApplicationFilter(selectApp)
                        updateSectionHead()
                        launchCoroutine {
                            updateMessagesForApplication(true, selectApp)
                        }
                    }
                    onBackPressedCallback.isEnabled = false
                }
            }
        )

        swipeRefreshLayout.isEnabled = false
        messagesView
            .viewTreeObserver
            .addOnScrollChangedListener {
                val topChild = messagesView.getChildAt(0)
                if (topChild != null) {
                    swipeRefreshLayout.isEnabled = topChild.top == 0
                } else {
                    swipeRefreshLayout.isEnabled = true
                }
            }

        val excludeFromRecent = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.setting_key_exclude_from_recent), false)
        Utils.setExcludeFromRecent(this, excludeFromRecent)
        updateConnectionStatus(WebSocketService.isConnected, viewModel.settings.url)
        launchCoroutine {
            updateMessagesForApplication(true, viewModel.appId)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        binding.learnGotify.setOnClickListener { openDocumentation() }
    }

    /** 顶栏：不再 setSupportActionBar，直接用自定义标题与两个 icon-btn。 */
    private fun initTopbar() {
        binding.appBarDrawer.actionRefresh.setOnClickListener { refreshAll() }
        binding.appBarDrawer.actionMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * 连接状态条（原型 .connection）。
     *
     * connected=false 时文案与圆点切到未连接/重连中状态。
     */
    private fun updateConnectionStatus(connected: Boolean, url: String?) {
        binding.appBarDrawer.connectionTitle.setText(
            if (connected) R.string.messages_connected else R.string.messages_disconnected
        )
        binding.appBarDrawer.connectionSubtitle.text = (url ?: viewModel.settings.url).orEmpty()
        binding.appBarDrawer.connectionLive.visibility =
            if (connected) View.VISIBLE else View.GONE
        binding.appBarDrawer.connectionLive.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) R.color.gotify_success else R.color.gotify_warning
            )
        )
        binding.appBarDrawer.connectionDot.setBackgroundResource(
            if (connected) {
                R.drawable.gotify_messages_dot_ripple
            } else {
                R.drawable.gotify_messages_dot_off
            }
        )
    }

    private fun refreshAll() {
        CoilInstance.evict(this)
        startActivity(Intent(this, InitializationActivity::class.java))
        finish()
    }

    private fun onRefresh() {
        CoilInstance.evict(this)
        listMessageAdapter.collapseExpanded()
        viewModel.messages.clear()
        launchCoroutine {
            updateMessagesAndStopLoading(loadMore(viewModel.appId))
        }
    }

    private fun openDocumentation() {
        val browserIntent = Intent(Intent.ACTION_VIEW, "https://gotify.net/docs/pushmsg".toUri())
        startActivity(browserIntent)
    }

    private fun onUpdateApps(applications: List<Application>) {
        viewModel.targetReferences.clear()
        applicationNames = applications.associate { it.id to it.name }
        listMessageAdapter.setApplications(applications)
        rebuildDrawerMenu(applications)
        updateApplicationFilters(applications)
        updateSectionHead()
        updateMessagesAndStopLoading(viewModel.messages[viewModel.appId])
    }

    /**
     * 抽屉菜单（原型 .drawer-section + .drawer-item）。
     *
     * messages_menu.xml 不在本页可改范围内，因此分组标题与条目在此按原型重建。
     */
    private fun rebuildDrawerMenu(applications: List<Application>) {
        val menu: Menu = binding.navView.menu
        menu.clear()
        viewModel.targetReferences.clear()

        menu.add(
            Menu.NONE,
            MENU_ID_HEADER_SOURCES,
            MENU_ORDER_HEADER,
            R.string.messages_drawer_sources
        )
            .setEnabled(false)
        val allItem = menu.add(
            MENU_GROUP_SOURCES,
            R.id.nav_all_messages,
            MENU_ORDER_ITEM,
            R.string.messages_drawer_all
        )
        allItem.isCheckable = true
        allItem.setIcon(R.drawable.gotify_messages_icon_all)

        var selectedItem: MenuItem = allItem
        applications.forEachIndexed { index, app ->
            val item = menu.add(MENU_GROUP_SOURCES, index, MENU_ORDER_ITEM, app.name)
            item.isCheckable = true
            if (app.id == viewModel.appId) selectedItem = item
            val target = Utils.toDrawable { icon -> item.icon = icon }
            viewModel.targetReferences.add(target)
            val request = ImageRequest.Builder(this)
                .data(Utils.resolveAbsoluteUrl(viewModel.settings.url + "/", app.image))
                .error(R.drawable.ic_alarm)
                .placeholder(R.drawable.ic_placeholder)
                .size(100, 100)
                .target(target)
                .build()
            CoilInstance.get(this).enqueue(request)
        }

        menu.add(
            Menu.NONE,
            MENU_ID_HEADER_MORE,
            MENU_ORDER_HEADER_MORE,
            R.string.messages_drawer_more
        )
            .setEnabled(false)
        menu.add(
            MENU_GROUP_MORE,
            R.id.push_message,
            MENU_ORDER_ITEM_MORE,
            R.string.messages_drawer_send
        )
            .setIcon(R.drawable.ic_send)
        menu.add(
            MENU_GROUP_MORE,
            R.id.settings,
            MENU_ORDER_ITEM_MORE,
            R.string.messages_drawer_settings
        )
            .setIcon(R.drawable.ic_settings)
        menu.add(
            MENU_GROUP_MORE,
            R.id.nav_logs,
            MENU_ORDER_ITEM_MORE,
            R.string.messages_drawer_logs
        )
            .setIcon(R.drawable.ic_bug_report)
        // 原型未画「删除全部消息」，但这是既有功能，不能因为改版而丢失。
        menu.add(MENU_GROUP_MORE, R.id.action_delete_all, MENU_ORDER_ITEM_MORE, R.string.delete_all)
            .setIcon(R.drawable.ic_delete)
        menu.add(MENU_GROUP_MORE, R.id.logout, MENU_ORDER_LOGOUT, R.string.messages_drawer_logout)
            .setIcon(R.drawable.ic_power_setting)

        selectAppInMenu(selectedItem)
    }

    private fun updateApplicationFilters(applications: List<Application>) {
        val filters = binding.appBarDrawer.applicationFilters
        filters.removeAllViews()
        filters.addView(
            createApplicationFilter(
                getString(R.string.messages_filter_all),
                MessageState.ALL_MESSAGES
            )
        )
        applications.forEach { application ->
            filters.addView(createApplicationFilter(application.name, application.id))
        }
        selectApplicationFilter(viewModel.appId)
    }

    /** 选中态由 @color/gotify_chip_* 选择器提供（代码创建 Chip 需显式套用，不能只靠 style）。 */
    private fun createApplicationFilter(label: String, appId: Long): Chip {
        return Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            text = label
            isCheckable = true
            isCheckedIconVisible = false
            tag = appId
            val density = resources.displayMetrics.density
            setChipBackgroundColorResource(R.color.gotify_chip_bg)
            chipStrokeColor = ContextCompat.getColorStateList(context, R.color.gotify_chip_stroke)
            chipStrokeWidth = density * 1f
            chipCornerRadius = density * 999f
            setTextColor(ContextCompat.getColorStateList(context, R.color.gotify_chip_text))
            setTextAppearance(R.style.TextAppearance_Gotify_Button)
            chipMinHeight = density * 32f
            setOnClickListener { selectApplication(appId, label) }
        }
    }

    private fun selectApplication(appId: Long, label: CharSequence) {
        if (appId == viewModel.appId) return
        viewModel.appId = appId
        updateSectionHead()
        selectApplicationFilter(appId)
        selectAppInMenu(findApplicationMenuItem(appId))
        startLoading()
        launchCoroutine {
            updateMessagesForApplication(true, appId)
        }
    }

    private fun selectApplicationFilter(appId: Long) {
        val filters = binding.appBarDrawer.applicationFilters
        for (index in 0 until filters.childCount) {
            val chip = filters.getChildAt(index) as Chip
            chip.isChecked = chip.tag == appId
        }
    }

    private fun findApplicationMenuItem(appId: Long): MenuItem? {
        if (appId == MessageState.ALL_MESSAGES) {
            return binding.navView.menu.findItem(R.id.nav_all_messages)
        }
        val applications = viewModel.appsHolder.get()
        val index = applications.indexOfFirst { it.id == appId }
        return if (index >= 0) binding.navView.menu.findItem(index) else null
    }

    private fun initDrawer() {
        // 应用图标是彩色图片，不能被 NavigationView 的图标着色统一染色
        binding.navView.itemIconTintList = null
        binding.navView.setNavigationItemSelectedListener(this)

        val headerView = binding.navView.getHeaderView(0)
        val settings = viewModel.settings

        headerView.findViewById<TextView>(R.id.header_connection).text = settings.url
        headerView.findViewById<TextView>(R.id.header_user).text = settings.user?.name.orEmpty()
        headerView.findViewById<TextView>(R.id.header_version).text = getString(
            R.string.versions,
            BuildConfig.VERSION_NAME,
            settings.serverVersion
        )

        headerView.findViewById<View>(R.id.refresh_all)
            .setOnClickListener { refreshAll() }
    }

    private fun addBackPressCallback() {
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val sources = viewModel.appsHolder.get()
        when (item.itemId) {
            R.id.nav_all_messages -> {
                updateAppOnDrawerClose = MessageState.ALL_MESSAGES
                startLoading()
            }

            R.id.logout -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.logout)
                    .setMessage(getString(R.string.logout_confirm))
                    .setPositiveButton(R.string.yes) { _, _ -> doLogout() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return true
            }

            R.id.action_delete_all -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_all)
                    .setMessage(R.string.ack)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        launchCoroutine { deleteMessages(viewModel.appId) }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
                return true
            }

            R.id.nav_logs -> startActivity(Intent(this, LogsActivity::class.java))

            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))

            R.id.push_message -> startActivity(Intent(this, ShareActivity::class.java))

            else -> {
                val app = sources.getOrNull(item.itemId)
                if (app != null) {
                    updateAppOnDrawerClose = app.id
                    startLoading()
                }
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun doLogout() {
        setContentView(R.layout.splash)
        launchCoroutine {
            deleteClientAndNavigateToLogin()
        }
    }

    private fun startLoading() {
        binding.swipeRefresh.isRefreshing = true
        binding.messagesView.visibility = View.GONE
    }

    private fun stopLoading() {
        binding.swipeRefresh.isRefreshing = false
        binding.messagesView.visibility = View.VISIBLE
    }

    override fun onResume() {
        Logger.info("OnResume " + javaClass.simpleName)
        val context = applicationContext
        val nManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nManager.cancelAll()
        val filter = IntentFilter()
        filter.addAction(WebSocketService.NEW_MESSAGE_BROADCAST)
        filter.addAction(WebSocketService.CONNECTION_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(
                connectionReceiver,
                IntentFilter(WebSocketService.CONNECTION_BROADCAST),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                connectionReceiver,
                IntentFilter(WebSocketService.CONNECTION_BROADCAST)
            )
        }
        launchCoroutine {
            updateMissedMessages(viewModel.messages.getLastReceivedMessage())
        }
        // 连接状态广播可能发生在注册之前，这里用服务当前状态补齐
        updateConnectionStatus(WebSocketService.isConnected, viewModel.settings.url)
        // Force re-render of all items to update relative date-times on app resume.
        listMessageAdapter.notifyDataSetChanged()
        selectAppInMenu(findApplicationMenuItem(viewModel.appId))
        updateSectionHead()
        super.onResume()
    }

    override fun onPause() {
        unregisterQuietly(receiver)
        unregisterQuietly(connectionReceiver)
        super.onPause()
    }

    /** 登出会先 setContentView(R.layout.splash)，此时广播可能已不在注册状态。 */
    private fun unregisterQuietly(target: BroadcastReceiver) {
        try {
            unregisterReceiver(target)
        } catch (e: IllegalArgumentException) {
            Logger.warn(e, "Receiver was not registered")
        }
    }

    private fun selectAppInMenu(appItem: MenuItem?) {
        if (appItem != null) {
            appItem.isChecked = true
        }
    }

    private fun scheduleDeletion(message: Message) {
        listMessageAdapter.collapseExpanded()
        val messages = viewModel.messages
        messages.deleteLocal(message)
        loadedMessages = messages[viewModel.appId]
        applyMessageSearch()
        showDeletionSnackbar()
    }

    private fun undoDelete() {
        val messages = viewModel.messages
        if (messages.undoDeleteLocal() != null) {
            loadedMessages = messages[viewModel.appId]
            applyMessageSearch()
        }
    }

    private fun showDeletionSnackbar() {
        val view: View = binding.swipeRefresh
        val snackbar = Snackbar.make(view, R.string.snackbar_deleted, Snackbar.LENGTH_LONG)
        snackbar.setAction(R.string.snackbar_undo) { undoDelete() }
        snackbar.addCallback(SnackbarCallback())
        snackbar.show()
    }

    private inner class SnackbarCallback : BaseCallback<Snackbar?>() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
            super.onDismissed(transientBottomBar, event)
            if (event != DISMISS_EVENT_ACTION && event != DISMISS_EVENT_CONSECUTIVE) {
                // Execute deletion when the snackbar disappeared without pressing the undo button
                // DISMISS_EVENT_CONSECUTIVE should be excluded as well, because it would cause the
                // deletion to be sent to the server twice, since the deletion is sent to the server
                // in MessageFacade if a message is deleted while another message was already
                // waiting for deletion.
                launchCoroutine {
                    commitDeleteMessage()
                }
            }
        }
    }

    private inner class SwipeToDeleteCallback(private val adapter: ListMessageAdapter) :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private var icon: Drawable?
        private val background: ColorDrawable

        init {
            val backgroundColorId =
                ContextCompat.getColor(this@MessagesActivity, R.color.swipeBackground)
            val iconColorId = ContextCompat.getColor(this@MessagesActivity, R.color.swipeIcon)
            val drawable = ContextCompat.getDrawable(this@MessagesActivity, R.drawable.ic_delete)
            icon = null
            if (drawable != null) {
                icon = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(icon!!, iconColorId)
            }
            background = backgroundColorId.toDrawable()
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        /**
         * 加入按天分组头后 adapter position 不再等于消息下标，
         * 因此必须经 adapter.itemAt() 换算；分组头返回 null，直接跳过删除。
         */
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val message = adapter.itemAt(viewHolder.adapterPosition) ?: return
            scheduleDeletion(message.message)
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            icon?.let {
                val itemView = viewHolder.itemView
                val iconHeight = itemView.height / 3
                val scale = iconHeight / it.intrinsicHeight.toDouble()
                val iconWidth = (it.intrinsicWidth * scale).toInt()
                val iconMarginLeftRight = 50
                val iconMarginTopBottom = (itemView.height - iconHeight) / 2
                val iconTop = itemView.top + iconMarginTopBottom
                val iconBottom = itemView.bottom - iconMarginTopBottom
                if (dX > 0) {
                    // Swiping to the right
                    val iconLeft = itemView.left + iconMarginLeftRight
                    val iconRight = itemView.left + iconMarginLeftRight + iconWidth
                    it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    background.setBounds(
                        itemView.left,
                        itemView.top,
                        itemView.left + dX.toInt(),
                        itemView.bottom
                    )
                } else if (dX < 0) {
                    // Swiping to the left
                    val iconLeft = itemView.right - iconMarginLeftRight - iconWidth
                    val iconRight = itemView.right - iconMarginLeftRight
                    it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    background.setBounds(
                        itemView.right + dX.toInt(),
                        itemView.top,
                        itemView.right,
                        itemView.bottom
                    )
                } else {
                    // View is unswiped
                    it.setBounds(0, 0, 0, 0)
                    background.setBounds(0, 0, 0, 0)
                }
                background.draw(c)
                it.draw(c)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private inner class MessageListOnScrollListener : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(view: RecyclerView, scrollState: Int) {}

        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            val linearLayoutManager = view.layoutManager as LinearLayoutManager?
            if (linearLayoutManager != null) {
                val lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition()
                val totalItemCount = view.adapter!!.itemCount
                if (lastVisibleItem > totalItemCount - 15 &&
                    totalItemCount != 0 &&
                    viewModel.messages.canLoadMore(viewModel.appId)
                ) {
                    if (!isLoadMore) {
                        isLoadMore = true
                        launchCoroutine {
                            loadMore(viewModel.appId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateMissedMessages(id: Long) {
        if (id == -1L) return

        val newMessages = MissedMessageUtil(viewModel.client.createService(MessageApi::class.java))
            .missingMessages(id).filterNotNull()
        viewModel.messages.addMessages(newMessages)

        if (newMessages.isNotEmpty()) {
            updateMessagesForApplication(true, viewModel.appId)
        }
    }

    private suspend fun loadMore(appId: Long): List<MessageWithImage> {
        val messagesWithImages = viewModel.messages.loadMore(appId)
        withContext(Dispatchers.Main) {
            updateMessagesAndStopLoading(messagesWithImages)
        }
        return messagesWithImages
    }

    private suspend fun deleteMessages(appId: Long) {
        withContext(Dispatchers.Main) {
            startLoading()
        }
        val success = viewModel.messages.deleteAll(appId)
        if (success) {
            updateMessagesForApplication(false, viewModel.appId)
        } else {
            withContext(Dispatchers.Main) {
                Utils.showSnackBar(
                    this@MessagesActivity,
                    getString(R.string.messages_delete_failed)
                )
            }
        }
    }

    private suspend fun updateMessagesForApplication(withLoadingSpinner: Boolean, appId: Long) {
        if (withLoadingSpinner) {
            withContext(Dispatchers.Main) {
                startLoading()
            }
        }
        viewModel.messages.loadMoreIfNotPresent(appId)
        withContext(Dispatchers.Main) {
            updateMessagesAndStopLoading(viewModel.messages[appId])
        }
    }

    private suspend fun addSingleMessage(message: Message) {
        viewModel.messages.addMessages(listOf(message))
        updateMessagesForApplication(false, viewModel.appId)
    }

    private suspend fun commitDeleteMessage() {
        viewModel.messages.commitDelete()
        updateMessagesForApplication(false, viewModel.appId)
    }

    private fun deleteClientAndNavigateToLogin() {
        val settings = viewModel.settings
        val tokenClient = ClientFactory.clientToken(settings)
        stopService(Intent(this@MessagesActivity, WebSocketService::class.java))
        try {
            Logger.info("Logging out...")
            val authApi = tokenClient.createService(AuthApi::class.java)
            Api.execute(authApi.logout())
        } catch (e: ApiException) {
            if (e.code == 404) {
                Logger.info("Logout endpoint not available, falling back to client deletion")
                deleteCurrentClient(tokenClient, settings)
            } else {
                Logger.error(e, "Could not logout")
            }
        }

        viewModel.settings.clear()
        startActivity(Intent(this@MessagesActivity, LoginActivity::class.java))
        finish()
    }

    private fun deleteCurrentClient(tokenClient: ApiClient, settings: Settings) {
        val api = tokenClient.createService(ClientApi::class.java)
        try {
            val clients = Api.execute(api.clients)
            val currentClient = clients.firstOrNull { it.token == settings.token }
            if (currentClient != null) {
                Logger.info("Delete client with id " + currentClient.id)
                Api.execute(api.deleteClient(currentClient.id))
            } else {
                Logger.error("Could not delete client, client does not exist.")
            }
        } catch (e: ApiException) {
            Logger.error(e, "Could not delete client")
        }
    }

    private fun updateMessagesAndStopLoading(messageWithImages: List<MessageWithImage>) {
        isLoadMore = false
        stopLoading()
        loadedMessages = messageWithImages
        applyMessageSearch()
    }

    private fun applyMessageSearch() {
        val query = binding.appBarDrawer.messageSearch.text?.toString()?.trim().orEmpty()
        val filterKey = "${viewModel.appId}"
        val messages = if (query.isEmpty()) {
            loadedMessages
        } else {
            loadedMessages.filter { message ->
                val source = applicationNames[message.message.appid].orEmpty()
                message.message.title.orEmpty().contains(query, ignoreCase = true) ||
                    message.message.message.contains(query, ignoreCase = true) ||
                    source.contains(query, ignoreCase = true)
            }
        }
        // 切换筛选 / 刷新 / 搜索时重置展开态
        if (filterKey != lastFilterKey || query != lastQuery) {
            lastFilterKey = filterKey
            lastQuery = query
            listMessageAdapter.collapseExpanded()
        }

        val hasQuery = query.isNotEmpty()
        binding.textView2.setText(
            if (hasQuery) R.string.messages_empty_search else R.string.no_messages_yet
        )
        binding.learnGotify.visibility =
            if (hasQuery) View.GONE else View.VISIBLE
        binding.flipper.displayedChild = if (messages.isEmpty()) 1 else 0

        updateSectionCount(messages.size)
        listMessageAdapter.updateList(messages)
    }

    private fun updateSectionCount(visible: Int) {
        binding.appBarDrawer.sectionCount.text = getString(R.string.messages_count, visible)
    }

    /** 分区头标题（原型 #view-title）：全部 / 某个应用名。 */
    private fun updateSectionHead() {
        binding.appBarDrawer.sectionTitle.text = if (viewModel.appId == MessageState.ALL_MESSAGES) {
            getString(R.string.messages_drawer_all)
        } else {
            applicationNames[viewModel.appId] ?: getString(R.string.messages_drawer_all)
        }
    }

    private fun ListMessageAdapter.updateList(list: List<MessageWithImage>) {
        this.submitList(group(list)) {
            val topChild = binding.messagesView.getChildAt(0)
            if (topChild != null && topChild.top == 0) {
                binding.messagesView.scrollToPosition(0)
            }
        }
    }

    companion object {
        private const val MENU_GROUP_HEADER = 900
        private const val MENU_GROUP_SOURCES = 901
        private const val MENU_GROUP_MORE = 902
        private const val MENU_ID_HEADER_SOURCES = 910
        private const val MENU_ID_HEADER_MORE = 911
        private const val MENU_ORDER_HEADER = 0
        private const val MENU_ORDER_ITEM = 1
        private const val MENU_ORDER_HEADER_MORE = 10
        private const val MENU_ORDER_ITEM_MORE = 11
        private const val MENU_ORDER_LOGOUT = 12
    }
}
