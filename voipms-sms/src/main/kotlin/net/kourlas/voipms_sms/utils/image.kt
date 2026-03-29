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
import android.net.Uri
import android.util.Base64
import java.io.File

/**
 * Reads an image from a content URI, compresses it to JPEG under the
 * size limit, and saves it to a temporary cache file.
 *
 * @param context Application context for content resolver access.
 * @param uri Content URI of the image to compress.
 * @param maxBytes Maximum file size in bytes (default 1.2 MB).
 * @return Path to the cached compressed file, or null on failure.
 */
fun compressImageToCache(
    context: Context,
    uri: Uri,
    maxBytes: Int = 1_200_000
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
        val file = File(cacheDir, "pending_mms.jpeg")

        file.outputStream().use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

        if (file.length() <= maxBytes) {
            return file.absolutePath
        }

        // Reduce quality first, then scale down if still too large
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
 * Reads a cached image file and returns it as a base64 data URI
 * suitable for the VoIP.ms sendMMS API.
 *
 * @param filePath Path to the cached JPEG file.
 * @return Base64 data URI string, or null on failure.
 */
fun readCachedImageAsBase64(filePath: String): String? {
    val file = File(filePath)
    if (!file.exists()) return null
    val bytes = file.readBytes()
    return "data:image/jpeg;base64," +
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
