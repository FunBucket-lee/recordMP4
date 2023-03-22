package com.qing.recordmp4.utils

import android.graphics.Bitmap
import android.media.Image.Plane
import android.media.ImageReader

object YUVTools {
    /******************************* YUV420旋转算法  */ // I420或YV12顺时针旋转
    fun rotateP(src: ByteArray, dest: ByteArray, w: Int, h: Int, rotation: Int) {
        when (rotation) {
            0 -> System.arraycopy(src, 0, dest, 0, src.size)
            90 -> rotateP90(src, dest, w, h)
            180 -> rotateP180(src, dest, w, h)
            270 -> rotateP270(src, dest, w, h)
        }
    }

    // NV21或NV12顺时针旋转
    fun rotateSP(src: ByteArray, dest: ByteArray, w: Int, h: Int, rotation: Int) {
        when (rotation) {
            0 -> System.arraycopy(src, 0, dest, 0, src.size)
            90 -> rotateSP90(src, dest, w, h)
            180 -> rotateSP180(src, dest, w, h)
            270 -> rotateSP270(src, dest, w, h)
        }
    }

    // NV21或NV12顺时针旋转90度
    fun rotateSP90(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        var k = 0
        for (i in 0 until w) {
            for (j in h - 1 downTo 0) {
                dest[k++] = src[j * w + i]
            }
        }
        pos = w * h
        var i = 0
        while (i <= w - 2) {
            for (j in h / 2 - 1 downTo 0) {
                dest[k++] = src[pos + j * w + i]
                dest[k++] = src[pos + j * w + i + 1]
            }
            i += 2
        }
    }

    // NV21或NV12顺时针旋转270度
    fun rotateSP270(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        var k = 0
        for (i in w - 1 downTo 0) {
            for (j in 0 until h) {
                dest[k++] = src[j * w + i]
            }
        }
        pos = w * h
        var i = w - 2
        while (i >= 0) {
            for (j in 0 until h / 2) {
                dest[k++] = src[pos + j * w + i]
                dest[k++] = src[pos + j * w + i + 1]
            }
            i -= 2
        }
    }

    // NV21或NV12顺时针旋转180度
    fun rotateSP180(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        var k = w * h - 1
        while (k >= 0) {
            dest[pos++] = src[k--]
        }
        k = src.size - 2
        while (pos < dest.size) {
            dest[pos++] = src[k]
            dest[pos++] = src[k + 1]
            k -= 2
        }
    }

    // I420或YV12顺时针旋转90度
    fun rotateP90(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        //旋转Y
        var k = 0
        for (i in 0 until w) {
            for (j in h - 1 downTo 0) {
                dest[k++] = src[j * w + i]
            }
        }
        //旋转U
        pos = w * h
        for (i in 0 until w / 2) {
            for (j in h / 2 - 1 downTo 0) {
                dest[k++] = src[pos + j * w / 2 + i]
            }
        }

        //旋转V
        pos = w * h * 5 / 4
        for (i in 0 until w / 2) {
            for (j in h / 2 - 1 downTo 0) {
                dest[k++] = src[pos + j * w / 2 + i]
            }
        }
    }

    // I420或YV12顺时针旋转270度
    fun rotateP270(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        //旋转Y
        var k = 0
        for (i in w - 1 downTo 0) {
            for (j in 0 until h) {
                dest[k++] = src[j * w + i]
            }
        }
        //旋转U
        pos = w * h
        for (i in w / 2 - 1 downTo 0) {
            for (j in 0 until h / 2) {
                dest[k++] = src[pos + j * w / 2 + i]
            }
        }

        //旋转V
        pos = w * h * 5 / 4
        for (i in w / 2 - 1 downTo 0) {
            for (j in 0 until h / 2) {
                dest[k++] = src[pos + j * w / 2 + i]
            }
        }
    }

    // I420或YV12顺时针旋转180度
    fun rotateP180(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = 0
        var k = w * h - 1
        while (k >= 0) {
            dest[pos++] = src[k--]
        }
        k = w * h * 5 / 4
        while (k >= w * h) {
            dest[pos++] = src[k--]
        }
        k = src.size - 1
        while (pos < dest.size) {
            dest[pos++] = src[k--]
        }
    }

