package com.copymore.android

import android.net.Uri
import androidx.core.content.FileProvider

/** Private files are stored by UUID; expose the original MIME type to paste targets. */
class ReceivedFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val context = context ?: return super.getType(uri)
        val name = uri.lastPathSegment
        return Repository.get(context).state.value.clips.firstOrNull { it.fileName == name }?.mime ?: super.getType(uri)
    }
}
