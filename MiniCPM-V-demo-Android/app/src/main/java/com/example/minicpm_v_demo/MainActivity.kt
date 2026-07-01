package com.example.minicpm_v_demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnImage: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnClearChat: ImageButton
    private lateinit var btnNewChat: ImageButton
    private lateinit var btnChatHistory: ImageButton
    private lateinit var btnModelManager: ImageButton
    private lateinit var btnGenerationSettings: ImageButton
    private lateinit var cardInputBar: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var tvTitle: TextView

    private lateinit var engine: LlamaEngine
    private var generationJob: Job? = null
    private var generationWatchdogJob: Job? = null
    private var isModelReady = false
    private var isImagePrefilled = false
    private var isImageProcessing = false
    private var isProcessingVideo = false
    private var hasAutoLoaded = false
    private var loadedModelId: String? = null
    private var messageIdCounter = 1L
    private var currentConversationId: String? = null
    private val messages = mutableListOf<ChatMessage>()
    private var createdWithLocale: String? = null
    private var isLocaleRestart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdWithLocale = LocaleManager.currentLanguage(this).tag

        // If the selected model is a TTS model, redirect to TtsActivity immediately.
        // The chat interface is only meaningful for LLM/VLM models.
        if (shouldRedirectToTts()) {
            startActivity(Intent(this, TtsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Edge-to-edge: pad the root content for status/nav bars and the IME
        // so the bottom input bar follows the soft keyboard up. Without this,
        // targetSdk=35+ draws content behind the IME and the input bar gets
        // covered.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootContent = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = maxOf(sysBars.bottom, ime.bottom)
            )
            insets
        }

        LlamaEngine.migrateLegacyLayoutIfNeeded(applicationContext)

        initViews()
        setupRecyclerView()
        setupClickListeners()
        initEngine()
    }

    private fun initViews() {
        recyclerChat = findViewById(R.id.recycler_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnImage = findViewById(R.id.btn_image)
        btnCamera = findViewById(R.id.btn_camera)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        btnNewChat = findViewById(R.id.btn_new_chat)
        btnChatHistory = findViewById(R.id.btn_chat_history)
        btnModelManager = findViewById(R.id.btn_model_manager)
        btnGenerationSettings = findViewById(R.id.btn_generation_settings)
        cardInputBar = findViewById(R.id.card_input_bar)
        appBarLayout = findViewById(R.id.appBarLayout)
        tvTitle = findViewById(R.id.tv_title)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(Markwon.create(this))
        chatAdapter.setOnStopClick {
            engine.cancelGeneration()
        }
        chatAdapter.setOnSuggestionClick { suggestion ->
            if (isModelReady && !isProcessingVideo) {
                etInput.setText(suggestion)
                handleUserInput()
            } else if (!isModelReady) {
                Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_wait_video, Toast.LENGTH_SHORT).show()
            }
        }

        recyclerChat.layoutManager = LinearLayoutManager(this)
        recyclerChat.adapter = chatAdapter

        cardInputBar.viewTreeObserver.addOnGlobalLayoutListener {
            recyclerChat.setPadding(
                recyclerChat.paddingLeft,
                recyclerChat.paddingTop,
                recyclerChat.paddingRight,
                cardInputBar.height
            )
        }

        messages.add(createWelcomeCard())
        chatAdapter.submitList(messages.toList())
    }

    private fun setupClickListeners() {
        // Pick image OR video.  iOS demo's HXPhotoPicker exposes both
        // photo and video in a single picker; on Android we ask SAF
        // for either MIME, so the user gets the same "pick anything
        // viewable" affordance with no extra "video" button.  Video is
        // only fed to the model if the loaded model is V-4.6 (gated in
        // [handleSelectedMedia] / [LlamaEngine.isVideoUnderstandingSupported]).
        btnImage.setOnClickListener { getMedia.launch(arrayOf("image/*", "video/*")) }
        btnCamera.setOnClickListener { openCamera() }
        btnSend.setOnClickListener { handleUserInput() }
        btnNewChat.setOnClickListener { startNewConversation() }
        btnChatHistory.setOnClickListener { showChatHistory() }
        btnClearChat.setOnClickListener { showClearChatDialog() }
        btnModelManager.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        btnGenerationSettings.setOnClickListener { showGenerationSettingsDialog() }
        tvTitle.setOnClickListener { showDownloadedModelPicker() }

        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                collapseAppBar()
                scrollToBottom()
            }
        }
    }

    private fun collapseAppBar() {
        appBarLayout.setExpanded(false, true)
    }

    private fun scrollToBottom() {
        recyclerChat.post {
            val adapterCount = chatAdapter.itemCount
            if (adapterCount == 0) return@post
            val layoutManager = recyclerChat.layoutManager as? LinearLayoutManager ?: return@post
            val lastView = layoutManager.findViewByPosition(adapterCount - 1)
            if (lastView != null) {
                val offset = recyclerChat.height - recyclerChat.paddingBottom - lastView.height
                layoutManager.scrollToPositionWithOffset(adapterCount - 1, offset.coerceAtMost(0))
            } else {
                recyclerChat.scrollToPosition(adapterCount - 1)
            }
        }
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat)
            .setMessage(R.string.clear_chat_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                clearChat()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startNewConversation() {
        if (generationJob?.isActive == true || (::engine.isInitialized && engine.state.value is LlamaState.Generating)) {
            Toast.makeText(this, R.string.toast_wait_generating, Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentConversation()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (::engine.isInitialized && engine.state.value is LlamaState.ModelReady) {
                    engine.clearContext()
                }
                withContext(Dispatchers.Main) {
                    currentConversationId = null
                    clearChatUI()
                    Toast.makeText(this@MainActivity, R.string.new_chat_started, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting new conversation", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_new_chat_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showChatHistory() {
        val history = ChatHistoryStore.list(this)
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.no_chat_history, Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_chat_history, null, false)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        var dialog: AlertDialog? = null
        val adapter = ChatHistoryAdapter(
            items = history,
            onOpen = { item ->
                dialog?.dismiss()
                openHistoryConversation(item.id)
            },
            onRename = { item ->
                dialog?.dismiss()
                showRenameHistoryDialog(item)
            },
            onDelete = { item ->
                dialog?.dismiss()
                showDeleteHistoryConfirm(item)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear_history) { _, _ -> showClearHistoryConfirm() }
            .show()
    }

    private fun formatHistoryItem(summary: ChatHistoryStore.Summary): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(summary.updatedAt))
        return getString(R.string.history_item_format, summary.title, time, summary.messageCount)
    }

    private fun showHistoryActionDialog(summary: ChatHistoryStore.Summary) {
        val actions = arrayOf(
            getString(R.string.open_history),
            getString(R.string.rename_history),
            getString(R.string.delete_history)
        )
        AlertDialog.Builder(this)
            .setTitle(summary.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openHistoryConversation(summary.id)
                    1 -> showRenameHistoryDialog(summary)
                    2 -> showDeleteHistoryConfirm(summary)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameHistoryDialog(summary: ChatHistoryStore.Summary) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(summary.title)
            selectAll()
            setPadding(48, 12, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_history)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (ChatHistoryStore.rename(this, summary.id, input.text.toString())) {
                    Toast.makeText(this, R.string.history_renamed, Toast.LENGTH_SHORT).show()
                    showChatHistory()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteHistoryConfirm(summary: ChatHistoryStore.Summary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_history)
            .setMessage(getString(R.string.delete_history_confirm, summary.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (ChatHistoryStore.delete(this, summary.id)) {
                    if (currentConversationId == summary.id) {
                        currentConversationId = null
                    }
                    Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    showChatHistory()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClearHistoryConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                ChatHistoryStore.clear(this)
                currentConversationId = null
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openHistoryConversation(conversationId: String) {
        if (generationJob?.isActive == true || (::engine.isInitialized && engine.state.value is LlamaState.Generating)) {
            Toast.makeText(this, R.string.toast_wait_generating, Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentConversation()
        val restored = ChatHistoryStore.loadMessages(this, conversationId)
        if (restored.isEmpty()) {
            Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (::engine.isInitialized && engine.state.value is LlamaState.ModelReady) {
                    engine.clearContext()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing context before opening history", e)
            }

            withContext(Dispatchers.Main) {
                currentConversationId = conversationId
                messages.clear()
                messages.addAll(restored)
                messageIdCounter = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
                isImagePrefilled = false
                chatAdapter.submitList(messages.toList()) {
                    scrollToBottom()
                }
                Toast.makeText(this@MainActivity, R.string.history_opened, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Pops up the slice-cap picker.  The slider drives a live preview of
     * the selected value; only on dialog "confirm" do we persist + push
     * the value to native.  Cancel = no-op.
     *
     * Live update path is cheap (no mmproj reload), but we still gate it
     * behind a confirm step so users don't accidentally regenerate cached
     * embeddings while dragging the knob.
     */
    private fun showImageSliceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_image_slice, null, false)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_image_slice)
        val tvValue = view.findViewById<android.widget.TextView>(R.id.tv_image_slice_value)

        val initial = LlamaEngine.getImageMaxSliceNums(this)
        slider.value = initial.toFloat()
        tvValue.text = initial.toString()
        slider.addOnChangeListener { _, value, _ -> tvValue.text = value.toInt().toString() }

        AlertDialog.Builder(this)
            .setTitle(R.string.image_slice_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = slider.value.toInt()
                lifecycleScope.launch {
                    engine.setImageMaxSliceNums(chosen)
                    val msgRes = if (engine.isVisionSupported) {
                        R.string.image_slice_apply_toast
                    } else {
                        R.string.image_slice_pending_toast
                    }
                    Toast.makeText(this@MainActivity, getString(msgRes, chosen), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGenerationSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_generation_settings, null, false)
        val sliderTemperature = view.findViewById<Slider>(R.id.slider_temperature)
        val sliderTopP = view.findViewById<Slider>(R.id.slider_top_p)
        val sliderTopK = view.findViewById<Slider>(R.id.slider_top_k)
        val sliderPredict = view.findViewById<Slider>(R.id.slider_predict_length)
        val tvTemperature = view.findViewById<TextView>(R.id.tv_temperature_value)
        val tvTopP = view.findViewById<TextView>(R.id.tv_top_p_value)
        val tvTopK = view.findViewById<TextView>(R.id.tv_top_k_value)
        val tvPredict = view.findViewById<TextView>(R.id.tv_predict_value)

        fun applySettings(settings: GenerationSettingsStore.Settings) {
            sliderTemperature.value = settings.temperature
            sliderTopP.value = settings.topP
            sliderTopK.value = settings.topK.toFloat()
            sliderPredict.value = settings.predictLength.toFloat()
        }

        fun refreshLabels() {
            tvTemperature.text = getString(
                R.string.generation_temperature_value,
                String.format(Locale.getDefault(), "%.2f", sliderTemperature.value)
            )
            tvTopP.text = getString(
                R.string.generation_top_p_value,
                String.format(Locale.getDefault(), "%.2f", sliderTopP.value)
            )
            val topK = sliderTopK.value.toInt()
            tvTopK.text = if (topK == 0) {
                getString(R.string.generation_top_k_disabled)
            } else {
                getString(R.string.generation_top_k_value, topK)
            }
            tvPredict.text = getString(R.string.generation_predict_value, sliderPredict.value.toInt())
        }

        applySettings(GenerationSettingsStore.get(this))
        refreshLabels()

        sliderTemperature.addOnChangeListener { _, _, _ -> refreshLabels() }
        sliderTopP.addOnChangeListener { _, _, _ -> refreshLabels() }
        sliderTopK.addOnChangeListener { _, _, _ -> refreshLabels() }
        sliderPredict.addOnChangeListener { _, _, _ -> refreshLabels() }

        AlertDialog.Builder(this)
            .setTitle(R.string.generation_settings)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val settings = GenerationSettingsStore.Settings(
                    temperature = sliderTemperature.value,
                    topP = sliderTopP.value,
                    topK = sliderTopK.value.toInt(),
                    predictLength = sliderPredict.value.toInt()
                )
                GenerationSettingsStore.save(this, settings)
                val isGenerating = generationJob?.isActive == true ||
                        (::engine.isInitialized && engine.state.value is LlamaState.Generating)
                if (::engine.isInitialized && !isGenerating) {
                    engine.applyGenerationSettings(settings)
                }
                Toast.makeText(this, R.string.generation_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearChatUI() {
        messages.clear()
        messages.add(createWelcomeCard())
        messageIdCounter = 1L
        isImagePrefilled = false
        chatAdapter.submitList(messages.toList())
    }

    private fun createWelcomeCard(): ChatMessage.WelcomeCard {
        val selectedModel = LlamaEngine.getSelectedModel(applicationContext)
        return ChatMessage.WelcomeCard(
            isTextOnly = selectedModel.isTextOnly,
            variant = Random.nextInt(10_000)
        )
    }

    private fun saveCurrentConversation() {
        currentConversationId = ChatHistoryStore.saveConversation(
            this,
            currentConversationId,
            messages
        )
    }

    private fun clearChat() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.clearContext()
                withContext(Dispatchers.Main) {
                    currentConversationId = null
                    clearChatUI()
                    Toast.makeText(this@MainActivity, R.string.clear_chat_toast, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing context", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_clear_chat_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initEngine() {
        lifecycleScope.launch(Dispatchers.Default) {
            engine = LlamaEngine.getInstance(applicationContext)
            withContext(Dispatchers.Main) {
                observeEngineState()
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized,
                    is LlamaState.Initializing -> {
                        enableInput(false)
                    }
                    is LlamaState.Initialized -> {
                        enableInput(false)
                        if (!hasAutoLoaded) {
                            hasAutoLoaded = true
                            loadDefaultModel()
                        }
                    }
                    is LlamaState.LoadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.ModelReady -> {
                        isModelReady = true
                        loadedModelId = LlamaEngine.getSelectedModel(applicationContext).id
                        enableInput(true)
                        updateUIForModelType()
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt,
                    is LlamaState.Generating -> {
                        enableInput(false)
                    }
                    is LlamaState.PrefillingImage -> {
                        isModelReady = true
                        etInput.isEnabled = true
                        btnSend.isEnabled = !isProcessingVideo
                        btnImage.isEnabled = false
                        btnCamera.isEnabled = false
                    }
                    is LlamaState.UnloadingModel -> {
                        enableInput(false)
                    }
                    is LlamaState.Error -> {
                        enableInput(false)
                    }
                }
            }
        }
    }

    private fun enableInput(enable: Boolean) {
        etInput.isEnabled = enable
        btnSend.isEnabled = enable && !isProcessingVideo
        if (!enable) {
            btnImage.isEnabled = false
            btnCamera.isEnabled = false
        } else {
            val canUseVisionInput = engine.isVisionSupported && !isImageProcessing && !isProcessingVideo
            btnImage.isEnabled = canUseVisionInput
            btnCamera.isEnabled = canUseVisionInput
        }
    }

    private fun shouldRedirectToTts(): Boolean {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        return model.isTts
    }

    private fun updateUIForModelType() {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        val isVision = engine.isVisionSupported

        tvTitle.setText(if (isVision) R.string.app_title else R.string.app_title_text)
        btnImage.visibility = if (isVision) View.VISIBLE else View.GONE
        btnCamera.visibility = if (isVision) View.VISIBLE else View.GONE
        btnImage.isEnabled = isVision
        btnCamera.isEnabled = isVision

        refreshWelcomeCard(model.isTextOnly)
    }

    private fun refreshWelcomeCard(isTextOnly: Boolean) {
        val welcomeIndex = messages.indexOfFirst { it is ChatMessage.WelcomeCard }
        if (welcomeIndex >= 0) {
            val old = messages[welcomeIndex] as ChatMessage.WelcomeCard
            messages[welcomeIndex] = old.copy(isTextOnly = isTextOnly)
            chatAdapter.submitList(messages.toList())
        }
    }

    private fun loadDefaultModel() {
        val ctx = applicationContext
        val model = LlamaEngine.getSelectedModel(ctx)
        val ggufFile = File(LlamaEngine.modelPath(ctx))
        val mmprojPathStr = LlamaEngine.mmprojPath(ctx)
        val mmprojFile = mmprojPathStr?.let { File(it) }

        val ggufMissing = !ggufFile.exists()
        val mmprojMissing = !model.isTextOnly && (mmprojFile == null || !mmprojFile.exists())

        if (ggufMissing || mmprojMissing) {
            promptDownloadModels(
                ggufMissing = ggufMissing,
                mmprojMissing = mmprojMissing
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mmprojArg = if (mmprojFile != null && mmprojFile.exists()) mmprojFile.absolutePath else null
                engine.loadModel(ggufFile.absolutePath, mmprojArg)
                loadedModelId = model.id
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                engine.resetToInitialized()
                hasAutoLoaded = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_model_load_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptDownloadModels(ggufMissing: Boolean, mmprojMissing: Boolean) {
        val message = when {
            ggufMissing && mmprojMissing ->
                getString(R.string.download_prompt_all_missing)
            mmprojMissing ->
                getString(R.string.download_prompt_mmproj_missing)
            else ->
                getString(R.string.download_prompt_incomplete)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_prompt_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.go_download) { _, _ ->
                startActivity(Intent(this, ModelManagerActivity::class.java))
            }
            .setNegativeButton(R.string.later) { _, _ ->
                Toast.makeText(
                    this,
                    R.string.download_prompt_hint,
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun showDownloadedModelPicker() {
        if (generationJob?.isActive == true || (::engine.isInitialized && engine.state.value is LlamaState.Generating)) {
            Toast.makeText(this, R.string.toast_wait_generating, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadedModels = ModelInfo.AVAILABLE_MODELS
            .filter { !it.isTts && LlamaEngine.modelsExist(this, it) }
        if (downloadedModels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.quick_model_switch_title)
                .setMessage(R.string.quick_model_switch_empty)
                .setPositiveButton(R.string.go_download) { _, _ ->
                    startActivity(Intent(this, ModelManagerActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_quick_model_switch, null, false)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_quick_models)
        val selectedId = LlamaEngine.getSelectedModel(this).id
        var dialog: AlertDialog? = null
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = QuickModelAdapter(
            models = downloadedModels,
            selectedModelId = selectedId,
            loadedModelId = loadedModelId,
            onModelSelected = { model ->
                dialog?.dismiss()
                requestSwitchToModel(model)
            }
        )

        dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestSwitchToModel(target: ModelInfo) {
        val selected = LlamaEngine.getSelectedModel(this)
        if (target.id == selected.id && loadedModelId == target.id && engine.state.value is LlamaState.ModelReady) {
            Toast.makeText(this, getString(R.string.quick_model_already_loaded, target.displayName), Toast.LENGTH_SHORT).show()
            return
        }

        val switchingVisionToText = engine.isVisionSupported && target.isTextOnly && currentChatHasImageContent()
        if (switchingVisionToText) {
            showVisionToTextWarning(target)
        } else {
            switchToDownloadedModel(target)
        }
    }

    private fun currentChatHasImageContent(): Boolean =
        isImagePrefilled || messages.any { it is ChatMessage.UserMessage && it.imageBitmap != null }

    private fun showVisionToTextWarning(target: ModelInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_vision_to_text_warning, null, false)
        AlertDialog.Builder(this)
            .setTitle(R.string.vision_to_text_warning_title)
            .setView(view)
            .setPositiveButton(R.string.switch_anyway) { _, _ ->
                switchToDownloadedModel(target)
            }
            .setNegativeButton(R.string.keep_vision_model, null)
            .show()
    }

    private fun switchToDownloadedModel(target: ModelInfo) {
        enableInput(false)
        saveCurrentConversation()
        LlamaEngine.setSelectedModel(applicationContext, target.id)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (engine.state.value is LlamaState.ModelReady) {
                    engine.unloadModel()
                }
                val modelPath = LlamaEngine.modelPath(applicationContext, target)
                val mmprojPath = LlamaEngine.mmprojPath(applicationContext, target)
                val mmprojArg = mmprojPath?.let { if (File(it).exists()) it else null }
                engine.loadModel(modelPath, mmprojArg)
                loadedModelId = target.id
                withContext(Dispatchers.Main) {
                    currentConversationId = null
                    clearChatUI()
                    updateUIForModelType()
                    Toast.makeText(this@MainActivity, getString(R.string.toast_load_success, target.displayName), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching model from title picker", e)
                engine.resetToInitialized()
                hasAutoLoaded = false
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_model_load_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val getMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Media picker result: $uri")
        uri?.let { handleSelectedMedia(it) }
    }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            Toast.makeText(this, R.string.toast_camera_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            handleCapturedPhoto(bitmap)
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePhoto.launch(null)
        } else {
            Toast.makeText(this, R.string.toast_camera_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        if (!isModelReady || !engine.isVisionSupported) {
            Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhoto.launch(null)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleSelectedMedia(uri: Uri) {
        Log.i(TAG, "Selected media uri=$uri, isModelReady=$isModelReady, engineState=${engine.state.value.javaClass.simpleName}")
        if (!isModelReady) {
            Toast.makeText(this, R.string.toast_load_model_first, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = contentResolver.getType(uri).orEmpty()
        Log.i(TAG, "Selected media mime=${mime.ifBlank { "<empty>" }}")
        when {
            mime.startsWith("video/") -> handleSelectedVideo(uri)
            mime.startsWith("image/") || mime.isEmpty() -> handleSelectedImage(uri)
            else -> {
                Toast.makeText(this, getString(R.string.toast_unsupported_file, mime), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        isImageProcessing = true
        enableInput(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Begin processing selected image: $uri")
                val decodedBitmap = contentResolver.openInputStream(uri)?.use { input ->
                    val decodedBitmap = BitmapFactory.decodeStream(input)
                        ?: throw RuntimeException(getString(R.string.error_decode_image))
                    Log.i(TAG, "Decoded selected image ${decodedBitmap.width}x${decodedBitmap.height}")
                    decodedBitmap
                } ?: throw RuntimeException(getString(R.string.error_read_image))
                processVisionBitmap(decodedBitmap, getFileName(uri))
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_image_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isImageProcessing = false
                    enableInput(isModelReady)
                }
            }
        }
    }

    private fun handleCapturedPhoto(bitmap: Bitmap) {
        isImageProcessing = true
        enableInput(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                processVisionBitmap(bitmap, getString(R.string.captured_photo_name))
            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured photo", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_image_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isImageProcessing = false
                    enableInput(isModelReady)
                }
            }
        }
    }

    private suspend fun processVisionBitmap(sourceBitmap: Bitmap, sourceName: String) {
        val bitmap = downscaleForVision(sourceBitmap)
        if (bitmap !== sourceBitmap) {
            sourceBitmap.recycle()
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        Log.i(TAG, "Compressed $sourceName for vision ${bitmap.width}x${bitmap.height}, ${stream.size()} bytes")

        val imageBytes = stream.toByteArray()
        val width = bitmap.width
        val height = bitmap.height
        val sizeKb = imageBytes.size / 1024
        val imageInfo = "$sourceName · $width x $height ($sizeKb KB)"
        val msgId = messageIdCounter++

        withContext(Dispatchers.Main) {
            val imageMessage = ChatMessage.UserMessage(
                id = msgId,
                text = "",
                imageBitmap = bitmap,
                imageInfo = imageInfo,
                isPrefilling = true
            )
            messages.add(imageMessage)
            chatAdapter.submitList(messages.toList()) {
                scrollToBottom()
            }
        }

        Log.i(TAG, "Prefilling $sourceName through official-style vision path...")
        engine.prefillImage(imageBytes)
        Log.i(TAG, "$sourceName prefilled successfully")

        isImagePrefilled = true

        withContext(Dispatchers.Main) {
            val index = messages.indexOfFirst { it.id == msgId }
            if (index >= 0) {
                messages[index] = (messages[index] as ChatMessage.UserMessage).copy(
                    isPrefilling = false
                )
                chatAdapter.submitList(messages.toList())
            }
        }
    }

    private fun downscaleForVision(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        val limit = LlamaEngine.getVisionImageMaxSide(this)
        if (limit == LlamaEngine.ORIGINAL_VISION_IMAGE_MAX_SIDE) {
            Log.i(TAG, "Keeping original vision image size ${source.width}x${source.height}")
            return source
        }
        if (maxSide <= limit) {
            return source
        }
        val scale = limit.toFloat() / maxSide.toFloat()
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        Log.i(TAG, "Downscaling vision image ${source.width}x${source.height} -> ${width}x${height} (limit=$limit)")
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /**
     * Video-understanding pipeline (iOS-equivalent
     * MBHomeViewController+CaptureVideo.processVideoFrame):
     * extract up to 64 uniformly-sampled frames off the IO dispatcher,
     * append a single chat cell with the first frame as thumbnail,
     * then hand the frames to [LlamaEngine.prefillVideoFrames] which
     * loops `prefillImage(...)` under a temporary slice=1 cap.
     *
     * Gated to MiniCPM-V-4.6 because that's where iOS enables the
     * feature and where the vision token layout matches this path.
     */
    private fun handleSelectedVideo(uri: Uri) {
        if (!engine.isVideoUnderstandingSupported) {
            Toast.makeText(this,
                R.string.video_only_v46,
                Toast.LENGTH_LONG).show()
            return
        }

        isProcessingVideo = true
        lifecycleScope.launch(Dispatchers.IO) {
            val msgId = messageIdCounter++
            val startNs = System.nanoTime()
            try {
                val extracted = VideoFrameExtractor.extract(applicationContext, uri)
                val info = VideoFrameExtractor.formatVideoInfo(applicationContext, extracted)
                Log.i(TAG, "Video info: $info")

                withContext(Dispatchers.Main) {
                    val videoMessage = ChatMessage.UserMessage(
                        id = msgId,
                        text = "",
                        imageBitmap = extracted.thumbnail,
                        imageInfo = info,
                        isPrefilling = true,
                        isVideo = true
                    )
                    messages.add(videoMessage)
                    chatAdapter.submitList(messages.toList()) {
                        scrollToBottom()
                    }
                }

                engine.prefillVideoFrames(extracted.frames) { current, total ->
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == msgId }
                        if (index >= 0) {
                            val cur = messages[index] as ChatMessage.UserMessage
                            messages[index] = cur.copy(
                                imageInfo = getString(R.string.video_processing_progress, info, current, total)
                            )
                            chatAdapter.submitList(messages.toList())
                        }
                    }
                }

                isImagePrefilled = true

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                withContext(Dispatchers.Main) {
                    isProcessingVideo = false
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        val cur = messages[index] as ChatMessage.UserMessage
                        messages[index] = cur.copy(
                            imageInfo = getString(R.string.video_preprocessing_done, info, elapsedMs / 1000.0),
                            isPrefilling = false
                        )
                        chatAdapter.submitList(messages.toList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing video", e)
                withContext(Dispatchers.Main) {
                    isProcessingVideo = false
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index >= 0) {
                        messages.removeAt(index)
                        chatAdapter.submitList(messages.toList())
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.toast_video_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "file-${System.currentTimeMillis()}"
    }

    private fun handleUserInput() {
        val userMsg = etInput.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        if (isImageProcessing || messages.any { it is ChatMessage.UserMessage && it.isPrefilling }) {
            Toast.makeText(this, R.string.toast_wait_image_prefill, Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessingVideo) {
            Toast.makeText(this, R.string.toast_wait_video, Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(
            TAG,
            "handleUserInput: length=${userMsg.length}, isImagePrefilled=$isImagePrefilled, " +
                "engineState=${engine.state.value.javaClass.simpleName}"
        )

        etInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)

        etInput.text = null
        enableInput(false)

        collapseAppBar()

        val msgId = messageIdCounter++
        val userMessage = ChatMessage.UserMessage(
            id = msgId,
            text = userMsg,
            imageBitmap = null,
            imageInfo = null
        )
        messages.add(userMessage)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        isImagePrefilled = false

        val aiMsgId = messageIdCounter++
        val aiMessage = ChatMessage.AiMessage(id = aiMsgId, text = "", isGenerating = true)
        messages.add(aiMessage)
        chatAdapter.setActiveAiMessage(aiMsgId)
        chatAdapter.submitList(messages.toList()) {
            scrollToBottom()
        }

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            val fullResponse = StringBuilder()
            val generationSettings = GenerationSettingsStore.get(this@MainActivity)
            generationWatchdogJob?.cancel()
            generationWatchdogJob = lifecycleScope.launch {
                delay(VISION_GENERATION_WATCHDOG_MS)
                if (generationJob?.isActive == true && fullResponse.isEmpty()) {
                    Log.w(TAG, "Generation watchdog fired after ${VISION_GENERATION_WATCHDOG_MS}ms")
                    engine.cancelGeneration()
                    val index = messages.indexOfFirst { it.id == aiMsgId }
                    if (index >= 0) {
                        messages[index] = (messages[index] as ChatMessage.AiMessage).copy(
                            text = "Vision request timed out. Please try a smaller image or reload the model.",
                            isGenerating = false
                        )
                    }
                    chatAdapter.setGeneratingDone(aiMsgId)
                    chatAdapter.clearActiveAiMessage()
                    chatAdapter.submitList(messages.toList())
                    enableInput(true)
                    scrollToBottom()
                }
            }
            engine.sendUserPrompt(userMsg, generationSettings.predictLength)
                .onCompletion {
                    generationWatchdogJob?.cancel()
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == aiMsgId }
                        if (index >= 0) {
                            messages[index] = (messages[index] as ChatMessage.AiMessage).copy(
                                text = fullResponse.toString(),
                                isGenerating = false
                            )
                        }
                        chatAdapter.setGeneratingDone(aiMsgId)
                        chatAdapter.clearActiveAiMessage()
                        chatAdapter.submitList(messages.toList())
                        saveCurrentConversation()
                        enableInput(true)
                        scrollToBottom()
                    }
                }
                .collect { token ->
                    fullResponse.append(token)
                    withContext(Dispatchers.Main) {
                        val currentText = fullResponse.toString()
                        val index = messages.indexOfFirst { it.id == aiMsgId }
                        if (index >= 0) {
                            messages[index] = ChatMessage.AiMessage(
                                id = aiMsgId,
                                text = currentText,
                                isGenerating = true
                            )
                        }
                        chatAdapter.updateStreamingText(aiMsgId, currentText)
                        scrollToBottom()
                    }
                }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is TextInputEditText) {
                val barRect = android.graphics.Rect()
                cardInputBar.getGlobalVisibleRect(barRect)
                if (!barRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        val currentTag = LocaleManager.currentLanguage(this).tag
        if (createdWithLocale != null && createdWithLocale != currentTag) {
            isLocaleRestart = true
            LocaleManager.recreateSeamlessly(this)
            return
        }
        // Re-check: if the model was switched to a TTS model while this
        // activity was in the background, redirect to TtsActivity.
        if (shouldRedirectToTts()) {
            startActivity(Intent(this, TtsActivity::class.java))
            finish()
            return
        }
        if (!::engine.isInitialized) return
        val selectedId = LlamaEngine.getSelectedModel(applicationContext).id

        if (loadedModelId != null && loadedModelId != selectedId) {
            loadedModelId = null
            hasAutoLoaded = false
            reloadAfterModelSwitch()
        } else if (LlamaEngine.consumeModelSwitched(applicationContext)) {
            loadedModelId = selectedId
            clearChatUI()
            updateUIForModelType()
        }
    }

    private fun reloadAfterModelSwitch() {
        enableInput(false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (engine.state.value is LlamaState.ModelReady) {
                    engine.unloadModel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading during model switch", e)
            }
            withContext(Dispatchers.Main) {
                clearChatUI()
                loadDefaultModel()
            }
        }
    }

    override fun onStop() {
        saveCurrentConversation()
        generationWatchdogJob?.cancel()
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isLocaleRestart && ::engine.isInitialized) {
            engine.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val VISION_GENERATION_WATCHDOG_MS = 120_000L
    }
}
