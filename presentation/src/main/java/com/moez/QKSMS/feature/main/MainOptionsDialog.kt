package com.moez.QKSMS.feature.main

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import com.moez.QKSMS.R


class MainOptionsDialog constructor(
    context: Context,
    private val listener: OnMainOptionsDialogItemClickListener,
    private val isArchive: Boolean,
    private val markPinned: Boolean
) : Dialog(context) {

    lateinit var listView: ListView
    var isClickDismissed=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)
        var items: Array<String> = emptyArray()
        var archiveOption="Archive"
        var pinOptions="Pin to top"
        if(isArchive) {    archiveOption="Unarchive"}
        if(!markPinned){pinOptions="Unpin"}

    items = arrayOf(

        archiveOption,
        "Delete",
        "Add to contacts",
        pinOptions,
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
                0 -> listener.onArchiveMessageClicked(isArchive)

            //    1 -> listener.onUnarchiveMessageClicked()

                1 -> listener.onDeleteMessagesClicked()

                2 -> listener.onAddToContactsClicked()

                3 -> listener.onPinToTopClicked(markPinned)

//                4 -> listener.onUnpinToTopClicked()

                4 -> listener.onMarkUnreadClicked()

                5 -> listener.onBlockClicked()

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
        fun onPinToTopClicked(markPinned: Boolean)
//        fun onUnpinToTopClicked()
        fun onMarkUnreadClicked()
        fun onBlockClicked()
    }
}