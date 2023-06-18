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

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
import android.content.res.Resources
import android.media.CamcorderProfile
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.*
import android.widget.NumberPicker
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkThemedActivity
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.*
import com.moez.QKSMS.feature.compose.editing.ChipsAdapter
import com.moez.QKSMS.feature.contacts.ContactsActivity
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.model.Recipient
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.compose_activity.*
import kotlinx.android.synthetic.main.qk_dialog.*
import kotlinx.android.synthetic.main.qk_dialog.view.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class ComposeActivity : QkThemedActivity(), ComposeView {

    companion object {
        private const val SelectContactRequestCode = 0
        private const val TakePhotoRequestCode = 1
        private const val AttachPhotoRequestCode = 2
        private const val AttachContactRequestCode = 3
        private const val AttachAudioRequestCode = 4
        private const val AttachVideoRequestCode = 5
        private const val RecordAudioRequestCode = 6
        private const val RecordVideoRequestCode = 7


        private const val CameraDestinationKey = "camera_destination"
    }

    @Inject
    lateinit var attachmentAdapter: AttachmentAdapter

    @Inject
    lateinit var chipsAdapter: ChipsAdapter

    @Inject
    lateinit var dateFormatter: DateFormatter

    @Inject
    lateinit var messageAdapter: MessagesAdapter

    @Inject
    lateinit var navigator: Navigator

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override val activityVisibleIntent: Subject<Boolean> = PublishSubject.create()
    override val chipsSelectedIntent: Subject<HashMap<String, String?>> = PublishSubject.create()
    override val chipDeletedIntent: Subject<Recipient> by lazy { chipsAdapter.chipDeleted }
    override val menuReadyIntent: Observable<Unit> = menu.map { }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val sendAsGroupIntent by lazy { sendAsGroupBackground.clicks() }
    override val messageClickIntent: Subject<Long> by lazy { messageAdapter.clicks }
    override val messagePartClickIntent: Subject<Long> by lazy { messageAdapter.partClicks }
    override val messagesSelectedIntent by lazy {
        messageAdapter.selectionChanges
    }
    override val cancelSendingIntent: Subject<Long> by lazy { messageAdapter.cancelSending }
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { attachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { message.textChanges() }
    override val attachIntent by lazy {
        Observable.merge(
            attach.clicks(), attachingBackground.clicks()
        )
    }
    override val cameraIntent by lazy { Observable.merge(camera.clicks(), cameraLabel.clicks()) }
    override val galleryIntent by lazy { Observable.merge(gallery.clicks(), galleryLabel.clicks()) }
    override val scheduleIntent by lazy {
        Observable.merge(
            schedule.clicks(), scheduleLabel.clicks()
        )
    }
    override val attachContactIntent by lazy {
        Observable.merge(
            contact.clicks(), contactLabel.clicks()
        )
    }

    override val attachmentSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val contactSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val inputContentIntent by lazy { message.inputContentSelected }
    override val scheduleSelectedIntent: Subject<Long> = PublishSubject.create()
    override val changeSimIntent by lazy { sim.clicks() }
    override val scheduleCancelIntent by lazy { scheduledCancel.clicks() }
    override val audioSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val videoSelectedIntent: Subject<Uri> = PublishSubject.create()

    //    override val sendIntent by lazy { send.clicks() }
    override val sendIntent by lazy {
        send_tv.clicks()
    }

    override val viewQksmsPlusIntent: Subject<Unit> = PublishSubject.create()
    override val backPressedIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy {
        ViewModelProviders.of(
            this, viewModelFactory
        )[ComposeViewModel::class.java]
    }

    private var lastPasteClickedTime: Date? = null

    private var cameraDestination: Uri? = null

    private val sVideoDuration = intArrayOf(0, 5, 10, 15, 20, 30, 40, 50, 60, 90, 120)

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compose_activity)
        showBackButton(true)
        viewModel.bindView(this)

        initClicks()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)



        contentView.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.CHANGING)
        }

        chipsAdapter.view = chips

        chips.itemAnimator = null
        chips.layoutManager = FlexboxLayoutManager(this)

        messageAdapter.autoScrollToStart(messageList) //not
        messageAdapter.emptyView = messagesEmpty

        messageList.setLastItemVisibleListener {
            // Last item is visible
            // Do something here
            messageAdapter.data?.second?.let {
//                messageList.scrollToPosition(it.size - 1)
//                messageAdapter.notifyDataSetChanged()
            }
        }

        messageList.setLastItemFocusedListener {
            // Last item has focus
            // Do something here
            makeToast("called")
            messageAdapter.data?.second?.let {
                messageList.scrollToPosition(it.size - 1)
                messageAdapter.notifyDataSetChanged()
                makeToast("focused ${it.size - 1}")
            }
        }



        messageList.setHasFixedSize(true) //not
        messageList.adapter = messageAdapter
        attachments.adapter = attachmentAdapter

        message.supportsInputContent = true

        theme.doOnNext { loading.setTint(it.theme) }.doOnNext { attach.setBackgroundTint(it.theme) }
            .doOnNext { attach.setTint(it.textPrimary) }.doOnNext { messageAdapter.theme = it }
            .autoDisposable(scope()).subscribe()

        window.callback = ComposeWindowCallback(window.callback, this)

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            messageBackground.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisibleIntent.onNext(true)
    }

    override fun onPause() {
        super.onPause()
        activityVisibleIntent.onNext(false)
    }

    override fun render(state: ComposeState) {
        if (state.hasError) {
            finish()
            return
        }

        threadId.onNext(state.threadId)

        // Old code for message selection changes
//        title = when {
//            state.selectedMessages > 0 -> getString(
//                R.string.compose_title_selected, state.selectedMessages
//            )
//            state.query.isNotEmpty() -> state.query
//            else -> state.conversationtitle
//        }


        title = when {
            state.selectedMessages > 0 -> {

                if (!(lastPasteClickedTime != null && (Date().time - lastPasteClickedTime!!.time) < 15000)) {
                    showOptionsDialog()
                } else {
                    lastPasteClickedTime = null
                }

                state.conversationtitle
            }

            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        }




        toolbarSubtitle.setVisible(state.query.isNotEmpty())
        toolbarSubtitle.text = getString(
            R.string.compose_subtitle_results, state.searchSelectionPosition, state.searchResults
        )

        toolbarTitle.setVisible(!state.editingMode)
        chips.setVisible(state.editingMode)
        composeBar.setVisible(!state.loading)

        // Don't set the adapters unless needed
        if (state.editingMode && chips.adapter == null) chips.adapter = chipsAdapter

        toolbar.menu.findItem(R.id.add)?.isVisible = state.editingMode


        // call menu is always visible
        toolbar.menu.findItem(R.id.call)?.isVisible = false //manyhill

        // uncomment code to visible call menu only when message selected
//        toolbar.menu.findItem(R.id.call)?.isVisible =
//            !state.editingMode && state.selectedMessages == 0 && state.query.isEmpty()

        toolbar.menu.findItem(R.id.info)?.isVisible = false
        //manyhill  !state.editingMode && state.selectedMessages == 0 && state.query.isEmpty()
        toolbar.menu.findItem(R.id.copy)?.isVisible =
            !state.editingMode && state.selectedMessages > 0

        // uncomment the code to see details menu
//        toolbar.menu.findItem(R.id.details)?.isVisible =
//            !state.editingMode && state.selectedMessages == 1


        toolbar.menu.findItem(R.id.delete)?.isVisible = false //manyhill all false
        // !state.editingMode && state.selectedMessages > 0
        toolbar.menu.findItem(R.id.forward)?.isVisible = false
        //   !state.editingMode && state.selectedMessages == 1
        toolbar.menu.findItem(R.id.previous)?.isVisible = false
        //  state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.next)?.isVisible = false
        //  state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.clear)?.isVisible = false
        // state.selectedMessages == 0 && state.query.isNotEmpty()

        chipsAdapter.data = state.selectedChips

        loading.setVisible(state.loading)

        sendAsGroup.setVisible(state.editingMode && state.selectedChips.size >= 2)
        sendAsGroupSwitch.isChecked = state.sendAsGroup

        messageList.setVisible(!state.editingMode || state.sendAsGroup || state.selectedChips.size == 1)
        messageAdapter.data = state.messages
        messageAdapter.highlight = state.searchSelectionId

        scheduledGroup.isVisible = state.scheduled != 0L
        scheduledTime.text = dateFormatter.getScheduledTimestamp(state.scheduled)

        attachments.setVisible(state.attachments.isNotEmpty())
        attachmentAdapter.data = state.attachments

        attach.animate().rotation(if (state.attaching) 135f else 0f).start()

        // Old Attaching options UI always gone
//        attaching.isVisible = state.attaching

        counter.text = state.remaining
        counter.setVisible(counter.text.isNotBlank())

        sim.setVisible(state.subscription != null)
        sim.contentDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        simIndex.text = state.subscription?.simSlotIndex?.plus(1)?.toString()

//        send.isEnabled = state.canSend
//        send.imageAlpha = if (state.canSend) 255 else 128
        send_tv.isEnabled = state.canSend
    }

    override fun clearSelection() = messageAdapter.clearSelection()

    override fun showDetails(details: String) {
        AlertDialog.Builder(this).setTitle(R.string.compose_details_title).setMessage(details)
            .setCancelable(true).show()
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0
        )
    }

    override fun requestSmsPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS
            ), 0
        )
    }

    override fun requestDatePicker() {




        val calendar = Calendar.getInstance()


        DatePickerDialog(
            this,
            DatePickerDialog.OnDateSetListener { _, year, month, day ->
                TimePickerDialog(
                    this,
                    TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                        calendar.set(Calendar.YEAR, year)
                        calendar.set(Calendar.MONTH, month)
                        calendar.set(Calendar.DAY_OF_MONTH, day)
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        scheduleSelectedIntent.onNext(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(this)
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()

        // On some devices, the keyboard can cover the date picker
       message.hideKeyboard()
    }

    override fun requestContact() {
        val intent =
            Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

        startActivityForResult(Intent.createChooser(intent, null), AttachContactRequestCode)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>) {
        message.hideKeyboard()
        val serialized =
            HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val intent = Intent(this, ContactsActivity::class.java).putExtra(
            ContactsActivity.SharingKey, sharing
        ).putExtra(ContactsActivity.ChipsKey, serialized)
        startActivityForResult(intent, SelectContactRequestCode)
    }

    override fun themeChanged() {
        messageList.scrapViews()
    }

    override fun showKeyboard() {
        message.postDelayed({
            message.showKeyboard()
        }, 200)
    }

    override fun requestCamera() {
        cameraDestination = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            .let { timestamp ->
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.TITLE, timestamp
                    )
                }
            }.let { cv -> contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(
            MediaStore.EXTRA_OUTPUT, cameraDestination
        )
        startActivityForResult(Intent.createChooser(intent, null), TakePhotoRequestCode)
    }
//updated
    override fun requestGallery() {
        val intent = Intent(Intent.ACTION_PICK).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setType("vnd.android.cursor.dir/image")
        startActivityForResult(Intent.createChooser(intent, null), AttachPhotoRequestCode)
    }

    override fun requestVoice() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setType("audio/3gp|audio/AMR|audio/mp3")
        startActivityForResult(Intent.createChooser(intent, null), AttachPhotoRequestCode)
    }
    override fun recordAudio() {
        val intent = Intent("android.intent.action.GET_CONTENT")
        intent.type = "audio/amr"
        intent.setClassName("com.android.soundrecorder", "com.android.soundrecorder.SoundRecorder")
        intent.putExtra("android.provider.MediaStore.extra.MAX_BYTES", 600000)
        intent.putExtra("exit_after_record", true)
        startActivityForResult(Intent.createChooser(intent, null), RecordAudioRequestCode)
    }
    override fun recordVideo() {
        val maxMessageSize =1048576-1024
        val j2 = (maxMessageSize.toFloat() * 0.85f).toLong()
        val videoCaptureDurationLimit = getVideoCaptureDurationLimit(j2)
///
           // .addCategory(Intent.CATEGORY_OPENABLE)

        ////////////////////
        val intent = Intent("android.media.action.VIDEO_CAPTURE")
        .putExtra("android.intent.extra.videoQuality", 0)
        .putExtra("android.intent.extra.sizeLimit", j2)
        .putExtra("android.intent.extra.durationLimit", videoCaptureDurationLimit)
        .putExtra(MediaStore.EXTRA_OUTPUT,Uri.parse("content://mms_temp_file/scrapSpace") )

        startActivityForResult(Intent.createChooser(intent, null), RecordVideoRequestCode)
    }

    fun getVideoCaptureDurationLimit(j: Long): Int {
        val camcorderProfile: CamcorderProfile?
        camcorderProfile = try {
            CamcorderProfile.get(0)
        } catch (e: RuntimeException) {
           // Log.e(TAG, "RuntimeException caught while getting camera info", e)
            null
        }
        if (camcorderProfile == null) {
            return 0
        }
        val j2: Long = j * 8 / (camcorderProfile.audioBitRate + camcorderProfile.videoBitRate)
        for (length in sVideoDuration.size - 1 downTo 0) {
            val iArr: IntArray = sVideoDuration
            if (j2 >= iArr[length]) {
                return iArr[length]
            }
        }
        return 0
    }

    override fun requestAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
        intent.setComponent(ComponentName("com.android.music", "com.android.music.MusicPicker"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setData(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(Intent.createChooser(intent, null), AttachAudioRequestCode)
    }
//updated
    override fun requestVideo() {

        val intent = Intent(Intent.ACTION_PICK)
        intent.setComponent(ComponentName("com.android.gallery3d","com.android.gallery3d.app.GalleryActivity"          ))
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setType("vnd.android.cursor.dir/video")
        startActivityForResult(Intent.createChooser(intent, null), AttachVideoRequestCode)
    }

    override fun setDraft(draft: String) {
        message.setText(draft)
        message.setSelection(draft.length)
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.data?.second?.indexOfLast { message -> message.id == id }
            ?.takeIf { position -> position != -1 }?.let(messageList::scrollToPosition) //not
    }

    override fun showQksmsPlusSnackbar(message: Int) {
        Snackbar.make(contentView, message, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.compose, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun getColoredMenuItems(): List<Int> {
        return super.getColoredMenuItems() + R.id.call
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == SelectContactRequestCode -> {
                chipsSelectedIntent.onNext(data?.getSerializableExtra(ContactsActivity.ChipsKey)
                    ?.let { serializable -> serializable as? HashMap<String, String?> }
                    ?: hashMapOf())
            }

            requestCode == TakePhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                cameraDestination?.let(attachmentSelectedIntent::onNext)
            }

            requestCode == AttachPhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(attachmentSelectedIntent::onNext) ?: data?.data?.let(
                    attachmentSelectedIntent::onNext
                )
            }

            requestCode == AttachAudioRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(audioSelectedIntent::onNext)
                    ?: data?.data?.let(audioSelectedIntent::onNext)
            }
            requestCode == RecordAudioRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(audioSelectedIntent::onNext)
                    ?: data?.data?.let(audioSelectedIntent::onNext)
            }
            requestCode == AttachVideoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(videoSelectedIntent::onNext)
                    ?: data?.data?.let(videoSelectedIntent::onNext)
            }

            requestCode ==  RecordVideoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(videoSelectedIntent::onNext)
                    ?: data?.data?.let(videoSelectedIntent::onNext)
            }
            requestCode == AttachContactRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.data?.let(contactSelectedIntent::onNext)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(CameraDestinationKey, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        cameraDestination = savedInstanceState.getParcelable(CameraDestinationKey)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onBackPressed() = backPressedIntent.onNext(Unit)


    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {


            return when (event?.keyCode ) {
                KeyEvent.KEYCODE_SOFT_LEFT -> {
                    attach_tv.performClick()
                    true
                }

                KeyEvent.KEYCODE_SOFT_RIGHT -> {
                   if(send_tv.isEnabled)
                       send_tv.performClick()
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if(message.isFocused && send_tv.isEnabled) {
                        send_tv.performClick()
                        true
                    }
                    else {   super.onKeyUp(keyCode, event)}
                }
                else -> super.onKeyUp(keyCode, event)
            }
        }





    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (messageList.isFocused || (currentFocus?.parent as? ViewGroup?)?.id == R.id.messageList) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (messageList.canScrollVertically(1)) {
                        (messageList.layoutManager as LinearLayoutManager).let {
                            val last = it.findLastVisibleItemPosition()
                            val first = it.findFirstVisibleItemPosition()
                            if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION && last - first > 0) {
                                super.onKeyDown(keyCode, event)
                            } else {
                                messageList.smoothScrollBy(0, messageList.height - 16)
                                true
                            }
                        }
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (messageList.canScrollVertically(-1)) {
                        (messageList.layoutManager as LinearLayoutManager).let {
                            val last = it.findLastVisibleItemPosition()
                            val first = it.findFirstVisibleItemPosition()
                            if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION && last - first > 0) {
                                super.onKeyDown(keyCode, event)
                            } else {
                                messageList.smoothScrollBy(0, 16 - messageList.height)
                                true
                            }
                        }
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }

                else -> super.onKeyDown(keyCode, event)
            }
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    private fun initClicks() {
        attach_tv.setOnClickListener {
            val popUp = PopupMenu(this, attach_tv, Gravity.BOTTOM)
            popUp.inflate(R.menu.attach_menu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                popUp.setForceShowIcon(true)
            }
            popUp.setOnMenuItemClickListener { item ->
                optionsItemIntent.onNext(item.itemId)
                true
            }
            popUp.show()
        }
    }
    override fun initSecondMenu() {

        val popUp = PopupMenu(this, attach_tv, Gravity.BOTTOM)
        popUp.inflate(R.menu.attach_second_menu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popUp.setForceShowIcon(true)
        }
        popUp.setOnMenuItemClickListener { item ->
            optionsItemIntent.onNext(item.itemId)
            true
        }
        popUp.show()

    }
     override fun initPhotoMenu() {

            val popUp = PopupMenu(this, attach_tv, Gravity.BOTTOM)
            popUp.inflate(R.menu.attach_photo_menu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                popUp.setForceShowIcon(true)
            }
            popUp.setOnMenuItemClickListener { item ->
                optionsItemIntent.onNext(item.itemId)
                true
            }
            popUp.show()

    }
    override fun initVideoMenu() {

        val popUp = PopupMenu(this, attach_tv, Gravity.BOTTOM)
        popUp.inflate(R.menu.attach_video_menu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popUp.setForceShowIcon(true)
        }
        popUp.setOnMenuItemClickListener { item ->
            optionsItemIntent.onNext(item.itemId)
            true
        }
        popUp.show()

    }
    private fun showOptionsDialog() {


        val listener = object : ComposeOptionsDialog.OnComposeOptionsDialogItemClickListener {
            override fun onCopyMessageClicked() {
                optionsItemIntent.onNext(R.id.copy)
            }

            override fun onPasteMessageClicked(dialog: ComposeOptionsDialog) {
                lastPasteClickedTime = Date()

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip

                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this@ComposeActivity)
                    // Use the 'text' variable to access the copied text
                    setDraft(text as String)
                }


                clearSelection()
                dialog.dismiss()

            }

            override fun onDeleteMessagesClicked() {
                optionsItemIntent.onNext(R.id.delete)
            }

            override fun onForwardMessageClicked() {
                optionsItemIntent.onNext(R.id.forward)
            }

            override fun onMessageInfoClicked() {
                optionsItemIntent.onNext(R.id.details)
            }

            override fun onMessageCallClicked() {
                optionsItemIntent.onNext(R.id.call)
            }

            override fun onSaveClicked() {
                optionsItemIntent.onNext(R.id.save)
            }

        }

        val dialog = ComposeOptionsDialog(this@ComposeActivity, listener)

        dialog.setOnDismissListener {
            if (!(it as ComposeOptionsDialog).isClickDismissed) {
                clearSelection()
            }
        }


        dialog.show()
    }


}