    /******************************* YUV420格式相互转换算法  */ // i420 -> nv12, yv12 -> nv21
    fun pToSP(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = w * h
        var u = pos
        var v = pos + (pos shr 2)
        System.arraycopy(src, 0, dest, 0, pos)
        while (pos < src.size) {
            dest[pos++] = src[u++]
            dest[pos++] = src[v++]
        }
    }

    // i420 -> nv21, yv12 -> nv12
    fun pToSPx(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = w * h
        var u = pos
        var v = pos + (pos shr 2)
        System.arraycopy(src, 0, dest, 0, pos)
        while (pos < src.size) {
            dest[pos++] = src[v++]
            dest[pos++] = src[u++]
        }
    }

    // nv12 -> i420, nv21 -> yv12
    fun spToP(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = w * h
        var u = pos
        var v = pos + (pos shr 2)
        System.arraycopy(src, 0, dest, 0, pos)
        while (pos < src.size) {
            dest[u++] = src[pos++]
            dest[v++] = src[pos++]
        }
    }

    // nv12 -> yv12, nv21 -> i420
    fun spToPx(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = w * h
        var u = pos
        var v = pos + (pos shr 2)
        System.arraycopy(src, 0, dest, 0, pos)
        while (pos < src.size) {
            dest[v++] = src[pos++]
            dest[u++] = src[pos++]
        }
    }

    // i420 <-> yv12
    fun pToP(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        val pos = w * h
        val off = pos shr 2
        System.arraycopy(src, 0, dest, 0, pos)
        System.arraycopy(src, pos, dest, pos + off, off)
        System.arraycopy(src, pos + off, dest, pos, off)
    }

    // nv12 <-> nv21
    fun spToSP(src: ByteArray, dest: ByteArray, w: Int, h: Int) {
        var pos = w * h
        System.arraycopy(src, 0, dest, 0, pos)
        while (pos < src.size) {
            dest[pos] = src[pos + 1]
            dest[pos + 1] = src[pos]
            pos += 2
        }
    }

    /******************************* YUV420转换Bitmap算法  */ // 此方法虽然是官方的，但是耗时是下面方法的两倍
    //    public static Bitmap nv21ToBitmap(byte[] data, int w, int h) {
    //        final YuvImage image = new YuvImage(data, ImageFormat.NV21, w, h, null);
    //        ByteArrayOutputStream os = new ByteArrayOutputStream(data.length);
    //        if (image.compressToJpeg(new Rect(0, 0, w, h), 100, os)) {
    //            byte[] tmp = os.toByteArray();
    //            return BitmapFactory.decodeByteArray(tmp, 0, tmp.length);
    //        }
    //        return null;
    //    }
    fun nv12ToBitmap(data: ByteArray, w: Int, h: Int): Bitmap {
        return spToBitmap(data, w, h, 0, 1)
    }

    fun nv21ToBitmap(data: ByteArray, w: Int, h: Int): Bitmap {
        return spToBitmap(data, w, h, 1, 0)
    }

    private fun spToBitmap(data: ByteArray, w: Int, h: Int, uOff: Int, vOff: Int): Bitmap {
        val plane = w * h
        val colors = IntArray(plane)
        var yPos = 0
        var uvPos = plane
        for (j in 0 until h) {
            for (i in 0 until w) {
                // YUV byte to RGB int
                val y1 = data[yPos].toInt() and 0xff
                val u = (data[uvPos + uOff].toInt() and 0xff) - 128
                val v = (data[uvPos + vOff].toInt() and 0xff) - 128
                val y1192 = 1192 * y1
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u
                r = if (r < 0) 0 else if (r > 262143) 262143 else r
                g = if (g < 0) 0 else if (g > 262143) 262143 else g
                b = if (b < 0) 0 else if (b > 262143) 262143 else b
                colors[yPos] = r shl 6 and 0xff0000 or
                        (g shr 2 and 0xff00) or (b shr 10 and 0xff)
                if (yPos++ and 1 == 1) uvPos += 2
            }
            if (j and 1 == 0) uvPos -= w
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.RGB_565)
    }

    fun i420ToBitmap(data: ByteArray, w: Int, h: Int): Bitmap {
        return pToBitmap(data, w, h, true)
    }

