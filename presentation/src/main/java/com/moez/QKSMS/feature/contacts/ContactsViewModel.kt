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
package com.moez.QKSMS.feature.contacts

import android.view.inputmethod.EditorInfo
import com.moez.QKSMS.common.base.QkViewModel
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.extensions.removeAccents
import com.moez.QKSMS.feature.compose.editing.ComposeItem
import com.moez.QKSMS.feature.compose.editing.PhoneNumberAction
import com.moez.QKSMS.filter.ContactFilter
import com.moez.QKSMS.filter.ContactGroupFilter
import com.moez.QKSMS.interactor.SetDefaultPhoneNumber
import com.moez.QKSMS.model.Contact
import com.moez.QKSMS.model.ContactGroup
import com.moez.QKSMS.model.Conversation
import com.moez.QKSMS.model.PhoneNumber
import com.moez.QKSMS.model.Recipient
import com.moez.QKSMS.repository.ContactRepository
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.util.PhoneNumberUtils
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.realm.RealmList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx2.awaitFirst
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

private const val CONTACT_SEARCH_DEBOUNCE_MS = 180L
private const val CONTACT_SEARCH_RESULT_LIMIT = 75

class ContactsViewModel @Inject constructor(
    @Named("sharing") sharing: Boolean,
    @Named("singleRecipient") private val singleRecipient: Boolean,
    serializedChips: HashMap<String, String?>,
    private val contactFilter: ContactFilter,
    private val contactGroupFilter: ContactGroupFilter,
    private val contactsRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val setDefaultPhoneNumber: SetDefaultPhoneNumber
) : QkViewModel<ContactsContract, ContactsState>(ContactsState()) {

    private val contactGroups: Observable<List<ContactGroup>> by lazy { contactsRepo.getUnmanagedContactGroups() }
    private val contacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts() }
    private val recents: Observable<List<Conversation>> by lazy {
        if (sharing) conversationRepo.getUnmanagedConversations() else Observable.just(listOf())
    }
    private val starredContacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts(true) }

    private val selectedChipMap = BehaviorSubject.createDefault(HashMap(serializedChips))
    private val selectedChips = selectedChipMap
        .observeOn(Schedulers.io())
        .map { hashmap ->
            hashmap.map { (address, lookupKey) ->
                Recipient(address = address, contact = lookupKey?.let(contactsRepo::getUnmanagedContact))
            }
        }

    private var shouldFocusContacts: Boolean = true

    override fun bindView(view: ContactsContract) {
        super.bindView(view)

        if (shouldFocusContacts) {
            view.focusContacts()
            shouldFocusContacts = false
        }

        val queryChanged = view.queryChangedIntent
            .map { query -> query.toString() }
            .distinctUntilChanged()

        // Update the state's query, so we know if we should show the cancel button
        queryChanged
                .autoDisposable(view.scope())
                .subscribe { query -> newState { copy(query = query) } }

        // Clear the query
        view.queryClearedIntent
                .autoDisposable(view.scope())
                .subscribe { view.clearQuery() }

        // Update the list of contact suggestions based on the query input, while also filtering out any contacts
        // based on the query input
        Observables
                .combineLatest(
                        queryChanged.debounce(CONTACT_SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS),
                        recents,
                        starredContacts,
                        contactGroups,
                        contacts,
                        selectedChips
                ) { query, recents, starredContacts, contactGroups, contacts, selectedChips ->
                    val composeItems = mutableListOf<ComposeItem>()
                    if (query.isBlank()) {
                        composeItems += recents
                                .map(ComposeItem::Recent)

                        composeItems += starredContacts
                                .map(ComposeItem::Starred)

                        composeItems += contactGroups
                                .map(ComposeItem::Group)

                        composeItems += contacts
                                .map(ComposeItem::Person)
                    } else {
                        // If the entry is a valid destination, allow it as a recipient
                        if (phoneNumberUtils.isPossibleNumber(query.toString())) {
                            val newAddress = phoneNumberUtils.formatNumber(query)
                            val newContact = Contact(numbers = RealmList(PhoneNumber(address = newAddress)))
                            composeItems += ComposeItem.New(newContact)
                        }

                        // Strip the accents from the query. This can be an expensive operation, so
                        // cache the result instead of doing it for each contact
                        val normalizedQuery = query.removeAccents()
                        composeItems += starredContacts
                                .asSequence()
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .map(ComposeItem::Starred)

                        composeItems += contactGroups
                                .asSequence()
                                .filter { group -> contactGroupFilter.filter(group, normalizedQuery) }
                                .map(ComposeItem::Group)

                        composeItems += contacts
                                .asSequence()
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .take(CONTACT_SEARCH_RESULT_LIMIT)
                                .map(ComposeItem::Person)
                    }

                    composeItems.sortedByDescending { item ->
                        item.getContacts().any { contact ->
                            contact.numbers.any { number ->
                                selectedChips.any { chip -> phoneNumberUtils.compare(chip.address, number.address) }
                            }
                        }
                    }
                }
                .subscribeOn(Schedulers.computation())
                .autoDisposable(view.scope())
                .subscribe { items -> newState { copy(composeItems = items) } }

        selectedChipMap
            .autoDisposable(view.scope())
            .subscribe { chips -> newState { copy(selectedChips = HashMap(chips)) } }

        // Listen for ComposeItems being selected, and then send them off to the number picker dialog in case
        // the user needs to select a phone number
        view.queryEditorActionIntent
                .filter { actionId -> actionId == EditorInfo.IME_ACTION_DONE }
                .withLatestFrom(state) { _, state -> state }
                .mapNotNull { state -> state.composeItems.firstOrNull() }
                .mergeWith(view.composeItemPressedIntent)
                .map { composeItem -> composeItem to false }
                .mergeWith(view.composeItemLongPressedIntent.map { composeItem -> composeItem to true })
                .observeOn(Schedulers.io())
                .map { (composeItem, force) ->
                    HashMap(composeItem.getContacts().associate { contact ->
                        if (contact.numbers.size == 1 || contact.getDefaultNumber() != null && !force) {
                            val address = contact.getDefaultNumber()?.address ?: contact.numbers[0]!!.address
                            address to contact.lookupKey
                        } else {
                            runBlocking {
                                newState { copy(selectedContact = contact) }
                                val action = view.phoneNumberActionIntent.awaitFirst()
                                newState { copy(selectedContact = null) }
                                val numberId = view.phoneNumberSelectedIntent.awaitFirst().value
                                val number = contact.numbers.find { number -> number.id == numberId }

                                if (action == PhoneNumberAction.CANCEL || number == null) {
                                    return@runBlocking null
                                }

                                if (action == PhoneNumberAction.ALWAYS) {
                                    val params = SetDefaultPhoneNumber.Params(contact.lookupKey, number.id)
                                    setDefaultPhoneNumber.execute(params)
                                }

                                number.address to contact.lookupKey
                            } ?: return@map hashMapOf<String, String?>()
                        }
                    })
                }
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(view.scope())
                .subscribe { result ->
                    if (result.isEmpty()) return@subscribe

                    if (singleRecipient) {
                        view.finish(HashMap(result.entries.take(1).associate { it.toPair() }))
                        return@subscribe
                    }

                    val updated = HashMap(selectedChipMap.value ?: hashMapOf())
                    val allSelected = result.keys.all { key ->
                        updated.keys.any { selected -> phoneNumberUtils.compare(selected, key) }
                    }

                    if (allSelected) {
                        result.keys.forEach { key ->
                            updated.keys.firstOrNull { selected -> phoneNumberUtils.compare(selected, key) }
                                ?.let(updated::remove)
                        }
                    } else {
                        updated.putAll(result)
                    }

                    selectedChipMap.onNext(updated)
                }

        view.doneIntent
            .withLatestFrom(selectedChipMap) { _, selected -> HashMap(selected) }
            .autoDisposable(view.scope())
            .subscribe(view::finish)
    }

}
