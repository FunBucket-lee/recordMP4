package com.qing.recordmp4.utils

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue


/**
 * 对ACC音频进行编码
 */
class AACEncodeConsumer : Thread() {

    companion object {
        private const val TAG = "AACEncodeConsumer"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val TIMES_OUT = 10000L
        private const val ACC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        private const val BUFFER_SIZE = 3584 //1600
        private const val AUDIO_BUFFER_SIZE = 1024

        /**
         * 默认采样率
         */
        const val DEFAULT_SAMPLE_RATE = 44100

        /**
         * 通道数为1
         */
        const val CHANNEL_COUNT_MONO = 1

        /**
         * 通道数为2
         */
        const val CHANNEL_COUNT_STEREO = 2

        /**
         * 单声道
         */
        const val CHANNEL_IN_MONO: Int = AudioFormat.CHANNEL_IN_MONO

        /**
         * 立体声
         */
        const val CHANNEL_IN_STEREO: Int = AudioFormat.CHANNEL_IN_STEREO

        /**
         * 16位采样精度
         */
        const val ENCODING_PCM_16BIT: Int = AudioFormat.ENCODING_PCM_16BIT

        /**
         * 8位采样精度
         */
        const val ENCODING_PCM_8BIT: Int = AudioFormat.ENCODING_PCM_8BIT

        /**
         * 音频源为MIC
         */
        const val SOURCE_MIC = MediaRecorder.AudioSource.MIC
    }

    // 编码器
    private var isExit = false
    private var isEncoderStarted = false
    private lateinit var mMuxerRef: WeakReference<MediaMuxerUtil>
    private lateinit var mParams: EncoderParams
    private lateinit var mAudioEncoder: MediaCodec
    private var newFormat: MediaFormat? = null
    private var prevPresentationTimes: Long = 0
    private var nanoTime: Long = 0 //System.nanoTime()

    fun setTmpMuxer(mMuxer: MediaMuxerUtil?, encoderParams: EncoderParams?) {
        if(mMuxer == null || encoderParams == null){
            return
        }
        mMuxerRef = WeakReference(mMuxer)
        mParams = encoderParams
        val muxer = mMuxerRef.get()
        if (muxer != null && newFormat != null) {
            muxer.addTrack(newFormat!!, false)
        }
    }

    class RawData {
        var buf: ByteArray = ByteArray(BUFFER_SIZE)
        var readBytes = 0
        var timeStamp: Long = 0

        fun merge(byteBuffer: ByteBuffer, length: Int) {
            System.arraycopy(byteBuffer.array(), byteBuffer.arrayOffset(), buf, readBytes, length)
            readBytes += length
            timeStamp = System.nanoTime()
        }

        fun canMerge(length: Int): Boolean {
            return readBytes + length < buf.size
        }
    }

    private val queue: LinkedBlockingQueue<RawData> = LinkedBlockingQueue()

    private var bigShip: RawData? = null

    /**
     * queue数据没处理完时，先放到bigShip里，确保编码器消费速度
     */
    fun addData(byteBuffer: ByteBuffer, length: Int) {
        if (bigShip == null) {
            bigShip = RawData()
            bigShip!!.merge(byteBuffer, length)
            if (queue.isEmpty()) {
                queue.offer(bigShip)
                bigShip = null
            }
        } else {
            if (bigShip!!.canMerge(length)) {
                bigShip!!.merge(byteBuffer, length)
            } else {
                queue.offer(bigShip)
                bigShip = null
            }
        }
    }

    private fun removeData(): RawData? {
        return queue.poll()
    }

    override fun run() {
        startCodec()
        while (!isExit) {
            try {
                val data = removeData()
                if (data != null) {
                    Log.d(TAG, "run: onWebRtcAudioRecording take data")
                    encoderBytes(data.buf, data.readBytes, data.timeStamp)
                }
                val mBufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex = 0
                while (outputBufferIndex >= 0) {
                    outputBufferIndex = mAudioEncoder.dequeueOutputBuffer(mBufferInfo, TIMES_OUT)
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "run: INFO_TRY_AGAIN_LATER")
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "run: INFO_OUTPUT_FORMAT_CHANGED")
                        synchronized(this@AACEncodeConsumer) {
                            newFormat = mAudioEncoder.outputFormat
                            val muxer = mMuxerRef.get()
                            muxer?.addTrack(newFormat!!, false)
                        }
                    } else {
                        if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            mBufferInfo.size = 0
                        }
                        if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "数据流结束，退出循环")
                            break
                        }
                        val outputBuffer = mAudioEncoder.getOutputBuffer(outputBufferIndex)
                        if (mBufferInfo.size != 0) {
                            if (outputBuffer == null) {
                                throw RuntimeException("encodecOutputBuffer" + outputBufferIndex + "was null")
                            }
                            val muxer = mMuxerRef.get()
                            if (muxer != null) {
                                Log.i(
                                    TAG,
                                    "------编码混合音频数据------------" + mBufferInfo.presentationTimeUs / 1000
                                )
                                muxer.pumpStream(outputBuffer, mBufferInfo, false)
                            }
                        }
                        mAudioEncoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        stopCodec()
    }

    private fun encoderBytes(audioBuf: ByteArray?, readBytes: Int, timeStamp: Long) {
        val inputBufferIndex = mAudioEncoder.dequeueInputBuffer(timeStamp)
        if (inputBufferIndex >= 0) {
            val inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex)
            if (audioBuf == null || readBytes <= 0) {
                mAudioEncoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    System.currentTimeMillis() / 1000,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } else {
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(audioBuf)
                }
                Log.e("chao", "audio set pts-------" + timeStamp / 1000 / 1000)
                mAudioEncoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    readBytes,
                    System.currentTimeMillis() / 1000,
                    0
                )
            }
        }
    }

    private fun startCodec() {
        val mCodecInfo = selectSupportCodec() ?: return
        try {
            mAudioEncoder = MediaCodec.createByCodecName(mCodecInfo.name)
            val mediaFormat = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MIME_TYPE)
                setInteger(MediaFormat.KEY_BIT_RATE, mParams.bitRate)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, mParams.audioSampleRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, ACC_PROFILE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, mParams.audioChannelCount)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE)
            }
            mAudioEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mAudioEncoder.start()
            isEncoderStarted = true
        } catch (e: Exception) {
            Log.d(TAG, "startCodec: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun stopCodec() {
        try {
            mAudioEncoder.stop()
            mAudioEncoder.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isEncoderStarted = false
    }

    fun exit() {
        isExit = true
    }

    /**
     * 遍历所有编解码器，返回第一个与指定MIME类型匹配的编码器
     * 判断是否有支持指定mime类型的编码器
     */
    private fun selectSupportCodec(): MediaCodecInfo? {
        val codeList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val numCodes = codeList.codecInfos.size
        for (i in 0 until numCodes) {
            val mediaCodecInfo = codeList.codecInfos[i]
            if (mediaCodecInfo.isEncoder) {
                continue
            }
            val supportedTypes = mediaCodecInfo.supportedTypes
            for (j in supportedTypes.indices) {
                if (supportedTypes[j] == MIME_TYPE) {
                    return mediaCodecInfo
                }
            }
        }
        return null
    }
}