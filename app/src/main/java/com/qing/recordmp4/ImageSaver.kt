package com.qing.recordmp4

import android.media.Image
import java.io.File
import java.io.FileOutputStream

class ImageSaver(private val mImage: Image, private val mFile: File) : Runnable {

    override fun run() {
        val buffer = mImage.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(mFile)
            output.write(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mImage.close()
            try {
                output?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}