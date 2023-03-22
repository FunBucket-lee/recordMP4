package com.qing.recordmp4.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.qing.recordmp4.utils.YUVTools.rotateP90
import com.qing.recordmp4.utils.YUVTools.rotateSP90
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.experimental.and


class H264EncodeConsumer : Thread() {

    companion object {
        private const val TAG = "EncodeVideo"
        private const val MIME_TYPE = "video/avc"

        // 间隔1s插入一帧关键帧
        private const val FRAME_INTERVAL = 1

        // 绑定编码器缓存区超时时间为10s
        private const val TIMES_OUT = 10000
    }

    // 硬编码器
    private var mVideoEncodec: MediaCodec? = null
    private var isExit = false
    private var isEncoderStart = false

    private var isAddKeyFrame = false
    private lateinit var mParams: EncoderParams
    private var newFormat: MediaFormat? = null
    private var mMuxerRef: WeakReference<MediaMuxerUtil>? = null
    private var mColorFormat = 0
    private var nanoTime: Long = 0 //System.nanoTime()

    @Synchronized
    fun setTmpMuxer(mMuxer: MediaMuxerUtil?, encoderParams: EncoderParams?) {
        if (mMuxer == null || encoderParams == null) {
            return
        }
        mMuxerRef = WeakReference(mMuxer)
        mParams = encoderParams
        val muxerUtil = mMuxerRef!!.get()
        if (muxerUtil != null && newFormat != null) {
            mMuxer.addTrack(newFormat!!, true)
        }
    }

    private fun startCodec() {
        try {
            val mCodecInfo = selectSupportCodec()
            if (mCodecInfo == null) {
                Log.d(TAG, "startCodec fail$MIME_TYPE")
                return
            }
            mColorFormat = selectSupportColorFormat(mCodecInfo)
            mVideoEncodec = MediaCodec.createByCodecName(mCodecInfo.name)
            val mFormat = MediaFormat().apply {
                setInteger(MediaFormat.KEY_BIT_RATE, mParams.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, mParams.frameRate)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, FRAME_INTERVAL)
            }
            if (mVideoEncodec != null) {
                mVideoEncodec!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mVideoEncodec!!.start()
                isEncoderStart = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopCodec() {
        if (mVideoEncodec != null) {
            mVideoEncodec!!.stop()
            mVideoEncodec!!.release()
            mVideoEncodec = null
            isAddKeyFrame = false
            isEncoderStart = false
            Log.d(TAG, "stopCodec")
        }
    }

    private val queue: LinkedBlockingQueue<RawData> = LinkedBlockingQueue()

    internal class RawData(var buf: ByteArray, var timeStamp: Long)

    fun addData(yuvData: ByteArray) {
        Log.e("chao", "**********add video" + System.nanoTime() / 1000 / 1000)
        queue.offer(RawData(yuvData, System.nanoTime()))
    }

    private fun removeData(): RawData? {
        return queue.poll()
    }

    private fun handleData(yuvData: ByteArray, timeStamp: Long) {
        if (!isEncoderStart) return
        try {
            val mWidth = mParams.frameWidth
            val mHeight = mParams.frameHeight
            val resultBytes = ByteArray(yuvData.size)
            if (mColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) { // I420
                rotateP90(yuvData, resultBytes, mWidth, mHeight)
            } else  /*if(mColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)*/ { //NV12
                rotateSP90(yuvData, resultBytes, mWidth, mHeight)
            }
            feedMediaCodecData(resultBytes, timeStamp)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun feedMediaCodecData(data: ByteArray, timeStamp: Long) {
        val inputBufferIndex = mVideoEncodec!!.dequeueInputBuffer(TIMES_OUT.toLong())
        if (inputBufferIndex >= 0) {
            val inputBuffer: ByteBuffer? = mVideoEncodec!!.getInputBuffer(inputBufferIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(data)
            }
            Log.e("chao", "video set pts......." + timeStamp / 1000 / 1000)
            mVideoEncodec!!.queueInputBuffer(
                inputBufferIndex,
                0,
                data.size,
                System.nanoTime() / 1000,
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            )
        }
    }

    override fun run() {
        try {
            if (!isEncoderStart) {
                sleep(200)
                startCodec()
            }
            while (!isExit && isEncoderStart) {
                val rawData = removeData()
                if (rawData != null) {
                    handleData(rawData.buf, rawData.timeStamp)
                }
                val mBufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex: Int
                do {
                    outputBufferIndex = mVideoEncodec!!.dequeueOutputBuffer(
                        mBufferInfo,
                        TIMES_OUT.toLong()
                    )
                    when (outputBufferIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            //                        Log.i(TAG, "INFO_TRY_AGAIN_LATER");
                        }

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            synchronized(this@H264EncodeConsumer) {
                                newFormat = mVideoEncodec!!.outputFormat
                                if (mMuxerRef != null) {
                                    val muxer = mMuxerRef!!.get()
                                    muxer?.addTrack(newFormat!!, true)
                                }
                            }
                            Log.i(TAG, "编码器输出缓存区格式改变，添加视频轨道到混合器")
                        }

                        else -> {
                            val outputBuffer: ByteBuffer? =
                                mVideoEncodec!!.getOutputBuffer(outputBufferIndex)
                            val type: Int = (outputBuffer!!.get(4) and 0x1F).toInt()
                            Log.d(TAG, "------还有数据---->$type")
                            if (type == 7 || type == 8) {
                                Log.e(TAG, "------PPS、SPS帧(非图像数据)，忽略-------")
                                mBufferInfo.size = 0
                            } else if (type == 5) {
                                if (mMuxerRef != null) {
                                    val muxer = mMuxerRef!!.get()
                                    if (muxer != null) {
                                        Log.i(
                                            TAG,
                                            "------编码混合  视频关键帧数据-----" + mBufferInfo.presentationTimeUs / 1000
                                        )
                                        muxer.pumpStream(outputBuffer, mBufferInfo, true)
                                    }
                                    isAddKeyFrame = true
                                }
                            } else {
                                if (isAddKeyFrame) {
                                    if (mMuxerRef != null) {
                                        val muxer = mMuxerRef!!.get()
                                        if (muxer != null) {
                                            Log.i(
                                                TAG,
                                                "------编码混合  视频普通帧数据-----" + mBufferInfo.presentationTimeUs / 1000
                                            )
                                            muxer.pumpStream(outputBuffer, mBufferInfo, true)
                                        }
                                    }
                                }
                            }
                            mVideoEncodec!!.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                } while (outputBufferIndex >= 0)
            }
            stopCodec()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    fun exit() {
        isExit = true
    }

    /**
     * 遍历所有编解码器，返回第一个与指定MIME类型匹配的编码器
     * 判断是否有支持指定mime类型的编码器
     */
    private fun selectSupportCodec(): MediaCodecInfo? {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val numCodecs = codecInfos.size
        for (i in 0 until numCodecs) {
            val codecInfo = codecInfos[i]
            if (!codecInfo.isEncoder) {
                continue
            }
            val types = codecInfo.supportedTypes
            for (j in types.indices) {
                if (types[j].equals(MIME_TYPE, ignoreCase = true)) {
                    return codecInfo
                }
            }
        }
        return null
    }

    /**
     * 根据mime类型匹配编码器支持的颜色格式
     */
    private fun selectSupportColorFormat(mCodecInfo: MediaCodecInfo): Int {
        val capabilities = mCodecInfo.getCapabilitiesForType(MIME_TYPE)
        val colorFormats = HashSet<Int>()
        for (i in capabilities.colorFormats) {
            colorFormats.add(i)
        }
        if (colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        if (colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        return 0
    }
}