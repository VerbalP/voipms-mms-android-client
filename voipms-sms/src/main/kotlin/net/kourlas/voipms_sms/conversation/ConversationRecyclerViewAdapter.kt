/*
 * VoIP.ms SMS
 * Copyright (C) 2015-2023 Michael Kourlas
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

package net.kourlas.voipms_sms.conversation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Filter
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.QuickContactBadge
import android.widget.TextView
import coil3.load
import coil3.request.crossfade
import coil3.size.Size
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.CustomApplication
import net.kourlas.voipms_sms.preferences.getAutoDownloadContactsOnly
import net.kourlas.voipms_sms.preferences.getAutoDownloadMmsImages
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.MediaType
import net.kourlas.voipms_sms.utils.generateThumbnail
import net.kourlas.voipms_sms.utils.getMediaExtension
import net.kourlas.voipms_sms.utils.getMediaType
import net.kourlas.voipms_sms.utils.isValidMediaFile
import okhttp3.Request
import java.io.File
import kotlinx.coroutines.runBlocking
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.demo.getConversationDemoMessages
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.ui.FastScroller
import net.kourlas.voipms_sms.utils.applyCircularMask
import net.kourlas.voipms_sms.utils.applyRoundedCornersMask
import net.kourlas.voipms_sms.utils.MessageReactions
import net.kourlas.voipms_sms.utils.getConversationViewDate
import net.kourlas.voipms_sms.utils.getConversationViewTopDate
import net.kourlas.voipms_sms.utils.getScrollBarDate
import net.kourlas.voipms_sms.utils.logException
import net.kourlas.voipms_sms.utils.showSnackbar
import java.util.Locale
import kotlin.math.max

/**
 * Recycler view adapter used by [ConversationActivity].
 *
 * @param activity The source [ConversationActivity].
 * @param recyclerView The recycler view used by the activity.
 * @param layoutManager The layout manager used by the recycler view.
 * @param conversationId The conversation ID of the conversation displayed by
 * the activity, consisting of the DID and contact.
 * @param contactBitmap The photo associated with the contact.
 */
