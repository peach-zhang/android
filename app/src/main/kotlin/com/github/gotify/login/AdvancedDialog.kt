package com.github.gotify.login

import android.content.Context
import android.view.LayoutInflater
import android.widget.CompoundButton
import androidx.annotation.StringRes
import androidx.core.widget.doOnTextChanged
import com.github.gotify.R
import com.github.gotify.databinding.AdvancedSettingsDialogBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class AdvancedDialog(
    private val context: Context,
    private val layoutInflater: LayoutInflater
) {
    private lateinit var binding: AdvancedSettingsDialogBinding
    private var onCheckedChangeListener: CompoundButton.OnCheckedChangeListener? = null
    private lateinit var onClickSelectCaCertificate: Runnable
    private lateinit var onClickRemoveCaCertificate: Runnable
    private lateinit var onClickSelectClientCertificate: Runnable
    private lateinit var onClickRemoveClientCertificate: Runnable
    private lateinit var onClose: (password: String) -> Unit

    /**
     * 原型 file-row 用「标签 · 状态」表达行内容，按钮只负责选择/移除，
     * 因此这里显式记录客户端证书是否已选中，而不再靠文案比对。
     */
    private var clientCertSelected = false

    fun onDisableSSLChanged(
        onCheckedChangeListener: CompoundButton.OnCheckedChangeListener?
    ): AdvancedDialog {
        this.onCheckedChangeListener = onCheckedChangeListener
        return this
    }

    fun onClickSelectCaCertificate(onClickSelectCaCertificate: Runnable): AdvancedDialog {
        this.onClickSelectCaCertificate = onClickSelectCaCertificate
        return this
    }

    fun onClickRemoveCaCertificate(onClickRemoveCaCertificate: Runnable): AdvancedDialog {
        this.onClickRemoveCaCertificate = onClickRemoveCaCertificate
        return this
    }

    fun onClickSelectClientCertificate(onClickSelectClientCertificate: Runnable): AdvancedDialog {
        this.onClickSelectClientCertificate = onClickSelectClientCertificate
        return this
    }

    fun onClickRemoveClientCertificate(onClickRemoveClientCertificate: Runnable): AdvancedDialog {
        this.onClickRemoveClientCertificate = onClickRemoveClientCertificate
        return this
    }

    fun onClose(onClose: (password: String) -> Unit): AdvancedDialog {
        this.onClose = onClose
        return this
    }

    fun show(
        disableSSL: Boolean,
        caCertPath: String? = null,
        caCertCN: String?,
        clientCertPath: String? = null,
        clientCertPassword: String?
    ): AdvancedDialog {
        binding = AdvancedSettingsDialogBinding.inflate(layoutInflater)
        binding.disableSSL.setText(R.string.login_skip_ssl)
        binding.disableSSL.isChecked = disableSSL
        binding.disableSSL.setOnCheckedChangeListener(onCheckedChangeListener)
        binding.clientCertPassword.hint = context.getString(R.string.login_client_cert_password)
        if (!clientCertPassword.isNullOrEmpty()) {
            binding.clientCertPasswordEdittext.setText(clientCertPassword)
        }
        binding.clientCertPasswordEdittext.doOnTextChanged { _, _, _, _ ->
            if (clientCertSelected) {
                showPasswordMissing(binding.clientCertPasswordEdittext.text.toString().isEmpty())
            }
        }
        if (caCertPath == null) {
            showSelectCaCertificate()
        } else {
            showRemoveCaCertificate(caCertCN ?: "")
        }
        if (clientCertPath == null) {
            showSelectClientCertificate()
        } else {
            showRemoveClientCertificate()
        }
        MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setTitle(R.string.login_advanced_title)
            .setMessage(R.string.login_advanced_message)
            .setPositiveButton(context.getString(R.string.login_dialog_done), null)
            .setOnDismissListener {
                onClose(binding.clientCertPasswordEdittext.text.toString())
            }
            .show()
        return this
    }

    private fun showSelectCaCertificate() {
        binding.toggleCaCert.setText(R.string.login_choose_file)
        binding.toggleCaCert.setOnClickListener { onClickSelectCaCertificate.run() }
        binding.selectedCaCert.text = rowText(R.string.login_ca_cert_label, noCertSelected())
    }

    fun showRemoveCaCertificate(certificateCN: String) {
        binding.toggleCaCert.setText(R.string.login_remove_file)
        binding.toggleCaCert.setOnClickListener {
            showSelectCaCertificate()
            onClickRemoveCaCertificate.run()
        }
        binding.selectedCaCert.text = rowText(R.string.login_ca_cert_label, certificateCN)
    }

    private fun showSelectClientCertificate() {
        clientCertSelected = false
        binding.toggleClientCert.setText(R.string.login_choose_file)
        binding.toggleClientCert.setOnClickListener { onClickSelectClientCertificate.run() }
        binding.selectedClientCert.text =
            rowText(R.string.login_client_cert_label, noCertSelected())
        showPasswordMissing(false)
        binding.clientCertPasswordEdittext.text = null
    }

    fun showRemoveClientCertificate() {
        clientCertSelected = true
        binding.toggleClientCert.setText(R.string.login_remove_file)
        binding.toggleClientCert.setOnClickListener {
            showSelectClientCertificate()
            onClickRemoveClientCertificate.run()
        }
        binding.selectedClientCert.text = rowText(
            R.string.login_client_cert_label,
            context.getString(R.string.login_cert_found)
        )
        showPasswordMissing(binding.clientCertPasswordEdittext.text.toString().isEmpty())
    }

    private fun rowText(@StringRes labelId: Int, status: String): CharSequence {
        return "${context.getString(labelId)} · $status"
    }

    private fun noCertSelected(): String = context.getString(R.string.login_no_cert_selected)

    private fun showPasswordMissing(toggled: Boolean) {
        val error = if (toggled) {
            context.getString(R.string.client_cert_password_missing)
        } else {
            null
        }
        binding.clientCertPassword.error = error
    }
}
