/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package com.moez.QKSMS.feature.compose.editing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkAdapter
import com.moez.QKSMS.common.base.QkViewHolder
import com.moez.QKSMS.common.util.Colors
import com.moez.QKSMS.common.util.extensions.forwardTouches
import com.moez.QKSMS.common.util.extensions.setTint
import com.moez.QKSMS.extensions.associateByNotNull
import com.moez.QKSMS.model.Contact
import com.moez.QKSMS.model.ContactGroup
import com.moez.QKSMS.model.Conversation
import com.moez.QKSMS.model.Recipient
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.util.PhoneNumberUtils
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.contact_list_item.*
import kotlinx.android.synthetic.main.contact_list_item.view.*
import javax.inject.Inject

class ComposeItemAdapter @Inject constructor(
    private val colors: Colors,
    private val conversationRepo: ConversationRepository,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkAdapter<ComposeItem>() {

    val clicks: Subject<ComposeItem> = PublishSubject.create()
    val longClicks: Subject<ComposeItem> = PublishSubject.create()

    private val numbersViewPool = RecyclerView.RecycledViewPool()
    private val disposables = CompositeDisposable()

    var recipients: Map<String, Recipient> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectedChips: Map<String, String?> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.contact_list_item, parent, false)

        view.icon.setTint(colors.theme().theme)

        view.numbers.setRecycledViewPool(numbersViewPool)
        view.numbers.adapter = PhoneNumberAdapter()
        view.numbers.forwardTouches(view)

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val item = getItem(adapterPosition)
                clicks.onNext(item)
            }
            view.setOnLongClickListener {
                val item = getItem(adapterPosition)
                longClicks.onNext(item)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val prevItem = if (position > 0) getItem(position - 1) else null
        val item = getItem(position)

        when (item) {
            is ComposeItem.New -> bindNew(holder, item.value)
            is ComposeItem.Recent -> bindRecent(holder, item.value, prevItem)
            is ComposeItem.Starred -> bindStarred(holder, item.value, prevItem)
            is ComposeItem.Person -> bindPerson(holder, item.value, prevItem)
            is ComposeItem.Group -> bindGroup(holder, item.value, prevItem)
        }
    }

    private fun bindNew(holder: QkViewHolder, contact: Contact) {
        holder.index.isVisible = false

        holder.icon.isVisible = false

        holder.avatar.recipients = listOf(createRecipient(contact))

        holder.title.text = contact.numbers.joinToString { it.address }

        holder.subtitle.isVisible = false

        holder.numbers.isVisible = false
        bindSelection(holder, ComposeItem.New(contact))
    }

    private fun bindRecent(holder: QkViewHolder, conversation: Conversation, prev: ComposeItem?) {
        holder.index.isVisible = false

        holder.icon.isVisible = prev !is ComposeItem.Recent
        holder.icon.setImageResource(R.drawable.ic_history_black_24dp)

        holder.avatar.recipients = conversation.recipients

        holder.title.text = conversation.getTitle()

        holder.subtitle.isVisible = conversation.recipients.size > 1 && conversation.name.isBlank()
        holder.subtitle.text = conversation.recipients.joinToString(", ") { recipient ->
            recipient.contact?.name ?: recipient.address
        }
        holder.subtitle.collapseEnabled = conversation.recipients.size > 1

        holder.numbers.isVisible = conversation.recipients.size == 1
        (holder.numbers.adapter as PhoneNumberAdapter).data = conversation.recipients
                .mapNotNull { recipient -> recipient.contact }
                .flatMap { contact -> contact.numbers }
        bindSelection(holder, ComposeItem.Recent(conversation))
    }

    private fun bindStarred(holder: QkViewHolder, contact: Contact, prev: ComposeItem?) {
        holder.index.isVisible = false

        holder.icon.isVisible = prev !is ComposeItem.Starred
        holder.icon.setImageResource(R.drawable.ic_star_black_24dp)

        holder.avatar.recipients = listOf(createRecipient(contact))

        holder.title.text = contact.name

        holder.subtitle.isVisible = false

        holder.numbers.isVisible = true
        (holder.numbers.adapter as PhoneNumberAdapter).data = contact.numbers
        bindSelection(holder, ComposeItem.Starred(contact))
    }

    private fun bindGroup(holder: QkViewHolder, group: ContactGroup, prev: ComposeItem?) {
        holder.index.isVisible = false

        holder.icon.isVisible = prev !is ComposeItem.Group
        holder.icon.setImageResource(R.drawable.ic_people_black_24dp)

        holder.avatar.recipients = group.contacts.map(::createRecipient)

        holder.title.text = group.title

        holder.subtitle.isVisible = true
        holder.subtitle.text = group.contacts.joinToString(", ") { it.name }
        holder.subtitle.collapseEnabled = group.contacts.size > 1

        holder.numbers.isVisible = false
        bindSelection(holder, ComposeItem.Group(group))
    }

    private fun bindPerson(holder: QkViewHolder, contact: Contact, prev: ComposeItem?) {
        holder.index.isVisible = false

        holder.icon.isVisible = false

        holder.avatar.recipients = listOf(createRecipient(contact))

        holder.title.text = contact.name

        holder.subtitle.isVisible = false

        holder.numbers.isVisible = true
        (holder.numbers.adapter as PhoneNumberAdapter).data = contact.numbers
        bindSelection(holder, ComposeItem.Person(contact))
    }

    private fun bindSelection(holder: QkViewHolder, item: ComposeItem) {
        val contacts = item.getContacts()
        val isSelected = contacts.isNotEmpty() && contacts.all { contact ->
            contact.numbers.any { number ->
                selectedChips.keys.any { selected -> phoneNumberUtils.compare(selected, number.address) }
            }
        }
        holder.containerView.isActivated = isSelected
        holder.check.isVisible = isSelected
    }

    private fun createRecipient(contact: Contact): Recipient {
        return recipients[contact.lookupKey] ?: Recipient(
            address = contact.numbers.firstOrNull()?.address ?: "",
            contact = contact)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        disposables += conversationRepo.getUnmanagedRecipients()
                .map { recipients -> recipients.associateByNotNull { recipient -> recipient.contact?.lookupKey } }
                .subscribe { recipients -> this@ComposeItemAdapter.recipients = recipients }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        disposables.clear()
    }

    override fun areItemsTheSame(old: ComposeItem, new: ComposeItem): Boolean {
        val oldIds = old.getContacts().map { contact -> contact.lookupKey }
        val newIds = new.getContacts().map { contact -> contact.lookupKey }
        return oldIds == newIds
    }

    override fun areContentsTheSame(old: ComposeItem, new: ComposeItem): Boolean {
        return false
    }

}
