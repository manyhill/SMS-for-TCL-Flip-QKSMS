package com.moez.QKSMS.feature.main

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import com.moez.QKSMS.R
import com.moez.QKSMS.common.util.extensions.makeToast

class MainOptionsDialog constructor(
    context: Context,
    private val listener: OnMainOptionsDialogItemClickListener,
    private val isArchive: Boolean
) : Dialog(context) {

    lateinit var listView: ListView
    var isClickDismissed=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)

        val items = arrayOf(
            "Archive",
            "Unarchive",
            "Delete",
            "Add to contacts",
            "Pin to top",
            "Mark unread",
            "Block"
        )

        val adapter: ArrayAdapter<String> =
            ArrayAdapter(context, R.layout.options_dialog_list_item, items)
        android.R.layout.simple_list_item_1
        listView.adapter = adapter

        listView.getChildAt( if (isArchive) 0 else 1)?.visibility = View.GONE

        initClicks()
    }




    private fun initClicks() {
        listView.setOnItemClickListener { parent, view, position, id ->
            when (position) {
                0 -> listener.onArchiveMessageClicked()

                1 -> listener.onUnarchiveMessageClicked()

                2 -> listener.onDeleteMessagesClicked()

                3 -> listener.onAddToContactsClicked()

                4 -> listener.onPinToTopClicked()

                5 -> listener.onMarkUnreadClicked()

                6 -> listener.onBlockClicked()

            }
            isClickDismissed=true
            dismiss()
        }
    }


    interface OnMainOptionsDialogItemClickListener {
        fun onArchiveMessageClicked()
        fun onUnarchiveMessageClicked()
        fun onDeleteMessagesClicked()
        fun onAddToContactsClicked()
        fun onPinToTopClicked()
        fun onMarkUnreadClicked()
        fun onBlockClicked()
    }
}