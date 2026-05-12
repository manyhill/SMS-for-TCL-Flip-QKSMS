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
import android.content.Intent
import android.net.Uri
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.telephony.SmsMessage
import android.text.util.Linkify
import android.util.Log
import androidx.core.content.getSystemService
import com.android.i18n.phonenumbers.PhoneNumberUtil
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkViewModel
import com.moez.QKSMS.common.util.ClipboardUtils
import com.moez.QKSMS.common.util.FileUtils.nothingToPlay
import com.moez.QKSMS.common.util.FileUtils.nothingToSave
import com.moez.QKSMS.common.util.FileUtils.saveFileToDownloads
import com.moez.QKSMS.common.util.FileUtils.saveFileToMusic
import com.moez.QKSMS.common.util.FileUtils.saveImageToGallery
import com.moez.QKSMS.common.util.FileUtils.saveVideoToGallery
import com.moez.QKSMS.common.util.MessageDetailsFormatter
import com.moez.QKSMS.common.util.extensions.makeToast
import com.moez.QKSMS.compat.SubscriptionManagerCompat
import com.moez.QKSMS.compat.TelephonyCompat
import com.moez.QKSMS.extensions.*
import com.moez.QKSMS.interactor.*
import com.moez.QKSMS.manager.ActiveConversationManager
import com.moez.QKSMS.manager.BillingManager
import com.moez.QKSMS.manager.PermissionManager
import com.moez.QKSMS.model.*
import com.moez.QKSMS.repository.ContactRepository
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.util.ActiveSubscriptionObservable
import com.moez.QKSMS.util.PhoneNumberUtils
import com.moez.QKSMS.util.Preferences
import com.moez.QKSMS.util.tryOrNull
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Named

private const val COMPOSE_MESSAGE_RENDER_LIMIT = 250L
private const val SAFE_MMS_PAYLOAD_BYTES = 600 * 1024L
private const val DEFAULT_MMS_PAYLOAD_BYTES = 300 * 1024L
private const val MMS_COMPRESSION_HEADROOM = 0.9


