package com.moez.QKSMS.common.util.extensions

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moez.QKSMS.feature.compose.MessagesAdapter

fun RecyclerView.setLastItemVisibleListener(onLastItemVisible: () -> Unit) {
    this.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)

            // Check if the last item is visible
            val layoutManager = recyclerView.layoutManager as LinearLayoutManager
            val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
            val itemCount = layoutManager.itemCount

            if (lastVisibleItemPosition == itemCount - 1) {
                // Last item is visible
                // Call the callback method
                onLastItemVisible.invoke()
            }
        }
    })
}

fun RecyclerView.setLastItemFocusedListener(onLastItemFocused: (() -> Unit)? = null) {
    adapter = adapter?.apply {
        if (this is MessagesAdapter) {
            this.onLastItemFocused = onLastItemFocused
        }
    }
}
