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
import android.graphics.Rect
import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.*
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.text.util.LinkifyCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
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
import com.moez.QKSMS.databinding.ComposeActivityBinding
import com.moez.QKSMS.feature.compose.editing.ChipsAdapter
import com.moez.QKSMS.feature.contacts.ContactsActivity
import com.moez.QKSMS.extensions.*
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.model.ScheduledMessage
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.model.Recipient
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.text.SimpleDateFormat
import java.io.File
import java.util.*
import javax.inject.Inject
import timber.log.Timber

private const val MAX_SMOOTH_SCROLL_MESSAGES = 20
private val HEIMISH_DIRECTORY_CONTACTS_URI = Uri.parse("content://com.belz.flipcallerid.contacts/contacts")

class ComposeActivity : QkThemedActivity(), ComposeView {

    private enum class AudioRecordingState {
        IDLE,
        RECORDING,
        RECORDED
    }

    companion object {
        private const val SelectContactRequestCode = 0
        private const val TakePhotoRequestCode = 1
        private const val AttachPhotoRequestCode = 2
        private const val AttachContactRequestCode = 3
        private const val AttachAudioRequestCode = 4
        private const val AttachVideoRequestCode = 5
        private const val RecordAudioRequestCode = 6
        private const val RecordVideoRequestCode = 7
        private const val DateTimePickerRequestCode = 8
        private const val RecordAudioPermissionRequestCode = 9
        private const val AttachFileRequestCode = 10
        private const val SpeechRecognitionRequestCode = 11

        private const val CameraDestinationKey = "camera_destination"
        private val pendingMediaRestoreMessages = mutableMapOf<Long, Long>()
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
    override val messageClickIntent: Subject<Long> = PublishSubject.create()
    override val messagePartClickIntent: Subject<Long> = PublishSubject.create()
    override val messagesSelectedIntent by lazy {
        messageAdapter.selectionChanges
    }
    override val messageOptionsSelectionIntent: Subject<List<Long>> = BehaviorSubject.create()
    override val messageOptionActionIntent: Subject<ComposeView.MessageOptionAction> = PublishSubject.create()
    override val cancelSendingIntent: Subject<Long> by lazy { messageAdapter.cancelSending }
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { attachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { message.textChanges().filter { !composeSearchMode } }
    override val searchQueryIntent: Subject<String> = PublishSubject.create()
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
    override val scheduleSelectedIntent: Subject<ComposeView.ScheduledOptions> = PublishSubject.create()
    override val changeSimIntent by lazy { sim.clicks() }
    override val scheduleCancelIntent by lazy { scheduledCancel.clicks() }
    override val audioSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val videoSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val fileSelectedIntent: Subject<Uri> = PublishSubject.create()

    override val sendIntent by lazy {
        sendTv.clicks().doOnNext {
            pendingFocusNewestSentMessage = true
        }
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

    private var isMMS: Boolean = false
    private var lastAttachingState: Boolean? = null
    private var optionsDialogShownForSelection = false
    private var lastFocusedMessageId: Long? = null
    private var pendingRestoreMessageFocusOnResume = false
    private var lastRenderedLatestMessageId: Long? = null
    private var selectedMessageCount = 0
    private var pendingFocusNewestSentMessage = false
    private var pendingAudioRecording = false
    private var pendingForceComposeFocusOnResume = false
    private var initialMessageLayoutThreadId: Long? = null
    var currentThreadId = 0L
    private var audioRecorder: MediaRecorder? = null
    private var recordingAudioFile: File? = null
    private var audioRecordingDialog: AlertDialog? = null
    private var compactMenuDialog: Dialog? = null
    private var audioRecordingState = AudioRecordingState.IDLE
    private var suppressAudioRecordingDialogCancel = false
    private var hasMultipleRecipientsForMenu = false
    private var forceExpandedRecipientsEditor = false
    private var currentAttachments: List<Attachment> = emptyList()
    private val ensureComposeFocusRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable

        if (hasPendingMediaRestore()) {
            if (message.isFocused) {
                message.clearFocus()
            }
            messageList.requestFocus()
            return@Runnable
        }

        val focused = currentFocus
        if (focused != null && isDescendantOf(contentView, focused)) {
            return@Runnable
        }

        if (restoreLastFocusedMessageIfVisibleIfAvailable()) {
            return@Runnable
        }

        when {
            message.isShown && message.isFocusable -> message.requestFocus()
            sendTv.isShown && sendTv.isFocusable -> sendTv.requestFocus()
            attachTv.isShown && attachTv.isFocusable -> attachTv.requestFocus()
            else -> contentView.requestFocus()
        }
    }
    private val forceComposeInteractionFocusRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable

        if (restoreLastFocusedMessageIfVisibleIfAvailable()) {
            return@Runnable
        }

        when {
            message.isShown && message.isFocusable -> {
                if (!message.isFocused) {
                    message.requestFocus()
                }
                message.setSelection(message.text?.length ?: 0)
            }
            sendTv.isShown && sendTv.isFocusable -> sendTv.requestFocus()
            attachTv.isShown && attachTv.isFocusable -> attachTv.requestFocus()
            else -> contentView.requestFocus()
        }
    }

    private val sVideoDuration = intArrayOf(0, 5, 10, 15, 20, 30, 40, 50, 60, 90, 120)

    private var datePickerDialog: DatePickerDialog? = null
    private var timePickerDialog: TimePickerDialog? = null
    private lateinit var binding: ComposeActivityBinding
    private var composeSearchMode = false
    private var draftBeforeSearch = ""

    private fun triggerSend(source: String): Boolean {
        pendingFocusNewestSentMessage = true
        sendTv.performClick()
        return true
    }

    private val contentView get() = binding.contentView
    private val messageList get() = binding.messageList
    private val messagesEmpty get() = binding.messagesEmpty
    private val loading get() = binding.loading
    private val sendAsGroup get() = binding.sendAsGroup
    private val sendAsGroupBackground get() = binding.sendAsGroupBackground
    private val sendAsGroupSwitch get() = binding.sendAsGroupSwitch
    private val composeBar get() = binding.composeBar
    private val messageBackground get() = binding.messageBackground
    private val scheduledGroup get() = binding.scheduledGroup
    private val scheduledTime get() = binding.scheduledTime
    private val scheduledCancel get() = binding.scheduledCancel
    private val attachments get() = binding.attachments
    private val message get() = binding.message
    private val searchCounter get() = binding.searchCounter
    private val sim get() = binding.sim
    private val simIndex get() = binding.simIndex
    private val counter get() = binding.counter
    private val send get() = binding.send
    private val toolbar get() = binding.toolbar
    private val toolbarTitle get() = binding.toolbarTitle
    private val toolbarSubtitle get() = binding.toolbarSubtitle
    private val recipientSummary get() = binding.recipientSummary
    private val chips get() = binding.chips
    private val headerAddRecipient get() = binding.headerAddRecipient
    private val headerViewRecipients get() = binding.headerViewRecipients
    private val attachingBackground get() = binding.attachingBackground
    private val contact get() = binding.contact
    private val contactLabel get() = binding.contactLabel
    private val schedule get() = binding.schedule
    private val scheduleLabel get() = binding.scheduleLabel
    private val gallery get() = binding.gallery
    private val galleryLabel get() = binding.galleryLabel
    private val camera get() = binding.camera
    private val cameraLabel get() = binding.cameraLabel
    private val attach get() = binding.attach
    private val attachTv get() = binding.attachTv
    private val sendTv get() = binding.sendTv
    private val moreTv get() = binding.moreTv

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = ComposeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        viewModel.bindView(this)

        toolbarTitle.isFocusable = true
        toolbarTitle.isFocusableInTouchMode = true

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

        currentThreadId = intent.getLongExtra("threadId", 0L)
        messageList.adapter = messageAdapter
        messageList.isFocusable = true
        messageList.isFocusableInTouchMode = true
        messageList.itemAnimator = null
        messageList.setHasFixedSize(true)
        messageList.setItemViewCacheSize(4)
        (messageList.layoutManager as? LinearLayoutManager)?.isItemPrefetchEnabled = false
        preparePendingMediaRestoreFocus()
        attachments.adapter = attachmentAdapter
        attachments.setHasFixedSize(true)

        messageAdapter.clicks
            .autoDisposable(scope())
            .subscribe { messageId ->
                if (rememberMessageByIdForRestore(messageId)) {
                    pendingRestoreMessageFocusOnResume = true
                }
                messageClickIntent.onNext(messageId)
            }

        messageAdapter.partClicks
            .autoDisposable(scope())
            .subscribe { partId ->
                if (rememberMessageForPartRestore(partId)) {
                    pendingRestoreMessageFocusOnResume = true
                }
                messagePartClickIntent.onNext(partId)
            }

        messageAdapter.selectionChanges
            .autoDisposable(scope())
            .subscribe { selection ->
                selectedMessageCount = selection.size
                if (selection.isNotEmpty() && !optionsDialogShownForSelection) {
                    messageOptionsSelectionIntent.onNext(selection.toList())
                    showOptionsDialog(isMMS)
                }
            }

        optionsItemIntent
            .autoDisposable(scope())
            .subscribe { itemId ->
                if (itemId == R.id.info || itemId == R.id.call || itemId == R.id.schedule_message) {
                    pendingForceComposeFocusOnResume = true
                }
        }

        message.supportsInputContent = true
        message.setOnFocusChangeListener { _, hasFocus ->
            val minLines = if (hasFocus) getVisibleComposeInputLines() else 1
            if (message.minLines != minLines) {
                message.minLines = minLines
            }
        }
        message.textChanges()
            .autoDisposable(scope())
            .subscribe {
                if (message.isFocused) {
                    val neededLines = getVisibleComposeInputLines()
                    if (message.minLines != neededLines) {
                        message.minLines = neededLines
                    }
                }
            }
        message.textChanges()
            .filter { composeSearchMode }
            .map { it.toString() }
            .distinctUntilChanged()
            .autoDisposable(scope())
            .subscribe(searchQueryIntent::onNext)

        theme.doOnNext { loading.setTint(it.theme) }.doOnNext { attach.setBackgroundTint(it.theme) }
            .doOnNext { attach.setTint(it.textPrimary) }.doOnNext { messageAdapter.theme = it }
            .autoDisposable(scope()).subscribe()

        window.callback = ComposeWindowCallback(window.callback, this)

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            messageBackground.setBackgroundTint(resources.getColor(R.color.threadSurface))
        }

