package com.github.gotify.sharing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils.launchCoroutine
import com.github.gotify.api.Api
import com.github.gotify.api.ApiException
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.CreateMessage
import com.github.gotify.databinding.ActivityShareBinding
import com.github.gotify.messages.provider.ApplicationHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.kotlin.Logger

internal class ShareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareBinding
    private lateinit var settings: Settings
    private lateinit var appsHolder: ApplicationHolder

    private var apps: List<Application> = emptyList()
    private var appsLoaded = false
    private var selectedAppIndex = NO_APP_SELECTED
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.info("Entering ${javaClass.simpleName}")
        binding.appBar.toolbar.title = getString(R.string.send_title)
        binding.appBar.toolbar.setNavigationIcon(R.drawable.gotify_send_back)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }

        settings = Settings(this)
        prefillSharedText()
        setupUi()

        if (!settings.tokenExists()) {
            // A snackbar would vanish together with the activity, so the toast stays here.
            Toast.makeText(
                applicationContext,
                R.string.not_loggedin_share,
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        val client = ClientFactory.clientToken(settings)
        appsHolder = ApplicationHolder(this, client)
        appsHolder.onUpdate { onAppsLoaded(appsHolder.get()) }
        appsHolder.onUpdateFailed { onAppsMissing() }
        appsHolder.request()
    }

    private fun prefillSharedText() {
        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.contentInput.setText(it) }
        }
    }

    private fun setupUi() {
        // The prototype ships priority 0 as the initial value.
        binding.priorityInput.setText(DEFAULT_PRIORITY.toString())
        binding.appField.setOnClickListener { showAppPicker() }
        binding.sendButton.setOnClickListener { pushMessage() }
        binding.titleInput.doAfterTextChanged { updatePreview() }
        binding.contentInput.doAfterTextChanged {
            updateCounter()
            updatePreview()
            hideError(binding.contentErrorMessage)
        }
        binding.priorityInput.doAfterTextChanged {
            updatePriorityDescription()
            hideError(binding.priorityErrorMessage)
        }
        refreshUi()
    }

    private fun onAppsLoaded(loadedApps: List<Application>) {
        apps = loadedApps
        appsLoaded = true
        selectedAppIndex = if (apps.isEmpty()) NO_APP_SELECTED else 0
        refreshUi()
    }

    private fun onAppsMissing() {
        apps = emptyList()
        appsLoaded = true
        selectedAppIndex = NO_APP_SELECTED
        refreshUi()
    }

    private fun refreshUi() {
        updateCounter()
        updatePriorityDescription()
        updatePreview()
        updateAppState()
    }

    private fun updateCounter() {
        val length = binding.contentInput.text?.length ?: 0
        binding.contentCounter.text = getString(R.string.design_content_limit, length)
    }

    private fun updatePriorityDescription() {
        val raw = binding.priorityInput.text?.toString()?.trim().orEmpty()
        binding.priorityDescription.text = when {
            raw.isEmpty() -> getString(R.string.send_priority_desc_empty)
            !PRIORITY_PATTERN.matches(raw) -> getString(R.string.send_priority_desc_invalid)
            else -> describePriority(raw.toInt())
        }
    }

    private fun describePriority(priority: Int): String = when {
        priority == 0 -> getString(R.string.send_priority_desc_default)
        priority <= 3 -> getString(R.string.send_priority_desc_low, priority)
        priority <= 7 -> getString(R.string.send_priority_desc_normal, priority)
        else -> getString(R.string.send_priority_desc_high, priority)
    }

    private fun updatePreview() {
        val app = selectedApp()
        binding.appFieldValue.text = app?.name ?: getString(R.string.send_app_field_empty)
        binding.previewIcon.text = appIconLabel(app)
        binding.previewAppName.text = app?.name ?: getString(R.string.send_preview_no_app)
        binding.previewTitle.text = binding.titleInput.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.send_preview_no_title)
        binding.previewContent.text = binding.contentInput.text?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.send_preview_placeholder)
    }

    private fun appIconLabel(app: Application?): String {
        val name = app?.name?.trim().orEmpty()
        return name.firstOrNull()?.uppercaseChar()?.toString()
            ?: getString(R.string.send_preview_icon_placeholder)
    }

    private fun updateAppState() {
        val appsAvailable = apps.isNotEmpty()
        val missingApps = appsLoaded && !appsAvailable
        binding.appField.isEnabled = appsAvailable && !sending
        binding.previewCard.visibility = if (missingApps) View.GONE else View.VISIBLE
        binding.previewEmpty.visibility = if (missingApps) View.VISIBLE else View.GONE
        binding.sendButton.isEnabled = appsAvailable && !sending
    }

    private fun showAppPicker() {
        if (apps.isEmpty()) {
            return
        }
        val names = apps.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.send_app_dialog_title)
            .setSingleChoiceItems(names, selectedAppIndex) { dialog, which ->
                selectedAppIndex = which
                hideError(binding.appErrorMessage)
                updatePreview()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pushMessage() {
        if (sending) {
            return
        }

        val app = selectedApp()
        if (app == null) {
            showError(binding.appErrorMessage, R.string.send_error_app_missing)
            return
        }

        val content = binding.contentInput.text?.toString().orEmpty()
        if (content.isBlank()) {
            showError(binding.contentErrorMessage, R.string.send_error_content_empty)
            binding.contentInput.requestFocus()
            return
        }

        val priorityText = binding.priorityInput.text?.toString()?.trim().orEmpty()
        if (!PRIORITY_PATTERN.matches(priorityText)) {
            showError(binding.priorityErrorMessage, R.string.send_error_priority_invalid)
            binding.priorityInput.requestFocus()
            return
        }

        val message = CreateMessage()
        val titleText = binding.titleInput.text?.toString().orEmpty()
        if (titleText.isNotBlank()) {
            message.title = titleText
        }
        message.message = content
        message.priority = priorityText.toLong()

        setSending(true)
        launchCoroutine {
            val sent = executeMessageCall(app, message)
            withContext(Dispatchers.Main) {
                setSending(false)
                if (sent) {
                    finishWithFeedback(R.string.send_success)
                } else {
                    showSnackbar(R.string.send_failed)
                }
            }
        }
    }

    private fun setSending(value: Boolean) {
        sending = value
        val label = if (value) R.string.send_button_sending else R.string.send_button
        binding.sendButton.setText(label)
        updateAppState()
    }

    private fun showSnackbar(@StringRes messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    private fun finishWithFeedback(@StringRes messageRes: Int) {
        // The snackbar belongs to this window, so let it stay visible for a moment before closing.
        showSnackbar(messageRes)
        val close = Runnable {
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
        binding.root.postDelayed(close, FEEDBACK_CLOSE_DELAY_MS)
    }

    private fun showError(view: TextView, @StringRes messageRes: Int) {
        view.text = getString(messageRes)
        view.visibility = View.VISIBLE
    }

    private fun hideError(view: TextView) {
        view.visibility = View.GONE
    }

    private fun selectedApp(): Application? = apps.getOrNull(selectedAppIndex)

    private fun executeMessageCall(app: Application, message: CreateMessage): Boolean {
        // In gotify 3.0, tokens aren't returned in the API anymore, but the push api allows
        // setting the appid with client auth.
        val client = if (app.token == null) {
            message.appid = app.id
            ClientFactory.clientToken(settings)
        } else {
            ClientFactory.clientToken(settings, app.token)
        }
        return try {
            val messageApi = client.createService(MessageApi::class.java)
            Api.execute(messageApi.createMessage(message))
            true
        } catch (apiException: ApiException) {
            Logger.error(apiException, "Failed sending message")
            false
        }
    }

    private companion object {
        const val NO_APP_SELECTED = -1
        const val DEFAULT_PRIORITY = 0
        const val FEEDBACK_CLOSE_DELAY_MS = 1200L
        val PRIORITY_PATTERN = Regex("\\d{1,3}")
    }
}
