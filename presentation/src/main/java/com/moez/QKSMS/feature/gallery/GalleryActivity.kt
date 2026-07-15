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

import android.Manifest
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.github.chrisbanes.photoview.PhotoView
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkActivity
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.setVisible
import com.moez.QKSMS.extensions.isVideo
import com.moez.QKSMS.model.MmsPart
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.compose_activity.*
import kotlinx.android.synthetic.main.gallery_activity.*
import kotlinx.android.synthetic.main.gallery_activity.toolbar
import kotlinx.android.synthetic.main.gallery_activity.toolbarSubtitle
import kotlinx.android.synthetic.main.gallery_activity.toolbarTitle
import timber.log.Timber
import javax.inject.Inject

class GalleryActivity : QkActivity(), GalleryView {

    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var pagerAdapter: GalleryPagerAdapter
    private lateinit var zoomIn : View
    private lateinit var zoomOut : View
    private var initialPartSelectionApplied = false
    private var initialPartSelectionRetries = 20

    val partId by lazy { intent.getLongExtra("partId", 0L) }

    private val optionsItemSubject: Subject<Int> = PublishSubject.create()
    private val pageChangedSubject: Subject<MmsPart> = PublishSubject.create()
    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[GalleryViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity)
        showBackButton(true)
        viewModel.bindView(this)
        Timber.d("Gallery onCreate partId=%d", partId)
zoomIn = findViewById(R.id.zoomin)
        zoomOut=findViewById(R.id.zoomout)

        zoomIn.setOnClickListener { adjustZoom(-0.75f) }
        zoomOut.setOnClickListener { adjustZoom(0.75f) }

