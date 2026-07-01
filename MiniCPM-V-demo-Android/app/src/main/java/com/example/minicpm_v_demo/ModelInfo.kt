package com.example.minicpm_v_demo

import android.content.Context

data class ModelQuantization(
    val id: String,
    val label: String,
    val ggufFileName: String,
    val ggufRemoteName: String? = null,
    val directGgufUrl: String? = null
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val descriptionResName: String,
    val ggufFileName: String,
    val mmprojFileName: String? = null,
    val acousticFileName: String? = null,
    val hfRepo: String? = null,
    val msRepo: String? = null,
    val hfBranch: String = "main",
    val msBranch: String = "master",
    val ggufRemoteName: String? = null,
    val mmprojRemoteName: String? = null,
    val acousticRemoteName: String? = null,
    val directGgufUrl: String? = null,
    val directMmprojUrl: String? = null,
    val directAcousticUrl: String? = null,
    val ggufMd5: String? = null,
    val mmprojMd5: String? = null,
    val acousticMd5: String? = null,
    val quantizations: List<ModelQuantization> = emptyList()
) {
    val isTextOnly: Boolean
        get() = mmprojFileName == null && acousticFileName == null

    val isTts: Boolean
        get() = acousticFileName != null

    fun getDescription(context: Context): String {
        val resId = context.resources.getIdentifier(descriptionResName, "string", context.packageName)
        return if (resId != 0) context.getString(resId) else descriptionResName
    }

    val ggufRemotePath: String
        get() = ggufRemoteName ?: ggufFileName

    val mmprojRemotePath: String?
        get() = mmprojFileName?.let { mmprojRemoteName ?: it }

    val acousticRemotePath: String?
        get() = acousticFileName?.let { acousticRemoteName ?: it }

    val hasDirectUrls: Boolean
        get() = if (isTextOnly) !directGgufUrl.isNullOrBlank() || quantizations.isNotEmpty()
                else !directGgufUrl.isNullOrBlank() && !directMmprojUrl.isNullOrBlank()

    val hasHfMsSources: Boolean
        get() = !hfRepo.isNullOrBlank() && !msRepo.isNullOrBlank()

    companion object {
        const val CUSTOM_LOCAL_MODEL_ID = "custom-local-catgirl"
        const val CUSTOM_LOCAL_GGUF_FILE_NAME = "catgirl-local-model.gguf"

        val CUSTOM_LOCAL_MODEL = ModelInfo(
            id = CUSTOM_LOCAL_MODEL_ID,
            displayName = "猫娘模型本地导入",
            descriptionResName = "model_desc_custom_local",
            ggufFileName = CUSTOM_LOCAL_GGUF_FILE_NAME
        )

        private const val TEXT_REPO_MIRROR =
            "https://hf-mirror.com/liumindmind/MiniNeko-1B-GGUF/resolve/main"
        private const val VISION_REPO_MIRROR =
            "https://hf-mirror.com/liumindmind/MiniNeko-V-1.3B-GGUF/resolve/main"

        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "minineko-vision-online",
                displayName = "猫娘视觉模型（在线下载）",
                descriptionResName = "model_desc_minineko_vision",
                ggufFileName = "MiniNeko_vision-1.3B-Thinking-Q4_K_M.gguf",
                mmprojFileName = "mmproj-MiniNeko_vision-1.3B-Thinking-f16.gguf",
                directGgufUrl = "$VISION_REPO_MIRROR/MiniNeko_vision-1.3B-Q4_K_M.gguf",
                directMmprojUrl = "$VISION_REPO_MIRROR/mmproj-MiniNeko_vision-1.3B-f16.gguf"
            ),
            ModelInfo(
                id = "minineko-text-online",
                displayName = "猫娘文本模型（在线下载）",
                descriptionResName = "model_desc_minineko_text",
                ggufFileName = "MiniNeko-1B-Q4_K_M.gguf",
                quantizations = listOf(
                    ModelQuantization(
                        id = "q4_k_m",
                        label = "Q4_K_M",
                        ggufFileName = "MiniNeko-1B-Q4_K_M.gguf",
                        directGgufUrl = "$TEXT_REPO_MIRROR/MiniNeko-1B-Q4_K_M.gguf"
                    ),
                    ModelQuantization(
                        id = "q5_k_m",
                        label = "Q5_K_M",
                        ggufFileName = "MiniNeko-1B-Q5_K_M.gguf",
                        directGgufUrl = "$TEXT_REPO_MIRROR/MiniNeko-1B-Q5_K_M.gguf"
                    ),
                    ModelQuantization(
                        id = "q8_0",
                        label = "Q8_0",
                        ggufFileName = "MiniNeko-1B-Q8_0.gguf",
                        directGgufUrl = "$TEXT_REPO_MIRROR/MiniNeko-1B-Q8_0.gguf"
                    ),
                    ModelQuantization(
                        id = "f16",
                        label = "F16",
                        ggufFileName = "MiniNeko-1B-f16.gguf",
                        directGgufUrl = "$TEXT_REPO_MIRROR/MiniNeko-1B-f16.gguf"
                    )
                )
            ),
            CUSTOM_LOCAL_MODEL
        )

        val DEFAULT_MODEL = AVAILABLE_MODELS.first()
    }
}
