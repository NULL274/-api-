package com.localmodel.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.localmodel.gateway.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val installed = ArrayList<File>()

    private var suppressCallbacks = false
    private var suppressSpinner = false

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadPrefsIntoViews()
        setupInstalledSpinner()

        binding.chooseModelButton.setOnClickListener { pickModel.launch(arrayOf("*/*")) }

        binding.lanSwitch.setOnCheckedChangeListener { _, _ -> updateServerUrl() }

        binding.lanSwitch.setOnCheckedChangeListener { _, _ -> updateServerUrl() }

        binding.serverSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            Prefs.serverEnabled = checked
            if (checked) {
                ensureOverlayPermission()
                ProxyService.start(this)
            } else {
                ProxyService.stop(this)
            }
            binding.serverSwitch.postDelayed({ updateServerUrl() }, 400)
        }

        binding.copyUrlButton.setOnClickListener { copyToClipboard(serverUrl()) }
        binding.copyKeyButton.setOnClickListener { copyToClipboard(Prefs.apiKey) }
        binding.regenKeyButton.setOnClickListener {
            Prefs.apiKey = Prefs.randomApiKey()
            binding.apiKeyInput.setText(Prefs.apiKey)
            toast(getString(R.string.toast_key_regenerated))
        }

        binding.restartServerButton.setOnClickListener {
            saveFields()
            if (Prefs.serverEnabled) {
                ProxyService.restart(this)
                toast("服务已重启")
            } else {
                toast("请先启用反代服务")
            }
        }

        updateServerUrl()

        binding.licensesButton.setOnClickListener { showLicenses() }
    }

    private fun showLicenses() {
        val text = readAsset("LICENSE") + "\n\n" + readAsset("THIRD-PARTY.md")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_licenses))
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun readAsset(name: String): String =
        runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun loadPrefsIntoViews() {
        suppressCallbacks = true
        binding.currentModelText.text = Prefs.modelPath ?: "未选择"
        when (Prefs.accelerator) {
            "CPU" -> binding.accCpu.isChecked = true
            "NPU" -> binding.accNpu.isChecked = true
            else -> binding.accGpu.isChecked = true
        }
        binding.thinkingSwitch.isChecked = Prefs.enableThinking
        binding.visionSwitch.isChecked = Prefs.enableVision
        binding.maxTokensInput.setText(Prefs.maxTokens.toString())
        binding.topKInput.setText(Prefs.topK.toString())
        binding.topPInput.setText(Prefs.topP.toString())
        binding.temperatureInput.setText(Prefs.temperature.toString())
        binding.systemPromptInput.setText(Prefs.systemPrompt)
        binding.serverSwitch.isChecked = Prefs.serverEnabled
        binding.lanSwitch.isChecked = Prefs.serverLan
        binding.portInput.setText(Prefs.serverPort.toString())
        binding.apiKeyInput.setText(Prefs.apiKey)
        suppressCallbacks = false
    }

    private fun setupInstalledSpinner() {
        installed.clear()
        installed.addAll(ModelStore.listInstalled(this))
        if (installed.isEmpty()) {
            binding.installedSpinner.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("暂无已导入模型"))
            return
        }
        val names = installed.map { it.name }
        binding.installedSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val current = Prefs.modelPath
        val selected = installed.indexOfFirst { it.absolutePath == current }
        suppressSpinner = true
        if (selected >= 0) binding.installedSpinner.setSelection(selected)
        suppressSpinner = false
        binding.installedSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    if (suppressSpinner) return
                    val file = installed.getOrNull(position) ?: return
                    Prefs.modelPath = file.absolutePath
                    Prefs.modelName = file.name
                    binding.currentModelText.text = file.absolutePath
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun saveFields() {
        Prefs.accelerator = when {
            binding.accCpu.isChecked -> "CPU"
            binding.accNpu.isChecked -> "NPU"
            else -> "GPU"
        }
        Prefs.enableThinking = binding.thinkingSwitch.isChecked
        Prefs.enableVision = binding.visionSwitch.isChecked
        Prefs.maxTokens = binding.maxTokensInput.text.toString().toIntOrNull() ?: Prefs.maxTokens
        Prefs.topK = binding.topKInput.text.toString().toIntOrNull() ?: Prefs.topK
        Prefs.topP = binding.topPInput.text.toString().toFloatOrNull() ?: Prefs.topP
        Prefs.temperature =
            binding.temperatureInput.text.toString().toFloatOrNull() ?: Prefs.temperature
        Prefs.systemPrompt = binding.systemPromptInput.text.toString()
        Prefs.serverLan = binding.lanSwitch.isChecked
        Prefs.serverPort = binding.portInput.text.toString().toIntOrNull() ?: Prefs.serverPort
        Prefs.apiKey = binding.apiKeyInput.text.toString().trim()
        updateServerUrl()
    }

    private fun importModel(uri: Uri) {
        val dialog = showProgressDialog(this, "正在导入模型…", horizontal = true)
        lifecycleScope.launch {
            try {
                val file = ModelStore.import(this@SettingsActivity, uri) { copied, total ->
                    if (total > 0) {
                        val percent = (copied * 100 / total).toInt()
                        runOnUiThread { dialog.setProgress(percent) }
                    }
                }
                dialog.dismiss()
                Prefs.modelPath = file.absolutePath
                Prefs.modelName = file.name
                binding.currentModelText.text = file.absolutePath
                setupInstalledSpinner()
                toast("已导入，返回对话页将自动加载")
            } catch (t: Throwable) {
                dialog.dismiss()
                toast("导入失败：${t.message}")
            }
        }
    }

    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        toast("请允许「在其他应用上层显示」，用于反代运行时的悬浮球")
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun serverUrl(): String = if (Prefs.serverLan) {
        "${NetUtils.lanBaseUrl(Prefs.serverPort)}/v1"
    } else {
        "${NetUtils.loopbackBaseUrl(Prefs.serverPort)}/v1"
    }

    private fun updateServerUrl() {
        val hint = if (Prefs.serverLan) "  （局域网，同一 WiFi 下其他设备可访问）" else "  （仅本机回环）"
        binding.serverUrlText.text = serverUrl() + hint
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("localmodel", text))
        toast(getString(R.string.toast_copied))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
