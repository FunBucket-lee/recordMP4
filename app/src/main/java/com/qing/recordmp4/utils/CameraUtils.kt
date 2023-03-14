package com.qing.recordmp4.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object CameraUtils {
    /**
     * 提取排列YUV数据
     */
    fun yuvToN21orN12(image: Image, nv21: ByteArray, w: Int, h: Int, type: String) {
        val planes = image.planes
        //getPlanes()[0]存储当前帧图像的所有 Y 分量
        //getPlanes()[1]存储当前帧图像的所有 U 分量
        //getPlanes()[2]存储当前帧图像的所有 V 分量
        val yRemaining = planes[0].buffer.remaining()
        val uRemaining = planes[1].buffer.remaining()
        val vRemaining = planes[2].buffer.remaining()
        //分别准备三个数据接收YUV分量
        val yRawSrcBytes = ByteArray(yRemaining)
        val uRawSrcBytes = ByteArray(uRemaining)
        val vRawSrcBytes = ByteArray(vRemaining)
        planes[0].buffer.get(yRawSrcBytes)
        planes[1].buffer.get(uRawSrcBytes)
        planes[2].buffer.get(vRawSrcBytes)
        var j = 0
        var k = 0
        val flag = type == "NV21"
        for (i in nv21.indices) {
            if (i < w * h) {
                //首先填充w*h个Y分量
                nv21[i] = yRawSrcBytes[i]
            } else {
                if (flag) {
                    //若NV21类型 则Y分量分配完后第一个将是V分量
                    nv21[i] = vRawSrcBytes[j]
                    //PixelStride有用数据步长 = 1紧凑按顺序填充，=2每间隔一个填充数据
                    j += planes[1].pixelStride
                } else {
                    //若NV12类型 则Y分量分配完后第一个将是U分量
                    nv21[i] = uRawSrcBytes[j]
                    //PixelStride有用数据步长 = 1紧凑按顺序填充，=2每间隔一个填充数据
                    k += planes[2].pixelStride
                }
                //紧接着可以交错UV或者VU排列不停的改变flag标志即可交错排列
                flag != flag
            }
        }
    }

    /**
     * YUV转换图像
     */
    fun nv21ToBitmap(nv21: ByteArray, weight: Int, height: Int): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, weight, height, null)
            val stream = ByteArrayOutputStream()
            //输出到对应流
            yuvImage.compressToJpeg(Rect(0, 0, weight, height), 100, stream)
            //对应字节流生成bitmap
            bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return bitmap
    }
}