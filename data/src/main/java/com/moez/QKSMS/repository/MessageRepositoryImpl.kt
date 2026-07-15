/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
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
package com.moez.QKSMS.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.core.content.contentValuesOf
import com.google.android.mms.ContentType
import com.google.android.mms.MMSPart
import com.google.android.mms.pdu_alt.MultimediaMessagePdu
import com.google.android.mms.pdu_alt.PduPersister
import com.klinker.android.send_message.SmsManagerFactory
import com.klinker.android.send_message.StripAccents
import com.klinker.android.send_message.Transaction
import com.moez.QKSMS.common.util.extensions.now
import com.moez.QKSMS.compat.TelephonyCompat
import com.moez.QKSMS.extensions.anyOf
import com.moez.QKSMS.manager.ActiveConversationManager
import com.moez.QKSMS.manager.KeyManager
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.model.Conversation
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.model.MmsPart
import com.moez.QKSMS.receiver.SendSmsReceiver
import com.moez.QKSMS.receiver.SmsDeliveredReceiver
import com.moez.QKSMS.receiver.SmsSentReceiver
import com.moez.QKSMS.util.ImageUtils
import com.moez.QKSMS.util.PhoneNumberUtils
import com.moez.QKSMS.util.Preferences
import com.moez.QKSMS.util.tryOrNull
import io.realm.Case
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import com.moez.QKSMS.util.RealmProvider

