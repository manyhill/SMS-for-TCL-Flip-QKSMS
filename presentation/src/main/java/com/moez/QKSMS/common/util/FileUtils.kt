package com.moez.QKSMS.common.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.moez.QKSMS.common.util.extensions.makeToast
import java.io.InputStream

object FileUtils {

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

}