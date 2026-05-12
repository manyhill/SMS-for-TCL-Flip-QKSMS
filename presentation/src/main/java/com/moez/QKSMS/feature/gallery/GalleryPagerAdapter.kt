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
package com.moez.QKSMS.feature.gallery

import android.content.Context
import android.graphics.Color
import android.webkit.MimeTypeMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.mms.ContentType
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkRealmAdapter
import com.moez.QKSMS.common.base.QkViewHolder
import com.moez.QKSMS.extensions.isImage
import com.moez.QKSMS.extensions.isVideo
import com.moez.QKSMS.model.MmsPart
import com.moez.QKSMS.util.GlideApp
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.gallery_image_page.*
import kotlinx.android.synthetic.main.gallery_image_page.view.*
import kotlinx.android.synthetic.main.gallery_video_page.*
import java.util.*
import javax.inject.Inject

class GalleryPagerAdapter @Inject constructor(
    private val context: Context
) : QkRealmAdapter<MmsPart>() {

    init {
        setHasStableIds(true)
    }

    companion object {
        private const val VIEW_TYPE_INVALID = 0
        private const val VIEW_TYPE_IMAGE = 1
        private const val VIEW_TYPE_VIDEO = 2
    }

    val clicks: Subject<View> = PublishSubject.create()

    private val contentResolver = context.contentResolver
    private val exoPlayers = Collections.newSetFromMap(WeakHashMap<ExoPlayer?, Boolean>())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return QkViewHolder(when (viewType) {
            VIEW_TYPE_IMAGE -> inflater.inflate(R.layout.gallery_image_page, parent, false).apply {
                image.setScaleLevels(1f, 2.5f, 5f)
            }

            VIEW_TYPE_VIDEO -> inflater.inflate(R.layout.gallery_video_page, parent, false)

            else -> inflater.inflate(R.layout.gallery_invalid_page, parent, false)

        }.apply { setOnClickListener(clicks::onNext) })
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val part = getItem(position) ?: return
        when (getItemViewType(position)) {
            VIEW_TYPE_IMAGE -> {
                val imageModel = part.getUri()

                // Request GIFs explicitly for animation
                when (part.getUri().let(contentResolver::getType)
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(part.getUri().lastPathSegment?.substringAfterLast('.', "")?.toLowerCase(Locale.getDefault()))
                ) {
                    ContentType.IMAGE_GIF -> GlideApp.with(context)
                        .asGif()
                        .apply(RequestOptions().override(Target.SIZE_ORIGINAL))
                        .load(imageModel)
                        .into(holder.image)

                    else -> GlideApp.with(context)
                        .asBitmap()
                        .apply(RequestOptions().override(Target.SIZE_ORIGINAL))
                        .load(imageModel)
                        .into(holder.image)
                }
            }

            VIEW_TYPE_VIDEO -> {
                holder.video.player?.let { existingPlayer ->
                    exoPlayers.remove(existingPlayer as? ExoPlayer)
                    existingPlayer.stop(true)
                    existingPlayer.release()
                }

                // FIX: no-arg AdaptiveTrackSelection.Factory() on newer ExoPlayer
                val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
                val trackSelector = DefaultTrackSelector(videoTrackSelectionFactory)
                val exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector)
                holder.video.player = exoPlayer
                holder.video.useController = false
                holder.video.setShutterBackgroundColor(Color.TRANSPARENT)
                exoPlayers.add(exoPlayer)

                val dataSourceFactory = DefaultDataSourceFactory(
                    context,
                    Util.getUserAgent(context, "QKSMS")
                )
                val videoSource = ExtractorMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(part.getUri())
                exoPlayer.prepare(videoSource)
                exoPlayer.playWhenReady = false
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: RecyclerView.NO_ID
    }

    override fun onViewRecycled(holder: QkViewHolder) {
        holder.video?.player?.let { player ->
            exoPlayers.remove(player as? ExoPlayer)
            player.stop(true)
            player.release()
        }
        holder.video?.player = null
        holder.image?.let { image ->
            GlideApp.with(image).clear(image)
        }
        super.onViewRecycled(holder)
    }

    override fun getItemViewType(position: Int): Int {
        val part = getItem(position)
        return when {
            part?.isImage() == true -> VIEW_TYPE_IMAGE
            part?.isVideo() == true -> VIEW_TYPE_VIDEO
            else -> VIEW_TYPE_INVALID
        }
    }

    fun destroy() {
        exoPlayers.forEach { exoPlayer -> exoPlayer?.release() }
        exoPlayers.clear()
    }

    fun pauseAllPlayers() {
        exoPlayers.forEach { exoPlayer ->
            exoPlayer?.playWhenReady = false
        }
    }
}
