package com.example.photoviewer

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ExifToolManager(private val context: Context) {

    val rotationQueue = ConcurrentHashMap<String, Int>()
    private val executor = Executors.newSingleThreadExecutor()

    fun queueRotation(filePath: String) {
        val currentAngle = rotationQueue[filePath] ?: 0
        val newAngle = (currentAngle + 90) % 360
        if (newAngle == 0) {
            rotationQueue.remove(filePath)
        } else {
            rotationQueue[filePath] = newAngle
        }
    }

    fun getPendingRotation(filePath: String): Int = rotationQueue[filePath] ?: 0

    fun getPendingUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        rotationQueue.keys.forEach { path ->
            getContentUriFromPath(path)?.let { uris.add(it) }
        }
        return uris
    }

    fun applyBatchRotations(
        onProgress: (Int, Int) -> Unit,
        onComplete: (successCount: Int, failCount: Int) -> Unit
    ) {
        executor.execute {
            val items = rotationQueue.toMap()
            var completed = 0
            var successCount = 0
            var failCount = 0
            val updatedPaths = mutableListOf<String>()

            items.forEach { (path, addAngle) ->
                val success = writeExifOrientation(path, addAngle)
                if (success) {
                    successCount++
                    updatedPaths.add(path)
                    rotationQueue.remove(path)
                } else {
                    failCount++
                }
                completed++
                onProgress(completed, items.size)
            }

            if (updatedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    context,
                    updatedPaths.toTypedArray(),
                    null,
                    null
                )
            }

            onComplete(successCount, failCount)
        }
    }

    private fun writeExifOrientation(filePath: String, addAngle: Int): Boolean {
        return try {
            val uri = getContentUriFromPath(filePath)

            val pfd = if (uri != null) {
                context.contentResolver.openFileDescriptor(uri, "rw")
            } else {
                null
            }

            val exif = if (pfd != null) {
                ExifInterface(pfd.fileDescriptor)
            } else {
                ExifInterface(filePath)
            }

            val currentOrientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val newOrientation = calculateNewOrientation(currentOrientation, addAngle)

            exif.setAttribute(ExifInterface.TAG_ORIENTATION, newOrientation.toString())
            exif.saveAttributes()

            pfd?.close()
            Log.d("ExifToolManager", "Successfully wrote EXIF Orientation: $newOrientation to $filePath")
            true
        } catch (e: Exception) {
            Log.e("ExifToolManager", "Failed to write EXIF for: $filePath. Error: ${e.message}", e)
            false
        }
    }

    private fun getContentUriFromPath(path: String): Uri? {
        val file = File(path)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun calculateNewOrientation(currentExif: Int, addAngle: Int): Int {
        val currentAngle = when (currentExif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        return when ((currentAngle + addAngle) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
