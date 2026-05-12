package com.moez.QKSMS.feature.main

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.moez.QKSMS.R


class MainOptionsDialog constructor(
    context: Context,
    private val listener: OnMainOptionsDialogItemClickListener,
    private val isArchive: Boolean,
    private val markMuted: Boolean,
    private val markPinned: Boolean,
    private val markRead: Boolean,
    private val multiOnly: Boolean,
    private val showSelectMultiple: Boolean
) : Dialog(context) {

    lateinit var listView: ListView
    var isClickDismissed=false
    private lateinit var actions: List<Int>

    companion object {
        private const val ACTION_ARCHIVE = 0
        private const val ACTION_DELETE = 1
        private const val ACTION_ADD_CONTACT = 2
        private const val ACTION_MUTE = 3
        private const val ACTION_PIN = 4
        private const val ACTION_MARK_UNREAD = 5
        private const val ACTION_BLOCK = 6
        private const val ACTION_SELECT_MULTIPLE = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)
        findViewById<TextView>(R.id.options_dialog_title)?.setText(R.string.thread_options)
        var archiveOption = context.getString(R.string.main_menu_archive)
        var muteOption = context.getString(R.string.main_menu_mute)
        var pinOptions = context.getString(R.string.main_menu_pin)
        var readOption = context.getString(R.string.main_menu_read)
        if (isArchive) { archiveOption = context.getString(R.string.main_menu_unarchive) }
        if (markMuted) { muteOption = context.getString(R.string.main_menu_unmute) }
        if (!markPinned) { pinOptions = context.getString(R.string.main_menu_unpin) }
        if (!markRead) { readOption = context.getString(R.string.main_menu_unread) }

        val actionItems = mutableListOf<Pair<Int, String>>()

        actionItems += ACTION_DELETE to context.getString(R.string.main_menu_delete)
        if (!multiOnly) {
            actionItems += ACTION_MUTE to muteOption
        }
        actionItems += ACTION_ARCHIVE to archiveOption
        actionItems += ACTION_PIN to pinOptions
        if (!multiOnly) {
            actionItems += ACTION_ADD_CONTACT to context.getString(R.string.main_menu_add_contact)
        }
        actionItems += ACTION_MARK_UNREAD to readOption
        actionItems += ACTION_BLOCK to context.getString(R.string.main_menu_block)

        if (showSelectMultiple) {
            actionItems += ACTION_SELECT_MULTIPLE to context.getString(R.string.main_menu_select_multiple)
        }
        actions = actionItems.map { it.first }

        val adapter: ArrayAdapter<String> =
            ArrayAdapter(context, R.layout.options_dialog_list_item, actionItems.map { it.second })
        listView.adapter = adapter

        initClicks()
    }




    private fun initClicks() {
        listView.setOnItemClickListener { parent, view, position, id ->
            when (actions[position]) {
                ACTION_ARCHIVE -> listener.onArchiveMessageClicked(isArchive)
                ACTION_DELETE -> listener.onDeleteMessagesClicked()
                ACTION_ADD_CONTACT -> listener.onAddToContactsClicked()
                ACTION_MUTE -> listener.onMuteConversationClicked()
                ACTION_PIN -> listener.onPinToTopClicked()
                ACTION_MARK_UNREAD -> listener.onMarkReadClicked()
                ACTION_BLOCK -> listener.onBlockClicked()
                ACTION_SELECT_MULTIPLE -> listener.onSelectMultipleClicked()
            }
            isClickDismissed=true
            dismiss()
        }
    }


    interface OnMainOptionsDialogItemClickListener {
        fun onArchiveMessageClicked(isArchive: Boolean)
        //fun onUnarchiveMessageClicked()
        fun onDeleteMessagesClicked()
        fun onAddToContactsClicked()
        fun onMuteConversationClicked()
        fun onPinToTopClicked()
//        fun onUnpinToTopClicked()
        fun onMarkReadClicked()
        fun onBlockClicked()
        fun onSelectMultipleClicked()
    }
}
