/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.feature.compose

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkAdapter
import com.moez.QKSMS.common.base.QkViewHolder
import com.moez.QKSMS.common.widget.QkTextView
import com.moez.QKSMS.common.util.extensions.getDisplayName
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.util.GlideApp
import ezvcard.Ezvcard
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.android.synthetic.main.attachment_contact_list_item.*
import kotlinx.android.synthetic.main.attachment_file_list_item.filename
import kotlinx.android.synthetic.main.attachment_file_list_item.size
import kotlinx.android.synthetic.main.attachment_image_list_item.*
import kotlinx.android.synthetic.main.attachment_image_list_item.view.*
import javax.inject.Inject

class AttachmentAdapter @Inject constructor(
    private val context: Context
) : QkAdapter<Attachment>() {

    companion object {
        private const val VIEW_TYPE_IMAGE = 0
        private const val VIEW_TYPE_CONTACT = 1
        private const val VIEW_TYPE_VIDEO = 2
        private const val VIEW_TYPE_FILE = 3
        private const val VIEW_TYPE_AUDIO_FILE = 4
        private const val PROGRESS_INTERVAL_MS = 250L

        private val mainHandler = Handler(Looper.getMainLooper())

        private var activePlayer: MediaPlayer? = null
        private var activeUriKey: String? = null
        private var activeDurationMs: Int = 0
        private var activeHolder: WeakReference<QkViewHolder>? = null
        private var activeAdapter: AttachmentAdapter? = null

        private val progressTick = object : Runnable {
            override fun run() {
                val player = activePlayer ?: return
                val holder = activeHolder?.get() ?: return
                val adapter = activeAdapter ?: return

                adapter.renderAudioPlaybackState(
                    holder = holder,
                    isPlaying = player.isPlaying,
                    progressMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = activeDurationMs
                )

                if (player.isPlaying) {
                    mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }
        }

        private fun stopProgressUpdates() {
            mainHandler.removeCallbacks(progressTick)
        }
    }

    val attachmentDeleted: Subject<Attachment> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            VIEW_TYPE_IMAGE -> inflater.inflate(R.layout.attachment_image_list_item, parent, false)
                    .apply { thumbnailBounds.clipToOutline = true }

            VIEW_TYPE_CONTACT -> inflater.inflate(R.layout.attachment_contact_list_item, parent, false)
            VIEW_TYPE_VIDEO -> inflater.inflate(R.layout.attachment_image_list_item, parent, false)
                .apply { thumbnailBounds.clipToOutline = true }

            VIEW_TYPE_FILE -> inflater.inflate(R.layout.attachment_file_list_item, parent, false)
            VIEW_TYPE_AUDIO_FILE -> inflater.inflate(R.layout.attachment_audio_list_item, parent, false)

            else -> null!! // Impossible
        }

        return QkViewHolder(view).apply {
            view.findViewById<View>(R.id.detach).setOnClickListener {
                val position = adapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: return@setOnClickListener
                val attachment = getItem(position)
                attachmentDeleted.onNext(attachment)
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val attachment = getItem(position)

        when (attachment) {
            is Attachment.Image -> Glide.with(context)
                    .load(attachment.getUri())
                    .into(holder.thumbnail)

            is Attachment.Contact -> Observable.just(attachment.vCard)
                    .mapNotNull { vCard -> Ezvcard.parse(vCard).first() }
                    .map { vcard -> vcard.getDisplayName() ?: "" }
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { displayName ->
                        holder.name?.text = displayName
                        holder.name?.isVisible = displayName.isNotEmpty()
                    }

            is Attachment.Video -> {
                GlideApp.with(context).load(attachment.getUri()).fitCenter().into(holder.thumbnail)
            }
            is Attachment.File -> {
                if (holder.itemViewType == VIEW_TYPE_AUDIO_FILE) {
                    bindAudioFile(holder, attachment)
                } else {
                    bindFile(holder, attachment)
                }
            }
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Attachment.Image -> VIEW_TYPE_IMAGE
        is Attachment.Contact -> VIEW_TYPE_CONTACT
        is Attachment.Video -> VIEW_TYPE_VIDEO
        is Attachment.File -> if (getItem(position).isAudioFile()) VIEW_TYPE_AUDIO_FILE else VIEW_TYPE_FILE
    }

    override fun onViewRecycled(holder: QkViewHolder) {
        super.onViewRecycled(holder)
        if (activeHolder?.get() === holder) {
            releaseActiveAudio()
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (activeAdapter === this) {
            releaseActiveAudio()
        }
    }

    private fun bindFile(holder: QkViewHolder, attachment: Attachment.File) {
        Observable.just(attachment.getName(context))
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { name ->
                holder.filename?.text = name
                holder.filename?.isVisible = name?.isNotEmpty() ?: false
            }
        Observable.just(attachment.getSize(context))
            .map { bytes ->
                when (bytes) {
                    in 0..999 -> "$bytes B"
                    in 1000..999999 -> "${"%.1f".format(bytes / 1000f)} KB"
                    in 1000000..9999999 -> "${"%.1f".format(bytes / 1000000f)} MB"
                    else -> "${"%.1f".format(bytes / 1000000000f)} GB"
                }
            }
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { size ->
                holder.size?.text = size
                holder.size?.isVisible = size?.isNotEmpty() ?: false
            }
    }

    private fun bindAudioFile(holder: QkViewHolder, attachment: Attachment.File) {
        val uri = attachment.getUri()
        val uriKey = uri?.toString().orEmpty()
        holder.containerView.tag = uriKey
        holder.containerView.setOnClickListener {
            toggleAudioPlayback(uri, uriKey, holder)
        }
        holder.containerView.findViewById<ImageView>(R.id.playIcon)?.setOnClickListener {
            toggleAudioPlayback(uri, uriKey, holder)
        }

        renderAudioPlaybackState(holder, isPlaying = false, progressMs = 0, durationMs = 0)

        Observable.fromCallable { getAudioDurationMs(attachment) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { durationMs ->
                if (holder.containerView.tag != uriKey) return@subscribe
                if (activeUriKey == uriKey) {
                    activeDurationMs = durationMs.coerceAtLeast(activeDurationMs)
                    activeHolder = WeakReference(holder)
                    activeAdapter = this
                }
                renderAudioPlaybackState(
                    holder = holder,
                    isPlaying = activeUriKey == uriKey && activePlayer?.isPlaying == true,
                    progressMs = if (activeUriKey == uriKey) activePlayer?.currentPosition ?: 0 else 0,
                    durationMs = if (activeUriKey == uriKey) activeDurationMs else durationMs
                )
            }
    }

    private fun toggleAudioPlayback(uri: Uri?, uriKey: String, holder: QkViewHolder) {
        if (uri == null) return

        when {
            activeUriKey != uriKey -> startAudioPlayback(uri, uriKey, holder)
            activePlayer?.isPlaying == true -> pauseAudioPlayback(holder)
            else -> resumeAudioPlayback(holder)
        }
    }

    private fun startAudioPlayback(uri: Uri, uriKey: String, holder: QkViewHolder) {
        releaseActiveAudio()

        val player = MediaPlayer()
        activePlayer = player
        activeUriKey = uriKey
        activeDurationMs = 0
        activeHolder = WeakReference(holder)
        activeAdapter = this

        renderAudioPlaybackState(holder, isPlaying = false, progressMs = 0, durationMs = 0)

        try {
            player.setDataSource(context, uri)
            player.setOnPreparedListener { prepared ->
                activeDurationMs = prepared.duration.coerceAtLeast(activeDurationMs)
                prepared.start()
                renderAudioPlaybackState(
                    holder = activeHolder?.get() ?: holder,
                    isPlaying = true,
                    progressMs = prepared.currentPosition.coerceAtLeast(0),
                    durationMs = activeDurationMs
                )
                stopProgressUpdates()
                mainHandler.post(progressTick)
            }
            player.setOnCompletionListener {
                activeHolder?.get()?.let { active ->
                    renderAudioPlaybackState(active, isPlaying = false, progressMs = 0, durationMs = activeDurationMs)
                }
                releaseActiveAudio(keepUi = true)
            }
            player.setOnErrorListener { _, _, _ ->
                releaseActiveAudio()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releaseActiveAudio()
        }
    }

    private fun pauseAudioPlayback(holder: QkViewHolder) {
        activePlayer?.pause()
        stopProgressUpdates()
        renderAudioPlaybackState(
            holder = holder,
            isPlaying = false,
            progressMs = activePlayer?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = activeDurationMs
        )
    }

    private fun resumeAudioPlayback(holder: QkViewHolder) {
        val player = activePlayer ?: return
        player.start()
        renderAudioPlaybackState(
            holder = holder,
            isPlaying = true,
            progressMs = player.currentPosition.coerceAtLeast(0),
            durationMs = activeDurationMs
        )
        stopProgressUpdates()
        mainHandler.post(progressTick)
    }

    private fun releaseActiveAudio(keepUi: Boolean = false) {
        stopProgressUpdates()
        val holder = activeHolder?.get()

        activePlayer?.setOnPreparedListener(null)
        activePlayer?.setOnCompletionListener(null)
        activePlayer?.setOnErrorListener(null)
        runCatching { activePlayer?.stop() }
        runCatching { activePlayer?.reset() }
        runCatching { activePlayer?.release() }

        if (!keepUi && holder != null) {
            renderAudioPlaybackState(
                holder = holder,
                isPlaying = false,
                progressMs = 0,
                durationMs = activeDurationMs
            )
        }

        activePlayer = null
        activeUriKey = null
        activeHolder = null
        activeAdapter = null
        activeDurationMs = 0
    }

    private fun renderAudioPlaybackState(
        holder: QkViewHolder,
        isPlaying: Boolean,
        progressMs: Int,
        durationMs: Int
    ) {
        holder.containerView.findViewById<ImageView>(R.id.playIcon)?.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        holder.containerView.findViewById<ProgressBar>(R.id.progress)?.apply {
            max = durationMs.coerceAtLeast(1)
            progress = progressMs.coerceIn(0, max)
        }
        holder.containerView.findViewById<QkTextView>(R.id.duration)?.text = when {
            durationMs <= 0 -> context.getString(R.string.audio_message_label)
            progressMs > 0 -> context.getString(
                R.string.audio_message_progress_format,
                formatDuration(progressMs.toLong()),
                formatDuration(durationMs.toLong())
            )
            else -> formatDuration(durationMs.toLong())
        }
    }

    private fun Attachment.isAudioFile(): Boolean {
        val file = this as? Attachment.File ?: return false
        val contentType = file.getContentType(context)
        if (contentType?.startsWith("audio/", ignoreCase = true) == true) {
            return true
        }

        val path = file.getUri()?.lastPathSegment?.toLowerCase(Locale.getDefault()).orEmpty()
        return listOf(".amr", ".aac", ".m4a", ".mp3", ".ogg", ".wav", ".3gp").any(path::endsWith)
    }

    private fun getAudioDurationMs(attachment: Attachment.File): Int {
        val uri = attachment.getUri() ?: return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

}