class ComposeViewModel @Inject constructor(
    @Named("query") private val query: String,
    @Named("threadId") private val threadId: Long,
    @Named("addresses") private val addresses: List<String>,
    @Named("forwardMode") private val forwardMode: Boolean,
    @Named("text") private val sharedText: String,
    @Named("attachments") private val sharedAttachments: Attachments,
    private val contactRepo: ContactRepository,
    private val context: Context,
    private val activeConversationManager: ActiveConversationManager,
    private val addScheduledMessage: AddScheduledMessage,
    private val billingManager: BillingManager,
    private val cancelMessage: CancelDelayedMessage,
    private val conversationRepo: ConversationRepository,
    private val deleteMessages: DeleteMessages,
    private val markRead: MarkRead,
    private val messageDetailsFormatter: MessageDetailsFormatter,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val notificationManager: com.moez.QKSMS.manager.NotificationManager,
    private val permissionManager: PermissionManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val retrySending: RetrySending,
    private val sendMessage: SendMessage,
    private val subscriptionManager: SubscriptionManagerCompat
) : QkViewModel<ComposeView, ComposeState>(
    ComposeState(
        editingMode = threadId == 0L, threadId = threadId, query = query
    )
) {
    private fun dedupeRecipientsByAddress(recipients: List<Recipient>): List<Recipient> {
        val deduped = mutableListOf<Recipient>()
        recipients.forEach { candidate ->
            val exists = deduped.any { existing ->
                phoneNumberUtils.compare(existing.address, candidate.address)
            }
            if (!exists) deduped += candidate
        }
        return deduped
    }

    private fun dedupeAddresses(addresses: List<String>): List<String> {
        val deduped = mutableListOf<String>()
        addresses.forEach { candidate ->
            val exists = deduped.any { existing ->
                phoneNumberUtils.compare(existing, candidate)
            }
            if (!exists) deduped += candidate
        }
        return deduped
    }

    private fun normalizeSelectedRecipients(recipients: List<Recipient>): List<Recipient> {
        return dedupeRecipientsByAddress(recipients).let { deduped ->
            if (forwardMode) deduped.take(1) else deduped
        }
    }

    private fun conversationRecipientAddresses(conversation: Conversation): List<String> {
        return conversation.recipients
            .filter { recipient -> recipient.isValid }
            .map { recipient -> recipient.address }
    }

    private fun sameRecipients(left: List<String>, right: List<String>): Boolean {
        val leftDeduped = dedupeAddresses(left)
        val rightDeduped = dedupeAddresses(right)
        return leftDeduped.size == rightDeduped.size &&
                leftDeduped.all { leftAddress ->
                    rightDeduped.any { rightAddress ->
                        phoneNumberUtils.compare(leftAddress, rightAddress)
                    }
                }
    }

    private fun threadIdForSend(
        state: ComposeState,
        conversation: Conversation,
        addresses: List<String>
    ): Long {
        val conversationAddresses = conversationRecipientAddresses(conversation)
        return when {
            state.editingMode && !sameRecipients(conversationAddresses, addresses) -> 0L
            conversation.id > 0 -> conversation.id
            else -> threadId
        }
    }

    private fun showConversationAfterSend(addresses: List<String>) {
        if (addresses.isEmpty()) return

        disposables += Observable.fromCallable {
            conversationRepo.getOrCreateConversation(addresses)?.id ?: 0L
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .filter { resolvedThreadId -> resolvedThreadId != 0L }
            .switchMap { resolvedThreadId ->
                conversationRepo.getConversationAsync(resolvedThreadId)
                    .asObservable()
                    .filter { sentConversation -> sentConversation.isLoaded }
                    .take(1)
            }
            .filter { sentConversation -> sentConversation.isValid }
            .subscribe(
                { sentConversation ->
                    currentSelectedRecipients = listOf()
                    conversation.onNext(sentConversation)
                    newState {
                        copy(
                            editingMode = false,
                            threadId = sentConversation.id,
                            selectedChips = listOf(),
                            sendAsGroup = true,
                            loading = false
                        )
                    }
                },
                Timber::w
            )
    }

    private fun Message.sortedParts() = parts
        .filterNotNull()
        .sortedWith(compareBy<MmsPart> { if (it.seq >= 0) it.seq else Int.MAX_VALUE }.thenBy { it.id })

    private fun Message.firstMediaPart(): MmsPart? = sortedParts().firstOrNull { part ->
        part.isImage() || part.isVideo()
    }

    private fun Message.firstNonMediaPart(): MmsPart? = sortedParts().firstOrNull { part ->
        !part.isImage() && !part.isVideo() && !part.isText() && !part.isSmil()
    }

    private fun MmsPart.isAudioPart(): Boolean = type.startsWith("audio/")

    private fun Message.toDraftAttachments(): List<Attachment> = sortedParts().mapNotNull { part ->
        when {
            part.isImage() -> Attachment.Image(part.getUri())
            part.isVideo() -> Attachment.Video(part.getUri())
            part.type.startsWith("audio/") -> Attachment.File(part.getUri())
            !part.isText() && !part.isSmil() -> Attachment.File(part.getUri())
            else -> null
        }
    }

    private val attachments: Subject<List<Attachment>> =
        BehaviorSubject.createDefault(sharedAttachments)
    private val conversation: Subject<Conversation> = BehaviorSubject.createDefault(Conversation(0))
    private val messages: Subject<List<Message>> = BehaviorSubject.create()
    private val selectedChips: Subject<List<Recipient>> = BehaviorSubject.createDefault(listOf())
    private val searchResults: Subject<List<Message>> = BehaviorSubject.create()
    private val searchSelection: Subject<Long> = BehaviorSubject.createDefault(-1)

    private var shouldShowContacts = threadId == 0L && addresses.isEmpty()
    private var currentSelectedRecipients: List<Recipient> = listOf()

    private fun applySelectedChips(chips: List<Recipient>) {
        val deduped = normalizeSelectedRecipients(chips)
        currentSelectedRecipients = deduped
        selectedChips.onNext(deduped)
        newState {
            copy(
                editingMode = true,
                selectedChips = deduped,
                sendAsGroup = sendAsGroup || deduped.size > 1
            )
        }
    }

    init {
        val initialConversation =
            threadId.takeIf { it != 0L }?.let(conversationRepo::getConversationAsync)
                ?.asObservable() ?: Observable.empty()

        val selectedConversation = when {
            threadId == 0L -> Observable.empty<Conversation>()
            else -> selectedChips.skipWhile { it.isEmpty() }.map { chips -> chips.map { it.address } }
                .distinctUntilChanged().doOnNext { newState { copy(loading = true) } }
                .observeOn(Schedulers.io()).map { addresses ->
                    Pair(
                        conversationRepo.getOrCreateConversation(addresses)?.id ?: 0, addresses
                    )
                }.observeOn(AndroidSchedulers.mainThread())
                .doOnNext { newState { copy(loading = false) } }
                .switchMap { (threadId, addresses) ->
                    // If we already have this thread in realm, or we're able to obtain it from the
                    // system, just return that.
                    threadId.takeIf { it > 0 }?.let {
                        return@switchMap conversationRepo.getConversationAsync(threadId)
                            .asObservable()
                    }

                    // Otherwise, we'll monitor the conversations until our expected conversation is created
                    conversationRepo.getConversations().asObservable().filter { it.isLoaded }
                        .observeOn(Schedulers.io())
                        .map { conversationRepo.getOrCreateConversation(addresses)?.id ?: 0 }
                        .observeOn(AndroidSchedulers.mainThread()).switchMap { actualThreadId ->
                            when (actualThreadId) {
                                0L -> Observable.just(Conversation(0))
                                else -> conversationRepo.getConversationAsync(actualThreadId)
                                    .asObservable()
                            }
                        }
                }
        }

        // Merges two potential conversation sources (threadId from constructor and contact selection) into a single
        // stream of conversations. If the conversation was deleted, notify the activity to shut down
        disposables += selectedConversation.mergeWith(initialConversation)
            .filter { conversation -> conversation.isLoaded }.doOnNext { conversation ->
                if (!conversation.isValid) {
                    newState { copy(hasError = true) }
                }
            }.filter { conversation -> conversation.isValid }.subscribe(conversation::onNext)

        if (addresses.isNotEmpty()) {
            applySelectedChips(addresses.map { address -> Recipient(address = address) })
        }

        // When searching, render the matched messages instead of the recent-message window so
        // every counted result can be reached by previous/next navigation.
        disposables += Observables.combineLatest(
            conversation.distinctUntilChanged { conversation -> conversation.id },
            state.map { state -> state.query }.distinctUntilChanged()
        ) { conversation, query -> conversation to query }
            .observeOn(AndroidSchedulers.mainThread())
            .switchMap { (conversation, query) ->
                val liveMessages = if (query.isBlank()) {
                    messageRepo.getRecentMessages(
                        conversation.id,
                        COMPOSE_MESSAGE_RENDER_LIMIT
                    )
                } else {
                    messageRepo.getMessages(conversation.id, query)
                }
                liveMessages
                    .asObservable()
                    .doOnNext {
                        newState { copy(threadId = conversation.id, messages = Pair(conversation, liveMessages)) }
                    }
            }
            .subscribe(messages::onNext)

        disposables += conversation.map { conversation -> conversation.getTitle() }
            .distinctUntilChanged()
            .subscribe { title -> newState { copy(conversationtitle = title) } }

        disposables += prefs.sendAsGroup.asObservable().distinctUntilChanged()
            .subscribe { enabled -> newState { copy(sendAsGroup = enabled) } }

        disposables += attachments.subscribe { attachments -> newState { copy(attachments = attachments) } }

        disposables += state.map { state -> state.query }
            .distinctUntilChanged()
            .withLatestFrom(conversation) { query, conversation -> query to conversation.id }
            .switchMap { (query, conversationId) ->
                if (query.isBlank()) {
                    io.reactivex.Observable.just(listOf<Message>())
                } else {
                    messageRepo.getMessages(conversationId, query)
                        .asObservable()
                        .filter { messages -> messages.isLoaded }
                        .filter { messages -> messages.isValid }
                        .map { messages -> messages.toList() }
                }
            }
            .subscribe(searchResults::onNext)

        disposables += Observables.combineLatest(
            searchSelection, searchResults
        ) { selected, messages ->
            if (selected == -1L) {
                messages.lastOrNull()?.let { message -> searchSelection.onNext(message.id) }
            } else {
                val position = messages.indexOfFirst { it.id == selected } + 1
                newState { copy(searchSelectionPosition = position, searchResults = messages.size) }
            }
            Unit
        }.subscribe()

        val latestSubId =
            messages.map { messages -> messages.lastOrNull()?.subId ?: -1 }.distinctUntilChanged()

        val subscriptions = ActiveSubscriptionObservable(subscriptionManager)
        disposables += Observables.combineLatest(latestSubId, subscriptions) { subId, subs ->
            val sub = if (subs.size > 1) subs.firstOrNull { it.subscriptionId == subId }
                ?: subs[0] else null
            newState { copy(subscription = sub) }
        }.subscribe()
    }

    override fun bindView(view: ComposeView) {
        super.bindView(view)

        view.searchQueryIntent.autoDisposable(view.scope()).subscribe { query ->
            searchSelection.onNext(-1)
            newState { copy(query = query, searchSelectionId = -1, searchSelectionPosition = 0, searchResults = 0) }
        }

        val sharing = !forwardMode && (sharedText.isNotEmpty() || sharedAttachments.isNotEmpty())
        if (forwardMode) {
            if (sharedText.isNotBlank()) {
                view.setDraft(sharedText)
            }
            if (sharedAttachments.isNotEmpty()) {
                attachments.onNext(sharedAttachments)
            }
        }
        if (shouldShowContacts) {
            shouldShowContacts = false
            view.showContacts(sharing, selectedChips.blockingFirst(), singleRecipient = true)
        }

        view.chipsSelectedIntent.withLatestFrom(selectedChips) { hashmap, chips ->
            Log.d("QK-COMPOSE", "chipsSelected result=${hashmap.keys} existing=${chips.map { it.address }} forwardMode=$forwardMode")
            if (hashmap.isEmpty() && chips.isEmpty()) {
                newState { copy(hasError = true) }
            }
            hashmap.filter { (address) ->
                chips.none { recipient -> phoneNumberUtils.compare(address, recipient.address) }
            }
        }.filter { hashmap -> hashmap.isNotEmpty() }.map { hashmap ->
            hashmap.map { (address, lookupKey) ->
                conversationRepo.getRecipients().asSequence()
                    .filter { recipient -> recipient.contact?.lookupKey == lookupKey }
                    .firstOrNull { recipient ->
                        phoneNumberUtils.compare(recipient.address, address)
                    } ?: Recipient(
                    address = address, contact = lookupKey?.let(contactRepo::getUnmanagedContact)
                )
            }.let { recipients -> if (forwardMode) recipients.take(1) else recipients }
        }.autoDisposable(view.scope()).subscribe { chips ->
            Log.d("QK-COMPOSE", "chipsSelected add chips=${chips.map { it.address }}")
            applySelectedChips(selectedChips.blockingFirst() + chips)
            view.showKeyboard()
        }

        // Set the contact suggestions list to visible when the add button is pressed
        view.optionsItemIntent.filter { it == R.id.add }
            .withLatestFrom(selectedChips, conversation, state) { _, chips, conversation, state ->
                val conversationRecipients = conversation.recipients.filter { it.isValid }
                val initialChips = when {
                    state.editingMode && chips.isNotEmpty() -> chips
                    conversationRecipients.isNotEmpty() -> conversationRecipients
                    else -> chips
                }

                Pair(initialChips, state.editingMode)
            }
            .autoDisposable(view.scope())
            .subscribe { (chips, editingMode) ->
                if (!editingMode) {
                    selectedChips.onNext(chips)
                    newState { copy(editingMode = true, selectedChips = chips, sendAsGroup = true) }
                } else {
                    newState { copy(sendAsGroup = true) }
                }
                view.showContacts(sharing, chips, singleRecipient = forwardMode)
            }

        // Update the list of selected contacts when a new contact is selected or an existing one is deselected
        view.chipDeletedIntent.autoDisposable(view.scope()).subscribe { contact ->
            val result = selectedChips.blockingFirst().filterNot { it == contact }
            if (result.isEmpty()) {
                view.showContacts(sharing, result, singleRecipient = true)
            }
            applySelectedChips(result)
        }

        // When the menu is loaded, trigger a new state so that the menu options can be rendered correctly
        view.menuReadyIntent.autoDisposable(view.scope()).subscribe { newState { copy() } }

        // Open the phone dialer if the call button is clicked
        view.optionsItemIntent.filter { it == R.id.call }
            .withLatestFrom(conversation) { _, conversation -> conversation }
            .mapNotNull { conversation -> conversation.recipients.firstOrNull() }
            .map { recipient -> recipient.address }.autoDisposable(view.scope())
            .subscribe { address -> navigator.makePhoneCall(address) }



        // Open the conversation settings if info button is clicked
        view.optionsItemIntent.filter { it == R.id.info }
            .withLatestFrom(conversation) { _, conversation -> conversation }
            .autoDisposable(view.scope())
            .subscribe { conversation -> navigator.showConversationInfo(conversation.id) }

        view.optionsItemIntent.filter { it == R.id.view_recipients }
            .withLatestFrom(selectedChips, conversation, state) { _, chips, conversation, state ->
                when {
                    state.editingMode && chips.isNotEmpty() -> chips
                    else -> conversation.recipients.filter { it.isValid }
                }
            }
            .map { recipients -> recipients.distinctBy { it.address } }
            .filter { recipients -> recipients.size > 1 }
            .autoDisposable(view.scope())
            .subscribe { recipients -> view.showRecipientsDialog(recipients) }

        // Copy the message contents
        view.messageOptionActionIntent
            .filter { action -> action.itemId == R.id.copy }
            .map { action ->
                Log.d("QK-MSGOPT", "handler=copy selection=${action.messageIds}")
                val messages = action.messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }
                val text = when (messages.size) {
                    1 -> messages.first().getText()
                    else -> messages.foldIndexed("") { index, acc, message ->
                        when {
                            index == 0 -> message.getText()
                            messages[index - 1].compareSender(message) -> "$acc\n${message.getText()}"
                            else -> "$acc\n\n${message.getText()}"
                        }
                    }
                }

                ClipboardUtils.copy(context, text)
            }.autoDisposable(view.scope()).subscribe { view.clearSelection() }


        // Save attachments to the appropriate device folder
        view.messageOptionActionIntent
            .filter { action -> action.itemId == R.id.save }
            .map { action ->
                Log.d("QK-MSGOPT", "handler=save selection=${action.messageIds}")
                val messages = action.messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }
                val clickedMessage: Message = messages.first()
                var savedAny = false
                if (clickedMessage.isMms()) {
                    for (part in clickedMessage.parts) {
                        part?.let { p ->
                            when {
                                p.isVideo() -> {
                                    context.saveVideoToGallery(p.getUri())
                                    savedAny = true
                                }
                                p.isImage() -> {
                                    context.saveImageToGallery(p.getUri())
                                    savedAny = true
                                }
                                p.isAudioPart() -> {
                                    messageRepo.savePart(p.id)?.let { file ->
                                        savedAny = true
                                        context.saveFileToMusic(file)
                                    }
                                }
                                !p.isText() && !p.isSmil() -> {
                                    messageRepo.savePart(p.id)?.let { file ->
                                        savedAny = true
                                        context.saveFileToDownloads(file)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                if (!savedAny) {
                    context.nothingToSave()
                } else {
                    context.makeToast("Saved")
                }
                savedAny
            }.autoDisposable(view.scope()).subscribe { view.clearSelection() }


        // Mute/Unmute notifications for the current conversation
        view.optionsItemIntent.filter { it == R.id.mute }
            .withLatestFrom(state) { _, state -> state.threadId }
            .filter { threadId -> threadId != 0L }
            .autoDisposable(view.scope())
            .subscribe { threadId ->
                if (notificationManager.isConversationMuted(threadId)) {
                    notificationManager.unmuteConversation(threadId)
                    context.makeToast(R.string.toast_conversation_unmuted)
                } else {
                    notificationManager.muteConversation(threadId)
                    context.makeToast(R.string.toast_conversation_muted)
                }
                view.clearSelection()
            }


        // Show the message details
        view.messageOptionActionIntent
            .filter { action -> action.itemId == R.id.details }
            .map { action ->
                Log.d("QK-MSGOPT", "handler=details selection=${action.messageIds}")
                action.messageIds
            }
            .mapNotNull { messages -> messages.firstOrNull().also { view.clearSelection() } }
            .mapNotNull(messageRepo::getMessage).map(messageDetailsFormatter::format)
            .autoDisposable(view.scope()).subscribe { view.showDetails(it) }


        // Delete the messages
        view.messageOptionActionIntent.filter { action -> action.itemId == R.id.delete }
            .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
            .withLatestFrom(
                conversation
            ) { action, conversation ->
                Log.d("QK-MSGOPT", "handler=delete selection=${action.messageIds} conversationId=${conversation.id}")
                deleteMessages.execute(DeleteMessages.Params(action.messageIds, conversation.id))
            }.autoDisposable(view.scope()).subscribe { view.clearSelection() }

        // Forward the message
        view.messageOptionActionIntent
            .filter { action -> action.itemId == R.id.forward }
            .map { action ->
                Log.d("QK-MSGOPT", "handler=forward selection=${action.messageIds}")
                action.messageIds.firstOrNull()?.let { messageRepo.getMessage(it) }?.let { message ->
                    navigator.showForward(message.getText(), message.toDraftAttachments())
                }
            }.autoDisposable(view.scope()).subscribe { view.clearSelection() }


        // Show the previous search result
        view.optionsItemIntent.filter { it == R.id.previous }
            .withLatestFrom(searchSelection, searchResults) { _, selection, messages ->
                val currentPosition = messages.indexOfFirst { it.id == selection }
                if (currentPosition <= 0L) messages.lastOrNull()?.id ?: -1
                else messages.getOrNull(currentPosition - 1)?.id ?: -1
            }.filter { id -> id != -1L }.autoDisposable(view.scope()).subscribe(searchSelection)

        // Show the next search result
        view.optionsItemIntent.filter { it == R.id.next }
            .withLatestFrom(searchSelection, searchResults) { _, selection, messages ->
                val currentPosition = messages.indexOfFirst { it.id == selection }
                if (currentPosition >= messages.size - 1) messages.firstOrNull()?.id ?: -1
                else messages.getOrNull(currentPosition + 1)?.id ?: -1
            }.filter { id -> id != -1L }.autoDisposable(view.scope()).subscribe(searchSelection)

        // Clear the search
        view.optionsItemIntent.filter { it == R.id.clear }.autoDisposable(view.scope())
            .subscribe {
                searchSelection.onNext(-1)
                newState { copy(query = "", searchSelectionId = -1, searchSelectionPosition = 0, searchResults = 0) }
            }

        // Toggle the group sending mode
        view.sendAsGroupIntent.autoDisposable(view.scope())
            .subscribe { prefs.sendAsGroup.set(!prefs.sendAsGroup.get()) }

        // Scroll to search position
        searchSelection.filter { id -> id != -1L }
            .doOnNext { id -> newState { copy(searchSelectionId = id) } }
            .autoDisposable(view.scope()).subscribe(view::scrollToMessage)

        // Theme changes
        prefs.keyChanges.filter { key -> key.contains("theme") }.doOnNext { view.themeChanged() }
            .autoDisposable(view.scope()).subscribe()

        // Retry sending
        view.messageClickIntent.mapNotNull(messageRepo::getMessage)
            .filter { message -> message.isFailedMessage() }
            .doOnNext { message -> retrySending.execute(message.id) }.autoDisposable(view.scope())
            .subscribe()

        // Retry sending
        view.messageClickIntent.mapNotNull(messageRepo::getMessage)
           // .filter { message -> message.isMms() }

            .doOnNext {
                          message ->

//                     view.setDraft(message.getText()) }.autoDisposable(view.scope())
//                    .subscribe { message ->
//                        cancelMessage.execute(CancelDelayedMessage.Params(message.id, message.threadId))
//                    }

                val targetMediaPart = message.firstMediaPart()
                Timber.d(
                    "Compose row click messageId=%d threadId=%d parts=%s targetMediaPart=%s",
                    message.id,
                    message.threadId,
                    message.sortedParts().joinToString { part ->
                        "id=${part.id},seq=${part.seq},type=${part.type}"
                    },
                    targetMediaPart?.id?.toString() ?: "null"
                )
                targetMediaPart
                    ?.let { mediaPart ->
                        navigator.showMedia(mediaPart.id)
                    }
                    ?: message.firstNonMediaPart()?.let { filePart ->
                        if (!filePart.isAudioPart()) {
                            messageRepo.savePart(filePart.id)?.let(navigator::viewFile)
                        }
                    }
                val contacts = mutableListOf<String>()

                val phonePattern = "(\\+\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4,5}"
                val emailPattern = "([a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+\\.[a-zA-Z0-9_-]+)"

                val phoneRegex = Regex(phonePattern)
                val emailRegex = Regex(emailPattern)

                val matches =  emailRegex.findAll(message.getText())+phoneRegex.findAll(message.getText() )
               if (matches.count()>0) {
                   for (match in matches) {
                       val value = match.value
                       contacts.add(value)
                   }
                   view.showContactsDialog(contacts)
               }
            }.autoDisposable(view.scope())
         .subscribe()


        // Media attachment clicks //can delete after delete open manyhill
        view.messageOptionActionIntent
            .filter { action -> action.itemId == R.id.play }
            .map { action ->
                Log.d("QK-MSGOPT", "handler=play selection=${action.messageIds}")
                val messages = action.messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }
                val clickedMessage: Message = messages.first()

                if (clickedMessage.isMms()) {
                    val targetMediaPart = clickedMessage.firstMediaPart()
                    Timber.d(
                        "Compose play action messageId=%d threadId=%d parts=%s targetMediaPart=%s",
                        clickedMessage.id,
                        clickedMessage.threadId,
                        clickedMessage.sortedParts().joinToString { part ->
                            "id=${part.id},seq=${part.seq},type=${part.type}"
                        },
                        targetMediaPart?.id?.toString() ?: "null"
                    )
                    targetMediaPart
                        ?.let { mediaPart ->
                            navigator.showMedia(mediaPart.id)
                        }
                        ?: clickedMessage.firstNonMediaPart()?.let { filePart ->
                            if (!filePart.isAudioPart()) {
                                messageRepo.savePart(filePart.id)?.let(navigator::viewFile)
                            }
                        }
                }
                else {
                    context.nothingToPlay()
                }
            }.autoDisposable(view.scope()).subscribe { view.clearSelection() }

        view.messagePartClickIntent.mapNotNull(messageRepo::getPart)
            .filter { part -> part.isImage() || part.isVideo() }.autoDisposable(view.scope())
            .subscribe { part -> navigator.showMedia(part.id)
                newState { copy(isMMS = true) }
              }


        // Non-media attachment clicks
        view.messagePartClickIntent.mapNotNull(messageRepo::getPart)
            .filter { part -> !part.isImage() && !part.isVideo() && !part.isAudioPart() }.autoDisposable(view.scope())
            .subscribe { part ->
                if (permissionManager.hasStorage()) {
                    messageRepo.savePart(part.id)?.let(navigator::viewFile)
                } else {
                    view.requestStoragePermission()
                }
                newState { copy(isMMS = false) }
            }


        // Update the State when the message selected count changes
        view.messagesSelectedIntent.map { selection -> selection.size }.autoDisposable(view.scope())
            .subscribe { messages ->
                newState {
                    copy(
                        selectedMessages = messages,
                        editingMode = if (messages > 0) false else editingMode
                    )
                }
            }

        // Cancel sending a message
        view.cancelSendingIntent.mapNotNull(messageRepo::getMessage)
            .doOnNext { message ->
                if (message.isSending() && message.date > System.currentTimeMillis()) {
                    view.setDraft(message.getText())
                    attachments.onNext(message.toDraftAttachments())
                }
            }
            .autoDisposable(view.scope())
            .subscribe { message ->
                if (message.isSending() && message.date > System.currentTimeMillis()) {
                    cancelMessage.execute(CancelDelayedMessage.Params(message.id, message.threadId))
                }
            }

        // Set the current conversation
        Observables.combineLatest(
            view.activityVisibleIntent.distinctUntilChanged(),
            conversation.mapNotNull { conversation ->
                conversation.takeIf { it.isValid }?.id
            }.distinctUntilChanged()
        ) { visible, threadId ->
            when (visible) {
                true -> {
                    activeConversationManager.setActiveConversation(threadId)
                    markRead.execute(listOf(threadId))
                }

                false -> activeConversationManager.setActiveConversation(null)
            }
        }.autoDisposable(view.scope()).subscribe()

        // Save draft when the activity goes into the background
        view.activityVisibleIntent.filter { visible -> !visible }
            .withLatestFrom(conversation) { _, conversation -> conversation }
            .mapNotNull { conversation -> conversation.takeIf { it.isValid }?.id }
            .observeOn(Schedulers.io()).withLatestFrom(view.textChangedIntent) { threadId, draft ->
                conversationRepo.saveDraft(threadId, draft.toString())
            }.autoDisposable(view.scope()).subscribe()

        // Open the attachment options
        view.attachIntent.autoDisposable(view.scope())
            .subscribe { newState { copy(attaching = !attaching) } }


        // Attach a photo from camera
        view.cameraIntent.autoDisposable(view.scope()).subscribe {
            if (permissionManager.hasStorage()) {
                newState { copy(attaching = false) }
                view.requestCamera()
            } else {
                view.requestStoragePermission()
            }
        }

        view.optionsItemIntent.filter { it == R.id.take_photo }.autoDisposable(view.scope())
            .subscribe {
                if (permissionManager.hasStorage()) {
                    newState { copy(attaching = false) }
                    view.requestCamera()
                } else {
                    view.requestStoragePermission()
                }
            }

        // Attach a photo from gallery
        view.galleryIntent.doOnNext { newState { copy(attaching = false) } }
            .autoDisposable(view.scope()).subscribe { view.requestGallery() }

        view.optionsItemIntent.filter { it == R.id.attach_second_menu }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.initSecondMenu() }

        view.optionsItemIntent.filter { it == R.id.paste }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.pasteText() }

        view.optionsItemIntent.filter { it == R.id.attach_photo }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.requestGallery() }

        view.optionsItemIntent.filter { it == R.id.photo_menu }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.initPhotoMenu() }

        view.optionsItemIntent.filter { it == R.id.video_menu }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.initVideoMenu() }

        view.optionsItemIntent.filter { it == R.id.audio_menu }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.initAudioMenu() }

        view.optionsItemIntent.filter { it == R.id.more_menu }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.initMoreMenu() }



        // Choose a time to schedule the message
        view.scheduleIntent.doOnNext { newState { copy(attaching = false) } }
            .withLatestFrom(billingManager.upgradeStatus) { _, upgraded -> upgraded }
            .filter { upgraded ->
                upgraded.also { if (!upgraded) view.showQksmsPlusSnackbar(R.string.compose_scheduled_plus) }
            }.autoDisposable(view.scope()).subscribe { view.requestDatePicker() }

        view.optionsItemIntent.filter { it == R.id.schedule_message }
            .doOnNext { newState { copy(attaching = false) } }
            .withLatestFrom(billingManager.upgradeStatus) { _, upgraded -> upgraded }
            .filter { upgraded ->
                upgraded.also { if (!upgraded) view.showQksmsPlusSnackbar(R.string.compose_scheduled_plus) }
            }.autoDisposable(view.scope()).subscribe { view.requestDatePicker() }


        // A photo was selected
        Observable.merge(view.attachmentSelectedIntent.map { uri -> Attachment.Image(uri) },
            view.inputContentIntent.map { inputContent -> Attachment.Image(inputContent = inputContent) })
            .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
            .doOnNext(attachments::onNext).autoDisposable(view.scope())
            .subscribe { newState { copy(attaching = false) } }

        // Set the scheduled time
        view.scheduleSelectedIntent.filter { scheduled ->
            (scheduled > System.currentTimeMillis()).also { future ->
                if (!future) context.makeToast(R.string.compose_scheduled_future)
            }
        }.autoDisposable(view.scope())
            .subscribe { scheduled -> newState { copy(scheduled = scheduled) } }

        // Attach an audio file