class ConversationRecyclerViewAdapter(
    private val activity: ConversationActivity,
    private val recyclerView: RecyclerView,
    private val layoutManager: LinearLayoutManager,
    private val conversationId: ConversationId,
    private val contactBitmap: Bitmap
) :
    RecyclerView.Adapter<ConversationRecyclerViewAdapter.MessageViewHolder>(),
    Filterable,
    Iterable<ConversationRecyclerViewAdapter.MessageItem>,
    FastScroller.SectionTitleProvider {

    // List of items shown by the adapter; the index of each item
    // corresponds to the location of each item in the adapter.
    private val _messageItems = mutableListOf<MessageItem>()
    val messageItems: List<MessageItem>
        get() = _messageItems

    // Reaction emojis to display, keyed by the database ID of the message they
    // react to. Reaction messages themselves are removed from the item list.
    private var reactionsByMessageId: Map<Long, List<String>> = emptyMap()

    // Current and previous filter constraint.
    private var currConstraint: String = ""
    private var prevConstraint: String = ""

    // The total number of items that can be retrieved and which have been
    // retrieved.
    private var maxLimit = 0L
    private var currLimit = ADDITIONAL_ITEMS_INCREMENT

    // Whether the adapter is currently loading additional items.
    var loadingMoreItems = false

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): MessageViewHolder {
        // Inflate the appropriate view, given the view type
        val itemView = when (viewType) {
            R.layout.conversation_item_incoming -> {
                LayoutInflater.from(parent.context)
                    .inflate(
                        R.layout.conversation_item_incoming,
                        parent, false
                    )
            }

            R.layout.conversation_item_outgoing -> {
                LayoutInflater.from(parent.context)
                    .inflate(
                        R.layout.conversation_item_outgoing,
                        parent, false
                    )
            }

            else -> throw Exception("Unknown view type $viewType")
        }
        return MessageViewHolder(itemView, viewType)
    }

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int
    ) {
        // Set up view to match message at position
        updateViewHolderViewHeight(holder, position)
        updateViewHolderContactBadge(holder, position)
        updateViewHolderMedia(holder, position)
        updateViewHolderMessageText(holder, position)
        updateViewHolderReactions(holder, position)
        updateViewHolderDateText(holder, position)
        updateViewHolderColours(holder, position)
    }

    /**
     * Displays any reactions ("tapbacks") that target the message at the given
     * position, as an emoji chip tucked under the message bubble. The reaction
     * messages themselves are not shown as separate bubbles.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderReactions(
        holder: MessageViewHolder,
        position: Int
    ) {
        val message = messageItems[position].message
        val emojis = reactionsByMessageId[message.databaseId]
        if (emojis.isNullOrEmpty()) {
            holder.reactionsView.visibility = View.GONE
        } else {
            holder.reactionsView.text = emojis.joinToString(" ")
            holder.reactionsView.visibility = View.VISIBLE
        }
    }

    /**
     * Sets the height of the view represented by the view holder to the
     * appropriate height, depending on whether the message is the first
     * message in a group.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderViewHeight(
        holder: MessageViewHolder,
        position: Int
    ) {
        val marginParams = holder.itemView.layoutParams
            as ViewGroup.MarginLayoutParams
        marginParams.topMargin =
            if (isFirstMessageInGroup(
                    position,
                    combineIncomingOutgoing = false
                )
            ) {
                activity.resources.getDimension(
                    R.dimen.conversation_item_margin_top_primary
                ).toInt()
            } else {
                activity.resources.getDimension(
                    R.dimen.conversation_item_margin_top_secondary
                ).toInt()
            }
    }

    /**
     * Displays or hides the contact badge on the view holder. If the view
     * holder is displayed, sets the content of the view holder to a photo
     * or a material design letter.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderContactBadge(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = messageItems[position]
        val message = messageItem.message

        val contactBadge = holder.contactBadge
        if (contactBadge != null) {
            // Show contact badge if first message in group
            if (isFirstMessageInGroup(
                    position,
                    combineIncomingOutgoing = false
                )
            ) {
                holder.contactBadge.visibility = View.VISIBLE
                contactBadge.assignContactFromPhone(message.contact, true)
                contactBadge.setImageBitmap(contactBitmap)
            } else {
                holder.contactBadge.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * Displays the text of the message on the view holder. Selects and
     * highlights part of the text if a filter is configured.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderMessageText(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = messageItems[position]
        val message = messageItem.message

        val messageText = holder.messageText
        val messageTextBuilder = SpannableStringBuilder()

        if (message.text == "" && message.medias.isEmpty()) {
            messageTextBuilder.append(message.fullDisplayText)
            messageTextBuilder.setSpan(
                StyleSpan(Typeface.ITALIC), 0,
                messageTextBuilder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        } else {
            // Use the trimmed body so carrier whitespace/newline-only MMS
            // text parts don't inflate the bubble with empty space.
            val displayText = message.text.trim()
            val textStart = messageTextBuilder.length
            messageTextBuilder.append(displayText)

            // Highlight text that matches the search filter
            if (currConstraint != "") {
                val index =
                    displayText.lowercase(Locale.getDefault()).indexOf(
                        currConstraint.lowercase(Locale.getDefault())
                    )
                if (index != -1) {
                    messageTextBuilder.setSpan(
                        BackgroundColorSpan(
                            ContextCompat.getColor(
                                activity, R.color.highlight
                            )
                        ),
                        textStart + index,
                        textStart + index + currConstraint.length,
                        SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                    messageTextBuilder.setSpan(
                        ForegroundColorSpan(
                            ContextCompat.getColor(
                                activity, android.R.color.black
                            )
                        ),
                        textStart + index,
                        textStart + index + currConstraint.length,
                        SpannableString.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }
            }

            // Only list media that can't be rendered inline (unsupported
            // types). Loadable media already appears as an inline thumbnail,
            // so showing its raw URL as text would be redundant.
            val unrenderedMedia = message.medias.filter {
                !File(it).exists() && it.isNotEmpty()
                    && !isLoadableMediaUrl(it)
            }
            if (unrenderedMedia.isNotEmpty()) {
                if (messageTextBuilder.isNotEmpty()) {
                    messageTextBuilder.append("\n\n")
                }

                val length = messageTextBuilder.length
                messageTextBuilder.append(
                    unrenderedMedia.joinToString("\n\n") {
                        activity.getString(
                            R.string.conversation_info_media, it
                        )
                    }
                )
                messageTextBuilder.setSpan(
                    StyleSpan(Typeface.ITALIC), length,
                    messageTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
            }
        }

        messageText.text = messageTextBuilder
        messageText.visibility =
            if (messageTextBuilder.isEmpty()) View.GONE else View.VISIBLE

        Linkify.addLinks(
            messageText,
            Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS or Linkify.WEB_URLS
        )
        messageText.setOnClickListener { textView ->
            activity.onItemClick(textView)
        }
        messageText.setOnLongClickListener { textView ->
            activity.onItemLongClick(textView)
            true
        }
        messageText.movementMethod =
            BetterLinkMovementMethod.newInstance().apply {
                setOnLinkLongClickListener { _, _ ->
                    true
                }
            }
    }

    private fun isLoadableMediaUrl(url: String): Boolean {
        return LOADABLE_MEDIA_URL_PATTERN.matches(url)
    }

    private fun getMediaCacheFile(mediaUrl: String): File {
        val hash = mediaUrl.hashCode().toUInt().toString(16)
        val ext = getMediaExtension(mediaUrl)
        val cacheDir = File(activity.cacheDir, "media")
        cacheDir.mkdirs()
        return File(cacheDir, "$hash.$ext")
    }

    private fun getThumbnailCacheFile(mediaUrl: String): File {
        val hash = mediaUrl.hashCode().toUInt().toString(16)
        val cacheDir = File(activity.cacheDir, "media")
        cacheDir.mkdirs()
        return File(cacheDir, "thumb_$hash.jpeg")
    }

    /**
     * Marker file recording that a previous download produced a non-media
     * response (e.g. VoIP.ms "Not found" for expired media). Its presence
     * stops the auto-download loop and shows an "unavailable" state instead
     * of caching the error body as a broken attachment.
     */
    private fun getMediaFailedMarker(mediaUrl: String): File {
        val hash = mediaUrl.hashCode().toUInt().toString(16)
        val cacheDir = File(activity.cacheDir, "media")
        cacheDir.mkdirs()
        return File(cacheDir, "$hash.failed")
    }

    // Active MediaPlayer instance for audio playback
    private var activeMediaPlayer: MediaPlayer? = null

    private fun updateViewHolderMedia(
        holder: MessageViewHolder,
        position: Int
    ) {
        val message = messageItems[position].message
        val mediaContainer = holder.mediaContainer

        mediaContainer.removeAllViews()

        val mediaUrls = message.medias
        if (mediaUrls.isEmpty()) {
            mediaContainer.visibility = View.GONE
            mediaContainer.minimumWidth = 0
            return
        }

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val mediaWidthPx = screenWidth * 2 / 3
        val marginBottomPx = activity.resources.getDimensionPixelSize(
            R.dimen.media_image_margin_bottom
        )
        val placeholderHeightPx = mediaWidthPx / 2

        // Determine if auto-download is enabled for this contact
        val shouldAutoDownload = getAutoDownloadMmsImages(activity)
            && (!getAutoDownloadContactsOnly(activity)
            || getContactName(activity, message.contact) != null)

        mediaContainer.minimumWidth = mediaWidthPx

        for (mediaUrl in mediaUrls) {
            val localFile = File(mediaUrl)
            val isLocalFile = localFile.exists()

            if (isLocalFile || isLoadableMediaUrl(mediaUrl)) {
                val mediaType = getMediaType(
                    if (isLocalFile) mediaUrl else getMediaExtension(mediaUrl)
                )

                if (isLocalFile) {
                    // --- OUTGOING: local file ---
                    addLocalMediaView(
                        mediaContainer, localFile, mediaType,
                        mediaWidthPx, marginBottomPx
                    )
                } else {
                    // --- INCOMING: remote URL ---
                    val cachedFile = getMediaCacheFile(mediaUrl)
                    val failedMarker = getMediaFailedMarker(mediaUrl)

                    // Demote legacy junk: a tiny cache file is an error body
                    // (e.g. VoIP.ms "Not found") cached by an older build, not
                    // real media. Mark it failed so it isn't shown as openable.
                    if (cachedFile.exists()
                        && cachedFile.length() < MIN_VALID_MEDIA_BYTES
                    ) {
                        cachedFile.delete()
                        failedMarker.createNewFile()
                    }

                    when {
                        cachedFile.exists() -> addCachedMediaView(
                            mediaContainer, cachedFile, mediaType,
                            mediaWidthPx, marginBottomPx
                        )
                        failedMarker.exists() -> addUnavailableView(
                            mediaContainer, mediaUrl, cachedFile, mediaType,
                            mediaWidthPx, marginBottomPx, placeholderHeightPx
                        )
                        shouldAutoDownload -> addAutoDownloadView(
                            mediaContainer, mediaUrl, cachedFile, mediaType,
                            mediaWidthPx, marginBottomPx, placeholderHeightPx
                        )
                        else -> addPlaceholderView(
                            mediaContainer, mediaUrl, cachedFile, mediaType,
                            mediaWidthPx, marginBottomPx, placeholderHeightPx
                        )
                    }
                }
            }
        }

        mediaContainer.visibility =
            if (mediaContainer.childCount > 0) View.VISIBLE else View.GONE
    }

    /** Creates a video thumbnail with a play icon overlay. */
    private fun addVideoView(
        container: LinearLayout, file: File,
        widthPx: Int, marginPx: Int, index: Int = -1
    ) {
        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginPx }
        }
        val iv = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        val thumbFile = getThumbnailCacheFile(file.absolutePath)
        if (!thumbFile.exists()) {
            generateThumbnail(file.absolutePath, thumbFile.absolutePath)
        }
        if (thumbFile.exists()) {
            iv.load(thumbFile) { size(Size(widthPx, widthPx)) }
        } else {
            iv.setBackgroundColor(
                ContextCompat.getColor(activity, android.R.color.darker_gray)
            )
            iv.minimumHeight = widthPx / 2
        }
        val playOverlay = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                128, 128, android.view.Gravity.CENTER
            )
            setImageResource(android.R.drawable.ic_media_play)
            alpha = 0.8f
        }
        frame.addView(iv)
        frame.addView(playOverlay)
        frame.setOnClickListener { launchMediaViewer(file) }
        if (index >= 0) container.addView(frame, index)
        else container.addView(frame)
    }

    /** Adds a view for a locally cached outgoing media file. */
    private fun addLocalMediaView(
        container: LinearLayout, file: File, type: MediaType,
        widthPx: Int, marginPx: Int
    ) {
        // Persist outgoing files to media cache for future access
        val persistedFile = ensurePersistedCache(file)

        when (type) {
            MediaType.IMAGE, MediaType.GIF -> {
                val iv = createMediaImageView(widthPx, marginPx)
                iv.load(persistedFile) { size(Size(widthPx, widthPx)) }
                iv.setOnClickListener { launchMediaViewer(persistedFile) }
                container.addView(iv)
            }
            MediaType.AUDIO -> {
                addAudioPlayerView(container, persistedFile, widthPx, marginPx)
            }
            MediaType.VIDEO -> {
                addVideoView(container, persistedFile, widthPx, marginPx)
            }
            MediaType.OTHER -> {}
        }
    }

    /** Copies outgoing file to persistent media cache if not already there. */
    private fun ensurePersistedCache(file: File): File {
        if (file.absolutePath.contains("/media/")) return file
        val hash = file.absolutePath.hashCode().toUInt().toString(16)
        val cacheDir = File(activity.cacheDir, "media")
        cacheDir.mkdirs()
        val persisted = File(cacheDir, "$hash.${file.extension}")
        if (!persisted.exists()) {
            file.copyTo(persisted, overwrite = true)
        }
        return persisted
    }

    /** Adds a view for a cached downloaded media file. */
    private fun addCachedMediaView(
        container: LinearLayout, file: File, type: MediaType,
        widthPx: Int, marginPx: Int
    ) {
        when (type) {
            MediaType.IMAGE, MediaType.GIF -> {
                val iv = createMediaImageView(widthPx, marginPx)
                iv.load(file) { size(Size(widthPx, widthPx)) }
                iv.setOnClickListener { launchMediaViewer(file) }
                container.addView(iv)
            }
            MediaType.AUDIO -> {
                addAudioPlayerView(container, file, widthPx, marginPx)
            }
            MediaType.VIDEO -> {
                addVideoView(container, file, widthPx, marginPx)
            }
            MediaType.OTHER -> {}
        }
    }

    /** Shows placeholder, downloads on click, then shows media. */
    private fun addPlaceholderView(
        container: LinearLayout, mediaUrl: String, cachedFile: File,
        type: MediaType, widthPx: Int, marginPx: Int, placeholderHeight: Int
    ) {
        val placeholder = LayoutInflater.from(activity)
            .inflate(R.layout.media_placeholder, container, false)
        placeholder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, placeholderHeight
        ).apply { bottomMargin = marginPx }

        // Set icon based on media type
        val icon = placeholder.findViewById<ImageView>(android.R.id.icon)
            ?: (placeholder as? ViewGroup)?.getChildAt(0) as? ImageView
        when (type) {
            MediaType.AUDIO -> icon?.setImageResource(
                android.R.drawable.ic_lock_silent_mode_off
            )
            MediaType.VIDEO -> icon?.setImageResource(
                android.R.drawable.ic_media_play
            )
            else -> icon?.setImageResource(
                android.R.drawable.ic_menu_gallery
            )
        }

        placeholder.setOnClickListener {
            downloadMediaWithProgress(
                container, placeholder, mediaUrl, cachedFile,
                type, widthPx, marginPx
            )
        }

        container.addView(placeholder)
    }

    /**
     * Shows an "unavailable" tile for media whose download returned a
     * non-media response (typically expired media). Tapping it clears the
     * failed marker and retries the download rather than opening a broken
     * file.
     */
    private fun addUnavailableView(
        container: LinearLayout, mediaUrl: String, cachedFile: File,
        type: MediaType, widthPx: Int, marginPx: Int, placeholderHeight: Int,
        index: Int = -1
    ) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.media_placeholder, container, false)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, placeholderHeight
        ).apply { bottomMargin = marginPx }

        val icon = view.findViewById<ImageView>(android.R.id.icon)
            ?: (view as? ViewGroup)?.getChildAt(0) as? ImageView
        icon?.setImageResource(android.R.drawable.ic_menu_report_image)

        view.setOnClickListener {
            getMediaFailedMarker(mediaUrl).delete()
            downloadMediaWithProgress(
                container, view, mediaUrl, cachedFile, type, widthPx, marginPx
            )
        }

        if (index >= 0) container.addView(view, index)
        else container.addView(view)
    }

    /** Auto-downloads immediately with a progress indicator. */
    private fun addAutoDownloadView(
        container: LinearLayout, mediaUrl: String, cachedFile: File,
        type: MediaType, widthPx: Int, marginPx: Int, placeholderHeight: Int
    ) {
        val progressView = LayoutInflater.from(activity)
            .inflate(R.layout.media_downloading, container, false)
        progressView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, placeholderHeight
        ).apply { bottomMargin = marginPx }
        container.addView(progressView)

        downloadMediaWithProgressView(
            container, progressView, mediaUrl, cachedFile,
            type, widthPx, marginPx
        )
    }

    /** Downloads media, shows circular progress, then replaces with media. */
    private fun downloadMediaWithProgress(
        container: LinearLayout, placeholder: View,
        mediaUrl: String, cachedFile: File,
        type: MediaType, widthPx: Int, marginPx: Int
    ) {
        // Replace placeholder with progress view (same size)
        val idx = container.indexOfChild(placeholder)
        val progressView = LayoutInflater.from(activity)
            .inflate(R.layout.media_downloading, container, false)
        progressView.layoutParams = placeholder.layoutParams
        container.removeView(placeholder)
        container.addView(progressView, idx)

        downloadMediaWithProgressView(
            container, progressView, mediaUrl, cachedFile,
            type, widthPx, marginPx
        )
    }

    private fun downloadMediaWithProgressView(
        container: LinearLayout, progressView: View,
        mediaUrl: String, cachedFile: File,
        type: MediaType, widthPx: Int, marginPx: Int
    ) {
        val progressBar = progressView.findViewById<ProgressBar>(
            R.id.download_progress
        )
        val progressText = progressView.findViewById<TextView>(
            R.id.download_progress_text
        )

        CustomApplication.getApplication().applicationScope.launch(
            Dispatchers.IO
        ) {
            try {
                val request = Request.Builder().url(mediaUrl).build()
                val response = HttpClientManager.getInstance().client
                    .newCall(request).execute()
                if (!response.isSuccessful) {
                    handleMediaDownloadFailure(
                        container, progressView, mediaUrl, cachedFile,
                        type, widthPx, marginPx
                    )
                    return@launch
                }

                val totalBytes = response.body.contentLength()
                var downloadedBytes = 0L

                cachedFile.outputStream().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also {
                                bytesRead = it
                            } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else 0
                            val dlKB = downloadedBytes / 1024
                            val totalKB = totalBytes / 1024
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                                progressText.text = "$dlKB KB / $totalKB KB"
                            }
                        }
                    }
                }

                // VoIP.ms returns HTTP 200 with a short text error body for
                // expired media; reject anything that isn't decodable media so
                // it isn't cached and shown as a broken attachment.
                if (!isValidMediaFile(cachedFile, type)) {
                    handleMediaDownloadFailure(
                        container, progressView, mediaUrl, cachedFile,
                        type, widthPx, marginPx
                    )
                    return@launch
                }
                getMediaFailedMarker(mediaUrl).delete()

                withContext(Dispatchers.Main) {
                    val idx = container.indexOfChild(progressView)
                    container.removeView(progressView)
                    when (type) {
                        MediaType.IMAGE, MediaType.GIF -> {
                            val iv = createMediaImageView(widthPx, marginPx)
                            iv.load(cachedFile) {
                                crossfade(true)
                                size(Size(widthPx, widthPx))
                            }
                            iv.setOnClickListener {
                                launchMediaViewer(cachedFile)
                            }
                            container.addView(iv, idx)
                        }
                        MediaType.AUDIO -> {
                            addAudioPlayerView(
                                container, cachedFile, widthPx, marginPx, idx
                            )
                        }
                        MediaType.VIDEO -> {
                            addVideoView(
                                container, cachedFile,
                                widthPx, marginPx, idx
                            )
                        }
                        MediaType.OTHER -> {}
                    }
                }
            } catch (_: Exception) {
                handleMediaDownloadFailure(
                    container, progressView, mediaUrl, cachedFile,
                    type, widthPx, marginPx
                )
            }
        }
    }

    /**
     * Handles a failed/expired media download: drops the partial or
     * non-media file, records a failed marker so auto-download won't loop,
     * and replaces the progress tile with a tappable "unavailable" tile.
     */
    private suspend fun handleMediaDownloadFailure(
        container: LinearLayout, progressView: View, mediaUrl: String,
        cachedFile: File, type: MediaType, widthPx: Int, marginPx: Int
    ) {
        cachedFile.delete()
        getMediaFailedMarker(mediaUrl).createNewFile()
        withContext(Dispatchers.Main) {
            val idx = container.indexOfChild(progressView)
            if (idx >= 0) container.removeView(progressView)
            addUnavailableView(
                container, mediaUrl, cachedFile, type,
                widthPx, marginPx, widthPx / 2, idx
            )
            showSnackbar(
                activity, R.id.coordinator_layout,
                activity.getString(R.string.conversation_media_unavailable)
            )
        }
    }

    private fun createMediaImageView(
        widthPx: Int, marginPx: Int
    ): ImageView {
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginPx }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = activity.getString(R.string.message_media)
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun addAudioPlayerView(
        container: LinearLayout, file: File,
        widthPx: Int, marginPx: Int, index: Int = -1
    ) {
        val outerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginPx }
            setBackgroundResource(R.drawable.media_placeholder_bg)
            setPadding(24, 20, 24, 20)
        }

        val controlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val playButton = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_media_play)
            background = null
            contentDescription = "Play"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val seekBar = android.widget.SeekBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            max = 100
            progress = 0
            setPadding(16, 0, 16, 0)
        }

        controlRow.addView(playButton)
        controlRow.addView(seekBar)

        val timeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val elapsedText = TextView(activity).apply {
            text = "0:00"
            setTextColor(
                ContextCompat.getColor(activity, android.R.color.white)
            )
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(48, 0, 0, 0)
        }

        val totalText = TextView(activity).apply {
            text = "0:00"
            setTextColor(
                ContextCompat.getColor(activity, android.R.color.white)
            )
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 8, 0)
        }

        // Get total duration
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            val durationMs = mp.duration
            totalText.text = formatDuration(durationMs)
            seekBar.max = durationMs
            mp.release()
        } catch (_: Exception) {
            totalText.text = "--:--"
        }

        timeRow.addView(elapsedText)
        timeRow.addView(totalText)

        outerLayout.addView(controlRow)
        outerLayout.addView(timeRow)

        // Update handler for seekbar progress
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                activeMediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        seekBar.progress = mp.currentPosition
                        elapsedText.text = formatDuration(mp.currentPosition)
                        handler.postDelayed(this, 250)
                    }
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        activeMediaPlayer?.seekTo(progress)
                        elapsedText.text = formatDuration(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )

        playButton.setOnClickListener {
            if (activeMediaPlayer?.isPlaying == true) {
                activeMediaPlayer?.pause()
                playButton.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(updateRunnable)
            } else {
                activeMediaPlayer?.release()
                activeMediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
                seekBar.max = activeMediaPlayer!!.duration
                playButton.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)

                activeMediaPlayer?.setOnCompletionListener {
                    playButton.setImageResource(
                        android.R.drawable.ic_media_play
                    )
                    seekBar.progress = 0
                    elapsedText.text = "0:00"
                    handler.removeCallbacks(updateRunnable)
                    activeMediaPlayer?.release()
                    activeMediaPlayer = null
                }
            }
        }

        if (index >= 0) {
            container.addView(outerLayout, index)
        } else {
            container.addView(outerLayout)
        }
    }

    private fun launchMediaViewer(file: File) {
        try {
            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // No viewer available
        }
    }

    /**
     * Displays the date and time for the message. Optionally displays a
     * the status of the message in certain cases instead, such as when the
     * message is being sent or has failed to send. Optionally hides it
     * altogether, depending on the message's placement in a group.
     *
     * Optionally displays the date and time of the conversation group,
     * depending on the message's placement in the group.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    private fun updateViewHolderDateText(
        holder: MessageViewHolder,
        position: Int
    ) {
        val messageItem = messageItems[position]
        val message = messageItem.message

        // Set per-message date and time
        val dateText = holder.dateText
        if (!message.isDelivered) {
            if (!message.isDeliveryInProgress) {
                // Show tried but failed to send text
                val dateTextBuilder = SpannableStringBuilder()
                dateTextBuilder.append(
                    activity.getString(
                        R.string.conversation_message_not_sent
                    )
                )
                dateTextBuilder.setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(
                            activity,
                            android.R.color.holo_red_dark
                        )
                    ),
                    0,
                    dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                dateTextBuilder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    dateTextBuilder.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                dateText.text = dateTextBuilder
            } else {
                // Show sending text
                dateText.text = activity.getString(
                    R.string.conversation_message_sending
                )
            }
            dateText.visibility = View.VISIBLE
        } else {
            dateText.text = getConversationViewDate(activity, message.date)
        }

        // Set conversation group date and time
        val topDateText = holder.topDateText
        if (isFirstMessageInGroup(position, combineIncomingOutgoing = true)) {
            topDateText.text = getConversationViewTopDate(message.date)
            topDateText.visibility = View.VISIBLE
        } else {
            topDateText.visibility = View.GONE
        }
    }

    /**
     * Sets the colours of text in the view holder, depending on the message
     * type.
     *
     * @param holder The message view holder to use.
     * @param position The position of the view in the adapter.
     */
    fun updateViewHolderColours(holder: MessageViewHolder, position: Int) {
        val messageItem = messageItems[position]
        val message = messageItem.message

        // Incoming messages have the secondary color, while outgoing messages
        // have the primary color; dark variants are used for selection
        val smsContainer = holder.smsContainer
        if (message.isIncoming) {
            if (messageItem.checked) {
                smsContainer.setBackgroundResource(
                    R.color.message_incoming_checked
                )
            } else {
                smsContainer.setBackgroundResource(R.color.message_incoming)
            }
        } else {
            if (messageItem.checked) {
                smsContainer.setBackgroundResource(
                    R.color.message_outgoing_checked
                )
            } else {
                smsContainer.setBackgroundResource(R.color.message_outgoing)
            }
        }
    }

    override fun getItemViewType(i: Int): Int =
    // There are two different view types: one for incoming messages and
        // one for outgoing messages
        if (messageItems[i].message.isIncoming) {
            R.layout.conversation_item_incoming
        } else {
            R.layout.conversation_item_outgoing
        }

    override fun getItemCount(): Int = messageItems.size

    /**
     * Gets the number of items in the adapter that are checked.
     */
    fun getCheckedItemCount(): Int = messageItems.filter { it.checked }.size

    operator fun get(i: Int): MessageItem = messageItems[i]

    override fun iterator(): Iterator<MessageItem> = messageItems.iterator()

    override fun getSectionTitle(position: Int): String =
        getScrollBarDate(messageItems[position].message.date)

    override fun getFilter(): Filter = object : Filter() {
        /**
         * Perform filtering using the specified filter constraint.
         */
        fun doFiltering(constraint: CharSequence): ConversationFilter {
            // Get filtered messages
            val resultsObject = ConversationFilter()
            if (!BuildConfig.IS_DEMO) {
                runBlocking {
                    val filterString = constraint.toString()
                        .trim { it <= ' ' }
                        .lowercase(Locale.getDefault())
                    maxLimit = Database.getInstance(activity)
                        .getConversationMessagesFilteredCount(
                            conversationId, filterString
                        )
                    if (currLimit > maxLimit) {
                        currLimit = max(maxLimit, ADDITIONAL_ITEMS_INCREMENT)
                    }
                    // Oldest first, matching the conversation view order.
                    val messages = Database.getInstance(activity)
                        .getConversationMessagesFiltered(
                            conversationId,
                            filterString,
                            currLimit
                        ).asReversed()
                    if (filterString.isEmpty()) {
                        // Collapse reactions onto their target messages. When a
                        // search filter is active we leave messages untouched so
                        // that reaction text remains searchable.
                        val result = MessageReactions.process(messages)
                        resultsObject.messages.addAll(result.visibleMessages)
                        resultsObject.reactions = result.reactionsByMessageId
                    } else {
                        resultsObject.messages.addAll(messages)
                    }
                }
            } else {
                resultsObject.messages.addAll(
                    getConversationDemoMessages(
                        activity.bubble
                    )
                )
            }
            return resultsObject
        }

        override fun performFiltering(
            constraint: CharSequence
        ): FilterResults = try {
            val resultsObject = doFiltering(constraint)

            // Return filtered messages
            val results = FilterResults()
            results.count = resultsObject.messages.size
            results.values = resultsObject
            results
        } catch (e: Exception) {
            logException(e)
            FilterResults()
        }

        override fun publishResults(
            constraint: CharSequence,
            results: FilterResults?
        ) {
            if (results?.values == null) {
                showSnackbar(
                    activity, R.id.coordinator_layout,
                    activity.getString(
                        R.string.new_conversation_error_refresh
                    )
                )
                return
            }

            // Process new filter string
            prevConstraint = currConstraint
            currConstraint = constraint.toString().trim { it <= ' ' }

            // The Android results interface uses type Any, so we have
            // no choice but to use an unchecked cast
            val resultsObject = results.values as ConversationFilter
            val newMessages = resultsObject.messages

            // Update reactions before binding so view holders pick them up.
            reactionsByMessageId = resultsObject.reactions

            // Create copy of current messages
            val oldMessages = mutableListOf<Message>()
            messageItems.mapTo(oldMessages) { it.message }

            // Iterate through messages, determining which messages have
            // been added, changed, or removed to show appropriate
            // animations and update views
            var newIdx = 0
            var oldIdx = 0
            val messageIndexes = mutableListOf<Int>()
            while (oldIdx < oldMessages.size || newIdx < newMessages.size) {
                // Positive value indicates addition, negative value
                // indicates deletion, zero indicates changed, moved, or
                // nothing
                val comparison: Int = when {
                    newIdx >= newMessages.size -> -1
                    oldIdx >= oldMessages.size -> 1
                    else -> oldMessages[oldIdx]
                        .conversationViewCompareTo(newMessages[newIdx])
                }

                when {
                    comparison < 0 -> {
                        // Remove old message
                        _messageItems.removeAt(newIdx)
                        notifyItemRemoved(newIdx)
                        oldIdx += 1
                    }

                    comparison > 0 -> {
                        // Add new message
                        _messageItems.add(
                            newIdx,
                            MessageItem(newMessages[newIdx])
                        )
                        notifyItemInserted(newIdx)
                        newIdx += 1
                    }

                    else -> {
                        // Even though the view might not need to be changed,
                        // update the underlying message anyways just to be
                        // safe
                        messageItems[newIdx].message = newMessages[newIdx]
                        messageIndexes.add(newIdx)

                        oldIdx += 1
                        newIdx += 1
                    }
                }
            }

            for (idx in messageIndexes) {
                // Get the view holder for the view
                val viewHolder = recyclerView
                    .findViewHolderForAdapterPosition(idx)
                    as MessageViewHolder?

                if (viewHolder != null) {
                    // Try to update the view holder directly so that we
                    // don't see the "change" animation
                    onBindViewHolder(viewHolder, idx)
                } else {
                    // We can't find the view holder (probably because
                    // it's not actually visible), so we'll just tell
                    // the adapter to redraw the whole view to be safe
                    notifyItemChanged(idx)
                }
            }

            // Show message if filter returned no messages
            val emptyTextView = activity.findViewById<TextView>(
                R.id.empty_text
            )
            if (messageItems.isEmpty()) {
                if (currConstraint == "") {
                    emptyTextView.text = activity.getString(
                        R.string.conversation_no_messages
                    )
                } else {
                    emptyTextView.text = activity.getString(
                        R.string.conversation_no_results, currConstraint
                    )
                }
            } else {
                emptyTextView.text = ""
            }

            // Hack to force last message to not be below the send message
            // text box
            if (messageItems.size > 1) {
                if (layoutManager.findLastVisibleItemPosition()
                    >= messageItems.size - 2
                ) {
                    layoutManager.scrollToPosition(messageItems.size - 1)
                }
            }

            loadingMoreItems = false
        }
    }

    /**
     * Refreshes the adapter using the currently defined filter constraint.
     */
    fun refresh() = filter.filter(currConstraint)

    /**
     * Refreshes the adapter using the specified filter constraint.
     */
    fun refresh(constraint: String) = filter.filter(constraint)

    /**
     * Loads additional items from the database.
     */
    fun loadMoreItems() {
        if (currLimit + ADDITIONAL_ITEMS_INCREMENT <= maxLimit) {
            loadingMoreItems = true
            currLimit += ADDITIONAL_ITEMS_INCREMENT
            refresh()
        }
    }

    /**
     * Helper class used to store messages.
     */
    class ConversationFilter {
        internal val messages = mutableListOf<Message>()
        internal var reactions: Map<Long, List<String>> = emptyMap()
    }

    /**
     * Returns true if the message at the specified position is the first
     * message in a group, which is a collection of messages that are
     * spaced together based on whether the message is incoming or outgoing,
     * as well as the time between the messages.
     *
     * @param position The position of the specified message.
     * @param combineIncomingOutgoing If true, both incoming and outgoing
     * messages are considered to be part of the same group.
     */
    private fun isFirstMessageInGroup(
        position: Int, combineIncomingOutgoing: Boolean
    ): Boolean {
        val message = _messageItems[position].message
        val previousMessage: Message? = if (position > 0) {
            _messageItems[position - 1].message
        } else {
            null
        }
        return previousMessage == null
            || (!combineIncomingOutgoing
            && message.isIncoming != previousMessage.isIncoming)
            || message.date.time - previousMessage.date.time > 60000
    }

    /**
     * A container for a message item in the adapter that also tracks whether
     * the item is checked in addition to the message itself.
     *
     * @param message The message represented by the message item.
     */
    inner class MessageItem(var message: Message) {
        private var _checked = false
        val checked: Boolean
            get() = _checked

        /**
         * Sets whether or not the message item is checked.
         *
         * @param checked Whether the message item is checked.
         * @param position The position of the message item in the adapter.
         */
        fun setChecked(checked: Boolean, position: Int) {
            val previous = _checked
            _checked = checked

            val holder = recyclerView.findViewHolderForAdapterPosition(position)
                as MessageViewHolder?

            if ((previous && !_checked) || (!previous && _checked)) {
                if (holder != null) {
                    updateViewHolderColours(holder, position)
                } else {
                    notifyItemChanged(position)
                }
            }
        }

        /**
         * Toggles the checked state of this message item.
         *
         * @param position The position of the message item in the adapter.
         */
        fun toggle(position: Int) = setChecked(!_checked, position)
    }

    /**
     * A container for the views associated with a message item.
     *
     * @param itemView The primary view of the message item.
     */
    class MessageViewHolder internal constructor(
        itemView: View,
        viewType: Int
    ) : RecyclerView.ViewHolder(
        itemView
    ) {
        // All configurable views on a message item
        internal val contactBadge: QuickContactBadge? =
            if (viewType == R.layout.conversation_item_incoming) {
                itemView.findViewById(R.id.photo)
            } else {
                null
            }
        internal val smsContainer: View =
            itemView.findViewById(R.id.sms_container)
        internal val mediaContainer: LinearLayout =
            itemView.findViewById(R.id.media_container)
        internal val messageText: TextView =
            itemView.findViewById(R.id.message)
        internal val reactionsView: TextView =
            itemView.findViewById(R.id.reactions)
        internal val dateText: TextView =
            itemView.findViewById(R.id.date)
        internal val topDateText: TextView =
            itemView.findViewById(R.id.top_date)

        init {
            // Allow the message view itself to be selectable and add rounded
            // corners to it
            smsContainer.isClickable = true
            smsContainer.isLongClickable = true
            applyRoundedCornersMask(smsContainer)

            // Apply circular mask to and remove overlay from contact badge
            // to match Android Messages aesthetic
            if (contactBadge != null) {
                applyCircularMask(contactBadge)
                contactBadge.setOverlay(null)
            }
        }
    }

    companion object {
        // Strict regex for VoIP.ms media URLs to prevent path traversal.
        private val LOADABLE_MEDIA_URL_PATTERN =
            Regex("^https://voip\\.ms/media/[a-zA-Z0-9_=-]+/media\\.(jpeg|jpg|gif|png|mp3|wav|midi|mp4|3gp)$")

        // A cached media file smaller than this is treated as a non-media
        // error body (VoIP.ms returns short text like "Not found" for expired
        // media), never as a real attachment. Real MMS media is always KBs.
        private const val MIN_VALID_MEDIA_BYTES = 256L

        // The number of additional items to retrieve when loadMoreItems is
        // called.
        private const val ADDITIONAL_ITEMS_INCREMENT = 100L

        // When the message with this index is shown, we should start to load
        // more items.
        const val START_LOAD_INDEX = ADDITIONAL_ITEMS_INCREMENT / 4
    }
}
