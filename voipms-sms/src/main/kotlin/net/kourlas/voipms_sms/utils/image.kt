/*
 * VoIP.ms SMS
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import okhttp3.Request
import java.io.File

const val MAX_MMS_BYTES = 1_200_000

/**
 * Returns the file extension from a media URL or file path.
 */
fun getMediaExtension(url: String): String {
    val lastSegment = url.substringAfterLast('/')
    val ext = lastSegment.substringAfterLast('.', "")
        .substringBefore('?').lowercase()
    return if (ext.isNotEmpty()) ext else "bin"
}

/**
 * Returns the MIME type category for a file extension.
 */
fun getMediaType(extensionOrPath: String): MediaType {
    val ext = if (extensionOrPath.contains('.')) {
        extensionOrPath.substringAfterLast('.').lowercase()
    } else {
        extensionOrPath.lowercase()
    }
    return when (ext) {
        "jpeg", "jpg", "png", "bmp", "svg" -> MediaType.IMAGE
        "gif" -> MediaType.GIF
        "mp3", "wav", "midi", "m4a", "aac", "ogg", "amr" -> MediaType.AUDIO
        "mp4", "3gp", "3gpp", "webm" -> MediaType.VIDEO
        else -> MediaType.OTHER
    }
}

enum class MediaType {
    IMAGE, GIF, AUDIO, VIDEO, OTHER
}

/**
 * Reads an image from a content URI, compresses it to JPEG under the
 * size limit, and saves it to a temporary cache file.
 */
fun compressImageToCache(
    context: Context,
    uri: Uri,
    index: Int = 0,
    maxBytes: Int = MAX_MMS_BYTES
): String? {
    val inputStream = context.contentResolver.openInputStream(uri)
        ?: return null
    val bitmap = BitmapFactory.decodeStream(inputStream)
        ?: return null
    inputStream.close()

    var quality = 85
    var scaledBitmap = bitmap

    while (quality >= 10) {
        val cacheDir = File(context.cacheDir, "outgoing")
        cacheDir.mkdirs()
        val file = File(cacheDir, "pending_mms_$index.jpeg")

        file.outputStream().use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

        if (file.length() <= maxBytes) {
            return file.absolutePath
        }

        if (quality > 30) {
            quality -= 15
        } else {
            quality = 85
            scaledBitmap = Bitmap.createScaledBitmap(
                scaledBitmap,
                scaledBitmap.width / 2,
                scaledBitmap.height / 2,
                true
            )
        }
    }

    return null
}

/**
 * Reads a cached media file and returns it as a base64 data URI
 * suitable for the VoIP.ms sendMMS API.
 */
fun readCachedMediaAsBase64(filePath: String): String? {
    val file = File(filePath)
    if (!file.exists()) return null

    val extension = file.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension) ?: "application/octet-stream"

    val bytes = file.readBytes()
    return "data:$mimeType;base64," +
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * Generates a thumbnail for a media file and caches it.
 * Returns the cached thumbnail path, or null for audio (use icon instead).
 */
fun generateThumbnail(filePath: String, thumbnailPath: String): Boolean {
    val file = File(filePath)
    if (!file.exists()) return false

    val type = getMediaType(filePath)
    val thumbFile = File(thumbnailPath)
    thumbFile.parentFile?.mkdirs()

    return when (type) {
        MediaType.IMAGE, MediaType.GIF -> {
            // For images/GIFs, create a small JPEG thumbnail
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return false
            val scale = 200.0 / maxOf(bitmap.width, bitmap.height)
            val thumb = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }
            thumbFile.outputStream().use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            true
        }
        MediaType.VIDEO -> {
            // Extract first frame
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                val frame = retriever.getFrameAtTime(0) ?: return false
                retriever.release()
                thumbFile.outputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        MediaType.AUDIO, MediaType.OTHER -> false
    }
}

/**
 * Downloads a media file from a URL to the media cache directory.
 * Returns the cached file, or null on failure.
 */
fun downloadMediaToCache(context: Context, mediaUrl: String): File? {
    val hash = mediaUrl.hashCode().toUInt().toString(16)
    val ext = getMediaExtension(mediaUrl)
    val cacheDir = File(context.cacheDir, "media")
    cacheDir.mkdirs()
    val cachedFile = File(cacheDir, "$hash.$ext")

    if (cachedFile.exists()) return cachedFile

    return try {
        val request = Request.Builder().url(mediaUrl).build()
        val response = HttpClientManager.getInstance().client
            .newCall(request).execute()
        if (!response.isSuccessful) return null

        cachedFile.outputStream().use { output ->
            response.body.byteStream().use { input ->
                input.copyTo(output)
            }
        }
        cachedFile
    } catch (_: Exception) {
        cachedFile.delete()
        null
    }
}
