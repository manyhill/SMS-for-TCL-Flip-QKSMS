package com.moez.QKSMS.common.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.moez.QKSMS.common.util.extensions.makeToast
import java.io.File
import java.io.InputStream
import java.util.Locale

object FileUtils {

    fun Context.nothingToSave()
    {
        makeToast("Nothing to save")

    }
    fun Context.nothingToPlay()
    {
        makeToast("Nothing to play")

    }
    fun Context.saveImageToGallery(imageUri: Uri) {
        val contentResolver = contentResolver
        var inputStream: InputStream? = null

        try {
            inputStream = contentResolver.openInputStream(imageUri)

            val filename = "${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            val collection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newImageUri = contentResolver.insert(collection, values)

            if (newImageUri != null) {
                val outputStream = contentResolver.openOutputStream(newImageUri)
                if (outputStream != null) {
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (inputStream?.read(buffer).also { read = it ?: -1 } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.close()
                }
            }
        } catch (e: Exception) {
            makeToast("Image saving failed")
            e.printStackTrace()
        } finally {
            inputStream?.close()
        }
        makeToast("Image saved successfully")
    }


    fun Context.saveVideoToGallery(videoUri: Uri) {
        val contentResolver = contentResolver
        var inputStream: InputStream? = null

        try {
            inputStream = contentResolver.openInputStream(videoUri)

            val filename = "${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }

            val collection =
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newVideoUri = contentResolver.insert(collection, values)

            if (newVideoUri != null) {
                val outputStream = contentResolver.openOutputStream(newVideoUri)
                if (outputStream != null) {
                    val buffer = ByteArray(4096)
                    var read: Int
                    if (inputStream != null) {
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }
                    outputStream.close()
                }
            }
        } catch (e: Exception) {
            makeToast("Video saving failed")
            e.printStackTrace()
        } finally {
            inputStream?.close()
        }
        makeToast("Video saved successfully")
    }

    fun Context.saveFileToMusic(sourceFile: File): Boolean {
        return saveFileToRelativePath(
            sourceFile = sourceFile,
            relativePath = Environment.DIRECTORY_MUSIC,
            fallbackLabel = "Audio"
        )
    }

    fun Context.saveFileToDownloads(sourceFile: File): Boolean {
        return saveFileToRelativePath(
            sourceFile = sourceFile,
            relativePath = Environment.DIRECTORY_DOWNLOADS,
            fallbackLabel = "File"
        )
    }

    private fun Context.saveFileToRelativePath(
        sourceFile: File,
        relativePath: String,
        fallbackLabel: String
    ): Boolean {
        return try {
            val displayName = sourceFile.name.takeIf { it.isNotBlank() }
                ?: "${fallbackLabel}_${System.currentTimeMillis()}"
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(sourceFile.extension.toLowerCase(Locale.getDefault()))
                ?: "application/octet-stream"

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }

            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val targetUri = contentResolver.insert(collection, values)
            if (targetUri != null) {
                contentResolver.openOutputStream(targetUri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } != null
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

}
