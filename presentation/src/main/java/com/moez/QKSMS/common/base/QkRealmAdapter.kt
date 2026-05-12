package com.moez.QKSMS.common.base

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.*
import timber.log.Timber

/**
 * Drop-in replacement for the old RealmRecyclerViewAdapter-based QkRealmAdapter.
 * - No external "realm-android-adapters" dependency.
 * - Supports RealmResults<T> and RealmList<T>.
 * - Fine-grained updates via OrderedRealmCollectionChangeListener.
 * - Keeps the same API: emptyView, updateData(...), getItem(...), selection helpers.
 */
abstract class QkRealmAdapter<T : RealmModel> : RecyclerView.Adapter<QkViewHolder>() {

    // ---- Public API kept the same -------------------------------------------------------------

    /** This view's visibility is controlled automatically based on data loaded/empty state. */
    var emptyView: View? = null
        set(value) {
            if (field === value) return
            field = value
            val d = data
            value?.visibility = if (d?.isLoaded == true && d.isEmpty()) View.VISIBLE else View.GONE
        }

    val selectionChanges: Subject<List<Long>> = BehaviorSubject.create()
    private var selection: List<Long> = emptyList()

    /** Toggle a selection id. If force=false and not in selection mode, it won't toggle. */
    protected fun toggleSelection(id: Long, force: Boolean = true, allowAdding: Boolean = true): Boolean {
        if (!force && selection.isEmpty()) return false
        if (!allowAdding && selection.isNotEmpty() && id !in selection) return false
        selection = if (id in selection) selection - id else selection + id
        selectionChanges.onNext(selection)
        return true
    }

    protected fun isSelected(id: Long): Boolean = id in selection

    protected fun hasSelection(): Boolean = selection.isNotEmpty()

    fun getSelection(): List<Long> = selection

    protected fun selectOnly(id: Long) {
        selection = listOf(id)
        selectionChanges.onNext(selection)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selection = emptyList()
        selectionChanges.onNext(selection)
        notifyDataSetChanged()
    }

    /** Kept for call sites that used to do super.getItem(...). */
    open fun getItem(index: Int): T? {
        if (index < 0) {
            Timber.w("Only indexes >= 0 are allowed. Input was: $index")
            return null
        }
        val d = data ?: return null
        return if (index < d.size) d[index] else null
    }

    /** Kept for call sites that used to do super.updateData(...). */
    open fun updateData(newData: OrderedRealmCollection<T>?) {
        if (data === newData) return

        // detach from old
        removeListeners(data)

        data = newData

        // update empty state immediately
        newData?.let {
            emptyView?.visibility = if (it.isLoaded && it.isEmpty()) View.VISIBLE else View.GONE
        }

        // attach to new
        addListeners(newData)

        // refresh UI once (fine-grained updates will handle subsequent changes)
        notifyDataSetChanged()
    }

    // ---- RecyclerView.Adapter lifecycle -------------------------------------------------------

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        addListeners(data)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        removeListeners(data)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = data?.size ?: 0

    // ---- Internal -----------------------------------------------------------------------------

    /** We keep data here; mirrors the old adapter’s idea of holding the collection. */
     var data: OrderedRealmCollection<T>? = null
        private set

    // Empty-state listeners (simple)
    private val emptyResultsListener = RealmChangeListener<RealmResults<T>> { d ->
        emptyView?.visibility = if (d.isLoaded && d.isEmpty()) View.VISIBLE else View.GONE
    }
    private val emptyListListener = RealmChangeListener<RealmList<T>> { d ->
        emptyView?.visibility = if (d.isLoaded && d.isEmpty()) View.VISIBLE else View.GONE
    }

    // Fine-grained change listeners
    private val resultsListener =
        OrderedRealmCollectionChangeListener<RealmResults<T>> { _, changes ->
            applyFineGrainedChanges(changes)
        }

    private val listListener =
        OrderedRealmCollectionChangeListener<RealmList<T>> { _, changes ->
            applyFineGrainedChanges(changes)
        }

    private fun addListeners(d: OrderedRealmCollection<T>?) {
        when (d) {
            is RealmResults<T> -> {
                if (!d.canReceiveChangeNotifications()) return
                try {
                    d.addChangeListener(emptyResultsListener)
                    d.addChangeListener(resultsListener)
                } catch (error: IllegalStateException) {
                    Timber.d(error, "Skipping RealmResults listeners for non-live collection")
                }
            }
            is RealmList<T> -> {
                if (!d.canReceiveChangeNotifications()) return
                try {
                    d.addChangeListener(emptyListListener)
                    d.addChangeListener(listListener)
                } catch (error: IllegalStateException) {
                    Timber.d(error, "Skipping RealmList listeners for non-live collection")
                }
            }
        }
    }

    private fun removeListeners(d: OrderedRealmCollection<T>?) {
        when (d) {
            is RealmResults<T> -> {
                if (!d.canReceiveChangeNotifications()) return
                try {
                    d.removeChangeListener(emptyResultsListener)
                    d.removeChangeListener(resultsListener)
                } catch (error: IllegalStateException) {
                    Timber.d(error, "Skipping RealmResults listener removal for non-live collection")
                }
            }
            is RealmList<T> -> {
                if (!d.canReceiveChangeNotifications()) return
                try {
                    d.removeChangeListener(emptyListListener)
                    d.removeChangeListener(listListener)
                } catch (error: IllegalStateException) {
                    Timber.d(error, "Skipping RealmList listener removal for non-live collection")
                }
            }
        }
    }

    private fun RealmResults<T>.canReceiveChangeNotifications(): Boolean {
        return isManaged && isValid && !isFrozen
    }

    private fun RealmList<T>.canReceiveChangeNotifications(): Boolean {
        return isManaged && isValid && !isFrozen
    }

    private fun applyFineGrainedChanges(changes: OrderedCollectionChangeSet) {
        // Deletions first (reverse order) to keep indices valid
        val dels = changes.deletionRanges
        for (i in dels.indices.reversed()) {
            val r = dels[i]
            notifyItemRangeRemoved(r.startIndex, r.length)
        }
        // Insertions
        for (r in changes.insertionRanges) {
            notifyItemRangeInserted(r.startIndex, r.length)
        }
        // Modifications
        for (r in changes.changeRanges) {
            notifyItemRangeChanged(r.startIndex, r.length)
        }
    }
}
