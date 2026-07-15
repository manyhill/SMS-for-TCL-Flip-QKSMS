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
package com.moez.QKSMS.feature.main
import android.net.Uri
import com.moez.QKSMS.feature.compose.ComposeActivity
import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.androidxcompat.drawerOpen
import com.moez.QKSMS.common.base.QkThemedActivity
import com.moez.QKSMS.common.util.extensions.*
import com.moez.QKSMS.feature.blocking.BlockingDialog
import com.moez.QKSMS.feature.changelog.ChangelogDialog
import com.moez.QKSMS.feature.conversations.ConversationItemTouchCallback
import com.moez.QKSMS.feature.conversations.ConversationsAdapter
import com.moez.QKSMS.interactor.MarkPinned
import com.moez.QKSMS.interactor.MarkUnpinned
import com.moez.QKSMS.manager.NotificationManager
import com.moez.QKSMS.manager.ChangelogManager
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.SyncRepository
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.drawer_view.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.main_permission_hint.*
import kotlinx.android.synthetic.main.main_syncing.*
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    @Inject
    lateinit var blockingDialog: BlockingDialog

    @Inject
    lateinit var disposables: CompositeDisposable

    @Inject
    lateinit var navigator: Navigator

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var mainConversationRepo: ConversationRepository

    @Inject
    lateinit var markPinned: MarkPinned

    @Inject
    lateinit var markUnpinned: MarkUnpinned

    private val composeClicks: Subject<Unit> = PublishSubject.create()
    override val composeIntent: Observable<Unit> = composeClicks


    @Inject
    lateinit var conversationsAdapter: ConversationsAdapter

    @Inject
    lateinit var drawerBadgesExperiment: DrawerBadgesExperiment

    @Inject
    lateinit var searchAdapter: SearchAdapter

    @Inject
    lateinit var itemTouchCallback: ConversationItemTouchCallback

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { toolbarSearch.textChanges() }

    override val drawerOpenIntent: Observable<Boolean> by lazy {
        drawerLayout.drawerOpen(Gravity.START).doOnNext { dismissKeyboard() }
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(
            listOf(backPressedSubject,
                inbox.clicks().map { NavItem.INBOX },
                archived.clicks().map { NavItem.ARCHIVED },
                backup.clicks().map { NavItem.BACKUP },
                scheduled.clicks().map { NavItem.SCHEDULED },
                blocking.clicks().map { NavItem.BLOCKING },
                settings.clicks().map { NavItem.SETTINGS },
                plus.clicks().map { NavItem.PLUS },
                help.clicks().map { NavItem.HELP },
                invite.clicks().map { NavItem.INVITE })
        )
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val conversationOptionActionIntent: Subject<Pair<Int, List<Long>>> = PublishSubject.create()
    override val muteConversationIntent: Subject<Long> = PublishSubject.create()
    override val pinConversationIntent: Subject<Long> = PublishSubject.create()
//    override val plusBannerIntent by lazy { plusBanner.clicks() }
//    override val dismissRatingIntent by lazy { rateDismiss.clicks() }
//    override val rateIntent by lazy { rateOkay.clicks() }
    private val conversationSelectionSnapshots: Subject<List<Long>> = BehaviorSubject.createDefault(listOf())
    override val conversationsSelectedIntent by lazy {
        Observable.merge(conversationsAdapter.selectionChanges, conversationSelectionSnapshots)
    }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy {
        ViewModelProviders.of(
            this, viewModelFactory
        )[MainViewModel::class.java]
    }
    private val toggle by lazy {
        ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.main_drawer_open_cd, 0
        )
    }
    private fun handleExternalSmsIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SENDTO && intent?.action != Intent.ACTION_VIEW) return false

        val data = intent.data ?: return false
        val recipients = getRecipients(data)
        if (recipients.isBlank()) return false

        val composeIntent = Intent(this, ComposeActivity::class.java).apply {
            setData(data)
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

        startActivity(composeIntent)
        return true
    }
    private fun getRecipients(uri: Uri): String {
        val base = Uri.decode(uri.schemeSpecificPart ?: "")
        val position = base.indexOf('?')
        return if (position == -1) base else base.substring(0, position)
    }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingProgress, "progress", 0, 0) }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val snackbar by lazy { findViewById<View>(R.id.snackbar) }
    private val syncing by lazy { findViewById<View>(R.id.syncing) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()
    private var selectedConversationCount = 0
    private var conversationOptionsShownForSelection = false
    private var conversationMultipleSelectionMode = false
    private var currentOptionsIsArchive = false
    private var currentOptionsMarkMuted = false
    private var currentOptionsMarkPinned = true
    private var currentOptionsMarkRead = true
    private var lastFocusedConversationId: Long? = null
    private var pendingRestoreConversationFocus = false
    private var pendingShowOptionsForSelection = false
    private var lastSelectedConversationToastCount = 0
    private var selectedConversationToast: Toast? = null
    private var didBindMainViewModel = false
    private var isInboxPage = true
    private var isArchivedPage = false
    private var isSearchingPage = false
    private val delayedConversationRefresh = Runnable {
        if (recyclerView.adapter === conversationsAdapter) {
            conversationsAdapter.notifyDataSetChanged()
        }
    }

    private fun ensureMainFocus() {
        if (!hasWindowFocus()) return
        if (currentFocus != null) return
        if (!recyclerView.isShown) return

        recyclerView.post {
            if (!hasWindowFocus()) return@post
            if (currentFocus != null) return@post
            if (restoreLastFocusedConversation()) return@post

            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
            val firstVisibleRow = firstVisiblePosition
                ?.let(layoutManager::findViewByPosition)
                ?.takeIf { it.isShown && it.visibility == View.VISIBLE }

            when {
                firstVisibleRow?.requestFocus() == true -> Unit
                !recyclerView.isFocused -> recyclerView.requestFocus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)

        if (handleExternalSmsIntent(intent)) {
            finish()
            return
        }

        setContentView(R.layout.main_activity)

        (snackbar as? ViewStub)?.setOnInflateListener { _, _ ->
            snackbarButton.clicks().autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                .subscribe(snackbarButtonIntent)
        }

        (syncing as? ViewStub)?.setOnInflateListener { _, _ ->
            syncingProgress?.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            syncingProgress?.indeterminateTintList =
                ColorStateList.valueOf(theme.blockingFirst().theme)
        }

        toggle.syncState()
        toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        initClicks()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        recyclerView.setHasFixedSize(true)
        recyclerView.isFocusable = true
        recyclerView.isFocusableInTouchMode = true
        recyclerView.itemAnimator = null
        recyclerView.setItemViewCacheSize(6)
        (recyclerView.layoutManager as? LinearLayoutManager)?.isItemPrefetchEnabled = false

        toolbar.navigationIcon = null

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(recyclerView)
        conversationsAdapter.rowFocused.autoDisposable(scope()).subscribe { conversationId ->
            lastFocusedConversationId = conversationId
        }

        conversationsAdapter.rowLongClicks.autoDisposable(scope()).subscribe { conversationId ->
            conversationMultipleSelectionMode = false
            conversationsAdapter.multipleSelectionEnabled = false
            selectedConversationCount = 1
            currentOptionsMarkMuted = notificationManager.isConversationMuted(conversationId)

            recyclerView.post {
                val resolvedMutedState = resolveCurrentMutedState(currentOptionsMarkMuted)
                val resolvedPinnedState = resolveCurrentPinnedState(currentOptionsMarkPinned)
                val resolvedReadState = resolveCurrentReadState(currentOptionsMarkRead)

                showOptionsDialog(
                    currentOptionsIsArchive,
                    resolvedMutedState,
                    resolvedPinnedState,
                    resolvedReadState,
                    multiOnly = false,
                    showSelectMultiple = true
                )
            }
        }

        conversationsAdapter.selectionChanges.autoDisposable(scope()).subscribe { selection ->
            if (conversationMultipleSelectionMode) {
                selectedConversationCount = selection.size
                if (selection.isEmpty()) {
                    lastSelectedConversationToastCount = 0
                } else {
                    showSelectedConversationToast(selection.size)
                }
            }
        }
        drawer.clicks().autoDisposable(scope()).subscribe()

        theme.autoDisposable(scope()).subscribe { theme ->
            val states = arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(-android.R.attr.state_activated)
            )

            ColorStateList(
                states,
                intArrayOf(
                    theme.theme,
                    resolveThemeColor(android.R.attr.textColorSecondary)
                )
            ).let { tintList ->
                inboxIcon.imageTintList = tintList
                archivedIcon.imageTintList = tintList
            }

            listOf(plusBadge1, plusBadge2).forEach { badge ->
                badge.setBackgroundTint(theme.theme)
                badge.setTextColor(theme.textPrimary)
            }
            syncingProgress?.progressTintList = ColorStateList.valueOf(theme.theme)
            syncingProgress?.indeterminateTintList = ColorStateList.valueOf(theme.theme)
            linearLayout_compose.setBackgroundTint(theme.theme)
        }

        if (Build.VERSION.SDK_INT <= 22) {
            toolbarSearch.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }

        bindMainViewModelIfNeeded()

        drawerLayout.post {
            if (isFinishing || isDestroyed) return@post

            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                activityResumedIntent.onNext(true)
            }

            ensureMainFocus()
        }
    }

    private fun bindMainViewModelIfNeeded() {
        if (didBindMainViewModel) return

        didBindMainViewModel = true
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (handleExternalSmsIntent(intent)) {
            finish()
            return
        }

        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markMuted = when (state.page) {
            is Inbox -> state.page.markMuted
            is Archived -> state.page.markMuted
            else -> false
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }
        currentOptionsIsArchive = state.page is Archived
        currentOptionsMarkMuted = markMuted
        currentOptionsMarkPinned = markPinned
        currentOptionsMarkRead = markRead
        isInboxPage = state.page is Inbox
        isArchivedPage = state.page is Archived
        isSearchingPage = state.page is Searching
        selectedConversationCount = selectedConversations
        if (selectedConversations == 0) {
            conversationOptionsShownForSelection = false
            conversationMultipleSelectionMode = false
            pendingShowOptionsForSelection = false
            lastSelectedConversationToastCount = 0
        } else if (selectedConversations > 1) {
            conversationMultipleSelectionMode = true
        }
        conversationsAdapter.multipleSelectionEnabled = conversationMultipleSelectionMode
        val showSelectionChrome = selectedConversations > 1 || conversationMultipleSelectionMode

        toolbarSearch.setVisible(state.page is Inbox && !showSelectionChrome || state.page is Searching)
        toolbarTitle.setVisible(toolbarSearch.visibility != View.VISIBLE)
//        toolbarSearch.setOnKeyListener { v, keyCode, event ->
//            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ) {
//                recyclerView.requestFocus()
//                true
//            } else {
//                false
//            }}
        toolbar.menu.clear()

        listOf(plusBadge1, plusBadge2).forEach { badge ->
            badge.isVisible = drawerBadgesExperiment.variant && !state.upgraded
        }
        //plus.isVisible = state.upgraded
//        plusBanner.isVisible = !state.upgraded
//        rateLayout.setVisible(state.showRating)

//        compose.setVisible(state.page is Inbox || state.page is Archived)
        linearLayout_compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView =
            empty.takeIf { state.page is Inbox || state.page is Archived }
        searchAdapter.emptyView = empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(showSelectionChrome)
                title = if (showSelectionChrome) {
                    getString(R.string.main_title_selected, state.page.selected)
                } else {
                    getString(R.string.app_name)
                }
                if (recyclerView.adapter !== conversationsAdapter) recyclerView.adapter =
                    conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(recyclerView)
                empty.setText(R.string.inbox_empty_text)
            }

            is Searching -> {
                showBackButton(true)
                if (recyclerView.adapter !== searchAdapter) recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(showSelectionChrome)

                title = if (showSelectionChrome) {
                    getString(R.string.main_title_selected, state.page.selected)
                } else {
                    getString(R.string.title_archived)
                }

                if (recyclerView.adapter !== conversationsAdapter) recyclerView.adapter =
                    conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                empty.setText(R.string.archived_empty_text)
            }
        }

        inbox.isActivated = state.page is Inbox
        archived.isActivated = state.page is Archived
        if (drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (!drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen) {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncing.isVisible = false
                snackbar.isVisible =
                    !state.defaultSms || !state.smsPermission || !state.contactPermission
            }

            is SyncRepository.SyncProgress.Running -> {
                syncing.isVisible = true
                syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(
                        syncingProgress.progress, state.syncing.progress
                    )
                }.start()
                syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbar.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarTitle?.setText(R.string.main_default_sms_title)
                snackbarMessage?.setText(R.string.main_default_sms_message)
                snackbarButton?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_sms)
                snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_contacts)
                snackbarButton?.setText(R.string.main_permission_allow)
            }
        }

        ensureMainFocus()
    }

    override fun onResume() {
        super.onResume()
        activityResumedIntent.onNext(true)
        refreshConversationListState()
        if (pendingRestoreConversationFocus) {
            pendingRestoreConversationFocus = false
            recyclerView.post {
                if (!restoreLastFocusedConversation()) {
                    ensureMainFocus()
                }
            }
        } else {
            ensureMainFocus()
        }
    }

    override fun onPause() {
        pendingRestoreConversationFocus = rememberFocusedConversationForRestore()
        recyclerView.removeCallbacks(delayedConversationRefresh)
        clearSelection()
        super.onPause()
        activityResumedIntent.onNext(false)
    }

    override fun onDestroy() {
        recyclerView.removeCallbacks(delayedConversationRefresh)
        super.onDestroy()
        disposables.dispose()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConversationListState(delayed = true)
            ensureMainFocus()
        }
    }

    private fun refreshConversationListState(delayed: Boolean = false) {
        if (recyclerView.adapter !== conversationsAdapter) return

        conversationsAdapter.notifyDataSetChanged()

        if (delayed) {
            recyclerView.removeCallbacks(delayedConversationRefresh)
            recyclerView.postDelayed(delayedConversationRefresh, 250)
        }
    }

    override fun showBackButton(show: Boolean) {
        toggle.onDrawerSlide(drawer, if (show) 1f else 0f)
        toggle.drawerArrowDrawable.color = when (show) {
            true -> resolveThemeColor(android.R.attr.textColorSecondary)
            false -> resolveThemeColor(android.R.attr.textColorPrimary)
        }
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
            ), 0
        )
    }

    override fun clearSearch() {
        dismissKeyboard()
        toolbarSearch.text = null
    }

    override fun clearSelection() {
        resetConversationSelection()
    }

    override fun themeChanged() {
        recyclerView.scrapViews()
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(this).setTitle(R.string.dialog_delete_title)
            .setMessage(resources.getQuantityString(R.plurals.dialog_delete_message, count, count))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                confirmDeleteIntent.onNext(
                    conversations
                )
            }.setNegativeButton(R.string.button_cancel, null).show()
    }

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) {
        changelogDialog.show(changelog)
    }

    override fun showArchivedSnackbar() {
        Snackbar.make(drawerLayout, R.string.toast_archived, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        emitOptionsItem(item.itemId)
        return true
    }

    override fun onBackPressed() {
        if (conversationMultipleSelectionMode || selectedConversationCount > 0) {
            clearSelection()
            ensureMainFocus()
            return
        }

        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        if (isInboxPage && !isArchivedPage && !isSearchingPage) {
            finish()
            return
        }

        backPressedSubject.onNext(NavItem.BACK)
    }

//    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
//        //Toast.makeText(this,"focus: "+ currentFocus.toString(), Toast.LENGTH_LONG).show()
//        return when (event?.scanCode) {
//            139 -> {
//                compose.performClick()
//                true
//            }
//            48 -> {
//                openOptions()
//                true
//            }
//            else -> super.onKeyUp(keyCode, event)
//        }
//    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        //Toast.makeText(this,"focus: "+ currentFocus.toString(), Toast.LENGTH_LONG).show()
        return when (event?.keyCode ?: keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT, 1 -> {
                compose.performClick()
                true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT, 2 -> {

                openOptions()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun openOptions() {

        if (selectedConversationCount > 0) {
            val resolvedMutedState = resolveCurrentMutedState(currentOptionsMarkMuted)
            val resolvedPinnedState = resolveCurrentPinnedState(currentOptionsMarkPinned)
            val resolvedReadState = resolveCurrentReadState(currentOptionsMarkRead)

            showOptionsDialog(
                currentOptionsIsArchive,
                resolvedMutedState,
                resolvedPinnedState,
                resolvedReadState,
                multiOnly = conversationMultipleSelectionMode || selectedConversationCount > 1,
                showSelectMultiple = selectedConversationCount == 1 && !conversationMultipleSelectionMode
            )
            return
        }

        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (!drawerLayout.isDrawerVisible(GravityCompat.START)) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }
    private fun initClicks() {
        compose.setOnClickListener {
            launchComposeDirect()
        }
        options.setOnClickListener {
            openOptions()
        }
    }

    private fun launchComposeDirect() {
        val adapterSelection = conversationsAdapter.getSelection().toList()

        if (selectedConversationCount > 0 || adapterSelection.isNotEmpty()) {
            resetConversationSelection()
        }

        navigator.showCompose()
    }

    private fun resetConversationSelection() {
        conversationMultipleSelectionMode = false
        pendingShowOptionsForSelection = false
        selectedConversationCount = 0
        lastSelectedConversationToastCount = 0
        conversationsAdapter.multipleSelectionEnabled = false
        conversationsAdapter.clearSelection()
    }

    private fun showSelectedConversationToast(count: Int) {
        lastSelectedConversationToastCount = count
        selectedConversationToast?.cancel()
        selectedConversationToast = Toast.makeText(
            this,
            resources.getQuantityString(R.plurals.conversations_selected_toast, count, count),
            Toast.LENGTH_SHORT
        ).also { toast -> toast.show() }
    }

    private fun resolveCurrentMutedState(defaultValue: Boolean): Boolean {
        val selectedThreadId = conversationsAdapter.getSelection().singleOrNull() ?: return defaultValue
        return notificationManager.isConversationMuted(selectedThreadId)
    }

    private fun resolveCurrentPinnedState(defaultValue: Boolean): Boolean {
        val selectedThreadId = conversationsAdapter.getSelection().singleOrNull() ?: return defaultValue
        val position = conversationsAdapter.findPositionById(selectedThreadId)
        val conversation = conversationsAdapter.getItem(position) ?: return defaultValue
        return !conversation.pinned
    }

    private fun resolveCurrentReadState(defaultValue: Boolean): Boolean {
        val selectedThreadId = conversationsAdapter.getSelection().singleOrNull() ?: return defaultValue
        val position = conversationsAdapter.findPositionById(selectedThreadId)
        val conversation = conversationsAdapter.getItem(position) ?: return defaultValue
        return conversation.unread
    }

    private fun toggleConversationMute(threadId: Long) {
        val mutedBefore = notificationManager.isConversationMuted(threadId)

        if (mutedBefore) {
            notificationManager.unmuteConversation(threadId)
        } else {
            notificationManager.muteConversation(threadId)
        }

        val mutedAfter = notificationManager.isConversationMuted(threadId)

        currentOptionsMarkMuted = mutedAfter
        conversationsAdapter.notifyDataSetChanged()
    }

    private fun toggleConversationPin(threadId: Long) {
        val pinnedBefore = mainConversationRepo.getConversation(threadId)?.pinned ?: false

        if (pinnedBefore) {
            markUnpinned.execute(listOf(threadId)) {
                refreshPinnedConversationAfterToggle(threadId, pinnedBefore)
            }
        } else {
            markPinned.execute(listOf(threadId)) {
                refreshPinnedConversationAfterToggle(threadId, pinnedBefore)
            }
        }
    }

    private fun refreshPinnedConversationAfterToggle(threadId: Long, pinnedBefore: Boolean) {
        val pinnedAfter = mainConversationRepo.getConversation(threadId)?.pinned ?: pinnedBefore

        currentOptionsMarkPinned = !pinnedAfter
        conversationsAdapter.updateData(mainConversationRepo.getConversations(currentOptionsIsArchive))
        recyclerView.postDelayed(delayedConversationRefresh, 80)
    }

    private fun emitOptionsItem(itemId: Int) {
        conversationSelectionSnapshots.onNext(conversationsAdapter.getSelection().toList())
        optionsItemIntent.onNext(itemId)
    }

    private fun emitConversationOptionAction(itemId: Int) {
        val selection = conversationsAdapter.getSelection().toList()
        conversationSelectionSnapshots.onNext(selection)
        conversationOptionActionIntent.onNext(itemId to selection)
    }

    private fun rememberFocusedConversationForRestore(): Boolean {
        val focusedView = currentFocus ?: return false
        val holder = recyclerView.findContainingViewHolder(focusedView) ?: return false
        val position = holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return false
        val conversation = conversationsAdapter.getItem(position) ?: return false
        lastFocusedConversationId = conversation.id
        return true
    }

    private fun restoreLastFocusedConversation(): Boolean {
        val conversationId = lastFocusedConversationId ?: return false
        val position = conversationsAdapter.findPositionById(conversationId)
            .takeIf { it != -1 }
            ?: return false
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val existingView = layoutManager.findViewByPosition(position)

        if (existingView?.isShown == true) {
            if (!existingView.isFocused) {
                existingView.requestFocus()
            }
            return true
        }

        layoutManager.scrollToPositionWithOffset(position, 0)
        recyclerView.post {
            layoutManager.findViewByPosition(position)
                ?.takeIf { it.isShown && !it.isFocused }
                ?.requestFocus()
        }
        return true
    }

    private fun showOptionsDialog(
        isArchive: Boolean,
        markMuted: Boolean,
        markPinned: Boolean,
        markRead: Boolean,
        multiOnly: Boolean = conversationMultipleSelectionMode || selectedConversationCount > 1,
        showSelectMultiple: Boolean = selectedConversationCount == 1 && !conversationMultipleSelectionMode
    ) {
        val listener = object : MainOptionsDialog.OnMainOptionsDialogItemClickListener {
            override fun onArchiveMessageClicked(isArchive: Boolean) {
                emitConversationOptionAction(R.id.archive)
            }

//            override fun onUnarchiveMessageClicked() {
//                optionsItemIntent.onNext(R.id.unarchive)
//            }

            override fun onDeleteMessagesClicked() {
                emitConversationOptionAction(R.id.delete)
            }

            override fun onAddToContactsClicked() {
                emitConversationOptionAction(R.id.add)
            }

            override fun onMuteConversationClicked() {
                val threadId = conversationsAdapter.getSelection().singleOrNull()
                threadId?.let(::toggleConversationMute)
                if (!conversationMultipleSelectionMode) {
                    clearSelection()
                }
            }

            override fun onPinToTopClicked() {
                val threadId = conversationsAdapter.getSelection().singleOrNull()
                threadId?.let(::toggleConversationPin)
                if (!conversationMultipleSelectionMode) {
                    clearSelection()
                }
            }
//            override fun onUnpinToTopClicked() {
//                optionsItemIntent.onNext(R.id.unpin)
//            }

            override fun onMarkReadClicked() {
                if (markRead) {
                    emitConversationOptionAction(R.id.read)
                } else {
                    emitConversationOptionAction(R.id.unread)
                }
            }

            override fun onBlockClicked() {
                emitConversationOptionAction(R.id.block)
            }

            override fun onSelectMultipleClicked() {
                conversationMultipleSelectionMode = true
                conversationsAdapter.multipleSelectionEnabled = true
                selectedConversationCount.takeIf { it > 0 }?.let(::showSelectedConversationToast)
                ensureMainFocus()
            }

        }

        val dialog = MainOptionsDialog(
            this@MainActivity,
            listener,
            isArchive,
            markMuted,
            markPinned,
            markRead,
            multiOnly,
            showSelectMultiple
        )
        val clearSingleSelectionOnDismiss = showSelectMultiple && !multiOnly
        dialog.setOnDismissListener {
            if (clearSingleSelectionOnDismiss && !dialog.isClickDismissed) {
                clearSelection()
            }
        }

        dialog.show()

    }

}