    fun yv12ToBitmap(data: ByteArray, w: Int, h: Int): Bitmap {
        return pToBitmap(data, w, h, false)
    }

    private fun pToBitmap(data: ByteArray, w: Int, h: Int, uv: Boolean): Bitmap {
        val plane = w * h
        val colors = IntArray(plane)
        val off = plane shr 2
        var yPos = 0
        var uPos = plane + if (uv) 0 else off
        var vPos = plane + if (uv) off else 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                // YUV byte to RGB int
                val y1 = data[yPos].toInt() and 0xff
                val u = (data[uPos].toInt() and 0xff) - 128
                val v = (data[vPos].toInt() and 0xff) - 128
                val y1192 = 1192 * y1
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u
                r = if (r < 0) 0 else if (r > 262143) 262143 else r
                g = if (g < 0) 0 else if (g > 262143) 262143 else g
                b = if (b < 0) 0 else if (b > 262143) 262143 else b
                colors[yPos] = r shl 6 and 0xff0000 or
                        (g shr 2 and 0xff00) or (b shr 10 and 0xff)
                if (yPos++ and 1 == 1) {
                    uPos++
                    vPos++
                }
            }
            if (j and 1 == 0) {
                uPos -= (w shr 1)
                vPos -= (w shr 1)
            }
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.RGB_565)
    }

    fun planesToColors(planes: Array<Plane>, height: Int): IntArray {
        val yPlane = planes[0].buffer
        val uPlane = planes[1].buffer
        val vPlane = planes[2].buffer
        var bufferIndex = 0
        val total = yPlane.capacity()
        val uvCapacity = uPlane.capacity()
        val width = planes[0].rowStride
        val rgbBuffer = IntArray(width * height)
        var yPos = 0
        for (i in 0 until height) {
            var uvPos = (i shr 1) * width
            for (j in 0 until width) {
                if (uvPos >= uvCapacity - 1) break
                if (yPos >= total) break
                val y1 = yPlane[yPos++].toInt() and 0xff

                /*
              The ordering of the u (Cb) and v (Cr) bytes inside the planes is a
              bit strange. The _first_ byte of the u-plane and the _second_ byte
              of the v-plane build the u/v pair and belong to the first two pixels
              (y-bytes), thus usual YUV 420 behavior. What the Android devs did
              here (IMHO): just copy the interleaved NV21 U/V data to two planes
              but keep the offset of the interleaving.
             */
                val u = (uPlane[uvPos].toInt() and 0xff) - 128
                val v = (vPlane[uvPos].toInt() and 0xff) - 128
                if (j and 1 == 1) {
                    uvPos += 2
                }

                // This is the integer variant to convert YCbCr to RGB, NTSC values.
                // formulae found at
                // https://software.intel.com/en-us/android/articles/trusted-tools-in-the-new-android-world-optimization-techniques-from-intel-sse-intrinsics-to
                // and on StackOverflow etc.
                val y1192 = 1192 * y1
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u
                r = if (r < 0) 0 else if (r > 262143) 262143 else r
                g = if (g < 0) 0 else if (g > 262143) 262143 else g
                b = if (b < 0) 0 else if (b > 262143) 262143 else b
                rgbBuffer[bufferIndex++] = r shl 6 and 0xff0000 or
                        (g shr 2 and 0xff00) or (b shr 10 and 0xff)
            }
        }
        return rgbBuffer
    }

    /**
     * 从ImageReader中获取byte[]数据
     */
    fun getBytesFromImageReader(imageReader: ImageReader): ByteArray? {
        try {
            imageReader.acquireNextImage().use { image ->
                val planes = image.planes
                val b0 = planes[0].buffer
                val b1 = planes[1].buffer
                val b2 = planes[2].buffer
                val y = b0.remaining()
                val u = y shr 2
                val bytes = ByteArray(y + u + u)
                if (b1.remaining() > u) { // y420sp
                    b0[bytes, 0, b0.remaining()]
                    b1[bytes, y, b1.remaining()] // uv
                } else { // y420p
                    b0[bytes, 0, b0.remaining()]
                    b1[bytes, y, b1.remaining()] // u
                    b2[bytes, y + u, b2.remaining()] // v
                }
                return bytes
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}