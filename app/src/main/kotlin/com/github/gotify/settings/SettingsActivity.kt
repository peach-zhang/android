package com.github.gotify.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.github.gotify.R
import com.github.gotify.Utils
import com.github.gotify.databinding.SettingsActivityBinding
import com.github.gotify.service.WebSocketService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView

/**
 * 设置页（对应 gotify-settings-concept.html）。
 *
 * 使用自定义 View 布局而不是 PreferenceFragmentCompat：原型的设置行、
 * 分组标题、说明卡片与 Preference 的默认样式差异过大。
 * 键名与默认值与原 root_preferences.xml 完全一致，读写逻辑等价。
 */
internal class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: SettingsActivityBinding

    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupAppBar()
        setupAppearance()
        setupNotifications()
        setupConnection()
        renderIntentPermissionValue()
    }

    override fun onResume() {
        super.onResume()
        renderIntentPermissionValue()
    }

    private fun setupAppBar() {
        binding.appBar.toolbar.apply {
            setTitle(R.string.title_activity_settings)
            setNavigationIcon(R.drawable.gotify_settings_back)
            setNavigationContentDescription(R.string.settings_back)
            setNavigationOnClickListener { finish() }
        }
    }

    private fun setupAppearance() {
        renderThemeValue()
        renderMessageLayoutValue()
        renderTimeFormatValue()
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowMessageLayout.setOnClickListener { showMessageLayoutDialog() }
        binding.rowTimeFormat.setOnClickListener { showTimeFormatDialog() }

        binding.switchExcludeFromRecent.isChecked =
            booleanPreference(R.string.setting_key_exclude_from_recent, false)
        binding.switchExcludeFromRecent.setOnCheckedChangeListener { _, isChecked ->
            setBooleanPreference(R.string.setting_key_exclude_from_recent, isChecked)
            Utils.setExcludeFromRecent(this, isChecked)
        }
        binding.rowExcludeFromRecent.setOnClickListener {
            binding.switchExcludeFromRecent.toggle()
        }
    }

    private fun setupNotifications() {
        val channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        binding.rowNotificationChannels.isEnabled = channelsSupported
        binding.switchNotificationChannels.isEnabled = channelsSupported
        if (channelsSupported) {
            binding.switchNotificationChannels.isChecked =
                booleanPreference(R.string.setting_key_notification_channels, false)
            binding.switchNotificationChannels.setOnCheckedChangeListener { _, isChecked ->
                setBooleanPreference(R.string.setting_key_notification_channels, isChecked)
                showRestartDialog()
            }
            binding.rowNotificationChannels.setOnClickListener {
                binding.switchNotificationChannels.toggle()
            }
        } else {
            binding.rowNotificationChannels.alpha = DISABLED_ALPHA
        }

        binding.rowIntentPermission.setOnClickListener { showIntentPermissionDialog() }

        binding.switchPromptOnreceive.isChecked =
            booleanPreference(R.string.setting_key_prompt_onreceive_intent, true)
        binding.switchPromptOnreceive.setOnCheckedChangeListener { _, isChecked ->
            setBooleanPreference(R.string.setting_key_prompt_onreceive_intent, isChecked)
        }
        binding.rowPromptOnreceive.setOnClickListener {
            binding.switchPromptOnreceive.toggle()
        }
    }

    private fun setupConnection() {
        renderReconnectDelayValue()
        binding.rowReconnectDelay.setOnClickListener { showReconnectDelayDialog() }

        binding.switchExponentialBackoff.isChecked =
            booleanPreference(R.string.setting_key_exponential_backoff, true)
        binding.switchExponentialBackoff.setOnCheckedChangeListener { _, isChecked ->
            setBooleanPreference(R.string.setting_key_exponential_backoff, isChecked)
            requestWebSocketRestart()
        }
        binding.rowExponentialBackoff.setOnClickListener {
            binding.switchExponentialBackoff.toggle()
        }
    }

    private fun renderThemeValue() {
        val entries = resources.getStringArray(R.array.settings_theme_entries)
        val values = resources.getStringArray(R.array.settings_theme_values)
        val index = values.indexOfFirst { it == currentTheme() }.coerceAtLeast(0)
        binding.valueTheme.text = entries[index]
    }

    private fun renderMessageLayoutValue() {
        val compact = getString(R.string.message_layout_value_compact)
        binding.valueMessageLayout.setText(
            if (currentMessageLayout() == compact) {
                R.string.message_layout_entry_compact
            } else {
                R.string.message_layout_entry_normal
            }
        )
    }

    private fun renderTimeFormatValue() {
        val absolute = getString(R.string.time_format_value_absolute)
        binding.valueTimeFormat.setText(
            if (currentTimeFormat() == absolute) {
                R.string.time_format_entry_absolute
            } else {
                R.string.time_format_entry_relative
            }
        )
    }

    private fun renderReconnectDelayValue() {
        binding.valueReconnectDelay.text =
            getString(R.string.design_reconnect_seconds, currentReconnectDelay())
    }

    private fun renderIntentPermissionValue() {
        binding.valueIntentPermission.setText(
            if (Settings.canDrawOverlays(this)) {
                R.string.settings_permission_granted
            } else {
                R.string.settings_permission_not_granted
            }
        )
    }

    private fun showThemeDialog() {
        showChoiceDialog(
            R.string.settings_theme_dialog_title,
            R.string.settings_theme_dialog_message,
            R.array.settings_theme_entries,
            R.array.settings_theme_values,
            currentTheme()
        ) { value ->
            setStringPreference(R.string.setting_key_theme, value)
            renderThemeValue()
            ThemeHelper.setTheme(this, value)
        }
    }

    private fun showMessageLayoutDialog() {
        showChoiceDialog(
            R.string.setting_message_layout,
            R.string.settings_message_layout_dialog_message,
            R.array.settings_message_layout_entries,
            R.array.settings_message_layout_values,
            currentMessageLayout()
        ) { value ->
            setStringPreference(R.string.setting_key_message_layout, value)
            renderMessageLayoutValue()
            showRestartDialog()
        }
    }

    private fun showTimeFormatDialog() {
        showChoiceDialog(
            R.string.setting_time_format,
            R.string.settings_time_format_dialog_message,
            R.array.settings_time_format_entries,
            R.array.settings_time_format_values,
            currentTimeFormat()
        ) { value ->
            setStringPreference(R.string.setting_key_time_format, value)
            renderTimeFormatValue()
        }
    }

    private fun showChoiceDialog(
        titleRes: Int,
        messageRes: Int,
        entriesRes: Int,
        valuesRes: Int,
        currentValue: String,
        onSelected: (String) -> Unit
    ) {
        val values = resources.getStringArray(valuesRes)
        val checked = values.indexOfFirst { it == currentValue }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setSingleChoiceItems(entriesRes, checked) { dialog, which ->
                dialog.dismiss()
                onSelected(values[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_dialog_confirm, null)
            .show()
    }

    private fun showReconnectDelayDialog() {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_DELAY_DIGITS))
        field.setText(currentReconnectDelay().toString())
        field.setSelection(field.text.length)
        field.setTextColor(ContextCompat.getColor(this, R.color.gotify_text_primary))
        field.textSize = 12f
        field.minHeight = dp(39)
        field.setPadding(dp(10), dp(8), dp(10), dp(8))
        field.setBackgroundResource(R.drawable.gotify_settings_input_bg)

        val hint = MaterialTextView(this)
        hint.setText(R.string.settings_reconnect_dialog_hint)
        hint.setTextColor(ContextCompat.getColor(this, R.color.gotify_text_secondary))
        hint.textSize = 10f
        hint.setPadding(dp(2), dp(6), dp(2), 0)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(hint)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reconnect_dialog_title)
            .setMessage(R.string.settings_reconnect_dialog_message)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_dialog_confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyReconnectDelay(field, hint, dialog)
            }
        }
        dialog.show()
    }

    private fun applyReconnectDelay(field: EditText, hint: TextView, dialog: AlertDialog) {
        val value = field.text.toString().trim().toIntOrNull()
        if (value == null || value !in MIN_RECONNECT_DELAY..MAX_RECONNECT_DELAY) {
            hint.setText(R.string.settings_reconnect_dialog_error)
            hint.setTextColor(ContextCompat.getColor(this, R.color.gotify_danger))
            return
        }
        setStringPreference(R.string.setting_key_reconnect_delay, value.toString())
        renderReconnectDelayValue()
        dialog.dismiss()
        requestWebSocketRestart()
    }

    private fun showIntentPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_intent_permission_dialog_title)
            .setMessage(R.string.settings_intent_permission_dialog_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_intent_permission_dialog_positive) { _, _ ->
                openSystemAlertWindowPermissionPage()
            }
            .show()
    }

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_restart_dialog_title)
            .setMessage(R.string.setting_restart_dialog_message)
            .setNegativeButton(R.string.setting_restart_dialog_button2, null)
            .setPositiveButton(R.string.setting_restart_dialog_button1) { _, _ -> restartApp() }
            .show()
    }

    private fun openSystemAlertWindowPermissionPage() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }

    /** 连接设置只在 WebSocketService 建立连接时读取，因此改值后要让服务重连一次。 */
    private fun requestWebSocketRestart() {
        ContextCompat.startForegroundService(this, Intent(this, WebSocketService::class.java))
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val componentName = intent!!.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun currentTheme(): String {
        return ThemeHelper.normalize(
            stringPreference(R.string.setting_key_theme, ThemeHelper.THEME_SYSTEM)
        )
    }

    private fun currentMessageLayout(): String {
        return stringPreference(
            R.string.setting_key_message_layout,
            getString(R.string.message_layout_value_normal)
        )
    }

    private fun currentTimeFormat(): String {
        return stringPreference(
            R.string.setting_key_time_format,
            getString(R.string.time_format_value_relative)
        )
    }

    private fun currentReconnectDelay(): Int {
        val stored = stringPreference(
            R.string.setting_key_reconnect_delay,
            DEFAULT_RECONNECT_DELAY.toString()
        )
        val parsed = stored.trim().toIntOrNull() ?: DEFAULT_RECONNECT_DELAY
        return parsed.coerceIn(MIN_RECONNECT_DELAY, MAX_RECONNECT_DELAY)
    }

    private fun booleanPreference(keyRes: Int, default: Boolean): Boolean {
        return preferences.getBoolean(getString(keyRes), default)
    }

    private fun setBooleanPreference(keyRes: Int, value: Boolean) {
        preferences.edit { putBoolean(getString(keyRes), value) }
    }

    private fun stringPreference(keyRes: Int, default: String): String {
        return preferences.getString(getString(keyRes), default) ?: default
    }

    private fun setStringPreference(keyRes: Int, value: String) {
        preferences.edit { putString(getString(keyRes), value) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val MIN_RECONNECT_DELAY = 5
        const val MAX_RECONNECT_DELAY = 1200
        const val DEFAULT_RECONNECT_DELAY = 60
        const val MAX_DELAY_DIGITS = 4
        const val DISABLED_ALPHA = 0.55f
    }
}
