/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package com.moez.QKSMS.feature.compose.part

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkViewHolder
import com.moez.QKSMS.common.util.Colors
import com.moez.QKSMS.common.util.extensions.resolveThemeColor
import com.moez.QKSMS.common.util.extensions.setBackgroundTint
import com.moez.QKSMS.common.util.extensions.setTint
import com.moez.QKSMS.feature.compose.BubbleUtils
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.model.MmsPart
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.mms_audio_list_item.*
import java.lang.ref.WeakReference
import java.util.Locale
import javax.inject.Inject

class AudioBinder @Inject constructor(
    colors: Colors,
    private val context: Context
) : PartBinder() {

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
        private val mainHandler = Handler(Looper.getMainLooper())

        private var activePlayer: MediaPlayer? = null
        private var activePartId: Long? = null
        private var activeDurationMs: Int = 0
        private var activeHolder: WeakReference<QkViewHolder>? = null
        private var activeBinder: AudioBinder? = null

        private val progressTick = object : Runnable {
            override fun run() {
                val player = activePlayer ?: return
                val holder = activeHolder?.get() ?: return
                val binder = activeBinder ?: return

                binder.renderPlaybackState(
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

    override val partLayout = R.layout.mms_audio_list_item
    override var theme = colors.theme()
    private val durationCache = mutableMapOf<Long, Int>()
    private val incomingBubbleColor = ContextCompat.getColor(context, R.color.threadBubbleIncoming)
    private val incomingBubbleSelectedColor = ContextCompat.getColor(context, R.color.threadBubbleIncomingSelected)
    private val outgoingBubbleColor = ContextCompat.getColor(context, R.color.threadBubbleOutgoing)
    private val outgoingBubbleSelectedColor = ContextCompat.getColor(context, R.color.threadBubbleOutgoingSelected)
    private val bubbleTextColor = ContextCompat.getColor(context, R.color.threadBubbleText)

    override fun canBindPart(part: MmsPart) = part.type.startsWith("audio")

    @SuppressLint("CheckResult")
    override fun bindPart(
        holder: QkViewHolder,
        part: MmsPart,
        message: Message,
        canGroupWithPrevious: Boolean,
        canGroupWithNext: Boolean
    ) {
        BubbleUtils.getBubble(false, canGroupWithPrevious, canGroupWithNext, message.isMe())
            .let(holder.audioBackground::setBackgroundResource)

        holder.containerView.setOnClickListener { togglePlayback(part, holder) }
        holder.playIcon.setOnClickListener { togglePlayback(part, holder) }

        Observable.fromCallable { getDurationMs(part) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { durationMs ->
                    durationCache[part.id] = durationMs
                    bindPlaybackState(holder, part.id, durationMs)
                },
                {
                    durationCache.remove(part.id)
                    bindPlaybackState(holder, part.id, 0)
                }
            )

        val params = holder.audioBackground.layoutParams as FrameLayout.LayoutParams
        val isActivated = holder.containerView.isActivated
        if (!message.isMe()) {
            holder.audioBackground.layoutParams = params.apply { gravity = Gravity.START }
            holder.audioBackground.setBackgroundTint(
                if (isActivated) {
                    incomingBubbleSelectedColor
                } else {
                    incomingBubbleColor
                }
            )
            holder.playIcon.setTint(bubbleTextColor)
            holder.duration.setTextColor(bubbleTextColor)
            tintProgress(holder.progress, bubbleTextColor)
        } else {
            holder.audioBackground.layoutParams = params.apply { gravity = Gravity.END }
            holder.audioBackground.setBackgroundTint(
                if (isActivated) {
                    outgoingBubbleSelectedColor
                } else {
                    outgoingBubbleColor
                }
            )
            holder.playIcon.setTint(bubbleTextColor)
            holder.duration.setTextColor(bubbleTextColor)
            tintProgress(holder.progress, bubbleTextColor)
        }
    }

    override fun performClick(part: MmsPart, holder: QkViewHolder): Boolean {
        togglePlayback(part, holder)
        return true
    }

    override fun onViewRecycled(holder: QkViewHolder) {
        super.onViewRecycled(holder)
        if (activeHolder?.get() === holder) {
            releaseActivePlayer()
        }
    }

    override fun onDetachedFromRecyclerView() {
        super.onDetachedFromRecyclerView()
        if (activeBinder === this) {
            releaseActivePlayer()
        }
    }

    private fun bindPlaybackState(holder: QkViewHolder, partId: Long, durationMs: Int) {
        if (activePartId == partId && activeHolder?.get() !== holder) {
            activeHolder = WeakReference(holder)
            activeBinder = this
        }

        val player = activePlayer
        val isActive = activePartId == partId && player != null
        val currentDuration = if (isActive) activeDurationMs else durationMs
        val currentProgress = if (isActive) player?.currentPosition ?: 0 else 0

        renderPlaybackState(
            holder = holder,
            isPlaying = isActive && player?.isPlaying == true,
            progressMs = currentProgress,
            durationMs = currentDuration
        )
    }

    private fun togglePlayback(part: MmsPart, holder: QkViewHolder) {
        when {
            activePartId != part.id -> startPlayback(part, holder)
            activePlayer?.isPlaying == true -> pausePlayback(holder)
            else -> resumePlayback(holder)
        }
    }

    private fun startPlayback(part: MmsPart, holder: QkViewHolder) {
        releaseActivePlayer()

        val player = MediaPlayer()
        activePlayer = player
        activePartId = part.id
        activeDurationMs = durationCache[part.id] ?: 0
        activeHolder = WeakReference(holder)
        activeBinder = this

        renderPlaybackState(holder, isPlaying = false, progressMs = 0, durationMs = activeDurationMs)

        try {
            player.setDataSource(context, part.getUri())
            player.setOnPreparedListener { prepared ->
                activeDurationMs = prepared.duration.coerceAtLeast(activeDurationMs)
                durationCache[part.id] = activeDurationMs
                prepared.start()
                renderPlaybackState(
                    holder = activeHolder?.get() ?: holder,
                    isPlaying = true,
                    progressMs = prepared.currentPosition.coerceAtLeast(0),
                    durationMs = activeDurationMs
                )
                stopProgressUpdates()
                mainHandler.post(progressTick)
            }
            player.setOnCompletionListener {
                val active = activeHolder?.get()
                if (active != null) {
                    renderPlaybackState(active, isPlaying = false, progressMs = 0, durationMs = activeDurationMs)
                }
                releaseActivePlayer(keepUi = true)
            }
            player.setOnErrorListener { _, _, _ ->
                releaseActivePlayer()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releaseActivePlayer()
        }
    }

    private fun pausePlayback(holder: QkViewHolder) {
        activePlayer?.pause()
        stopProgressUpdates()
        renderPlaybackState(
            holder = holder,
            isPlaying = false,
            progressMs = activePlayer?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = activeDurationMs
        )
    }

    private fun resumePlayback(holder: QkViewHolder) {
        val player = activePlayer ?: return
        player.start()
        renderPlaybackState(
            holder = holder,
            isPlaying = true,
            progressMs = player.currentPosition.coerceAtLeast(0),
            durationMs = activeDurationMs
        )
        stopProgressUpdates()
        mainHandler.post(progressTick)
    }

    private fun releaseActivePlayer(keepUi: Boolean = false) {
        stopProgressUpdates()
        val holder = activeHolder?.get()

        activePlayer?.setOnPreparedListener(null)
        activePlayer?.setOnCompletionListener(null)
        activePlayer?.setOnErrorListener(null)
        runCatching { activePlayer?.stop() }
        runCatching { activePlayer?.reset() }
        runCatching { activePlayer?.release() }

        if (!keepUi && holder != null) {
            renderPlaybackState(
                holder = holder,
                isPlaying = false,
                progressMs = 0,
                durationMs = activeDurationMs
            )
        }

        activePlayer = null
        activePartId = null
        activeHolder = null
        activeBinder = null
        activeDurationMs = 0
    }

    private fun renderPlaybackState(
        holder: QkViewHolder,
        isPlaying: Boolean,
        progressMs: Int,
        durationMs: Int
    ) {
        holder.playIcon.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        holder.progress.max = durationMs.coerceAtLeast(1)
        holder.progress.progress = progressMs.coerceIn(0, holder.progress.max)
        holder.duration.text = when {
            durationMs <= 0 -> context.getString(R.string.audio_message_label)
            progressMs > 0 -> context.getString(
                R.string.audio_message_progress_format,
                formatDuration(progressMs.toLong()),
                formatDuration(durationMs.toLong())
            )
            else -> formatDuration(durationMs.toLong())
        }
    }

    private fun tintProgress(progressBar: ProgressBar, color: Int) {
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun getDurationMs(part: MmsPart): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, part.getUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
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
