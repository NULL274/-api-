package com.localmodel.gateway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.localmodel.gateway.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ChatAdapter()
    private val messages = ArrayList<ChatMessage>()

    private var assistantIndex = -1
    private var job: Job? = null
    private var lastUiRefresh = 0L
    private var lastNoted: String? = null
    private var tokenCount = 0
    private var firstTokenMs = 0L

    private val pendingImages = ArrayList<Uri>()

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            pendingImages.clear()
            pendingImages.addAll(uris)
            updateAttachChip()
        }
    }

    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = DiagLog.export(this, uri)
        toast(getString(if (ok) R.string.toast_log_exported else R.string.toast_log_export_failed))
    }

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter
        binding.sendButton.setOnClickListener { send() }
        binding.stopButton.setOnClickListener { LlmEngine.stop() }
        binding.attachButton.setOnClickListener { pickImages.launch("image/*") }
        binding.clearAttachButton.setOnClickListener {
            pendingImages.clear()
            updateAttachChip()
        }
        updateAttachChip()
        binding.signatureText.text = AppSignature.text()

        adapter.onToggleThinking = { index ->
            if (index in messages.indices) {
                val message = messages[index]
                messages[index] = message.copy(
                    thinkingExpanded = !message.thinkingExpanded,
                    thinkingUserSet = true,
                )
                adapter.update(index, messages[index])
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        syncModelAndParams()
        updateStatus()
        refreshEmptyState()
        if (Prefs.serverEnabled && !ProxyService.running) {
            DiagLog.d("检测到反代已启用但未运行，自动拉起")
            runCatching { ProxyService.start(this) }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        if (job?.isActive != true) LlmEngine.stop()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_pick_model -> {
            launchModelPicker()
            true
        }

        R.id.action_clear_chat -> {
            lifecycleScope.launch {
                LlmEngine.resetChat()
                clearChat()
            }
            true
        }

        R.id.action_export_log -> {
            exportLog.launch("localmodel-log.txt")
            true
        }

        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun launchModelPicker() {
        pickModel.launch(arrayOf("*/*"))
    }

    private fun send() {
        if (job?.isActive == true) return
        val text = binding.input.text.toString().trim()
        if (text.isEmpty()) return
        if (!LlmEngine.isLoaded) {
            toast("请先选择并加载模型")
            return
        }

        if (pendingImages.isNotEmpty() && !Prefs.enableVision) {
            toast("已附加图片，请在设置中开启“多模态输入”")
            return
        }

        val uris = pendingImages.toList()
        binding.input.setText("")
        pendingImages.clear()
        updateAttachChip()
        messages.add(ChatMessage(ChatMessage.Author.USER, text, images = uris.map { it.toString() }))
        messages.add(ChatMessage(ChatMessage.Author.ASSISTANT, ""))
        assistantIndex = messages.size - 1
        adapter.submit(messages)
        refreshEmptyState()
        binding.chatList.scrollToPosition(assistantIndex)
        setGenerating(true)
        tokenCount = 0
        firstTokenMs = 0L

        job = lifecycleScope.launch {
            try {
                val images = withContext(Dispatchers.IO) { uris.mapNotNull { readImage(it) } }
                DiagLog.d("用户发送，文本长度=${text.length}，图片数=${images.size}")
                LlmEngine.chat(text, images).collect { onChunk(it) }
            } catch (t: Throwable) {
                DiagLog.e("生成失败", t)
                onGenerationError(t)
            } finally {
                flushAssistantMessage()
                setGenerating(false)
                LlmEngine.note?.let {
                    if (it != lastNoted) {
                        lastNoted = it
                        toast(it)
                    }
                }
            }
        }
    }

    private fun readImage(uri: Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun updateAttachChip() {
        val count = pendingImages.size
        binding.attachRow.visibility = if (count == 0) View.GONE else View.VISIBLE
        binding.attachText.text = "已附加 $count 张图片"
    }

    private fun onChunk(chunk: LlmEngine.Chunk) {
        val index = assistantIndex
        if (index !in messages.indices) return
        if (chunk.text.isEmpty() && chunk.thinking.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        if (tokenCount == 0) firstTokenMs = now
        tokenCount++
        val tokensPerSecond = if (tokenCount > 1 && now > firstTokenMs) {
            (tokenCount - 1) * 1000.0 / (now - firstTokenMs)
        } else {
            null
        }

        val current = messages[index]
        val answerStarted = chunk.text.isNotEmpty() && current.content.isEmpty()
        messages[index] = current.copy(
            content = current.content + chunk.text,
            thinking = chunk.thinking?.let { (current.thinking ?: "") + it } ?: current.thinking,
            thinkingExpanded = when {
                current.thinkingUserSet -> current.thinkingExpanded
                answerStarted -> false
                !chunk.thinking.isNullOrEmpty() -> true
                else -> current.thinkingExpanded
            },
            tokensPerSecond = tokensPerSecond ?: current.tokensPerSecond,
        )

        if (now - lastUiRefresh < 60) return
        lastUiRefresh = now
        adapter.update(index, messages[index])
        binding.chatList.scrollToPosition(index)
    }

    private fun flushAssistantMessage() {
        val index = assistantIndex
        if (index in messages.indices) adapter.update(index, messages[index])
    }

    private fun onGenerationError(t: Throwable) {
        val index = assistantIndex
        if (index in messages.indices && messages[index].content.isEmpty()) {
            messages[index] = messages[index].copy(content = "[错误] ${t.message ?: "生成失败"}")
            adapter.update(index, messages[index])
        }
        toast(t.message ?: "生成失败")
    }

    private fun setGenerating(generating: Boolean) {
        binding.sendButton.visibility = if (generating) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (generating) View.VISIBLE else View.GONE
        binding.input.isEnabled = !generating
    }

    private fun clearChat() {
        messages.clear()
        assistantIndex = -1
        adapter.submit(messages)
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        binding.emptyText.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun importModel(uri: Uri) {
        val dialog = showProgressDialog(this, "正在导入模型…", horizontal = true)
        lifecycleScope.launch {
            try {
                val file = ModelStore.import(this@MainActivity, uri) { copied, total ->
                    if (total > 0) {
                        val percent = (copied * 100 / total).toInt()
                        runOnUiThread { dialog.setProgress(percent) }
                    }
                }
                dialog.dismiss()
                Prefs.modelPath = file.absolutePath
                Prefs.modelName = file.name
                loadModel(file, currentParams(), clearChat = true)
            } catch (t: Throwable) {
                dialog.dismiss()
                toast("导入失败：${t.message}")
            }
        }
    }

    private fun syncModelAndParams() {
        val path = Prefs.modelPath ?: return
        if (!File(path).exists()) return
        val params = currentParams()
        val loadedParams = LlmEngine.loadedParams
        val changed = LlmEngine.loadedModelPath != path ||
            loadedParams == null ||
            signature(loadedParams) != signature(params)
        if (!changed || job?.isActive == true) return
        lifecycleScope.launch { loadModel(File(path), params, clearChat = messages.isNotEmpty()) }
    }

    private suspend fun loadModel(file: File, params: LlmEngine.Params, clearChat: Boolean) {
        val dialog = showProgressDialog(this, "正在加载模型…")
        val result = withContext(Dispatchers.IO) {
            LlmEngine.load(this@MainActivity, file.absolutePath, params)
        }
        dialog.dismiss()
        if (result.isSuccess) {
            Prefs.modelPath = file.absolutePath
            Prefs.modelName = file.name
            if (clearChat) clearChat()
            val note = LlmEngine.note
            toast(if (note != null) "已加载：${file.name}（$note）" else "模型已加载：${file.name}")
            lastNoted = note
        } else {
            toast("加载失败：${result.exceptionOrNull()?.message}")
        }
        updateStatus()
    }

    private fun currentParams() = Prefs.engineParams()

    private fun signature(params: LlmEngine.Params): String = listOf(
        params.accelerator,
        params.maxTokens,
        params.topK,
        params.topP,
        params.temperature,
        params.systemPrompt,
        params.enableThinking,
        params.enableVision,
    ).joinToString("|")

    private fun updateStatus() {
        val model = if (LlmEngine.isLoaded) {
            val backend = LlmEngine.activeBackend ?: ""
            "${Prefs.modelName.ifBlank { "已加载" }}" + if (backend.isNotEmpty()) "[$backend]" else ""
        } else {
            "未加载模型"
        }
        val server = if (ProxyService.running) "服务运行中 · ${ProxyService.baseUrl ?: ""}" else "服务未启动"
        binding.statusText.text = "模型：$model　|　$server"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
