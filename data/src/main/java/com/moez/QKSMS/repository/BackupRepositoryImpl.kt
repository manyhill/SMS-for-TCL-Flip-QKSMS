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

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import com.moez.QKSMS.model.BackupFile
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.util.Preferences
import com.moez.QKSMS.util.QkFileObserver
import com.moez.QKSMS.util.tryOrNull
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository
) : BackupRepository {

    companion object {
        private val DEFAULT_BACKUP_DIRECTORY = Environment.getExternalStorageDirectory().toString() + "/QKSMS/Backups"
    }

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the entire file
     */
    data class BackupMetadata(
        val messageCount: Int = 0
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    private fun backupDirectory(): String = prefs.backupDirectory.get().takeIf { it.isNotBlank() }
            ?: DEFAULT_BACKUP_DIRECTORY

    private fun isTreeBackupDirectory(directory: String): Boolean = directory.startsWith("content://")

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        var messageCount = 0

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages)).toByteArray()

        try {
            // Create the directory and file
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(System.currentTimeMillis())
            val fileName = "backup-$timestamp.json"
            val directory = backupDirectory()

            if (isTreeBackupDirectory(directory)) {
                val parentUri = Uri.parse(directory)
                val fileUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    "application/json",
                    fileName
                )

                fileUri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(json)
                    }
                } ?: throw IllegalStateException("Unable to create backup file in selected folder")
            } else {
                val dir = File(directory).apply { mkdirs() }
                val file = File(dir, fileName)

                FileOutputStream(file, true).use { fileOutputStream -> fileOutputStream.write(json) }
            }
        } catch (e: Exception) {
            Timber.w(e)
        }

        // Mark the task finished, and set it as Idle a second later
        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    override fun getBackups(): Observable<List<BackupFile>> = Observable.just(backupDirectory())
            .switchMap { directory ->
                when {
                    isTreeBackupDirectory(directory) -> Observable.just(listTreeBackups(directory))
                    else -> QkFileObserver(directory).observable
                        .map { File(directory).listFiles() ?: arrayOf() }
                        .map { files -> fileBackups(files) }
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.computation())
            .map { files -> files.sortedByDescending { file -> file.date } }

    override fun performRestore(filePath: String) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val backup = when {
            filePath.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(filePath))
                ?.source()
                ?.buffer()
                ?.use { source -> moshi.adapter(Backup::class.java).fromJson(source) }

            else -> File(filePath).source().buffer().use { source ->
                moshi.adapter(Backup::class.java).fromJson(source)
            }
        }

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0
        var skippedCount = 0

        backup?.messages?.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            // Update the progress
            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))

            try {
                if (messageAlreadyExists(message)) {
                    skippedCount++
                    return@forEachIndexed
                }

                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (prefs.canUseSubId.get()) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to restore $errorCount/$messageCount messages"))
        }

        if (skippedCount > 0) {
            Timber.i("Skipped $skippedCount duplicate messages during restore")
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    override fun stopRestore() {
        stopFlag = true
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

    private fun fileBackups(files: Array<File>): List<BackupFile> {
        val adapter = moshi.adapter(BackupMetadata::class.java)

        return files.mapNotNull { file ->
            val backup = tryOrNull(false) {
                file.source().buffer().use(adapter::fromJson)
            } ?: return@mapNotNull null

            BackupFile(file.path, file.lastModified(), backup.messageCount, file.length())
        }
    }

    private fun listTreeBackups(directory: String): List<BackupFile> {
        val adapter = moshi.adapter(BackupMetadata::class.java)
        val treeUri = Uri.parse(directory)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )

        return context.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val backups = mutableListOf<BackupFile>()

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!name.endsWith(".json")) continue

                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idColumn)
                    )
                    val backup = tryOrNull(false) {
                        context.contentResolver.openInputStream(documentUri)
                            ?.source()
                            ?.buffer()
                            ?.use(adapter::fromJson)
                    } ?: continue

                    backups += BackupFile(
                        documentUri.toString(),
                        cursor.getLong(dateColumn),
                        backup.messageCount,
                        cursor.getLong(sizeColumn)
                    )
                }

                backups
            } ?: listOf()
    }

    private fun messageAlreadyExists(message: BackupMessage): Boolean {
        val selection = StringBuilder()
            .append("${Telephony.Sms.ADDRESS} = ?")
            .append(" AND ${Telephony.Sms.DATE} = ?")
            .append(" AND ${Telephony.Sms.TYPE} = ?")
            .append(" AND ${Telephony.Sms.BODY} = ?")

        val selectionArgs = mutableListOf(
            message.address,
            message.date.toString(),
            message.type.toString(),
            message.body
        )

        if (prefs.canUseSubId.get()) {
            selection.append(" AND ${Telephony.Sms.SUBSCRIPTION_ID} = ?")
            selectionArgs += message.subId.toString()
        }

        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            selection.toString(),
            selectionArgs.toTypedArray(),
            null
        )?.use { cursor -> cursor.moveToFirst() } ?: false
    }

}
