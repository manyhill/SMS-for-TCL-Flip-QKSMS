package com.moez.QKSMS.feature.compose

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.moez.QKSMS.R

class ComposeOptionsDialog constructor(
    context: Context,
    private val listener: OnComposeOptionsDialogItemClickListener,
    private val canSave: Boolean
) : Dialog(context) {

    private enum class Option {
        COPY,
        FORWARD,
        DELETE,
        SAVE,
        INFO
    }

    lateinit var listView: ListView
    var isClickDismissed = false
    private var options = listOf<Option>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.options_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        listView = findViewById(R.id.option_dialog_list_view)
        findViewById<TextView>(R.id.options_dialog_title)?.setText(R.string.message_options)

        options = mutableListOf<Option>().apply {
            add(Option.COPY)
            add(Option.FORWARD)
            add(Option.DELETE)
            if (canSave) add(Option.SAVE)
            add(Option.INFO)
        }
        val items = options.map { option ->
            when (option) {
                Option.COPY -> "Copy Text"
                Option.FORWARD -> "Forward Message"
                Option.DELETE -> "Delete Message"
                Option.SAVE -> "Save"
                Option.INFO -> "Message Info"
            }
        }
        val adapter: ArrayAdapter<String> =
            ArrayAdapter(context, R.layout.options_dialog_list_item, items)
        android.R.layout.simple_list_item_1
        listView.adapter = adapter
        initClicks()
    }

    private fun initClicks() {
        listView.setOnItemClickListener { parent, view, position, id ->
            when (options.getOrNull(position)) {
                Option.COPY -> listener.onCopyMessageClicked()
                Option.FORWARD -> listener.onForwardMessageClicked()
                Option.DELETE -> listener.onDeleteMessagesClicked()
                Option.SAVE -> listener.onSaveClicked()
                Option.INFO -> listener.onMessageInfoClicked()
                null -> Unit
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
        fun onSaveClicked()
    }

}
