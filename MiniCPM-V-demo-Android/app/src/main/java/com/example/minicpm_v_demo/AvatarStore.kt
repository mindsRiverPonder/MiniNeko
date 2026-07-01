package com.example.minicpm_v_demo

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream

object AvatarStore {
    enum class AvatarKind(
        val fileName: String,
        val defaultDrawable: Int
    ) {
        User("user-avatar.png", R.drawable.avatar_user_default),
        Catgirl("catgirl-avatar.png", R.drawable.avatar_catgirl)
    }

    private const val AVATAR_SUBDIR = "avatars"

    fun saveAvatar(context: Context, uri: Uri, kind: AvatarKind) {
        val outFile = avatarFile(context, kind)
        outFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open avatar image" }
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 128 * 1024)
            }
        }
    }

    fun applyAvatar(context: Context, imageView: ImageView, kind: AvatarKind) {
        val file = avatarFile(context, kind)
        if (file.exists() && file.length() > 0L) {
            imageView.setImageURI(Uri.fromFile(file))
        } else {
            imageView.setImageResource(kind.defaultDrawable)
        }
    }

    private fun avatarFile(context: Context, kind: AvatarKind): File =
        File(File(context.filesDir, AVATAR_SUBDIR), kind.fileName)
}