private const val SAFE_MMS_PAYLOAD_BYTES = 600 * 1024
private const val DEFAULT_MMS_PAYLOAD_BYTES = 300 * 1024
private const val MMS_COMPRESSION_HEADROOM = 0.9

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val activeConversationManager: ActiveConversationManager,
    private val context: Context,
    private val messageIds: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val syncRepository: SyncRepository
) : MessageRepository {

    override fun getMessages(threadId: Long, query: String): RealmResults<Message> {
        return RealmProvider.get()
            .where(Message::class.java)
            .equalTo("threadId", threadId)
            .let {
                when (query.isEmpty()) {
                    true -> it
                    false -> it
                        .beginGroup()
                        .contains("body", query, Case.INSENSITIVE)
                        .or()
                        .contains("parts.text", query, Case.INSENSITIVE)
                        .endGroup()
                }
            }
            .sort("date")
            .findAllAsync()
    }

    override fun getRecentMessages(threadId: Long, limit: Long): RealmResults<Message> {
        val realm = RealmProvider.get()
        val cutoffDate = realm.where(Message::class.java)
            .equalTo("threadId", threadId)
            .sort("date", Sort.DESCENDING)
            .limit(limit)
            .findAll()
            .lastOrNull()
            ?.date

        return realm.where(Message::class.java)
            .equalTo("threadId", threadId)
            .let { query ->
                when (cutoffDate) {
                    null -> query
                    else -> query.greaterThanOrEqualTo("date", cutoffDate)
                }
            }
            .sort("date")
            .findAllAsync()
    }

    override fun getMessage(id: Long): Message? {
        return RealmProvider.get()
            .also { realm -> realm.refresh() }
            .where(Message::class.java)
            .equalTo("id", id)
            .findFirst()
    }

    override fun getMessageForPart(id: Long): Message? {
        return RealmProvider.get()
            .where(Message::class.java)
            .equalTo("parts.id", id)
            .findFirst()
    }

    override fun getLastIncomingMessage(threadId: Long): RealmResults<Message> {
        return RealmProvider.get()
            .where(Message::class.java)
            .equalTo("threadId", threadId)
            .beginGroup()
            .beginGroup()
            .equalTo("type", "sms")
            .`in`("boxId", arrayOf(Sms.MESSAGE_TYPE_INBOX, Sms.MESSAGE_TYPE_ALL))
            .endGroup()
            .or()
            .beginGroup()
            .equalTo("type", "mms")
            .`in`("boxId", arrayOf(Mms.MESSAGE_BOX_INBOX, Mms.MESSAGE_BOX_ALL))
            .endGroup()
            .endGroup()
            .sort("date", Sort.DESCENDING)
            .findAll()
    }

    override fun getUnreadCount(): Long {
        return Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Conversation::class.java)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .greaterThan("unreadCount", 0)
                .count()
        }
    }

    override fun getPart(id: Long): MmsPart? {
        return RealmProvider.get()
            .where(MmsPart::class.java)
            .equalTo("id", id)
            .findFirst()
    }

    override fun getPartsForConversation(threadId: Long): RealmResults<MmsPart> {
        return RealmProvider.get()
            .where(MmsPart::class.java)
            .equalTo("messages.threadId", threadId)
            .beginGroup()
            .contains("type", "image/")
            .or()
            .contains("type", "video/")
            .or()
            .contains("type", "audio/")
            .endGroup()
            .sort("id", Sort.DESCENDING)
            .findAllAsync()
    }

    override fun savePart(id: Long): File? {
        val part = getPart(id) ?: return null

        val extension =
            MimeTypeMap.getSingleton().getExtensionFromMimeType(part.type) ?: return null
        val date = part.messages?.first()?.date
        val dir = File(Environment.getExternalStorageDirectory(), "QKSMS/Media").apply { mkdirs() }
        val fileName = part.name?.takeIf { name -> name.endsWith(extension) }
            ?: "${part.type.split("/").last()}_$date.$extension"
        var file: File
        var index = 0
        do {
            file = File(
                dir,
                if (index == 0) fileName else fileName.replace(
                    ".$extension",
                    " ($index).$extension"
                )
            )
            index++
        } while (file.exists())

        try {
            FileOutputStream(file).use { outputStream ->
                context.contentResolver.openInputStream(part.getUri())?.use { inputStream ->
                    inputStream.copyTo(outputStream, 1024)
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)

        return file.takeIf { it.exists() }
    }

    /**
     * Retrieves the list of messages which should be shown in the notification
     * for a given conversation
     */
    override fun getUnreadUnseenMessages(threadId: Long): RealmResults<Message> {
        return RealmProvider.get()
            .also { it.refresh() }
            .where(Message::class.java)
            .equalTo("seen", false)
            .equalTo("read", false)
            .equalTo("threadId", threadId)
            .sort("date")
            .findAll()
    }

    override fun getUnreadMessages(threadId: Long): RealmResults<Message> {
        return RealmProvider.get()
            .where(Message::class.java)
            .equalTo("read", false)
            .equalTo("threadId", threadId)
            .sort("date")
            .findAll()
    }

    override fun markAllSeen() {
        val realm = RealmProvider.get()
        val messages = realm.where(Message::class.java).equalTo("seen", false).findAll()
        realm.executeTransaction { messages.forEach { message -> message.seen = true } }
        realm.close()
    }

    override fun markSeen(threadId: Long) {
        val realm = RealmProvider.get()
        val messages = realm.where(Message::class.java)
            .equalTo("threadId", threadId)
            .equalTo("seen", false)
            .findAll()

        realm.executeTransaction {
            messages.forEach { message ->
                message.seen = true
            }
        }
        realm.close()
    }

    override fun markRead(vararg threadIds: Long) {
        Realm.getDefaultInstance()?.use { realm ->
            val messages = realm.where(Message::class.java)
                .anyOf("threadId", threadIds)
                .beginGroup()
                .equalTo("read", false)
                .or()
                .equalTo("seen", false)
                .endGroup()
                .findAll()

            realm.executeTransaction {
                messages.forEach { message ->
                    message.seen = true
                    message.read = true
                }

                realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()
                    .forEach { conversation -> conversation.unreadCount = 0 }
            }
        }

        val values = ContentValues()
        values.put(Sms.SEEN, true)
        values.put(Sms.READ, true)

        threadIds.forEach { threadId ->
            try {
                val uri =
                    ContentUris.withAppendedId(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI, threadId)
                context.contentResolver.update(uri, values, "${Sms.READ} = 0", null)
            } catch (exception: Exception) {
                Timber.w(exception)
            }
        }
    }

    override fun markUnread(vararg threadIds: Long) {
        Realm.getDefaultInstance()?.use { realm ->
            val conversations = realm.where(Conversation::class.java)
                .anyOf("id", threadIds)
                .equalTo("lastMessage.read", true)
                .findAll()

            realm.executeTransaction {
                conversations.forEach { conversation ->
                    conversation.lastMessage?.read = false
                    conversation.unreadCount = maxOf(conversation.unreadCount, 1)
                }
            }
        }
    }

    override fun sendMessage(
        subId: Int,
        threadId: Long,
        addresses: List<String>,
        body: String,
        attachments: List<Attachment>,
        delay: Int
    ) {
        val signedBody = when {
            prefs.signature.get().isEmpty() -> body
            body.isNotEmpty() -> body + '\n' + prefs.signature.get()
            else -> prefs.signature.get()
        }

        val smsManager = subId.takeIf { it != -1 }
            ?.let(SmsManagerFactory::createSmsManager)
            ?: SmsManager.getDefault()

        // We only care about stripping SMS
        val strippedBody = when (prefs.unicode.get()) {
            true -> StripAccents.stripAccents(signedBody)
            false -> signedBody
        }

        val parts = smsManager.divideMessage(strippedBody).orEmpty()
        val forceMms = prefs.longAsMms.get() && parts.size > 1

        if (addresses.size == 1 && attachments.isEmpty() && !forceMms) { // SMS
            if (delay > 0) { // With delay
                val sendTime = System.currentTimeMillis() + delay
                val message =
                    insertSentSms(subId, threadId, addresses.first(), strippedBody, sendTime)

                val intent = getIntentForDelayedSms(message.id)

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        sendTime,
                        intent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, sendTime, intent)
                }
            } else { // No delay
                val message = insertSentSms(subId, threadId, addresses.first(), strippedBody, now())
                sendSms(message)
            }
        } else { // MMS
            val parts = arrayListOf<MMSPart>()

            val maxWidth =
                smsManager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)
                    .takeIf { prefs.mmsSize.get() == -1 } ?: Int.MAX_VALUE

            val maxHeight =
                smsManager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)
                    .takeIf { prefs.mmsSize.get() == -1 } ?: Int.MAX_VALUE

            val carrierLimit = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
                .takeIf { it > 0 }
                ?: DEFAULT_MMS_PAYLOAD_BYTES

            val maxPayloadBytes = minOf(carrierLimit, SAFE_MMS_PAYLOAD_BYTES)


            val compressionLimit = when (prefs.mmsSize.get()) {
                -1 -> maxPayloadBytes
                0 -> maxPayloadBytes
                else -> minOf(prefs.mmsSize.get() * 1024, maxPayloadBytes)
            } * MMS_COMPRESSION_HEADROOM // Leave room for MMS headers/SMIL.

            var bytesUsed = 0.0

            var hasNotSkippedAttachment = true


            signedBody.takeIf { it.isNotEmpty() }?.toByteArray()?.let { bytes ->
                bytesUsed += bytes.size
                parts += MMSPart("text", ContentType.TEXT_PLAIN, bytes)
            }

            // Attach contacts
            parts += attachments
                .mapNotNull { attachment -> attachment as? Attachment.Contact }
                .map { attachment -> attachment.vCard.toByteArray() }
                .map { vCard ->
                    bytesUsed += vCard.size
                    MMSPart("contact", ContentType.TEXT_VCARD, vCard)
                }
            if (bytesUsed > compressionLimit) {
                Timber.w("MMS text/contact payload of ${bytesUsed.toInt() / 1024}Kb exceeds ${compressionLimit.toInt() / 1024}Kb limit.")
                hasNotSkippedAttachment = false
            }

            val videoBytesByAttachment = attachments
                .mapNotNull { attachment -> attachment as? Attachment.Video }
                .associateWith { attachment ->
                    var uri = attachment.getUri() ?: return@associateWith byteArrayOf()
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return@associateWith  byteArrayOf()
                    inputStream.readBytes()
                }
                .toMutableMap()

            videoBytesByAttachment.forEach { (attachment, bytes) ->
                val contentType: String = attachment.getContentType(context) ?: return@forEach
                if (bytes.size <= compressionLimit - bytesUsed) {
                    parts += MMSPart("video", contentType, bytes)
                    bytesUsed += bytes.size
                } else {
                    Timber.w("Video file of ${bytes.size / 1024}Kb cannot be sent in ${(compressionLimit - bytesUsed).toInt() / 1024}Kb remaining space.")
                    hasNotSkippedAttachment = false
                }
            }

            val fileBytesByAttachment = attachments
                .mapNotNull { attachment -> attachment as? Attachment.File }
                .associateWith { attachment ->
                    val uri = attachment.getUri() ?: return@associateWith byteArrayOf()
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return@associateWith byteArrayOf()
                    inputStream.readBytes()
                }
                .toMutableMap()

            fileBytesByAttachment.forEach { (attachment, bytes) ->
                val contentType: String = attachment.getContentType(context) ?: return@forEach
                if (contentType.let(ContentType::isSupportedAudioType)) {
                    if (bytes.size <= compressionLimit - bytesUsed) {
                        parts += MMSPart(attachment.getName(context)
                            ?: "Attachment", contentType, bytes)
                        bytesUsed += bytes.size
                    } else {
                        Timber.w("File of ${bytes.size / 1024}Kb cannot be sent in ${(compressionLimit - bytesUsed).toInt() / 1024}Kb remaining space.")
                        hasNotSkippedAttachment = false
                    }
                }
            }

            val imageBytesByAttachment = attachments
                .mapNotNull { attachment -> attachment as? Attachment.Image }
                .associateWith { attachment ->
                    val uri = attachment.getUri() ?: return@associateWith byteArrayOf()
                    when (attachment.isGif(context)) {
                        true -> ImageUtils.getScaledGif(context, uri, maxWidth, maxHeight)
                        false -> ImageUtils.getScaledImage(context, uri, maxWidth, maxHeight)
                    }
                }
                .toMutableMap()

            val imageByteCount = imageBytesByAttachment.values.sumBy { byteArray -> byteArray.size }
            if (imageByteCount > compressionLimit - bytesUsed) {
                imageBytesByAttachment.forEach { (attachment, originalBytes) ->
                    val uri = attachment.getUri() ?: return@forEach
                    val maxBytes = originalBytes.size / imageByteCount.toFloat() * (compressionLimit - bytesUsed)

                    // Get the image dimensions
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(uri),
                        null,
                        options
                    )
                    val width = options.outWidth
                    val height = options.outHeight
                    val aspectRatio = width.toFloat() / height.toFloat()

                    var attempts = 0
                    var scaledBytes = originalBytes

                    while (scaledBytes.size > maxBytes) {
                        // Estimate how much we need to scale the image down by. If it's still too big, we'll need to
                        // try smaller and smaller values
                        val scale = maxBytes / originalBytes.size * (0.9 - attempts * 0.2)
                        if (scale <= 0) {
                            Timber.w("Failed to compress ${originalBytes.size / 1024}Kb to ${maxBytes.toInt() / 1024}Kb")
                            hasNotSkippedAttachment = false
                            imageBytesByAttachment[attachment] = byteArrayOf()
                            return@forEach
                        }

                        val newArea = scale * width * height
                        val newWidth = sqrt(newArea * aspectRatio).toInt()
                        val newHeight = (newWidth / aspectRatio).toInt()

                        attempts++
                        scaledBytes = when (attachment.isGif(context)) {
                            true -> ImageUtils.getScaledGif(context, uri, newWidth, newHeight, 80)
                            false -> ImageUtils.getScaledImage(
                                context,
                                uri,
                                newWidth,
                                newHeight,
                                80
                            )
                        }

                        Timber.d("Compression attempt $attempts: ${scaledBytes.size / 1024}/${maxBytes.toInt() / 1024}Kb ($width*$height -> $newWidth*$newHeight)")
                    }

                    Timber.v("Compressed ${originalBytes.size / 1024}Kb to ${scaledBytes.size / 1024}Kb with a target size of ${maxBytes.toInt() / 1024}Kb in $attempts attempts")
                    imageBytesByAttachment[attachment] = scaledBytes
                }
            }

            imageBytesByAttachment.forEach { (attachment, bytes) ->
                if (bytes.isEmpty()) return@forEach
                parts += when (attachment.isGif(context)) {
                    true -> MMSPart("image", ContentType.IMAGE_GIF, bytes)
                    false -> MMSPart("image", ContentType.IMAGE_JPEG, bytes)
                }
            }

            // We need to strip the separators from outgoing MMS, or else they'll appear to have sent and not go through
            val transaction = Transaction(context)
            val recipients = addresses.map(phoneNumberUtils::normalizeNumber)
            if (parts.isNotEmpty() && hasNotSkippedAttachment) {
                transaction.sendNewMessage(subId, threadId, recipients, parts, null, null)
            } else if (!hasNotSkippedAttachment) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Attachment too large for MMS", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun sendSms(message: Message) {
        val smsManager = message.subId.takeIf { it != -1 }
            ?.let(SmsManagerFactory::createSmsManager)
            ?: SmsManager.getDefault()

        val parts = smsManager
            .divideMessage(if (prefs.unicode.get()) StripAccents.stripAccents(message.body) else message.body)
            ?: arrayListOf()

        val sentIntents = parts.map {
            val intent = Intent(context, SmsSentReceiver::class.java).putExtra("id", message.id)
            PendingIntent.getBroadcast(
                context,
                message.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val deliveredIntents = parts.map {
            val intent =
                Intent(context, SmsDeliveredReceiver::class.java).putExtra("id", message.id)
            val pendingIntent = PendingIntent
                .getBroadcast(
                    context,
                    message.id.toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            if (prefs.delivery.get()) pendingIntent else null
        }

        try {
            smsManager.sendMultipartTextMessage(
                message.address,
                null,
                parts,
                ArrayList(sentIntents),
                ArrayList(deliveredIntents)
            )
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Message body lengths: ${parts.map { it?.length }}")
            markFailed(message.id, Telephony.MmsSms.ERR_TYPE_GENERIC)
        }
    }

    override fun resendMms(message: Message) {
        val subId = message.subId
        val threadId = message.threadId
        val pdu = tryOrNull {
            PduPersister.getPduPersister(context).load(message.getUri()) as MultimediaMessagePdu
        } ?: return

        val addresses = pdu.to.map { it.string }.filter { it.isNotBlank() }
        val parts = message.parts.mapNotNull { part ->
            val bytes = tryOrNull(false) {
                context.contentResolver.openInputStream(part.getUri())
                    ?.use { inputStream -> inputStream.readBytes() }
            } ?: return@mapNotNull null

            MMSPart(part.name.orEmpty(), part.type, bytes)
        }

        Transaction(context).sendNewMessage(
            subId,
            threadId,
            addresses,
            parts,
            message.subject,
            message.getUri()
        )
    }

    override fun cancelDelayedSms(id: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getIntentForDelayedSms(id))
    }

    private fun getIntentForDelayedSms(id: Long): PendingIntent {
        val intent = Intent(context, SendSmsReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun insertSentSms(
        subId: Int,
        threadId: Long,
        address: String,
        body: String,
        date: Long
    ): Message {

        val message = Message().apply {
            this.threadId = threadId
            this.address = address
            this.body = body
            this.date = date
            this.subId = subId

            id = messageIds.newId()
            boxId = Sms.MESSAGE_TYPE_OUTBOX
            type = "sms"
            read = true
            seen = true
        }

        val values = contentValuesOf(
            Sms.ADDRESS to address,
            Sms.BODY to body,
            Sms.DATE to System.currentTimeMillis(),
            Sms.READ to true,
            Sms.SEEN to true,
            Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX,
            Sms.THREAD_ID to threadId
        )

        if (prefs.canUseSubId.get()) {
            values.put(Sms.SUBSCRIPTION_ID, message.subId)
        }

        var uri: android.net.Uri? = null

        Realm.getDefaultInstance().use { realm ->
            var managedMessage: Message? = null

            realm.executeTransaction {
                managedMessage = realm.copyToRealmOrUpdate(message)
            }

            uri = context.contentResolver.insert(Sms.CONTENT_URI, values)

            uri?.lastPathSegment?.toLongOrNull()?.let { contentId ->
                realm.executeTransaction {
                    managedMessage?.takeIf { it.isValid }?.contentId = contentId
                }
            }
        }

        if (threadId == 0L) {
            uri?.let(syncRepository::syncMessage)
        }

        return message
    }

    override fun insertReceivedSms(
        subId: Int,
        address: String,
        body: String,
        sentTime: Long
    ): Message {

        // Insert the message to Realm
        val message = Message().apply {
            this.address = address
            this.body = body
            this.dateSent = sentTime
            this.date = System.currentTimeMillis()
            this.subId = subId

            id = messageIds.newId()
            threadId = TelephonyCompat.getOrCreateThreadId(context, address)
            boxId = Sms.MESSAGE_TYPE_INBOX
            type = "sms"
            read = activeConversationManager.getActiveConversation() == threadId
        }
        Realm.getDefaultInstance().use { realm ->
            var managedMessage: Message? = null
            realm.executeTransaction {
                managedMessage = realm.copyToRealmOrUpdate(message)
            }

        // Insert the message to the native content provider
        val values = contentValuesOf(
            Sms.ADDRESS to address,
            Sms.BODY to body,
            Sms.DATE_SENT to sentTime
        )

        if (prefs.canUseSubId.get()) {
            values.put(Sms.SUBSCRIPTION_ID, message.subId)
        }

            context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
                ?.lastPathSegment
                ?.toLongOrNull()
                ?.let { contentId ->
                    realm.executeTransaction {
                        managedMessage?.takeIf { it.isValid }?.contentId = contentId
                    }
                }
        }


        return message
    }

    /**
     * Marks the message as sending, in case we need to retry sending it
     */
    override fun markSending(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val message = realm.where(Message::class.java).equalTo("id", id).findFirst()
            message?.let {
                // Update the message in realm
                realm.executeTransaction {
                    message.boxId = when (message.isSms()) {
                        true -> Sms.MESSAGE_TYPE_OUTBOX
                        false -> Mms.MESSAGE_BOX_OUTBOX
                    }
                }

                // Update the message in the native ContentProvider
                val values = when (message.isSms()) {
                    true -> contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX)
                    false -> contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_OUTBOX)
                }
                context.contentResolver.update(message.getUri(), values, null, null)
            }
        }
    }

    override fun markSent(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val message = realm.where(Message::class.java).equalTo("id", id).findFirst()
            message?.let {
                // Update the message in realm
                realm.executeTransaction {
                    message.boxId = Sms.MESSAGE_TYPE_SENT
                }

                // Update the message in the native ContentProvider
                val values = ContentValues()
                values.put(Sms.TYPE, Sms.MESSAGE_TYPE_SENT)
                context.contentResolver.update(message.getUri(), values, null, null)
            }
        }
    }

    override fun markFailed(id: Long, resultCode: Int) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val message = realm.where(Message::class.java).equalTo("id", id).findFirst()
            message?.let {
                // Update the message in realm
                realm.executeTransaction {
                    message.boxId = Sms.MESSAGE_TYPE_FAILED
                    message.errorCode = resultCode
                }

                // Update the message in the native ContentProvider
                val values = ContentValues()
                values.put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED)
                values.put(Sms.ERROR_CODE, resultCode)
                context.contentResolver.update(message.getUri(), values, null, null)
            }
        }
    }

    override fun markDelivered(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val message = realm.where(Message::class.java).equalTo("id", id).findFirst()
            message?.let {
                // Update the message in realm
                realm.executeTransaction {
                    message.deliveryStatus = Sms.STATUS_COMPLETE
                    message.dateSent = System.currentTimeMillis()
                    message.read = true
                }

                // Update the message in the native ContentProvider
                val values = ContentValues()
                values.put(Sms.STATUS, Sms.STATUS_COMPLETE)
                values.put(Sms.DATE_SENT, System.currentTimeMillis())
                values.put(Sms.READ, true)
                context.contentResolver.update(message.getUri(), values, null, null)
            }
        }
    }

    override fun markDeliveryFailed(id: Long, resultCode: Int) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val message = realm.where(Message::class.java).equalTo("id", id).findFirst()
            message?.let {
                // Update the message in realm
                realm.executeTransaction {
                    message.deliveryStatus = Sms.STATUS_FAILED
                    message.dateSent = System.currentTimeMillis()
                    message.read = true
                    message.errorCode = resultCode
                }

                // Update the message in the native ContentProvider
                val values = ContentValues()
                values.put(Sms.STATUS, Sms.STATUS_FAILED)
                values.put(Sms.DATE_SENT, System.currentTimeMillis())
                values.put(Sms.READ, true)
                values.put(Sms.ERROR_CODE, resultCode)
                context.contentResolver.update(message.getUri(), values, null, null)
            }
        }
    }

    override fun deleteMessages(vararg messageIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val messages = realm.where(Message::class.java)
                .anyOf("id", messageIds)
                .findAll()

            val uris = messages.map { it.getUri() }

            realm.executeTransaction { messages.deleteAllFromRealm() }

            uris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
        }
    }

    override fun getOldMessageCounts(maxAgeDays: Int): Map<Long, Int> {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(Message::class.java)
                .lessThan("date", now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))
                .findAll()
                .groupingBy { message -> message.threadId }
                .eachCount()
        }
    }

    override fun deleteOldMessages(maxAgeDays: Int) {
        return Realm.getDefaultInstance().use { realm ->
            val messages = realm.where(Message::class.java)
                .lessThan("date", now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))
                .findAll()

            val uris = messages.map { it.getUri() }

            realm.executeTransaction { messages.deleteAllFromRealm() }

            uris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
        }
    }
}