//        view.attachAudioIntent
//            .doOnNext { newState { copy(attaching = false) } }
//            .autoDisposable(view.scope())
//            .subscribe { view.requestAudio() }

        view.optionsItemIntent.filter { it == R.id.attach_audio }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.requestAudio() }

        view.optionsItemIntent.filter { it == R.id.record_audio }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.recordAudio() }


        // Attach a video file
//        view.attachVideoIntent
//            .doOnNext { newState { copy(attaching = false) } }
//            .autoDisposable(view.scope())
//            .subscribe { view.requestVideo() }

        view.optionsItemIntent.filter { it == R.id.attach_video }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.requestVideo() }

        view.optionsItemIntent.filter { it == R.id.record_video }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.recordVideo() }

        // Attach a contact
        view.attachContactIntent.doOnNext { newState { copy(attaching = false) } }
            .autoDisposable(view.scope()).subscribe { view.requestContact() }

        view.optionsItemIntent.filter { it == R.id.attach_contact }
            .doOnNext { newState { copy(attaching = false) } }.autoDisposable(view.scope())
            .subscribe { view.requestContact() }

        // Contact was selected for attachment
        view.contactSelectedIntent.map { uri -> Attachment.Contact(getVCard(uri)!!) }
            .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
            .subscribeOn(Schedulers.io()).autoDisposable(view.scope())
            .subscribe(attachments::onNext) { error ->
                context.makeToast(R.string.compose_contact_error)
                Timber.w(error)
            }

        Observable.merge(
            view.audioSelectedIntent.map { uri ->
                Attachment.File(uri)
            },
            view.inputContentIntent.map { inputContent ->
                Attachment.File(inputContent = inputContent)
            })
            .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
            .doOnNext(attachments::onNext)
            .autoDisposable(view.scope())
            .subscribe { newState { copy(attaching = false) } }

        Observable.merge(
            view.videoSelectedIntent.map { uri ->
                Attachment.Video(uri)
            },
            view.inputContentIntent.map { inputContent ->
                Attachment.Video(inputContent = inputContent)
            })
            .withLatestFrom(attachments) { attachment, attachments -> attachments + attachment }
            .doOnNext(attachments::onNext)
            .autoDisposable(view.scope())
            .subscribe { newState { copy(attaching = false) } }

        // Detach a photo
        view.attachmentDeletedIntent.withLatestFrom(attachments) { bitmap, attachments -> attachments.filter { it !== bitmap } }
            .autoDisposable(view.scope()).subscribe { attachments.onNext(it) }

        conversation.map { conversation -> conversation.draft }.distinctUntilChanged()
            .autoDisposable(view.scope()).subscribe { draft ->

                // If text was shared into the conversation, it should take priority over the
                // existing draft
                //
                // TODO: Show dialog warning user about overwriting draft
                if (sharedText.isNotBlank()) {
                    view.setDraft(sharedText)
                } else {
                    view.setDraft(draft)
                }
            }

        // Enable the send button when there is text input into the new message body or there's
        // an attachment, disable otherwise
        Observables.combineLatest(view.textChangedIntent, attachments) { text, attachments ->
            text.isNotBlank() || attachments.isNotEmpty()
        }.autoDisposable(view.scope()).subscribe { canSend -> newState { copy(canSend = canSend) } }

        // Show the remaining character counter when necessary
        view.textChangedIntent.observeOn(Schedulers.computation()).mapNotNull { draft ->
            tryOrNull {
                SmsMessage.calculateLength(
                    draft, prefs.unicode.get()
                )
            }
        }.map { array ->
            val messages = array[0]
            val remaining = array[2]

            when {
                messages <= 1 && remaining > 10 -> ""
                messages <= 1 && remaining <= 10 -> "$remaining"
                else -> "$remaining / $messages"
            }
        }.distinctUntilChanged().autoDisposable(view.scope())
            .subscribe { remaining -> newState { copy(remaining = remaining) } }

        // Cancel the scheduled time
        view.scheduleCancelIntent.autoDisposable(view.scope())
            .subscribe { newState { copy(scheduled = 0) } }

        // Toggle to the next sim slot
        view.changeSimIntent.withLatestFrom(state) { _, state ->
            val subs = subscriptionManager.activeSubscriptionInfoList
            val subIndex =
                subs.indexOfFirst { it.subscriptionId == state.subscription?.subscriptionId }
            val subscription = when {
                subIndex == -1 -> null
                subIndex < subs.size - 1 -> subs[subIndex + 1]
                else -> subs[0]
            }

            if (subscription != null) {
                context.getSystemService<Vibrator>()?.vibrate(40)
                context.makeToast(
                    String.format(
                        context.getString(R.string.compose_sim_changed_toast),
                        subscription.simSlotIndex + 1,
                        subscription.displayName
                    )
                )
            }

            newState { copy(subscription = subscription) }
        }.autoDisposable(view.scope()).subscribe()

        // Send a message when the send button is clicked, and disable editing mode if it's enabled
        view.sendIntent
            .doOnNext {
                Log.d(
                    "QK-COMPOSE",
                    "sendIntent received draftBlank=${view.getDraft().isBlank()}"
                )
            }
            .filter {
                val isDefaultSms = permissionManager.isDefaultSms()
                Log.d("QK-COMPOSE", "sendGate defaultSms=$isDefaultSms")
                if (!isDefaultSms) view.requestDefaultSms()
                isDefaultSms
            }.filter {
                val hasSendSms = permissionManager.hasSendSms()
                Log.d("QK-COMPOSE", "sendGate hasSendSms=$hasSendSms")
                if (!hasSendSms) view.requestSmsPermission()
                hasSendSms
            }
            .map { view.getDraft().toString() }.withLatestFrom(
                state, attachments, conversation, selectedChips
            ) { body, state, attachments, conversation, chips ->
                val subId = state.subscription?.subscriptionId ?: -1
                val currentRecipientAddresses = currentSelectedRecipients.map { recipient -> recipient.address }
                val addresses = dedupeAddresses(when {
                    currentRecipientAddresses.isNotEmpty() -> currentRecipientAddresses
                    state.editingMode && chips.isNotEmpty() -> chips.map { chip -> chip.address }
                    !state.editingMode && conversation.recipients.isNotEmpty() -> conversation.recipients.map { it.address }
                    else -> chips.map { chip -> chip.address }
                })
                Log.d(
                    "QK-COMPOSE",
                    "send addresses=$addresses stateEditing=${state.editingMode} stateChips=${state.selectedChips.map { it.address }} subjectChips=${chips.map { it.address }} currentRecipients=$currentRecipientAddresses conversationId=${conversation.id}"
                )
                if (body.isBlank() && attachments.isEmpty()) {
                    Log.d("QK-COMPOSE", "send ignored emptyBodyAndAttachments addresses=$addresses")
                    return@withLatestFrom
                }
                if (addresses.isEmpty()) {
                    Log.d("QK-COMPOSE", "send ignored emptyAddresses bodyBlank=${body.isBlank()}")
                    return@withLatestFrom
                }
                if (hasOversizedNonImageAttachments(body, attachments)) {
                    context.makeToast("Attachment too large for MMS")
                    return@withLatestFrom
                }
                val delay = when (prefs.sendDelay.get()) {
                    Preferences.SEND_DELAY_SHORT -> 3000
                    Preferences.SEND_DELAY_MEDIUM -> 5000
                    Preferences.SEND_DELAY_LONG -> 10000
                    else -> 0
                }
                val sendAsGroup = !state.editingMode || state.sendAsGroup
                val sendThreadId = threadIdForSend(state, conversation, addresses)
                val showSentConversation = state.editingMode && state.scheduled == 0L
                val onMessageSent = {
                    if (showSentConversation) {
                        showConversationAfterSend(addresses)
                    }
                }

                when {
                    // Scheduling a message
                    state.scheduled != 0L -> {
                        newState { copy(scheduled = 0) }
                        val uris =
                            attachments.mapNotNull { attachment ->
                                    when(attachment) {
                                        is Attachment.Image -> attachment.getUri()
                                        is Attachment.Video -> attachment.getUri()
                                        is Attachment.File -> attachment.getUri()
                                        else -> null
                                    }
                                }.mapNotNull { it.toString() }
                        val params = AddScheduledMessage.Params(
                            state.scheduled, subId, addresses, sendAsGroup, body, uris
                        )
                        addScheduledMessage.execute(params)
                        context.makeToast(R.string.compose_scheduled_toast)
                    }

                    // Sending a group message
                    sendAsGroup -> {
                        sendMessage.execute(
                            SendMessage.Params(
                                subId, sendThreadId, addresses, body, attachments, delay
                            ),
                            onMessageSent
                        )
                    }

                    // Sending a message to an existing conversation with one recipient
                    !state.editingMode && conversation.recipients.size == 1 -> {
                        val address = conversation.recipients.map { it.address }
                        sendMessage.execute(
                            SendMessage.Params(
                                subId, sendThreadId, address, body, attachments, delay
                            ),
                            onMessageSent
                        )
                    }

                    // Create a new conversation with one address
                    addresses.size == 1 -> {
                        sendMessage.execute(
                            SendMessage.Params(subId, sendThreadId, addresses, body, attachments, delay),
                            onMessageSent
                        )
                    }

                    // Send a message to multiple addresses
                    else -> {
                        addresses.forEach { addr ->
                            val threadId = tryOrNull(false) {
                                TelephonyCompat.getOrCreateThreadId(context, addr)
                            } ?: 0
                            val address = listOf(
                                conversationRepo.getConversation(threadId)?.recipients?.firstOrNull()?.address
                                    ?: addr
                            )
                            sendMessage.execute(
                                SendMessage.Params(
                                    subId, threadId, address, body, attachments, delay
                                ),
                                onMessageSent
                            )
                        }
                    }
                }

                view.setDraft("")
                this.attachments.onNext(ArrayList())

                if (state.editingMode) {
                    currentSelectedRecipients = listOf()
                    newState { copy(loading = showSentConversation, selectedChips = listOf()) }
                }
            }.autoDisposable(view.scope()).subscribe()

        // View QKSMS+
        view.viewQksmsPlusIntent.autoDisposable(view.scope())
            .subscribe { navigator.showQksmsPlusActivity("compose_schedule") }


        // Navigate back
        view.optionsItemIntent.filter { it == android.R.id.home }.map { }
            .mergeWith(view.backPressedIntent).withLatestFrom(state) { _, state ->
                when {
                    state.selectedMessages > 0 -> view.clearSelection()
                    else -> newState { copy(hasError = true) }
                }
            }.autoDisposable(view.scope()).subscribe()


    }

    fun onContactSelectionResult(hashmap: HashMap<String, String?>, view: ComposeView) {
        val chips = selectedChips.blockingFirst()
        Log.d("QK-COMPOSE", "chipsSelected result=${hashmap.keys} existing=${chips.map { it.address }} forwardMode=$forwardMode")

        if (hashmap.isEmpty() && chips.isEmpty()) {
            newState { copy(hasError = true) }
            return
        }

        val updatedChips = hashmap.map { (address, lookupKey) ->
            conversationRepo.getRecipients().asSequence()
                .filter { recipient -> recipient.contact?.lookupKey == lookupKey }
                .firstOrNull { recipient ->
                    phoneNumberUtils.compare(recipient.address, address)
                } ?: Recipient(
                address = address, contact = lookupKey?.let(contactRepo::getUnmanagedContact)
            )
        }

        applySelectedChips(updatedChips)
        view.showSelectedRecipients(updatedChips)
        view.showKeyboard()
    }

    fun onSendClicked(view: ComposeView) {
        Log.d(
            "QK-COMPOSE",
            "sendIntent received direct=true draftBlank=${view.getDraft().isBlank()} currentRecipients=${currentSelectedRecipients.map { it.address }}"
        )

        val isDefaultSms = permissionManager.isDefaultSms()
        Log.d("QK-COMPOSE", "sendGate defaultSms=$isDefaultSms")
        if (!isDefaultSms) {
            view.requestDefaultSms()
            return
        }

        val hasSendSms = permissionManager.hasSendSms()
        Log.d("QK-COMPOSE", "sendGate hasSendSms=$hasSendSms")
        if (!hasSendSms) {
            view.requestSmsPermission()
            return
        }

        val body = view.getDraft().toString()
        val state = state.blockingFirst()
        val attachments = attachments.blockingFirst()
        val conversation = conversation.blockingFirst()
        val chips = selectedChips.blockingFirst()
        val subId = state.subscription?.subscriptionId ?: -1
        val currentRecipientAddresses = currentSelectedRecipients.map { it.address }
        val addresses = dedupeAddresses(when {
            currentRecipientAddresses.isNotEmpty() -> currentRecipientAddresses
            state.editingMode && chips.isNotEmpty() -> chips.map { chip -> chip.address }
            conversation.recipients.isNotEmpty() -> conversation.recipients.map { it.address }
            else -> chips.map { chip -> chip.address }
        })
        Log.d(
            "QK-COMPOSE",
            "send addresses=$addresses stateEditing=${state.editingMode} stateChips=${state.selectedChips.map { it.address }} subjectChips=${chips.map { it.address }} currentRecipients=$currentRecipientAddresses conversationId=${conversation.id}"
        )
        if (body.isBlank() && attachments.isEmpty()) {
            Log.d("QK-COMPOSE", "send ignored emptyBodyAndAttachments addresses=$addresses")
            return
        }
        if (addresses.isEmpty()) {
            Log.d("QK-COMPOSE", "send ignored emptyAddresses bodyBlank=${body.isBlank()}")
            return
        }
        if (hasOversizedNonImageAttachments(body, attachments)) {
            context.makeToast("Attachment too large for MMS")
            return
        }

        val delay = when (prefs.sendDelay.get()) {
            Preferences.SEND_DELAY_SHORT -> 3000
            Preferences.SEND_DELAY_MEDIUM -> 5000
            Preferences.SEND_DELAY_LONG -> 10000
            else -> 0
        }
        val sendAsGroup = !state.editingMode || state.sendAsGroup
        val sendThreadId = threadIdForSend(state, conversation, addresses)
        val showSentConversation = state.editingMode && state.scheduled == 0L
        val onMessageSent = {
            if (showSentConversation) {
                showConversationAfterSend(addresses)
            }
        }

        when {
            state.scheduled != 0L -> {
                newState { copy(scheduled = 0) }
                val uris = attachments.mapNotNull { attachment ->
                    when (attachment) {
                        is Attachment.Image -> attachment.getUri()
                        is Attachment.Video -> attachment.getUri()
                        is Attachment.File -> attachment.getUri()
                        else -> null
                    }
                }.mapNotNull { it.toString() }
                val params = AddScheduledMessage.Params(
                    state.scheduled, subId, addresses, sendAsGroup, body, uris
                )
                addScheduledMessage.execute(params)
                context.makeToast(R.string.compose_scheduled_toast)
            }

            sendAsGroup -> {
                sendMessage.execute(
                    SendMessage.Params(
                        subId, sendThreadId, addresses, body, attachments, delay
                    ),
                    onMessageSent
                )
            }

            !state.editingMode && conversation.recipients.size == 1 -> {
                val address = conversation.recipients.map { it.address }
                sendMessage.execute(
                    SendMessage.Params(
                        subId, sendThreadId, address, body, attachments, delay
                    ),
                    onMessageSent
                )
            }

            addresses.size == 1 -> {
                sendMessage.execute(
                    SendMessage.Params(subId, sendThreadId, addresses, body, attachments, delay),
                    onMessageSent
                )
            }

            else -> {
                addresses.forEach { addr ->
                    val threadId = tryOrNull(false) {
                        TelephonyCompat.getOrCreateThreadId(context, addr)
                    } ?: 0
                    val address = listOf(
                        conversationRepo.getConversation(threadId)?.recipients?.firstOrNull()?.address
                            ?: addr
                    )
                    sendMessage.execute(
                        SendMessage.Params(
                            subId, threadId, address, body, attachments, delay
                        ),
                        onMessageSent
                    )
                }
            }
        }

        view.setDraft("")
        this.attachments.onNext(ArrayList())
        currentSelectedRecipients = listOf()
        view.showSelectedRecipients(listOf())

        if (state.editingMode) {
            newState { copy(loading = showSentConversation, selectedChips = listOf()) }
        }
    }

    private fun hasOversizedNonImageAttachments(body: String, attachments: List<Attachment>): Boolean {
        if (attachments.isEmpty()) return false

        var bytes = body.toByteArray().size.toLong()
        attachments.forEach { attachment ->
            bytes += when (attachment) {
                is Attachment.Contact -> attachment.vCard.toByteArray().size.toLong()
                is Attachment.File -> attachment.getSize(context) ?: attachment.getUri()?.let(::getContentSize) ?: 0L
                is Attachment.Video -> attachment.getUri()?.let(::getContentSize) ?: 0L
                is Attachment.Image -> 0L
            }
        }

        return bytes > mmsPayloadBudgetBytes()
    }

    private fun mmsPayloadBudgetBytes(): Long {
        val preferredBytes = when (val selectedSize = prefs.mmsSize.get()) {
            -1 -> DEFAULT_MMS_PAYLOAD_BYTES
            0 -> SAFE_MMS_PAYLOAD_BYTES
            else -> selectedSize * 1024L
        }

        return (minOf(preferredBytes, SAFE_MMS_PAYLOAD_BYTES) * MMS_COMPRESSION_HEADROOM).toLong()
    }

    private fun getContentSize(uri: Uri): Long? {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }

        return tryOrNull(false) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > SAFE_MMS_PAYLOAD_BYTES) break
                }
                total
            }
        }
    }

    fun onMessageOptionAction(action: ComposeView.MessageOptionAction, view: ComposeView) {
        when (action.itemId) {
            R.id.copy -> {
                Log.d("QK-MSGOPT", "handler=copy selection=${action.messageIds}")
                val messages = action.messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }
                val text = when (messages.size) {
                    1 -> messages.first().getText()
                    else -> messages.foldIndexed("") { index, acc, message ->
                        when {
                            index == 0 -> message.getText()
                            messages[index - 1].compareSender(message) -> "$acc\n${message.getText()}"
                            else -> "$acc\n\n${message.getText()}"
                        }
                    }
                }
                ClipboardUtils.copy(context, text)
                view.clearSelection()
            }
            R.id.save -> {
                Log.d("QK-MSGOPT", "handler=save selection=${action.messageIds}")
                val messages = action.messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }
                val clickedMessage = messages.firstOrNull()
                var savedAny = false
                if (clickedMessage?.isMms() == true) {
                    for (part in clickedMessage.parts) {
                        part?.let { p ->
                            when {
                                p.isVideo() -> {
                                    context.saveVideoToGallery(p.getUri())
                                    savedAny = true
                                }
                                p.isImage() -> {
                                    context.saveImageToGallery(p.getUri())
                                    savedAny = true
                                }
                                p.isAudioPart() -> {
                                    messageRepo.savePart(p.id)?.let { file ->
                                        savedAny = true
                                        context.saveFileToMusic(file)
                                    }
                                }
                                !p.isText() && !p.isSmil() -> {
                                    messageRepo.savePart(p.id)?.let { file ->
                                        savedAny = true
                                        context.saveFileToDownloads(file)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                if (!savedAny) {
                    context.nothingToSave()
                } else {
                    context.makeToast("Saved")
                }
                view.clearSelection()
            }
            R.id.details -> {
                Log.d("QK-MSGOPT", "handler=details selection=${action.messageIds}")
                action.messageIds.firstOrNull()
                    ?.let(messageRepo::getMessage)
                    ?.let(messageDetailsFormatter::format)
                    ?.let(view::showDetails)
                view.clearSelection()
            }
            R.id.delete -> {
                Log.d("QK-MSGOPT", "handler=delete selection=${action.messageIds}")
                if (!permissionManager.isDefaultSms()) {
                    view.requestDefaultSms()
                    return
                }
                val conversationId = conversation.blockingFirst().id
                deleteMessages.execute(DeleteMessages.Params(action.messageIds, conversationId))
                view.clearSelection()
            }
            R.id.forward -> {
                Log.d("QK-MSGOPT", "handler=forward selection=${action.messageIds}")
                action.messageIds.firstOrNull()
                    ?.let(messageRepo::getMessage)
                    ?.let { message ->
                        navigator.showForward(message.getText(), message.toDraftAttachments())
                    }
                view.clearSelection()
            }
            R.id.play -> {
                Log.d("QK-MSGOPT", "handler=play selection=${action.messageIds}")
                val clickedMessage = action.messageIds.firstOrNull()?.let(messageRepo::getMessage)
                if (clickedMessage?.isMms() == true) {
                    val targetMediaPart = clickedMessage.firstMediaPart()
                    Timber.d(
                        "Compose play action messageId=%d threadId=%d parts=%s targetMediaPart=%s",
                        clickedMessage.id,
                        clickedMessage.threadId,
                        clickedMessage.sortedParts().joinToString { part ->
                            "id=${part.id},seq=${part.seq},type=${part.type}"
                        },
                        targetMediaPart?.id?.toString() ?: "null"
                    )
                    targetMediaPart
                        ?.let { mediaPart -> navigator.showMedia(mediaPart.id) }
                        ?: clickedMessage.firstNonMediaPart()?.let { filePart ->
                            if (!filePart.isAudioPart()) {
                                messageRepo.savePart(filePart.id)?.let(navigator::viewFile)
                            }
                        }
                } else {
                    context.nothingToPlay()
                }
                view.clearSelection()
            }
        }
    }

    private fun getVCard(contactData: Uri): String? {
        val lookupKey =
            context.contentResolver.query(contactData, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY))
            }

        val vCardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
        return context.contentResolver.openAssetFileDescriptor(vCardUri, "r")?.createInputStream()
            ?.readBytes()?.let { bytes -> String(bytes) }
    }

}
