package com.moez.QKSMS.feature.compose

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import com.moez.QKSMS.R

class ComposeOptionsDialog constructor(context: Context, private val listener: OnComposeOptionsDialogItemClickListener) :
    Dialog(context) {


    lateinit var listView: ListView
    var isClickDismissed=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)

        val items =
            arrayOf("Copy Message Text","Paste Message", "Forward Message", "Message Info", "Delete Messages","Call")
        val adapter: ArrayAdapter<String> =
            ArrayAdapter(context, R.layout.options_dialog_list_item, items)
        android.R.layout.simple_list_item_1
        listView.adapter = adapter
        initClicks()
    }

    private fun initClicks() {
        listView.setOnItemClickListener { parent, view, position, id ->
            when (position) {
                0 -> listener.onCopyMessageClicked()

                1 -> listener.onPasteMessageClicked(this@ComposeOptionsDialog)

                2 -> listener.onForwardMessageClicked()

                3 -> listener.onMessageInfoClicked()

                4 -> listener.onDeleteMessagesClicked()

                5 -> listener.onMessageCallClicked()

            }
            isClickDismissed=true
            dismiss()
        }
    }


    interface OnComposeOptionsDialogItemClickListener {
        fun onCopyMessageClicked()
        fun onPasteMessageClicked(dialog:ComposeOptionsDialog)
        fun onDeleteMessagesClicked()
        fun onForwardMessageClicked()
        fun onMessageInfoClicked()
        fun onMessageCallClicked()
    }

}