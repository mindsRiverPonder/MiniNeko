package com.example.minicpm_v_demo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnLoadModel: MaterialButton
    private lateinit var btnImportLocalModel: MaterialButton
    private lateinit var btnDeleteModel: MaterialButton
    private lateinit var progressDownload: LinearProgressIndicator
    private lateinit var recyclerModels: RecyclerView
    private lateinit var tvLanguageValue: TextView
    private lateinit var layoutQuantization: View
    private lateinit var spinnerQuantization: Spinner
    private var isUpdatingQuantization = false

    private lateinit var engine: LlamaEngine
    private lateinit var modelAdapter: ModelAdapter

    private val localModelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importLocalModel(it) }
        }

    private val userAvatarPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { saveAvatar(it, AvatarStore.AvatarKind.User) }
        }

    private val catgirlAvatarPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { saveAvatar(it, AvatarStore.AvatarKind.Catgirl) }
        }

    // Android 13+: POST_NOTIFICATIONS is a runtime permission. We need it
    // for the foreground download service's progress notification (without
    // a notification the OS will outright kill the foreground service).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // We start the download regardless of grant: the service still
            // posts a notification, the user just won't see it. Foreground
            // service itself is allowed without the permission.
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_notification_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
            startDownloadService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvModelStatus = findViewById(R.id.tv_model_status)
        btnDownload = findViewById(R.id.btn_download)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnImportLocalModel = findViewById(R.id.btn_import_local_model)
        btnDeleteModel = findViewById(R.id.btn_delete_model)
        progressDownload = findViewById(R.id.progress_download)
        recyclerModels = findViewById(R.id.recycler_models)
        tvLanguageValue = findViewById(R.id.tv_language_value)
        layoutQuantization = findViewById(R.id.layout_quantization)
        spinnerQuantization = findViewById(R.id.spinner_quantization)

        engine = LlamaEngine.getInstance(applicationContext)

        setupModelList()
        setupQuantizationPicker()
        updateLoadButtonState()
        observeEngineState()
        observeDownloadStatus()
        updateLanguageDisplay()

        btnDownload.setOnClickListener { onDownloadClicked() }
        btnLoadModel.setOnClickListener { loadSelectedModel() }
        btnImportLocalModel.setOnClickListener { openLocalModelPicker() }
        btnDeleteModel.setOnClickListener { confirmDeleteModel() }
        findViewById<View>(R.id.btn_user_avatar).setOnClickListener { openAvatarPicker(AvatarStore.AvatarKind.User) }
        findViewById<View>(R.id.btn_ai_avatar).setOnClickListener { openAvatarPicker(AvatarStore.AvatarKind.Catgirl) }
        findViewById<View>(R.id.btn_image_slice_settings).setOnClickListener { showImageSliceDialog() }
        findViewById<View>(R.id.btn_vision_compression).setOnClickListener { showVisionCompressionDialog() }
        findViewById<View>(R.id.btn_language).setOnClickListener { showLanguagePicker() }
    }

    private fun showImageSliceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_image_slice, null, false)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_image_slice)
        val tvValue = view.findViewById<TextView>(R.id.tv_image_slice_value)

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
                    Toast.makeText(this@ModelManagerActivity, getString(msgRes, chosen), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVisionCompressionDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_vision_compression, null, false)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_vision_compression)
        val tvValue = view.findViewById<TextView>(R.id.tv_vision_compression_value)
        val checkOriginal = view.findViewById<CheckBox>(R.id.check_original_image)

        fun refresh(value: Int) {
            tvValue.text = if (checkOriginal.isChecked) {
                getString(R.string.vision_compression_original_value)
            } else {
                getString(R.string.vision_compression_value, value)
            }
            slider.isEnabled = !checkOriginal.isChecked
            slider.alpha = if (checkOriginal.isChecked) 0.45f else 1.0f
        }

        val initial = LlamaEngine.getVisionImageMaxSide(this)
        checkOriginal.isChecked = initial == LlamaEngine.ORIGINAL_VISION_IMAGE_MAX_SIDE
        slider.value = (if (checkOriginal.isChecked) {
            LlamaEngine.DEFAULT_COMPRESSED_VISION_IMAGE_MAX_SIDE
        } else {
            initial
        }).toFloat()
        refresh(slider.value.toInt())
        checkOriginal.setOnCheckedChangeListener { _, _ -> refresh(slider.value.toInt()) }
        slider.addOnChangeListener { _, value, _ -> refresh(value.toInt()) }

        AlertDialog.Builder(this)
            .setTitle(R.string.vision_compression_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = if (checkOriginal.isChecked) {
                    LlamaEngine.ORIGINAL_VISION_IMAGE_MAX_SIDE
                } else {
                    slider.value.toInt()
                }
                LlamaEngine.setVisionImageMaxSide(this, chosen)
                val message = if (chosen == LlamaEngine.ORIGINAL_VISION_IMAGE_MAX_SIDE) {
                    getString(R.string.vision_compression_saved_original)
                } else {
                    getString(R.string.vision_compression_saved, chosen)
                }
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openLocalModelPicker() {
        localModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun openAvatarPicker(kind: AvatarStore.AvatarKind) {
        when (kind) {
            AvatarStore.AvatarKind.User -> userAvatarPicker.launch(arrayOf("image/*"))
            AvatarStore.AvatarKind.Catgirl -> catgirlAvatarPicker.launch(arrayOf("image/*"))
        }
    }

    private fun importLocalModel(uri: Uri) {
        val sourceName = getFileName(uri)
        btnImportLocalModel.isEnabled = false
        btnLoadModel.isEnabled = false
        btnDownload.isEnabled = false
        progressDownload.visibility = View.VISIBLE
        tvModelStatus.text = getString(R.string.importing_local_model, sourceName)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val customModel = ModelInfo.CUSTOM_LOCAL_MODEL
                val targetDir = File(LlamaEngine.modelDirFor(applicationContext, customModel))
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, ModelInfo.CUSTOM_LOCAL_GGUF_FILE_NAME)
                copyUriToFile(uri, targetFile)

                LlamaEngine.setSelectedModel(applicationContext, ModelInfo.CUSTOM_LOCAL_MODEL_ID)

                withContext(Dispatchers.Main) {
                    modelAdapter.updateSelection(ModelInfo.CUSTOM_LOCAL_MODEL_ID)
                    progressDownload.visibility = View.GONE
                    btnImportLocalModel.isEnabled = true
                    updateLoadButtonState()
                    tvModelStatus.text = getString(R.string.local_model_ready, sourceName)
                    Toast.makeText(
                        this@ModelManagerActivity,
                        R.string.toast_local_model_imported,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadSelectedModel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing local model", e)
                withContext(Dispatchers.Main) {
                    progressDownload.visibility = View.GONE
                    btnImportLocalModel.isEnabled = true
                    updateLoadButtonState()
                    tvModelStatus.text = getString(R.string.toast_local_model_import_failed, e.message)
                    Toast.makeText(
                        this@ModelManagerActivity,
                        getString(R.string.toast_local_model_import_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun saveAvatar(uri: Uri, kind: AvatarStore.AvatarKind) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AvatarStore.saveAvatar(applicationContext, uri, kind)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ModelManagerActivity, R.string.toast_avatar_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving avatar", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ModelManagerActivity,
                        getString(R.string.toast_avatar_save_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun copyUriToFile(uri: Uri, targetFile: File) {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { getString(R.string.error_read_file) }
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "local-model.gguf"
    }

    private fun setupModelList() {
        val selectedModel = LlamaEngine.getSelectedModel(this)
        modelAdapter = ModelAdapter(
            models = ModelInfo.AVAILABLE_MODELS,
            selectedModelId = selectedModel.id,
            onModelSelected = { model ->
                val previousModelId = LlamaEngine.getSelectedModel(this).id
                LlamaEngine.setSelectedModel(this, model.id)
                updateQuantizationPicker()
                updateLoadButtonState()

                if (previousModelId != model.id) {
                    val wasLoaded = engine.state.value is LlamaState.ModelReady
                    if (wasLoaded) {
                        reloadSelectedModel()
                    } else {
                        tvModelStatus.text = getString(R.string.switched_to_load, model.displayName)
                    }
                }
            }
        )

        recyclerModels.layoutManager = LinearLayoutManager(this)
        recyclerModels.adapter = modelAdapter
    }

    private fun setupQuantizationPicker() {
        spinnerQuantization.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingQuantization) return
                val model = LlamaEngine.getSelectedModel(this@ModelManagerActivity)
                val quant = model.quantizations.getOrNull(position) ?: return
                val previous = LlamaEngine.getSelectedQuantization(this@ModelManagerActivity, model)
                if (previous?.id == quant.id) return

                LlamaEngine.setSelectedQuantization(this@ModelManagerActivity, model.id, quant.id)
                updateLoadButtonState()

                val wasLoaded = engine.state.value is LlamaState.ModelReady
                if (wasLoaded && LlamaEngine.modelsExist(this@ModelManagerActivity)) {
                    reloadSelectedModel()
                } else {
                    tvModelStatus.text = getString(R.string.quantization_selected, quant.label)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateQuantizationPicker()
    }

    private fun updateQuantizationPicker() {
        val model = LlamaEngine.getSelectedModel(this)
        if (model.quantizations.isEmpty()) {
            layoutQuantization.visibility = View.GONE
            return
        }

        layoutQuantization.visibility = View.VISIBLE
        isUpdatingQuantization = true
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            model.quantizations.map { it.label }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerQuantization.adapter = adapter
        val selected = LlamaEngine.getSelectedQuantization(this, model)
        val selectedIndex = model.quantizations.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        spinnerQuantization.setSelection(selectedIndex, false)
        isUpdatingQuantization = false
    }

    private fun reloadSelectedModel() {
        val model = LlamaEngine.getSelectedModel(this)
        val modelPath = LlamaEngine.modelPath(applicationContext)

        if (!File(modelPath).exists()) {
            tvModelStatus.text = getString(R.string.switched_to_download, model.displayName)
            lifecycleScope.launch(Dispatchers.IO) {
                try { engine.unloadModel() } catch (_: Exception) {}
                withContext(Dispatchers.Main) { updateLoadButtonState() }
            }
            return
        }

        btnLoadModel.isEnabled = false
        btnDownload.isEnabled = false
        tvModelStatus.text = getString(R.string.switching_to, model.displayName)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.unloadModel()
                val mmprojPath = LlamaEngine.mmprojPath(applicationContext)
                val mmprojArg = mmprojPath?.let { if (File(it).exists()) it else null }
                engine.loadModel(modelPath, mmprojArg)
                LlamaEngine.markModelSwitched(applicationContext)
                withContext(Dispatchers.Main) {
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.toast_load_success, model.displayName), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading model", e)
                engine.resetToInitialized()
                withContext(Dispatchers.Main) {
                    tvModelStatus.text = getString(R.string.toast_load_failed, e.message)
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized -> {
                        tvModelStatus.text = getString(R.string.status_uninitialized)
                    }
                    is LlamaState.Initializing -> {
                        tvModelStatus.text = getString(R.string.status_initializing)
                    }
                    is LlamaState.Initialized -> {
                        tvModelStatus.text = getString(R.string.status_initialized)
                        updateLoadButtonState()
                    }
                    is LlamaState.LoadingModel -> {
                        tvModelStatus.text = getString(R.string.status_loading)
                        btnLoadModel.isEnabled = false
                        btnDownload.isEnabled = false
                    }
                    is LlamaState.ModelReady -> {
                        tvModelStatus.text = getString(R.string.status_ready)
                        btnLoadModel.isEnabled = true
                        btnDownload.isEnabled = true
                        updateLoadButtonState()
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt -> {
                        tvModelStatus.text = getString(R.string.status_generating)
                    }
                    is LlamaState.PrefillingImage -> {
                        tvModelStatus.text = getString(R.string.status_prefilling_image)
                    }
                    is LlamaState.Generating -> {
                        tvModelStatus.text = getString(R.string.status_generating)
                    }
                    is LlamaState.UnloadingModel -> {
                        tvModelStatus.text = getString(R.string.status_unloading)
                    }
                    is LlamaState.Error -> {
                        tvModelStatus.text = getString(R.string.status_error, state.exception.message)
                        btnLoadModel.isEnabled = true
                        btnDownload.isEnabled = true
                        updateLoadButtonState()
                    }
                }
            }
        }
    }

    private fun updateLoadButtonState() {
        val exists = LlamaEngine.modelsExist(this)
        val isReady = engine.state.value is LlamaState.ModelReady
        val selectedModel = LlamaEngine.getSelectedModel(this)
        val isCustomLocal = selectedModel.id == ModelInfo.CUSTOM_LOCAL_MODEL_ID
        btnDownload.isEnabled = !isCustomLocal
        btnLoadModel.isEnabled = exists
        btnDeleteModel.visibility = if (exists) View.VISIBLE else View.GONE
        btnLoadModel.text = when {
            isReady && exists -> getString(R.string.reload_model)
            exists -> getString(R.string.load_model)
            else -> getString(R.string.no_model_file)
        }
    }

    private fun onDownloadClicked() {
        if (LlamaEngine.getSelectedModel(this).id == ModelInfo.CUSTOM_LOCAL_MODEL_ID) {
            openLocalModelPicker()
            return
        }
        if (ModelDownloadController.isRunning) {
            Toast.makeText(this, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (LlamaEngine.modelsExist(this)) {
            Toast.makeText(this, R.string.toast_already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }

        // Android 13+ needs runtime POST_NOTIFICATIONS so the foreground
        // service notification is actually visible. Lower OS versions get
        // the permission for free at install time and just fall through.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startDownloadService()
    }

    private fun startDownloadService() {
        // Drop any prior terminal status so the UI re-enters Running cleanly.
        ModelDownloadController.acknowledge()

        btnDownload.isEnabled = false
        btnLoadModel.isEnabled = false
        progressDownload.visibility = View.VISIBLE
        tvModelStatus.text = getString(R.string.status_downloading)

        ModelDownloadService.start(applicationContext)
    }

    /**
     * Mirrors the foreground service's [ModelDownloadController] state into
     * the UI. We use repeatOnLifecycle(STARTED) so we don't burn cycles
     * collecting while the Activity is in the background, but we still
     * pick up any progress that arrived during that window when we resume.
     */
    private fun observeDownloadStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadController.status.collect { status ->
                    when (status) {
                        is ModelDownloadController.Status.Idle -> {
                            progressDownload.visibility = View.GONE
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                        }
                        is ModelDownloadController.Status.Running -> {
                            progressDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = false
                            btnLoadModel.isEnabled = false
                            tvModelStatus.text = status.message
                        }
                        is ModelDownloadController.Status.Completed -> {
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_complete_status)
                            Toast.makeText(this@ModelManagerActivity, R.string.download_complete_toast, Toast.LENGTH_SHORT).show()
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                        is ModelDownloadController.Status.Cancelled -> {
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_cancelled)
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                        is ModelDownloadController.Status.Failed -> {
                            Log.w(TAG, "Download failed: ${status.message}")
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_failed, status.message)
                            Toast.makeText(
                                this@ModelManagerActivity,
                                getString(R.string.download_failed, status.message),
                                Toast.LENGTH_LONG
                            ).show()
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                    }
                }
            }
        }
    }

    private fun loadSelectedModel() {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        // TTS models are loaded on-demand by TtsActivity, not via LlamaEngine.
        if (model.isTts) {
            LlamaEngine.markModelSwitched(applicationContext)
            finish() // return to parent; MainActivity will redirect
            return
        }

        val currentState = engine.state.value
        if (currentState is LlamaState.LoadingModel) {
            Toast.makeText(this, R.string.toast_already_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val modelPath = LlamaEngine.modelPath(applicationContext)
        val mmprojPath = LlamaEngine.mmprojPath(applicationContext)

        if (!File(modelPath).exists()) {
            Toast.makeText(this, R.string.toast_model_not_found, Toast.LENGTH_LONG).show()
            return
        }

        val isReload = currentState is LlamaState.ModelReady

        btnLoadModel.isEnabled = false
        btnDownload.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isReload) {
                    Log.i(TAG, "Unloading model for reload...")
                    engine.unloadModel()
                }

                val mmprojArg = mmprojPath?.let { path ->
                    if (File(path).exists()) path else null
                }
                engine.loadModel(modelPath, mmprojArg)
                // No default system prompt: aligned with iOS opt-r1. See
                // MainActivity.clearChat() for the rationale.

                withContext(Dispatchers.Main) {
                    btnLoadModel.isEnabled = true
                    btnDownload.isEnabled = true
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, R.string.toast_model_loaded, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                engine.resetToInitialized()
                withContext(Dispatchers.Main) {
                    tvModelStatus.text = getString(R.string.toast_load_failed, e.message)
                    btnLoadModel.isEnabled = true
                    btnDownload.isEnabled = true
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun confirmDeleteModel() {
        val model = LlamaEngine.getSelectedModel(this)
        val fileList = buildString {
            append("• ${model.ggufFileName}")
            if (model.mmprojFileName != null) {
                append("\n• ${model.mmprojFileName}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(getString(R.string.delete_dialog_message, model.displayName, fileList))
            .setPositiveButton(R.string.delete) { _, _ -> deleteModelFiles() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteModelFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelPath = LlamaEngine.modelPath(applicationContext)
                val mmprojPath = LlamaEngine.mmprojPath(applicationContext)

                var deleted = false
                File(modelPath).let { if (it.exists()) { it.delete(); deleted = true } }
                mmprojPath?.let { File(it) }?.let { if (it.exists()) { it.delete(); deleted = true } }
                // Also delete the acoustic GGUF for TTS models
                val acousticPath = LlamaEngine.acousticPath(applicationContext)
                acousticPath?.let { File(it) }?.let { if (it.exists()) { it.delete(); deleted = true } }

                withContext(Dispatchers.Main) {
                    updateLoadButtonState()
                    if (deleted) {
                        tvModelStatus.text = getString(R.string.model_files_deleted)
                        Toast.makeText(this@ModelManagerActivity, R.string.model_files_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ModelManagerActivity, R.string.toast_no_files_to_delete, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.toast_delete_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateLanguageDisplay() {
        tvLanguageValue.text = LocaleManager.currentLanguage(this).displayName
    }

    private fun showLanguagePicker() {
        val current = LocaleManager.currentLanguage(this)
        val options = LocaleManager.AppLanguage.entries.toTypedArray()
        val items = options.map {
            if (it == current) "${it.displayName}  \u2713" else it.displayName
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.language_picker_title)
            .setItems(items) { _, which ->
                val picked = options[which]
                if (picked != current) {
                    LocaleManager.setLanguageAndRestart(this, picked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private val TAG = ModelManagerActivity::class.java.simpleName
    }
}