        contentView.post {
            if (isFinishing || isDestroyed) return@post
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                activityVisibleIntent.onNext(true)
            }
        }
    }

    private fun getVisibleComposeInputLines(): Int {
        // Keep one spare visible line because the TCL typing add-on covers the last input line.
        return (message.lineCount + 1).coerceAtLeast(2)
    }

    private fun updateComposeBackgroundTopAnchor(scheduledVisible: Boolean, attachmentsVisible: Boolean) {
        val target = when {
            scheduledVisible -> R.id.scheduledCancel
            attachmentsVisible -> R.id.attachments
            else -> R.id.message
        }
        val params = messageBackground.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (params.topToTop == target && params.topToBottom == ConstraintLayout.LayoutParams.UNSET) {
            return
        }

        params.topToTop = target
        params.topToBottom = ConstraintLayout.LayoutParams.UNSET
        messageBackground.layoutParams = params
    }

    override fun onStart() {
        super.onStart()
        activityVisibleIntent.onNext(true)
    }

    override fun onPause() {
        super.onPause()
        Timber.d(
            "ComposeActivity onPause threadId=%s focus=%s selectedMessages=%s searchMode=%s",
            currentThreadId,
            currentFocus?.javaClass?.simpleName ?: "null",
            selectedMessageCount,
            composeSearchMode
        )
        if (rememberFocusedMessageForRestore()) {
            pendingRestoreMessageFocusOnResume = true
        }
        compactMenuDialog?.dismiss()
        compactMenuDialog = null
        activityVisibleIntent.onNext(false)
    }

    override fun render(state: ComposeState) {
        if (state.hasError) {
            finish()
            return
        }

        if (currentThreadId != state.threadId) {
            currentThreadId = state.threadId
            threadId.onNext(state.threadId)
        } else {
            currentThreadId = state.threadId
        }

        // Old code for message selection changes
//        title = when {
//            state.selectedMessages > 0 -> getString(
//                R.string.compose_title_selected, state.selectedMessages
//            )
//            state.query.isNotEmpty() -> state.query
//            else -> state.conversationtitle
//        }

        if (state.selectedMessages == 0) {
            optionsDialogShownForSelection = false
        }

        if (!state.editingMode) {
            forceExpandedRecipientsEditor = false
        }

        title = when {
            state.selectedMessages > 0 -> {
                if (lastPasteClickedTime != null && (Date().time - lastPasteClickedTime!!.time) >= 15000) {
                    lastPasteClickedTime = null
                }
                state.conversationtitle
            }
            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        }

        val searchActive = state.query.isNotEmpty()
        val searchPosition = state.searchSelectionPosition.coerceAtLeast(0)
        val searchTotal = state.searchResults.coerceAtLeast(0)
        val searchCounterText = "<$searchPosition/$searchTotal>"
        if (toolbarSubtitle.isVisible) {
            toolbarSubtitle.setVisible(false)
        }
        if (searchCounter.isVisible != composeSearchMode) {
            searchCounter.setVisible(composeSearchMode)
        }
        if (composeSearchMode && searchCounter.text?.toString() != searchCounterText) {
            searchCounter.text = searchCounterText
        }

        hasMultipleRecipientsForMenu = when {
            state.editingMode -> state.selectedChips.size > 1
            else -> (state.messages?.first?.recipients?.count { it.isValid } ?: 0) > 1
        }
        val messageCount = state.messages?.second?.size ?: 0
        val canEditRecipients = state.editingMode || messageCount == 0

        val toolbarTitleText = when {
            state.editingMode && state.selectedChips.size > 1 ->
                "${state.selectedChips.size} recipients"
            state.editingMode && state.selectedChips.size == 1 ->
                state.selectedChips.first().getDisplayName()
            else -> title?.toString().orEmpty()
        }
        if (toolbarTitle.text?.toString() != toolbarTitleText) {
            toolbarTitle.text = toolbarTitleText
        }
        if (!toolbarTitle.isVisible) {
            toolbarTitle.setVisible(true)
        }
        val recipientSummaryText = when {
            state.editingMode && state.selectedChips.size > 1 ->
                "To: ${state.selectedChips.joinToString(", ") { it.getDisplayName() }}"
            else -> ""
        }
        if (recipientSummary.text?.toString() != recipientSummaryText) {
            recipientSummary.text = recipientSummaryText
        }
        if (recipientSummary.isVisible != recipientSummaryText.isNotEmpty()) {
            recipientSummary.setVisible(recipientSummaryText.isNotEmpty())
        }
        val chipsVisible = false
        if (chips.isVisible) {
            chips.setVisible(false)
        }
        val showHeaderActions = state.selectedMessages == 0 && !chipsVisible
        val showAddRecipient = showHeaderActions && canEditRecipients
        if (headerAddRecipient.isVisible != showAddRecipient) {
            headerAddRecipient.isVisible = showAddRecipient
        }
        val showViewRecipients = showHeaderActions && hasMultipleRecipientsForMenu
        if (headerViewRecipients.isVisible != showViewRecipients) {
            headerViewRecipients.isVisible = showViewRecipients
        }
        if (composeBar.isVisible == state.loading) {
            composeBar.setVisible(!state.loading)
        }

        // Don't set the adapters unless needed
        if (state.editingMode && chips.adapter == null) chips.adapter = chipsAdapter

        toolbar.menu.findItem(R.id.add)?.let { item ->
            if (item.isVisible != canEditRecipients) {
                item.isVisible = canEditRecipients
            }
        }
        listOf(R.id.previous, R.id.next, R.id.clear).forEach { id ->
            toolbar.menu.findItem(id)?.let { item ->
                if (item.isVisible != searchActive) {
                    item.isVisible = searchActive
                }
            }
        }

        toolbar.menu.findItem(R.id.copy)?.let { item ->
            val copyVisible = !state.editingMode && state.selectedMessages > 0
            if (item.isVisible != copyVisible) {
                item.isVisible = copyVisible
            }
        }

        // uncomment the code to see details menu
//        toolbar.menu.findItem(R.id.details)?.isVisible =
//            !state.editingMode && state.selectedMessages == 1

        if (chipsAdapter.data !== state.selectedChips && chipsAdapter.data != state.selectedChips) {
            chipsAdapter.data = state.selectedChips
        }

        if (loading.isVisible != state.loading) {
            loading.setVisible(state.loading)
        }

        val sendAsGroupVisible = state.editingMode && state.selectedChips.size >= 2
        if (sendAsGroup.isVisible != sendAsGroupVisible) {
            sendAsGroup.setVisible(sendAsGroupVisible)
        }
        if (sendAsGroupSwitch.isChecked != state.sendAsGroup) {
            sendAsGroupSwitch.isChecked = state.sendAsGroup
        }

        val messageListVisible = !state.editingMode || state.sendAsGroup || state.selectedChips.size == 1
        if (messageList.isVisible != messageListVisible) {
            messageList.setVisible(messageListVisible)
        }

        val latestMessage = state.messages?.second?.lastOrNull()
        val latestMessageId = latestMessage?.id
        val latestMessageChanged = latestMessageId != lastRenderedLatestMessageId
        val pendingMediaRestoreMessageId = pendingMediaRestoreMessages[state.threadId]
        if (pendingMediaRestoreMessageId != null && lastFocusedMessageId == null) {
            lastFocusedMessageId = pendingMediaRestoreMessageId
        }
        val shouldRestoreMediaMessage =
            pendingMediaRestoreMessageId != null &&
                state.messages?.second?.any { message -> message.id == pendingMediaRestoreMessageId } == true
        val shouldPositionInitialMessages =
            latestMessage != null &&
                initialMessageLayoutThreadId != state.threadId &&
                !shouldRestoreMediaMessage

        if (pendingMediaRestoreMessageId != null) {
            preparePendingMediaRestoreFocus()
        }

        // Updated: feed data through the adapter's API
        messageAdapter.threadData = state.messages
        messageAdapter.highlight = state.searchSelectionId
        messageAdapter.searchQuery = state.query

        if (latestMessageChanged) {
            lastRenderedLatestMessageId = latestMessageId
        }

        if (shouldRestoreMediaMessage) {
            initialMessageLayoutThreadId = state.threadId
            pendingMediaRestoreMessages.remove(state.threadId)
            if (message.isFocused) {
                message.clearFocus()
                messageList.requestFocus()
            }
            restoreLastFocusedMessageIfVisible()
        } else if (shouldPositionInitialMessages) {
            initialMessageLayoutThreadId = state.threadId
            scrollLatestMessageIntoView()
            messageList.postDelayed({ scrollLatestMessageIntoView() }, 150)
        }

        if (pendingFocusNewestSentMessage && latestMessageChanged) {
            if (latestMessage != null && latestMessage.isMe()) {
                pendingFocusNewestSentMessage = false
                lastFocusedMessageId = latestMessage.id
                scrollLatestMessageIntoView()
                messageList.postDelayed({ scrollLatestMessageIntoView() }, 150)
            }
        }

        isMMS = state.isMMS

        val scheduledVisible = state.scheduled != 0L
        if (scheduledGroup.isVisible != scheduledVisible) {
            scheduledGroup.isVisible = scheduledVisible
        }
        val scheduledTimestamp = dateFormatter.getScheduledTimestamp(state.scheduled)
        val scheduledText = when (state.scheduledRepeatInterval) {
            ScheduledMessage.REPEAT_DAILY -> "$scheduledTimestamp - ${getString(R.string.compose_repeat_daily)}"
            ScheduledMessage.REPEAT_WEEKLY -> "$scheduledTimestamp - ${getString(R.string.compose_repeat_weekly)}"
            ScheduledMessage.REPEAT_MONTHLY -> "$scheduledTimestamp - ${getString(R.string.compose_repeat_monthly)}"
            else -> scheduledTimestamp
        }
        if (scheduledTime.text?.toString() != scheduledText) {
            scheduledTime.text = scheduledText
        }

        val attachmentsVisible = state.attachments.isNotEmpty()
        if (attachments.isVisible != attachmentsVisible) {
            attachments.setVisible(attachmentsVisible)
        }
        currentAttachments = state.attachments
        updateSoftKeyLabels(attachmentsVisible)
        updateComposeBackgroundTopAnchor(scheduledVisible, attachmentsVisible)
        if (attachmentAdapter.data !== state.attachments && attachmentAdapter.data != state.attachments) {
            attachmentAdapter.data = state.attachments
        }

        if (lastAttachingState != state.attaching) {
            lastAttachingState = state.attaching
            attach.animate().rotation(if (state.attaching) 135f else 0f).start()
        }

        // Old Attaching options UI always gone
//        attaching.isVisible = state.attaching

        if (counter.text?.toString() != state.remaining) {
            counter.text = state.remaining
        }
        val counterVisible = state.remaining.isNotBlank()
        if (counter.isVisible != counterVisible) {
            counter.setVisible(counterVisible)
        }

        val simVisible = state.subscription != null
        if (sim.isVisible != simVisible) {
            sim.setVisible(simVisible)
        }
        val simDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        if (sim.contentDescription?.toString() != simDescription) {
            sim.contentDescription = simDescription
        }
        val simIndexText = state.subscription?.simSlotIndex?.plus(1)?.toString()
        if (simIndex.text?.toString() != simIndexText) {
            simIndex.text = simIndexText
        }

//        send.isEnabled = state.canSend
//        send.imageAlpha = if (state.canSend) 255 else 128
        if (!sendTv.isEnabled) {
            sendTv.isEnabled = true
        }
        val sendAlpha = if (state.canSend || !message.text.isNullOrBlank()) 1f else 0.5f
        if (sendTv.alpha != sendAlpha) {
            sendTv.alpha = sendAlpha
        }
    }

    private fun updateSoftKeyLabels(hasAttachments: Boolean) {
        val rightPadding = (16 * resources.displayMetrics.density).toInt()
        if (hasAttachments) {
            if (!moreTv.isVisible) {
                moreTv.isVisible = true
            }
            if (sendTv.gravity != Gravity.CENTER) {
                sendTv.gravity = Gravity.CENTER
            }
            if (sendTv.paddingEnd != 0) {
                sendTv.setPadding(sendTv.paddingLeft, sendTv.paddingTop, 0, sendTv.paddingBottom)
            }
        } else {
            if (moreTv.isVisible) {
                moreTv.isVisible = false
            }
            if (sendTv.gravity != Gravity.END) {
                sendTv.gravity = Gravity.END
            }
            if (sendTv.paddingEnd != rightPadding) {
                sendTv.setPadding(sendTv.paddingLeft, sendTv.paddingTop, rightPadding, sendTv.paddingBottom)
            }
        }
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

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RecordAudioPermissionRequestCode
        )
    }

    override fun requestDatePicker() {

        startActivityForResult(
            Intent(this, DateTimePickerActivity::class.java),
            DateTimePickerRequestCode
        )

        // On some devices, the keyboard can cover the date picker
        message.hideKeyboard()
    }

    override fun requestContact() {
        val intent =
            Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

        startActivityForResult(Intent.createChooser(intent, null), AttachContactRequestCode)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>, singleRecipient: Boolean) {
        message.hideKeyboard()
        val serialized =
            HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val forwardMode = intent.extras?.getBoolean("forward_mode", false) ?: false
        val intent = Intent(this, ContactsActivity::class.java).putExtra(
            ContactsActivity.SharingKey, sharing && !forwardMode
        ).putExtra(ContactsActivity.ChipsKey, serialized)
            .putExtra(ContactsActivity.SingleRecipientKey, forwardMode || singleRecipient)
        startActivityForResult(intent, SelectContactRequestCode)
    }

    override fun showSelectedRecipients(chips: List<Recipient>) {
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAudioRecording = true
            requestAudioPermission()
            return
        }

        showInAppAudioRecordingDialog()
    }

    override fun recordVideo() {
        val maxMessageSize = 1048576 - 1024
        val j2 = (maxMessageSize.toFloat() * 0.85f).toLong()
        val videoCaptureDurationLimit = getVideoCaptureDurationLimit(j2)
///
        // .addCategory(Intent.CATEGORY_OPENABLE)

        ////////////////////
        val intent = Intent("android.media.action.VIDEO_CAPTURE")
            .putExtra("android.intent.extra.videoQuality", 0)
            .putExtra("android.intent.extra.sizeLimit", j2)
            .putExtra("android.intent.extra.durationLimit", videoCaptureDurationLimit)
            .putExtra(MediaStore.EXTRA_OUTPUT, Uri.parse("content://mms_temp_file/scrapSpace"))

        startActivityForResult(Intent.createChooser(intent, null), RecordVideoRequestCode)
    }

    private fun getVideoCaptureDurationLimit(j: Long): Int {
        val camcorderProfile: CamcorderProfile = try {
            CamcorderProfile.get(0)
        } catch (e: RuntimeException) {
            null
        } ?: return 0
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
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).data =
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        startActivityForResult(Intent.createChooser(intent, null), AttachAudioRequestCode)
    }

    //updated
    override fun requestVideo() {

        val intent = Intent(Intent.ACTION_PICK)
        intent.setComponent(
            ComponentName(
                "com.android.gallery3d",
                "com.android.gallery3d.app.GalleryActivity"
            )
        )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).type = "vnd.android.cursor.dir/video"

        startActivityForResult(Intent.createChooser(intent, null), AttachVideoRequestCode)
    }

    override fun requestFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        startActivityForResult(Intent.createChooser(intent, null), AttachFileRequestCode)
    }

    override fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.compose_speech_to_text))

        try {
            startActivityForResult(intent, SpeechRecognitionRequestCode)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.compose_speech_to_text_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun setDraft(draft: String) {
        message.setText(draft)
        message.setSelection(draft.length)
    }

    override fun getDraft(): CharSequence = message.text ?: ""

    private fun appendDraftText(text: CharSequence) {
        val insert = text.toString()
        if (insert.isEmpty()) return

        val start = message.selectionStart.takeIf { it >= 0 } ?: message.text?.length ?: 0
        val end = message.selectionEnd.takeIf { it >= 0 } ?: start
        val rangeStart = minOf(start, end)
        val rangeEnd = maxOf(start, end)
        message.text?.replace(rangeStart, rangeEnd, insert)
        val cursor = rangeStart + insert.length
        message.setSelection(cursor.coerceAtMost(message.text?.length ?: cursor))
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.data
            ?.indexOfLast { message -> message.id == id }
            ?.takeIf { position -> position != -1 }
            ?.let { position -> messageList.scrollToPosition(position) } //not
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
        menu?.findItem(R.id.call)?.isVisible = false
        menu?.findItem(R.id.info)?.isVisible = false
        menu?.findItem(R.id.delete)?.isVisible = false
        menu?.findItem(R.id.forward)?.isVisible = false
        menu?.findItem(R.id.previous)?.isVisible = false
        menu?.findItem(R.id.next)?.isVisible = false
        menu?.findItem(R.id.clear)?.isVisible = false
        return super.onCreateOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        preparePendingMediaRestoreFocus()
        Timber.d(
            "ComposeActivity onResume threadId=%s focus=%s pendingForce=%s pendingRestore=%s hasWindowFocus=%s",
            currentThreadId,
            currentFocus?.javaClass?.simpleName ?: "null",
            pendingForceComposeFocusOnResume,
            pendingRestoreMessageFocusOnResume,
            hasWindowFocus()
        )
        if (pendingForceComposeFocusOnResume) {
            pendingForceComposeFocusOnResume = false
            forceComposeInteractionFocus()
            return
        }
        if (pendingRestoreMessageFocusOnResume) {
            pendingRestoreMessageFocusOnResume = false
            restoreLastFocusedMessageIfVisible()
        }
        ensureComposeFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Timber.d(
            "ComposeActivity onWindowFocusChanged hasFocus=%s focus=%s",
            hasFocus,
            currentFocus?.javaClass?.simpleName ?: "null"
        )
        if (hasFocus) {
            preparePendingMediaRestoreFocus()
            ensureComposeFocus()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun getColoredMenuItems(): List<Int> {
        return super.getColoredMenuItems() + R.id.call
    }

    private fun persistReadPermissionIfPossible(uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == SelectContactRequestCode && resultCode == Activity.RESULT_OK -> {
                val chips = data?.getSerializableExtra(ContactsActivity.ChipsKey)
                    ?.let { serializable -> serializable as? HashMap<String, String?> }
                    ?: hashMapOf()
                chipsSelectedIntent.onNext(chips)
            }

            requestCode == SelectContactRequestCode && chipsAdapter.data.isNullOrEmpty() -> {
                chipsSelectedIntent.onNext(hashMapOf())
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

            requestCode == AttachFileRequestCode && resultCode == Activity.RESULT_OK -> {
                val flags = data?.flags ?: 0
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach { uri ->
                        persistReadPermissionIfPossible(uri, flags)
                        fileSelectedIntent.onNext(uri)
                    }
                    ?: data?.data?.let { uri ->
                        persistReadPermissionIfPossible(uri, flags)
                        fileSelectedIntent.onNext(uri)
                    }
            }

            requestCode == RecordVideoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount?.let { count -> 0 until count }
                    ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                    ?.forEach(videoSelectedIntent::onNext)
                    ?: data?.data?.let(videoSelectedIntent::onNext)
            }

            requestCode == AttachContactRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.data?.let(contactSelectedIntent::onNext)
            }

            requestCode == DateTimePickerRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.let {
                    showScheduleRepeatDialog(
                        it.getLongExtra(
                            DateTimePickerActivity.TIME_IN_MILLS,
                            System.currentTimeMillis()
                        )
                    )
                }
            }

            requestCode == SpeechRecognitionRequestCode && resultCode == Activity.RESULT_OK -> {
                val spokenText = data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                spokenText?.let(::appendDraftText)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun showScheduleRepeatDialog(date: Long) {
        val labels = arrayOf(
            getString(R.string.compose_repeat_none),
            getString(R.string.compose_repeat_daily),
            getString(R.string.compose_repeat_weekly),
            getString(R.string.compose_repeat_monthly)
        )
        val values = intArrayOf(
            ScheduledMessage.REPEAT_NONE,
            ScheduledMessage.REPEAT_DAILY,
            ScheduledMessage.REPEAT_WEEKLY,
            ScheduledMessage.REPEAT_MONTHLY
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.compose_repeat_title)
            .setItems(labels) { _, which ->
                scheduleSelectedIntent.onNext(ComposeView.ScheduledOptions(date, values[which]))
            }
            .setOnCancelListener {
                scheduleSelectedIntent.onNext(
                    ComposeView.ScheduledOptions(date, ScheduledMessage.REPEAT_NONE)
                )
            }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(CameraDestinationKey, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        cameraDestination = savedInstanceState.getParcelable(CameraDestinationKey)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onBackPressed() {
        Timber.d(
            "ComposeActivity onBackPressed focus=%s selectedMessages=%s searchMode=%s",
            currentFocus?.javaClass?.simpleName ?: "null",
            selectedMessageCount,
            composeSearchMode
        )
        if (composeSearchMode) {
            exitComposeSearchMode()
        } else if (selectedMessageCount > 0) {
            clearSelection()
            restoreLastFocusedMessageIfVisible()
        } else {
            backPressedIntent.onNext(Unit)
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }

    fun handleWindowBackKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false

        Timber.d(
            "ComposeActivity handleWindowBackKeyEvent action=%s repeat=%s focus=%s",
            event.action,
            event.repeatCount,
            currentFocus?.javaClass?.simpleName ?: "null"
        )

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> {
                onBackPressed()
                true
            }
            else -> false
        }
    }

    override fun onDestroy() {
        contentView.removeCallbacks(ensureComposeFocusRunnable)
        contentView.removeCallbacks(forceComposeInteractionFocusRunnable)
        audioRecordingDialog?.dismiss()
        audioRecordingDialog = null
        discardInAppAudioRecording()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == RecordAudioPermissionRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingAudioRecording) {
                pendingAudioRecording = false
                showInAppAudioRecordingDialog()
            } else {
                pendingAudioRecording = false
                Toast.makeText(this, R.string.compose_audio_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || event?.keyCode == KeyEvent.KEYCODE_BACK) {
            Timber.d(
                "ComposeActivity onKeyUp BACK repeat=%s focus=%s",
                event?.repeatCount ?: 0,
                currentFocus?.javaClass?.simpleName ?: "null"
            )
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }

        return when (event?.keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT -> {
                attachTv.performClick()
                true
            }

            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                if (moreTv.isShown) {
                    moreTv.performClick()
                    true
                } else {
                    triggerSend("softRight")
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (message.isFocused) {
                    triggerSend("dpadCenterMessage")
                } else {
                    val focusedMessage = findFocusedMessage()
                    focusedMessage?.let { message -> lastFocusedMessageId = message.id }
                    if (focusedMessage != null &&
                        focusedMessage.isSending() &&
                        focusedMessage.date > System.currentTimeMillis()
                    ) {
                        messageAdapter.cancelSending.onNext(focusedMessage.id)
                        true
                    } else {
                        if (focusedMessage != null) {
                            pendingRestoreMessageFocusOnResume = true
                        }
                        super.onKeyUp(keyCode, event)
                    }
                }
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focusedMessage = findFocusedMessage()
                focusedMessage?.let { message -> lastFocusedMessageId = message.id }
                if (focusedMessage != null &&
                    focusedMessage.isSending() &&
                    focusedMessage.date > System.currentTimeMillis()
                ) {
                    messageAdapter.cancelSending.onNext(focusedMessage.id)
                    true
                } else {
                    if (focusedMessage != null) {
                        pendingRestoreMessageFocusOnResume = true
                    }
                    super.onKeyUp(keyCode, event)
                }
            }

            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || event?.keyCode == KeyEvent.KEYCODE_BACK) {
            Timber.d(
                "ComposeActivity onKeyDown BACK repeat=%s focus=%s",
                event?.repeatCount ?: 0,
                currentFocus?.javaClass?.simpleName ?: "null"
            )
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }

        if (composeSearchMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    optionsItemIntent.onNext(R.id.previous)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    optionsItemIntent.onNext(R.id.next)
                    return true
                }

                else -> Unit
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && (message.isFocused || sendTv.isFocused)) {
            if (sendAsGroup.isVisible && sendAsGroupBackground.isShown) {
                focusHeaderAction()
                return true
            }
            focusLatestMessageAtBottom()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && sendAsGroupBackground.isFocused) {
            if (focusToolbarAction()) {
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            (headerAddRecipient.isFocused || headerViewRecipients.isFocused) &&
            sendAsGroup.isVisible && sendAsGroupBackground.isShown) {
            sendAsGroupBackground.requestFocus()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && toolbarTitle.isFocused) {
            if (focusFirstVisibleMessageAtTop()) {
                return true
            }
        }

         fun getItemRectInRecycler(itemView: View): Rect {
            val rect = Rect()
            if (!isDescendantOf(messageList, itemView)) return rect
            itemView.getDrawingRect(rect)
            messageList.offsetDescendantRectToMyCoords(itemView, rect)
            return rect
        }



        val itemView = findMessageItemView(currentFocus) ?: return super.onKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val rect = getItemRectInRecycler(itemView)
                val visibleBottom = messageList.height - messageList.paddingBottom

                if (rect.bottom > visibleBottom) {
                    val visibleHeight = messageList.height - messageList.paddingTop - messageList.paddingBottom
                    val pageStep = visibleHeight
                    val hiddenBottom = rect.bottom - visibleBottom
                    val extraLineAdvance = itemView.findViewById<TextView?>(R.id.body)?.lineHeight ?: 0
                    val delta = minOf(pageStep, hiddenBottom + extraLineAdvance)
                    messageList.scrollBy(0, delta)
                    itemView.requestFocus()
                    return true
                }

                val rawNext = itemView.focusSearch(View.FOCUS_DOWN)
                val next = findMessageItemView(rawNext)

                if (next != null && next !== itemView && isDescendantOf(messageList, next)) {
                    alignItemBottom(next)
                    return true
                }

                lastFocusedMessageId = messageList.findContainingViewHolder(itemView)
                    ?.adapterPosition
                    ?.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let(messageAdapter::getItem)
                    ?.id

                message.post {
                    if (!message.isFocused) {
                        message.requestFocus()
                    }
                    message.setSelection(message.text?.length ?: 0)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val rect = getItemRectInRecycler(itemView)
                val visibleTop = messageList.paddingTop

                // Still more of the current message hidden above: reveal it first
                if (rect.top < visibleTop) {
                    val pageStep = ((messageList.height - messageList.paddingTop - messageList.paddingBottom) * 0.85f).toInt()
                    val hiddenTop = visibleTop - rect.top
                    val delta = minOf(pageStep, hiddenTop)
                    messageList.scrollBy(0, -delta)
                    itemView.requestFocus()
                    return true
                }else {
                    // Current message fully shown, move to previous message
                    val prev = findMessageItemView(itemView.focusSearch(View.FOCUS_UP))
                    if (prev != null && prev !== itemView && isDescendantOf(messageList, prev)) {
                        alignItemTop(prev)
                        return true
                    } else {
                        if (focusHeaderAction()) {
                            return true
                        }
                        return super.onKeyDown(keyCode, event)
                    }
                }
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
    private fun focusLatestMessageAtBottom() {
        messageList.post {
            val lm = messageList.layoutManager as? LinearLayoutManager ?: return@post
            val targetPosition = lastFocusedMessageId
                ?.let { messageId ->
                    messageAdapter.data?.indexOfLast { message -> message.id == messageId }
                }
                ?.takeIf { it != null && it != -1 }
                ?: (messageAdapter.itemCount - 1)

            if (targetPosition < 0) return@post

            val existingTargetView = lm.findViewByPosition(targetPosition)
            if (existingTargetView?.isFocused == true) return@post

            lm.scrollToPositionWithOffset(targetPosition, messageList.height - 80)
            messageList.post {
                lm.findViewByPosition(targetPosition)?.let { targetView ->
                    if (!targetView.isFocused) {
                        targetView.requestFocus()
                    }
                }
            }
        }
    }

    private fun scrollLatestMessageIntoView() {
        messageList.post {
            val lm = messageList.layoutManager as? LinearLayoutManager ?: return@post
            val targetPosition = lastFocusedMessageId
                ?.let { messageId ->
                    messageAdapter.data?.indexOfLast { message -> message.id == messageId }
                }
                ?.takeIf { it != null && it != -1 }
                ?: (messageAdapter.itemCount - 1)

            if (targetPosition < 0) return@post

            scrollToLatestMessagePosition(lm, targetPosition)
            if (message.isShown && message.isFocusable && !message.isFocused) {
                message.requestFocus()
                message.setSelection(message.text?.length ?: 0)
            }
        }
    }

    private fun scrollToLatestMessagePosition(
        layoutManager: LinearLayoutManager,
        targetPosition: Int
    ) {
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
        val distanceToLatest = lastVisiblePosition
            ?.let { targetPosition - it }
            ?: Int.MAX_VALUE

        if (distanceToLatest > MAX_SMOOTH_SCROLL_MESSAGES) {
            layoutManager.scrollToPositionWithOffset(targetPosition, messageList.height - 80)
        } else {
            messageList.smoothScrollToPosition(targetPosition)
        }
    }

    private fun focusComposer() {
        message.post {
            if (!message.isFocused) {
                message.requestFocus()
            }
            message.setSelection(message.text?.length ?: 0)
        }
    }

    private fun focusFirstVisibleMessageAtTop(): Boolean {
        val lm = messageList.layoutManager as? LinearLayoutManager ?: return false
        val position = lm.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: lm.findFirstCompletelyVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            ?: return false

        messageList.post {
            lm.findViewByPosition(position)?.let(::alignItemTop)
        }
        return true
    }

    private fun alignItemBottom(itemView: View) {
        if (!isDescendantOf(messageList, itemView)) return

        val rect = Rect()
        itemView.getDrawingRect(rect)
        messageList.offsetDescendantRectToMyCoords(itemView, rect)

        val visibleBottom = messageList.height - messageList.paddingBottom
        val delta = rect.bottom - visibleBottom
        if (delta > 0) {
            messageList.scrollBy(0, delta)
        }
        if (!itemView.isFocused) {
            itemView.requestFocus()
        }
    }

    private fun alignItemTop(itemView: View) {
        if (!isDescendantOf(messageList, itemView)) return

        val rect = Rect()
        itemView.getDrawingRect(rect)
        messageList.offsetDescendantRectToMyCoords(itemView, rect)

        val visibleTop = messageList.paddingTop
        val delta = rect.top - visibleTop
        if (delta < 0) {
            messageList.scrollBy(0, delta)
        }
        if (!itemView.isFocused) {
            itemView.requestFocus()
        }
    }

    private fun focusToolbarAction(): Boolean {
        val target = when {
            toolbarTitle.isVisible -> toolbarTitle
            headerAddRecipient.isVisible -> headerAddRecipient
            headerViewRecipients.isVisible -> headerViewRecipients
            else -> null
        } ?: return false

        if (!target.isFocused) {
            target.requestFocus()
        }
        return true
    }

    private fun focusHeaderAction(): Boolean {
        if (sendAsGroup.isVisible && sendAsGroupBackground.isShown) {
            if (!sendAsGroupBackground.isFocused) {
                sendAsGroupBackground.requestFocus()
            }
            return true
        }

        return focusToolbarAction()
    }

    private fun initClicks() {
        attachTv.setOnClickListener {
            val items = mutableListOf(
                R.id.photo_menu,
                R.id.audio_menu,
                R.id.video_menu,
                R.id.attach_contact
            )
            if (hasClipboardText()) {
                items += R.id.paste
            }
            val messageCount = messageAdapter.data?.size ?: 0
            if (messageCount == 0 || forceExpandedRecipientsEditor) {
                items += R.id.add
            }
            items += R.id.more_menu
            showCompactMenu(R.string.options, items)
        }
        moreTv.setOnClickListener {
            showAttachmentMoreMenu()
        }
        headerAddRecipient.setOnClickListener {
            forceExpandedRecipientsEditor = true
            optionsItemIntent.onNext(R.id.add)
        }
        headerViewRecipients.setOnClickListener {
            optionsItemIntent.onNext(R.id.view_recipients)
        }
    }
    private fun isDescendantOf(parent: ViewGroup, child: View?): Boolean {
        var current = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun findMessageItemView(view: View?): View? {
        var current = view
        while (current != null && current.parent is View && current.parent !== messageList) {
            current = current.parent as? View
        }
        return current
    }

    private fun findFocusedMessage(): Message? {
        val itemView = findMessageItemView(currentFocus) ?: return null
        val position = messageList.findContainingViewHolder(itemView)
            ?.adapterPosition
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: return null

        return messageAdapter.getItem(position)
    }

    private fun rememberFocusedMessageForRestore(): Boolean {
        val message = findFocusedMessage() ?: return false
        lastFocusedMessageId = message.id
        return true
    }

    private fun rememberMessageByIdForRestore(messageId: Long): Boolean {
        lastFocusedMessageId = messageId
        if (messageHasMediaPart(messageId)) {
            rememberPendingMediaRestore(messageId)
        }
        return true
    }

    private fun rememberMessageForPartRestore(partId: Long): Boolean {
        val message = messageAdapter.data
            ?.firstOrNull { message -> message.parts.any { part -> part.id == partId } }
            ?: return false

        lastFocusedMessageId = message.id
        if (message.parts.any { part -> part.id == partId && (part.isImage() || part.isVideo()) }) {
            rememberPendingMediaRestore(message.id)
        }
        return true
    }

    private fun messageHasMediaPart(messageId: Long): Boolean {
        return messageAdapter.data
            ?.firstOrNull { message -> message.id == messageId }
            ?.parts
            ?.any { part -> part.isImage() || part.isVideo() } == true
    }

    private fun rememberPendingMediaRestore(messageId: Long) {
        if (currentThreadId == 0L) return
        pendingMediaRestoreMessages[currentThreadId] = messageId
    }

    private fun hasPendingMediaRestore(): Boolean {
        return pendingMediaRestoreMessages.containsKey(currentThreadId)
    }

    private fun preparePendingMediaRestoreFocus(): Boolean {
        if (!hasPendingMediaRestore()) return false
        if (message.isFocused) {
            message.clearFocus()
        }
        messageList.requestFocus()
        return true
    }

    private fun restoreLastFocusedMessageIfVisible() {
        val messageId = lastFocusedMessageId ?: return
        messageList.post {
            val lm = messageList.layoutManager as? LinearLayoutManager ?: return@post
            val position = messageAdapter.data
                ?.indexOfLast { message -> message.id == messageId }
                ?.takeIf { position -> position != -1 }
                ?: return@post

            val existingView = lm.findViewByPosition(position)
            if (existingView?.isShown == true) {
                requestFocusWithinMessageItem(existingView)
                return@post
            }

            lm.scrollToPositionWithOffset(position, (messageList.height / 2).coerceAtLeast(messageList.paddingTop))
            messageList.post restoreFocus@{
                val itemView = lm.findViewByPosition(position) ?: run {
                    messageList.scrollToPosition(position)
                    messageList.postDelayed({
                        lm.findViewByPosition(position)?.let(::requestFocusWithinMessageItem)
                    }, 80)
                    return@restoreFocus
                }
                requestFocusWithinMessageItem(itemView)
            }
        }
    }

    private fun ensureComposeFocus() {
        contentView.removeCallbacks(ensureComposeFocusRunnable)
        contentView.post(ensureComposeFocusRunnable)
    }

    private fun forceComposeInteractionFocus() {
        contentView.removeCallbacks(ensureComposeFocusRunnable)
        contentView.removeCallbacks(forceComposeInteractionFocusRunnable)
        contentView.post(forceComposeInteractionFocusRunnable)
    }

    private fun restoreLastFocusedMessageIfVisibleIfAvailable(): Boolean {
        val messageId = lastFocusedMessageId ?: return false
        val layoutManager = messageList.layoutManager as? LinearLayoutManager ?: return false
        val position = messageAdapter.data
            ?.indexOfLast { message -> message.id == messageId }
            ?.takeIf { it != -1 }
            ?: return false

        val existingView = layoutManager.findViewByPosition(position)
        if (existingView?.isShown == true) {
            requestFocusWithinMessageItem(existingView)
            return true
        }

        return false
    }

    private fun requestFocusWithinMessageItem(itemView: View) {
        if (message.isFocused) {
            message.clearFocus()
        }

        val attachmentsView = itemView.findViewById<RecyclerView?>(R.id.attachments)
        val attachmentChild = attachmentsView
            ?.takeIf { it.isShown && it.childCount > 0 }
            ?.getChildAt(0)

        val preferredTarget = attachmentChild
            ?: attachmentsView?.takeIf { it.isShown && it.isFocusable }
            ?: itemView.findViewById<View?>(R.id.body)?.takeIf { it.isShown && it.isFocusable }
            ?: itemView

        if (!preferredTarget.isFocused) {
            preferredTarget.requestFocus()
        }
    }

    private fun showInAppAudioRecordingDialog() {
        audioRecordingDialog?.dismiss()
        suppressAudioRecordingDialogCancel = false
        val dialogView = layoutInflater.inflate(R.layout.compose_audio_record_dialog, null)
        audioRecordingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener {
                if (suppressAudioRecordingDialogCancel) {
                    suppressAudioRecordingDialogCancel = false
                } else {
                    discardInAppAudioRecording()
                }
            }
            .show()

        audioRecordingDialog?.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        updateAudioRecordingDialog()
    }

    private fun updateAudioRecordingDialog() {
        val dialog = audioRecordingDialog ?: return
        val actionButton = dialog.findViewById<View>(R.id.audioRecordActionButton) ?: return
        val statusView = dialog.findViewById<TextView>(R.id.audioRecordStatus) ?: return

        when (audioRecordingState) {
            AudioRecordingState.IDLE -> {
                actionButton.background = ContextCompat.getDrawable(this, R.drawable.compose_audio_record_button_background)
                (actionButton as? TextView)?.text = "\uD83C\uDFA4"
                actionButton.contentDescription = getString(R.string.compose_audio_start_action)
                statusView.text = getString(R.string.compose_audio_record_idle_compact)
                actionButton.setOnClickListener {
                    beginInAppAudioRecording()
                }
            }

            AudioRecordingState.RECORDING -> {
                actionButton.background = ContextCompat.getDrawable(this, R.drawable.compose_audio_record_button_background_recording)
                (actionButton as? TextView)?.text = "\u25A0"
                actionButton.contentDescription = getString(R.string.compose_audio_stop_action)
                statusView.text = getString(R.string.compose_audio_recording_compact)
                actionButton.setOnClickListener {
                    finishInAppAudioRecording()
                }
            }

            AudioRecordingState.RECORDED -> {
                actionButton.background = ContextCompat.getDrawable(this, R.drawable.compose_audio_record_button_background_ready)
                (actionButton as? TextView)?.text = "\u2713"
                actionButton.contentDescription = getString(R.string.compose_attach_action)
                statusView.text = getString(R.string.compose_audio_record_ready_compact)
                actionButton.setOnClickListener {
                    attachRecordedAudio()
                }
            }
        }

        actionButton.post {
            if (!actionButton.isFocused) {
                actionButton.requestFocus()
            }
        }
    }

    private fun beginInAppAudioRecording() {
        discardInAppAudioRecording()

        val recordingsDir = File(cacheDir, "recordings").apply { mkdirs() }
        runCatching { File(recordingsDir, ".nomedia").createNewFile() }
        val outputFile = File(
            recordingsDir,
            "audio_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.amr"
        )

        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (error: Exception) {
            recorder.release()
            outputFile.delete()
            Toast.makeText(this, R.string.compose_audio_record_error, Toast.LENGTH_SHORT).show()
            return
        }

        audioRecorder = recorder
        recordingAudioFile = outputFile
        audioRecordingState = AudioRecordingState.RECORDING
        if (audioRecordingDialog == null) {
            showInAppAudioRecordingDialog()
        } else {
            updateAudioRecordingDialog()
        }
    }

    private fun finishInAppAudioRecording() {
        val recorder = audioRecorder ?: return

        audioRecorder = null

        val outputFile = recordingAudioFile

        var recordedSuccessfully = true
        try {
            recorder.stop()
        } catch (error: RuntimeException) {
            recordedSuccessfully = false
            outputFile?.delete()
            Toast.makeText(this, R.string.compose_audio_record_error, Toast.LENGTH_SHORT).show()
        } finally {
            recorder.reset()
            recorder.release()
        }

        if (recordedSuccessfully && outputFile != null && outputFile.exists()) {
            audioRecordingState = AudioRecordingState.RECORDED
            updateAudioRecordingDialog()
        } else {
            recordingAudioFile = null
            audioRecordingState = AudioRecordingState.IDLE
            updateAudioRecordingDialog()
        }
    }

    private fun attachRecordedAudio() {
        val outputFile = recordingAudioFile
        if (outputFile == null || !outputFile.exists()) {
            audioRecordingState = AudioRecordingState.IDLE
            updateAudioRecordingDialog()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
        audioSelectedIntent.onNext(uri)
        recordingAudioFile = null
        audioRecordingState = AudioRecordingState.IDLE
        suppressAudioRecordingDialogCancel = true
        audioRecordingDialog?.dismiss()
        audioRecordingDialog = null
    }

    private fun discardInAppAudioRecording() {
        audioRecorder?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: RuntimeException) {
            } finally {
                recorder.reset()
                recorder.release()
            }
        }
        audioRecorder = null
        recordingAudioFile?.delete()
        recordingAudioFile = null
        audioRecordingState = AudioRecordingState.IDLE
    }
    override fun initSecondMenu() {
        showCompactMenu(
            R.string.compose_attach_cd,
            listOf(R.id.photo_menu, R.id.audio_menu, R.id.video_menu, R.id.attach_file, R.id.attach_contact)
        )
    }

    override fun initMoreMenu() {
        val items = mutableListOf<Int>()
        if (hasMultipleRecipientsForMenu) {
            items += R.id.view_recipients
        }
        items += R.id.search
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            items += R.id.speech_to_text
        }
        items += listOf(R.id.schedule_message, R.id.call, R.id.info)
        showCompactMenu(R.string.drawer_more, items)
    }

    override fun initPhotoMenu() {
        showCompactMenu(R.string.compose_gallery_cd, listOf(R.id.take_photo, R.id.attach_photo))
    }

    override fun initAudioMenu() {
        showCompactMenu(R.string.compose_audio_cd, listOf(R.id.record_audio, R.id.attach_audio))
    }

    override fun pasteText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this@ComposeActivity)
            appendDraftText(text)
        }
    }

    private fun hasClipboardText(): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount <= 0) return false

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
        return !text.isNullOrEmpty()
    }

    override fun initVideoMenu() {
        showCompactMenu(R.string.compose_video_cd, listOf(R.id.record_video, R.id.attach_video))
    }

    private fun showCompactMenu(titleRes: Int, itemIds: List<Int>) {
        compactMenuDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.options_dialog)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val titleView = dialog.findViewById<TextView>(R.id.options_dialog_title)
        val listView = dialog.findViewById<ListView>(R.id.option_dialog_list_view)
        titleView.text = getString(titleRes)
        val labels = itemIds.map(::compactMenuLabel)
        listView.adapter = ArrayAdapter(this, R.layout.options_dialog_list_item, labels)
        listView.setOnItemClickListener { _, _, position, _ ->
            when (itemIds[position]) {
                R.id.add -> {
                    forceExpandedRecipientsEditor = true
                    optionsItemIntent.onNext(itemIds[position])
                }
                R.id.search -> enterComposeSearchMode()
                else -> optionsItemIntent.onNext(itemIds[position])
            }
            dialog.dismiss()
        }

        dialog.show()
        listView.post {
            if (!listView.isFocused) {
                listView.requestFocus()
            }
        }
        compactMenuDialog = dialog
    }

    private fun showAttachmentMoreMenu() {
        if (currentAttachments.isEmpty()) {
            triggerSend("attachmentMoreEmpty")
            return
        }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val playable = currentAttachments
            .mapIndexedNotNull { index, attachment ->
                if (attachment.isPlayablePendingAttachment()) index to attachment else null
            }

        playable.forEachIndexed { playableIndex, indexedAttachment ->
            val (_, attachment) = indexedAttachment
            labels += if (playable.size == 1) {
                getString(R.string.compose_attachment_play_short)
            } else {
                getString(R.string.compose_attachment_play_numbered_short, playableIndex + 1)
            }
            actions += { playPendingAttachment(attachment) }
        }

        currentAttachments.forEachIndexed { index, attachment ->
            labels += if (currentAttachments.size == 1) {
                getString(R.string.compose_attachment_remove_short)
            } else {
                getString(R.string.compose_attachment_remove_numbered_short, index + 1)
            }
            actions += { attachmentAdapter.attachmentDeleted.onNext(attachment) }
        }

        if (currentAttachments.size > 1) {
            labels += getString(R.string.compose_attachment_remove_all_short)
            actions += {
                currentAttachments.toList().forEach(attachmentAdapter.attachmentDeleted::onNext)
            }
        }

        labels += getString(R.string.send)
        actions += { triggerSend("attachmentMore") }

        showActionMenu(getString(R.string.compose_attachment_menu_title), labels, actions)
    }

    private fun showActionMenu(title: String, labels: List<String>, actions: List<() -> Unit>) {
        compactMenuDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.options_dialog)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val titleView = dialog.findViewById<TextView>(R.id.options_dialog_title)
        val listView = dialog.findViewById<ListView>(R.id.option_dialog_list_view)
        titleView.text = title
        listView.adapter = ArrayAdapter(this, R.layout.options_dialog_list_item, labels)
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            actions[position].invoke()
        }

        dialog.show()
        listView.post {
            if (!listView.isFocused) {
                listView.requestFocus()
            }
        }
        compactMenuDialog = dialog
    }

    private fun Attachment.isPlayablePendingAttachment(): Boolean {
        return when (this) {
            is Attachment.Video -> true
            is Attachment.File -> getContentType(this@ComposeActivity)
                ?.startsWith("audio/", ignoreCase = true) == true
            else -> false
        }
    }

    private fun playPendingAttachment(attachment: Attachment) {
        if (attachment is Attachment.File &&
            attachment.getContentType(this)?.startsWith("audio/", ignoreCase = true) == true
        ) {
            val index = currentAttachments.indexOfFirst { it === attachment || it == attachment }
            if (index == -1) return

            attachments.scrollToPosition(index)
            attachments.post {
                val holder = attachments.findViewHolderForAdapterPosition(index)
                if (holder?.itemView?.performClick() != true) {
                    Toast.makeText(this, R.string.gallery_error, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        openPendingAttachment(attachment)
    }

    private fun openPendingAttachment(attachment: Attachment) {
        val uri = when (attachment) {
            is Attachment.Video -> attachment.getUri()
            is Attachment.File -> attachment.getUri()
            is Attachment.Image -> attachment.getUri()
            is Attachment.Contact -> null
        } ?: return

        val type = when (attachment) {
            is Attachment.Video -> attachment.getContentType(this)
            is Attachment.File -> attachment.getContentType(this)
            is Attachment.Image -> contentResolver.getType(uri)
            is Attachment.Contact -> null
        } ?: "*/*"

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, null))
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.gallery_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun compactMenuLabel(itemId: Int): String = when (itemId) {
        R.id.attach_second_menu -> getString(R.string.attach)
        R.id.paste -> getString(R.string.compose_paste)
        R.id.add -> getString(R.string.menu_add_person)
        R.id.more_menu -> getString(R.string.menu_more_ellipsis)
        R.id.search -> getString(R.string.compose_menu_search)
        R.id.photo_menu -> getString(R.string.compose_gallery_cd)
        R.id.audio_menu -> getString(R.string.compose_audio_cd)
        R.id.video_menu -> getString(R.string.compose_video_cd)
        R.id.attach_file -> getString(R.string.compose_file_cd)
        R.id.attach_contact -> getString(R.string.compose_contact_cd)
        R.id.take_photo -> getString(R.string.compose_camera_cd)
        R.id.attach_photo -> getString(R.string.compose_gallery_cd)
        R.id.record_audio -> getString(R.string.compose_audio_record_cd)
        R.id.attach_audio -> getString(R.string.compose_audio_cd)
        R.id.record_video -> getString(R.string.compose_video_record_cd)
        R.id.attach_video -> getString(R.string.compose_video_cd)
        R.id.view_recipients -> getString(R.string.menu_view_recipients)
        R.id.schedule_message -> getString(R.string.compose_schedule_cd)
        R.id.speech_to_text -> getString(R.string.compose_speech_to_text)
        R.id.call -> getString(R.string.menu_call)
        R.id.info -> getString(R.string.info_title)
        else -> itemId.toString()
    }

    private fun enterComposeSearchMode() {
        if (!composeSearchMode) {
            draftBeforeSearch = message.text?.toString().orEmpty()
            composeSearchMode = true
        }

        message.setText("")
        message.hint = getString(R.string.compose_menu_search)
        message.requestFocus()
        message.setSelection(0)
        searchQueryIntent.onNext("")
    }

    private fun exitComposeSearchMode() {
        composeSearchMode = false
        optionsItemIntent.onNext(R.id.clear)
        message.hint = getString(R.string.compose_hint)
        message.setText(draftBeforeSearch)
        message.setSelection(message.text?.length ?: 0)
        message.requestFocus()
    }

    override fun showContactsDialog(contacts: MutableList<String>) {
        val choices = contacts.map { value -> ContactChoice(value, findNameForLinkedValue(value)) }
        val adapter = object : ArrayAdapter<ContactChoice>(this, android.R.layout.simple_list_item_2, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val choice = getItem(position) ?: return view
                val title = view.findViewById<TextView>(android.R.id.text1)
                val subtitle = view.findViewById<TextView>(android.R.id.text2)

                title.text = choice.value
                subtitle.text = choice.name ?: ""
                subtitle.visibility = if (choice.name.isNullOrBlank()) View.GONE else View.VISIBLE
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Select")
            .setAdapter(adapter) { _, position ->
                onLinkClicked(choices[position].value)
            }

            .setNegativeButton("Cancel", null)

            .show()
    }

    private data class ContactChoice(val value: String, val name: String?)

    private fun findNameForLinkedValue(value: String): String? {
        if (!android.util.Patterns.PHONE.matcher(value).matches()) return null
        return findAndroidContactName(value) ?: findHeimishDirectoryName(value)
    }

    private fun findAndroidContactName(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findHeimishDirectoryName(phoneNumber: String): String? {
        val target = phoneKey(phoneNumber) ?: return null
        return try {
            contentResolver.query(
                HEIMISH_DIRECTORY_CONTACTS_URI,
                arrayOf("phone", "name"),
                null,
                null,
                null
            )?.use { cursor ->
                val phoneIndex = cursor.getColumnIndexOrThrow("phone")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val phone = cursor.getString(phoneIndex) ?: continue
                    val key = phoneKey(phone) ?: continue
                    if (key == target) {
                        return@use cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun phoneKey(phoneNumber: String): String? {
        val digits = phoneNumber.filter(Char::isDigit)
        val normalized = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        return normalized.takeIf { it.length >= 7 }?.takeLast(10)
    }

    override fun showRecipientsDialog(recipients: List<Recipient>) {
        val adapter = object : ArrayAdapter<Recipient>(this, R.layout.recipient_dialog_item, recipients) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.recipient_dialog_item, parent, false)
                val recipient = getItem(position) ?: return view

                val avatar = view.findViewById<com.moez.QKSMS.common.widget.GroupAvatarView>(R.id.avatar)
                val title = view.findViewById<TextView>(R.id.title)
                val subtitle = view.findViewById<TextView>(R.id.subtitle)

                avatar.recipients = listOf(recipient)
                title.text = recipient.contact?.name?.takeIf { it.isNotBlank() } ?: recipient.address
                subtitle.text = recipient.address

                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_view_recipients)
            .setAdapter(adapter, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun onLinkClicked(link: String) {
        val message = if (android.util.Patterns.PHONE.matcher(link).matches()) {
            openDialerWithPhoneNumber(link)
        } else if (android.util.Patterns.EMAIL_ADDRESS.matcher(link).matches()) {
            openEmail(link)
        } else {
            "Link clicked: $link"
        }
        // Show the message using Toast or some other method
    }

    private fun openDialerWithPhoneNumber(phoneNumber: String) {
        val dialIntent = Intent(Intent.ACTION_DIAL)
        dialIntent.data = Uri.parse("tel:$phoneNumber")
        startActivity(dialIntent)
    }

    private fun openEmail(email: String) {
        val emailIntent = Intent(Intent.ACTION_SENDTO)
        emailIntent.data = Uri.parse("mailto:$email")
        startActivity(emailIntent)
    }

    private fun selectedMessagesCanSave(messageIds: List<Long>): Boolean {
        val selected = messageAdapter.data
            ?.filter { message -> messageIds.contains(message.id) }
            .orEmpty()

        return selected.any { message ->
            message.isMms() && message.parts.any { part ->
                part != null && (
                    part.isImage() ||
                        part.isVideo() ||
                        part.type.startsWith("audio/") ||
                        (!part.isText() && !part.isSmil())
                    )
            }
        }
    }

    fun showOptionsDialog(isMms: Boolean) {
        rememberFocusedMessageForRestore()
        val selectedMessageIds = messageAdapter.getSelection().toList()
        messageOptionsSelectionIntent.onNext(selectedMessageIds)
        val canSave = selectedMessagesCanSave(selectedMessageIds)

        fun dispatchMessageOption(itemId: Int, action: String, restoreFocus: Boolean = false) {
            if (restoreFocus) {
                pendingRestoreMessageFocusOnResume = true
            }
            val optionAction = ComposeView.MessageOptionAction(itemId, selectedMessageIds)
            messageOptionsSelectionIntent.onNext(selectedMessageIds)
            clearSelection()
            viewModel.onMessageOptionAction(optionAction, this@ComposeActivity)
        }

        val listener = object : ComposeOptionsDialog.OnComposeOptionsDialogItemClickListener {
            override fun onCopyMessageClicked() {
                dispatchMessageOption(R.id.copy, "copy")
            }

            override fun onPasteMessageClicked(dialog: ComposeOptionsDialog) {
                lastPasteClickedTime = Date()

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip

                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(this@ComposeActivity)
                    appendDraftText(text)
                }

                clearSelection()
                dialog.dismiss()

            }

            override fun onDeleteMessagesClicked() {
                dispatchMessageOption(R.id.delete, "delete")
            }

            override fun onForwardMessageClicked() {
                dispatchMessageOption(R.id.forward, "forward", restoreFocus = true)
            }

            override fun onMessageInfoClicked() {
                dispatchMessageOption(R.id.details, "details")
            }

            override fun onSaveClicked() {
                dispatchMessageOption(R.id.save, "save")
            }

        }

        val dialog = ComposeOptionsDialog(this@ComposeActivity, listener, canSave)

        optionsDialogShownForSelection = true

        dialog.setOnDismissListener {
            optionsDialogShownForSelection = false
            if (!(it as ComposeOptionsDialog).isClickDismissed) {
                clearSelection()
                restoreLastFocusedMessageIfVisible()
            }
        }

        dialog.show()
    }
}
