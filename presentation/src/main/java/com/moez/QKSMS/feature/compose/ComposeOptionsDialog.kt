package com.moez.QKSMS.feature.compose

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import com.moez.QKSMS.R
import kotlinx.android.synthetic.main.message_list_item_in.*

class ComposeOptionsDialog constructor(
    context: Context,
    private val listener: OnComposeOptionsDialogItemClickListener,
    private val isMMS: Boolean
) : Dialog(context) {


    lateinit var listView: ListView
    var isClickDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)

        var option="Open Message"
        if(isMMS) {    option="Open"}
        val items = arrayOf(
            "Copy Text",
            "Forward Message",
           option,
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

                2 -> listener.onPlayClicked()

                3 -> listener.onDeleteMessagesClicked()

                4 -> listener.onSaveClicked()

                5-> listener.onMessageInfoClicked()

               6 -> listener.onMessageCallClicked()







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
        fun onPlayClicked()
        fun onSaveClicked()
    }

}