        pager.adapter = pagerAdapter
        pager.visibility = View.INVISIBLE
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                this@GalleryActivity.onPageSelected(position)
            }
        })

        pagerAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (applyInitialPartSelection() == true) {
                    pagerAdapter.unregisterAdapterDataObserver(this)
                }
            }
        })
        scheduleInitialPartSelection()

    }

    private fun scheduleInitialPartSelection() {
        if (initialPartSelectionApplied) return
        if (initialPartSelectionRetries <= 0) {
            pager.visibility = View.VISIBLE
            return
        }

        root.postDelayed({
            if (applyInitialPartSelection() == true) {
                return@postDelayed
            }

            initialPartSelectionRetries--
            Timber.d(
                "Gallery scheduleInitialPartSelection retry remaining=%d requestedPartId=%d itemCount=%d",
                initialPartSelectionRetries,
                partId,
                pagerAdapter.itemCount
            )
            scheduleInitialPartSelection()
        }, 100)
    }

    private fun applyInitialPartSelection(): Boolean? {
        if (initialPartSelectionApplied) return true

        val parts = pagerAdapter.data?.takeIf { pagerAdapter.itemCount > 0 } ?: return false
        val index = parts.indexOfFirst { part -> part.id == partId }
        Timber.d(
            "Gallery applyInitialPartSelection requestedPartId=%d resolvedIndex=%d parts=%s",
            partId,
            index,
            parts.joinToString { part -> "id=${part.id},seq=${part.seq},type=${part.type}" }
        )
        if (index < 0) return false

        initialPartSelectionApplied = true
        pager.setCurrentItem(index, false)
        pager.visibility = View.VISIBLE
        onPageSelected(index)
        return true
    }

    private fun currentPagerRecyclerView(): RecyclerView? {
        return pager.getChildAt(0) as? RecyclerView
    }

    private fun currentPhotoView(): PhotoView? {
        return currentPagerRecyclerView()
            ?.findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView
            ?.findViewById(R.id.image)
    }

    private fun currentPlayerView(): PlayerView? {
        return currentPagerRecyclerView()
            ?.findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView
            ?.findViewById(R.id.video)
    }

    private fun currentVideoPlayer(): ExoPlayer? {
        return currentPlayerView()?.player as? ExoPlayer
    }

    private fun currentPart(): MmsPart? = pagerAdapter.getItem(pager.currentItem)

    private fun isCurrentPartVideo(): Boolean = currentPart()?.isVideo() == true

    private fun toggleCurrentVideoPlayback(): Boolean {
        val player = currentVideoPlayer() ?: return false
        player.playWhenReady = !player.playWhenReady
        updateVideoCenterHint()
        return true
    }

    private fun seekCurrentVideo(deltaMs: Long): Boolean {
        val player = currentVideoPlayer() ?: return false
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        if (target == player.currentPosition) return false
        player.seekTo(target)
        return true
    }

    private fun adjustZoom(delta: Float) {
        val image = currentPhotoView() ?: return
        val newScale = (image.scale + delta).coerceIn(1f, 5f)
        if (newScale != image.scale) {
            image.setScale(newScale, true)
        }
    }

    private fun resetCurrentImageZoom(): Boolean {
        val image = currentPhotoView() ?: return false
        if (image.scale <= 1f) return false
        image.setScale(1f, true)
        return true
    }

    private fun isCurrentImageZoomed(): Boolean = currentPhotoView()?.scale?.let { it > 1f } == true

    private fun imagePanStepX(image: PhotoView): Float = (image.width * 0.6f).coerceAtLeast(120f)

    private fun imagePanStepY(image: PhotoView): Float = (image.height * 0.6f).coerceAtLeast(120f)

    private fun panCurrentImage(dx: Float, dy: Float): Boolean {
        val image = currentPhotoView() ?: return false
        if (image.scale <= 1f) return false

        val matrix = Matrix().also(image.attacher::getSuppMatrix)
        val rect = RectF(image.displayRect ?: return false)

        var clampedDx = dx
        var clampedDy = dy

        if (rect.width() <= image.width) {
            clampedDx = 0f
        } else {
            if (rect.left + clampedDx > 0f) clampedDx = -rect.left
            if (rect.right + clampedDx < image.width) clampedDx = image.width - rect.right
        }

        if (rect.height() <= image.height) {
            clampedDy = 0f
        } else {
            if (rect.top + clampedDy > 0f) clampedDy = -rect.top
            if (rect.bottom + clampedDy < image.height) clampedDy = image.height - rect.bottom
        }

        if (clampedDx == 0f && clampedDy == 0f) return false

        matrix.postTranslate(clampedDx, clampedDy)
        return image.setDisplayMatrix(matrix)
    }

    fun onPageSelected(position: Int) {
        pagerAdapter.pauseAllPlayers()
        Timber.d(
            "Gallery onPageSelected position=%d currentPartId=%s requestedPartId=%d",
            position,
            pagerAdapter.getItem(position)?.id?.toString() ?: "null",
            partId
        )
        toolbarSubtitle.text = pagerAdapter.getItem(position)?.messages?.firstOrNull()?.date
                ?.let(dateFormatter::getDetailedTimestamp)
        toolbarSubtitle.isVisible = toolbarTitle.text.isNotBlank()
        val videoPage = pagerAdapter.getItem(position)?.isVideo() == true
        linearLayout_zoom.isVisible = !videoPage
        linearLayout_seek.isVisible = videoPage
        updateVideoCenterHint()

        pagerAdapter.getItem(position)?.run(pageChangedSubject::onNext)
    }

    private fun updateVideoCenterHint() {
        if (!isCurrentPartVideo()) {
            videoCenterHint.isVisible = false
            return
        }

        val playing = currentVideoPlayer()?.playWhenReady == true
        videoCenterHint.text = if (playing) "||" else "▶"
        videoCenterHint.isVisible = true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {


        return when (event?.keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                if (isCurrentPartVideo()) {
                    seekCurrentVideo(-5000) || super.onKeyUp(keyCode, event)
                } else {
                    zoomIn.performClick()
                    true
                }
            }

            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                if (isCurrentPartVideo()) {
                    seekCurrentVideo(5000) || super.onKeyUp(keyCode, event)
                } else {
                    zoomOut.performClick()
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isCurrentPartVideo()) {
                    toggleCurrentVideoPlayback() || super.onKeyUp(keyCode, event)
                } else {
                    super.onKeyUp(keyCode, event)
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isCurrentPartVideo()) {
                    toolbar.setVisible(!toolbar.isVisible)
                    true
                } else {
                    currentPhotoView()?.let { image ->
                        panCurrentImage(0f, imagePanStepY(image)) || super.onKeyUp(keyCode, event)
                    } ?: super.onKeyUp(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isCurrentPartVideo()) {
                    toolbar.setVisible(!toolbar.isVisible)
                    true
                } else {
                    currentPhotoView()?.let { image ->
                        panCurrentImage(0f, -imagePanStepY(image)) || super.onKeyUp(keyCode, event)
                    } ?: super.onKeyUp(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isCurrentPartVideo()) {
                    if (pager.currentItem > 0) {
                        pager.currentItem = pager.currentItem - 1
                        true
                    } else {
                        super.onKeyUp(keyCode, event)
                    }
                } else {
                    val handledPan = currentPhotoView()?.let { image ->
                        panCurrentImage(imagePanStepX(image), 0f)
                    } ?: false
                    if (handledPan || isCurrentImageZoomed()) {
                        true
                    } else if (pager.currentItem > 0) {
                        pager.currentItem = pager.currentItem - 1
                        true
                    } else {
                        super.onKeyUp(keyCode, event)
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isCurrentPartVideo()) {
                    if (pager.currentItem < pagerAdapter.itemCount - 1) {
                        pager.currentItem = pager.currentItem + 1
                        true
                    } else {
                        super.onKeyUp(keyCode, event)
                    }
                } else {
                    val handledPan = currentPhotoView()?.let { image ->
                        panCurrentImage(-imagePanStepX(image), 0f)
                    } ?: false
                    if (handledPan || isCurrentImageZoomed()) {
                        true
                    } else if (pager.currentItem < pagerAdapter.itemCount - 1) {
                        pager.currentItem = pager.currentItem + 1
                        true
                    } else {
                        super.onKeyUp(keyCode, event)
                    }
                }
            }


            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                onBackPressed()
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }


    override fun render(state: GalleryState) {
        toolbar.setVisible(state.navigationVisible)

        title = state.title
        pagerAdapter.updateData(state.parts)
        if (state.parts.isNullOrEmpty()) {
            pager.visibility = View.INVISIBLE
        } else if (partId == 0L) {
            pager.visibility = View.VISIBLE
        }
        applyInitialPartSelection()
        scheduleInitialPartSelection()
    }

    override fun optionsItemSelected(): Observable<Int> = optionsItemSubject

    override fun screenTouched(): Observable<*> = pagerAdapter.clicks

    override fun pageChanged(): Observable<MmsPart> = pageChangedSubject

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.gallery, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        if (!isCurrentPartVideo() && resetCurrentImageZoom()) {
            return
        }
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            else -> optionsItemSubject.onNext(item.itemId)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        pagerAdapter.destroy()
    }

}
