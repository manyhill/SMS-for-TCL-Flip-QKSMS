package com.moez.QKSMS.feature.compose

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import com.moez.QKSMS.R

class ComposeOptionsDialog constructor(
    context: Context,
    private val listener: OnComposeOptionsDialogItemClickListener
) : Dialog(context) {


    lateinit var listView: ListView
    var isClickDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)

        val items = arrayOf(
            "Copy Text",
            "Forward Message",
            "Delete Message",
            "Save to Gallery",
            "Message Info",
            "Call"
        )
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

              //  1 -> listener.onPasteMessageClicked(this@ComposeOptionsDialog)

                1 -> listener.onForwardMessageClicked()

                2 -> listener.onDeleteMessagesClicked()

                3 -> listener.onSaveClicked()

                4-> listener.onMessageInfoClicked()

                5 -> listener.onMessageCallClicked()







            }
            isClickDismissed = true
            dismiss()
        }
    }


    interface OnComposeOptionsDialogItemClickListener {
        fun onCopyMessageClicked()
        fun onPasteMessageClicked(dialog: ComposeOptionsDialog)
        fun onDeleteMessagesClicked()
        fun onForwardMessageClicked()
        fun onMessageInfoClicked()
        fun onMessageCallClicked()
        fun onSaveClicked()
    }

}