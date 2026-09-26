package com.github.gotify.login

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.api.CertUtils
import com.github.gotify.databinding.ActivityLoginBinding
import com.github.gotify.databinding.ClientNameDialogBinding
import com.github.gotify.init.InitializationActivity
import com.github.gotify.log.LogsActivity
import com.github.gotify.log.UncaughtExceptionHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.cert.X509Certificate
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.tinylog.kotlin.Logger

internal class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var settings: Settings
    private val viewModel: LoginViewModel by viewModels { LoginViewModel.Factory(settings) }

    private var caCertCN: String? = null
    private lateinit var advancedDialog: AdvancedDialog

    private val caDialogResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                require(result.resultCode == RESULT_OK) { "result was ${result.resultCode}" }
                requireNotNull(result.data) { "file path was null" }

                val uri = result.data!!.data ?: throw IllegalArgumentException("file path was null")
                val fileStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("file path was invalid")
                val destinationFile = File(filesDir, CertUtils.CA_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)

                caCertCN = getNameOfCertContent(destinationFile) ?: "unknown"
                settings.caCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveCaCertificate(caCertCN!!)
            } catch (e: Exception) {
                Utils.showSnackBar(this, getString(R.string.select_ca_failed, e.message))
            }
        }

    private val clientCertDialogResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                require(result.resultCode == RESULT_OK) { "result was ${result.resultCode}" }
                requireNotNull(result.data) { "file path was null" }

                val uri = result.data!!.data ?: throw IllegalArgumentException("file path was null")
                val fileStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("file path was invalid")
                val destinationFile = File(filesDir, CertUtils.CLIENT_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)

                settings.clientCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveClientCertificate()
            } catch (e: Exception) {
                Utils.showSnackBar(this, getString(R.string.select_client_failed, e.message))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UncaughtExceptionHandler.registerCurrentThread()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()
        Logger.info("Entering ${javaClass.simpleName}")
        settings = Settings(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        binding.bottomNote.text = bottomNoteText()

        // 设置默认 URL
        val defaultUrl = settings.url
        Logger.info("Setting default URL in EditText: $defaultUrl")
        if (defaultUrl.isNotEmpty()) {
            binding.gotifyUrlEditext.setText(defaultUrl)
        }

        binding.gotifyUrlEditext.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) {
                viewModel.invalidateUrl()
            }
            override fun afterTextChanged(editable: Editable) {}
        })

        binding.checkurl.setOnClickListener { doCheckUrl() }
        binding.openLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.advancedSettings.setOnClickListener { toggleShowAdvanced() }
        binding.login.setOnClickListener { doLogin() }
        binding.oidcLogin.setOnClickListener { doOidcLogin() }
        binding.passwordToggle.setOnClickListener { togglePasswordVisibility() }

        viewModel.state.observe(this) { render(it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { handleEvent(it) }
            }
        }

        handleOidcCallback(intent)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            // 在外层避让键盘，让滚动区域的实际高度随键盘变化。
            view.updatePadding(
                left = safeInsets.left,
                top = safeInsets.top,
                right = safeInsets.right,
                bottom = safeInsets.bottom
            )
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                view.doOnLayout {
                    val focused = binding.loginScroll.findFocus() ?: return@doOnLayout
                    // 从输入框逐级转换坐标，不能把深层子视图直接当作滚动容器的子视图。
                    val rect = Rect(0, 0, focused.width, focused.height)
                    focused.requestRectangleOnScreen(rect, true)
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOidcCallback(intent)
    }

    /**
     * 原型 .auth 由脚本控制显隐：地址验证成功前，步骤 02、登录方式与登录按钮全部隐藏。
     * 检查进行中则把主按钮置灰并改成「检查中…」。
     */
    private fun render(state: LoginState) {
        binding.urlError.visibility = View.GONE
        binding.serverResult.visibility = View.GONE
        binding.authContainer.visibility = View.GONE
        binding.oidcSection.visibility = View.GONE
        binding.checkurl.isEnabled = true
        binding.checkurl.setText(R.string.login_check_url)
        binding.login.isEnabled = true
        binding.oidcLogin.isEnabled = true
        binding.oidcLogin.alpha = 1f

        when (state) {
            LoginState.UrlInput -> Unit

            LoginState.CheckingUrl -> {
                binding.checkurl.isEnabled = false
                binding.checkurl.setText(R.string.login_checking_url)
            }

            LoginState.Ready -> showReadyState()

            LoginState.LoggingIn -> {
                showReadyState()
                binding.login.isEnabled = false
                setOidcBusy()
            }

            LoginState.WaitingForClientName, LoginState.CreatingClient -> {
                showReadyState()
                binding.login.isEnabled = false
            }

            LoginState.OidcAuthorizing -> {
                showReadyState()
                setOidcBusy()
            }

            LoginState.OidcWaitingForCallback -> showReadyState()

            LoginState.OidcExchangingToken -> {
                showReadyState()
                setOidcBusy()
                binding.checkurl.isEnabled = false
                binding.login.isEnabled = false
            }
        }
    }

    private fun showReadyState() {
        val info = viewModel.gotifyInfo ?: return
        binding.checkurl.setText(R.string.login_recheck_url)
        binding.serverResult.visibility = View.VISIBLE
        binding.serverResultText.text = getString(R.string.login_server_found, info.version)
        binding.authContainer.visibility = View.VISIBLE
        if (info.oidc) {
            binding.oidcSection.visibility = View.VISIBLE
        }
    }

    private fun setOidcBusy() {
        binding.oidcLogin.isEnabled = false
        binding.oidcLogin.alpha = DISABLED_ALPHA
    }

    private fun handleEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.OpenBrowser -> {
                startActivity(Intent(Intent.ACTION_VIEW, event.url.toUri()))
            }

            LoginEvent.LoginSuccess -> {
                Utils.showSnackBar(this, getString(R.string.created_client))
                startActivity(Intent(this, InitializationActivity::class.java))
                finish()
            }

            LoginEvent.ShowClientNameDialog -> {
                showClientNameDialog(viewModel::createClient)
            }

            is LoginEvent.VersionError -> {
                Utils.showSnackBar(
                    this,
                    getString(
                        R.string.version_failed_status_code,
                        "${event.url}/version",
                        event.code
                    )
                )
            }

            is LoginEvent.VersionException -> {
                Utils.showSnackBar(
                    this,
                    getString(R.string.version_failed, "${event.url}/version", event.message)
                )
            }

            LoginEvent.InvalidCredentials -> {
                Utils.showSnackBar(this, getString(R.string.wronguserpw))
            }

            LoginEvent.ClientCreationFailed -> {
                Utils.showSnackBar(this, getString(R.string.create_client_failed))
            }

            LoginEvent.OidcAuthorizeFailed -> {
                Utils.showSnackBar(this, getString(R.string.oidc_authorize_failed))
            }

            LoginEvent.OidcTokenExchangeFailed -> {
                Utils.showSnackBar(this, getString(R.string.oidc_token_exchange_failed))
            }
        }
    }

    /**
     * 原型里地址不合法时展示 field-error（warning_soft 底、warning 文字），
     * 而不是悬浮提示；这里用同一个 url_error 视图承载。
     */
    private fun doCheckUrl() {
        val url = binding.gotifyUrlEditext.text.toString().trim().trimEnd('/')
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null || parsedUrl.scheme !in SUPPORTED_SCHEMES) {
            binding.urlError.visibility = View.VISIBLE
            return
        }
        binding.urlError.visibility = View.GONE
        if (parsedUrl.scheme == "http") {
            showHttpWarning()
        }
        viewModel.checkUrl(url)
    }

    private fun doLogin() {
        val username = binding.usernameEditext.text.toString()
        val password = binding.passwordEditext.text.toString()
        viewModel.login(username, password)
    }

    private fun doOidcLogin() {
        showClientNameDialog(viewModel::startOidcAuthorize)
    }

    private fun togglePasswordVisibility() {
        val editText = binding.passwordEditext
        val visible = editText.inputType == VISIBLE_PASSWORD_INPUT_TYPE
        editText.inputType = if (visible) PASSWORD_INPUT_TYPE else VISIBLE_PASSWORD_INPUT_TYPE
        // 切换 inputType 会把字体重置为等宽，这里显式恢复默认无衬线字体。
        editText.typeface = Typeface.DEFAULT
        editText.setSelection(editText.text.length)
        binding.passwordToggle.setText(
            if (visible) R.string.login_password_show else R.string.login_password_hide
        )
    }

    /** 原型 .bottom-note strong：前缀加粗并使用 text_primary。 */
    private fun bottomNoteText(): CharSequence {
        val strong = getString(R.string.login_bottom_note_strong)
        val rest = getString(R.string.login_bottom_note_rest)
        val text = SpannableString("$strong $rest")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.gotify_text_primary)),
            0,
            strong.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    private fun showClientNameDialog(onConfirm: (String) -> Unit) {
        val clientDialogBinding = ClientNameDialogBinding.inflate(layoutInflater)
        val clientDialogEditext = clientDialogBinding.clientNameEditext
        clientDialogBinding.clientName.hint = getString(R.string.login_client_name_label)
        clientDialogEditext.setText(Build.MODEL)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.login_client_name_title)
            .setMessage(R.string.login_client_name_message)
            .setView(clientDialogBinding.root)
            .setPositiveButton(R.string.login_dialog_done) { _, _ ->
                onConfirm(clientDialogEditext.text.toString())
            }
            .setNegativeButton(R.string.login_dialog_cancel) { _, _ ->
                viewModel.cancelClientCreation()
            }
            .setCancelable(false)
            .show()
    }

    private fun handleOidcCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith(LoginViewModel.OIDC_REDIRECT_URI)) return

        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        if (code == null || state == null) {
            Logger.warn(
                "OIDC callback missing parameters (code=${code != null}, state=${state != null})"
            )
            return
        }

        viewModel.handleOidcCallback(code, state)
    }

    private fun toggleShowAdvanced() {
        advancedDialog = AdvancedDialog(this, layoutInflater)
            .onDisableSSLChanged { _, disable ->
                viewModel.invalidateUrl()
                settings.validateSSL = !disable
            }
            .onClickSelectCaCertificate {
                viewModel.invalidateUrl()
                doSelectCertificate(caDialogResultLauncher, R.string.select_ca_file)
            }
            .onClickRemoveCaCertificate {
                viewModel.invalidateUrl()
                settings.caCertPath = null
                caCertCN = null
            }
            .onClickSelectClientCertificate {
                viewModel.invalidateUrl()
                doSelectCertificate(clientCertDialogResultLauncher, R.string.select_client_file)
            }
            .onClickRemoveClientCertificate {
                viewModel.invalidateUrl()
                settings.clientCertPath = null
            }
            .onClose { newPassword ->
                settings.clientCertPassword = newPassword
            }
            .show(
                !settings.validateSSL,
                settings.caCertPath,
                caCertCN,
                settings.clientCertPath,
                settings.clientCertPassword
            )
    }

    private fun doSelectCertificate(
        resultLauncher: ActivityResultLauncher<Intent>,
        @StringRes descriptionId: Int
    ) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            resultLauncher.launch(Intent.createChooser(intent, getString(descriptionId)))
        } catch (_: ActivityNotFoundException) {
            Utils.showSnackBar(this, getString(R.string.please_install_file_browser))
        }
    }

    private fun showHttpWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warning)
            .setCancelable(true)
            .setMessage(R.string.http_warning)
            .setPositiveButton(R.string.i_understand, null)
            .show()
    }

    private fun getNameOfCertContent(file: File): String? {
        val ca = FileInputStream(file).use { CertUtils.parseCertificate(it) }
        return (ca as X509Certificate).subjectX500Principal.name
    }

    private fun copyStreamToFile(inputStream: InputStream, file: File) {
        FileOutputStream(file).use { inputStream.copyTo(it) }
    }

    companion object {
        const val OIDC_REDIRECT_URI = "gotify://oidc/callback"

        private const val DISABLED_ALPHA = 0.6f
        private val SUPPORTED_SCHEMES = listOf("http", "https")
        private val PASSWORD_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        private val VISIBLE_PASSWORD_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
}
