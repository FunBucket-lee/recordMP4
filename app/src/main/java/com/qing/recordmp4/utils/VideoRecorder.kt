package com.qing.recordmp4.utils

import java.nio.ByteBuffer

class VideoRecorder(private val filePath: String) {
    private var mAacConsumer: AACEncodeConsumer? = null
    private var mH264Consumer: H264EncodeConsumer? = null
    private var mMuxer: MediaMuxerUtil? = null

    private fun setEncodeParams(): EncoderParams = EncoderParams(
        filePath,
        640,
        480,
        600000,
        30,
        false,
        "",
        44100,
        AACEncodeConsumer.CHANNEL_COUNT_MONO,
        AACEncodeConsumer.DEFAULT_SAMPLE_RATE,
        AACEncodeConsumer.CHANNEL_IN_MONO,
        AACEncodeConsumer.ENCODING_PCM_16BIT,
        0
    )

    fun addAudioData(buffer: ByteArray) {
        try {
            mAacConsumer?.addData(ByteBuffer.wrap(buffer), buffer.size)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addVideoData(frame: ByteArray) {
        try {
            mH264Consumer?.addData(frame)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        val params = setEncodeParams()
        // 创建音视频编码线程
        mH264Consumer = H264EncodeConsumer()
        mAacConsumer = AACEncodeConsumer()
        mMuxer = MediaMuxerUtil(path = params.videoPath, 1000000)
        mAacConsumer?.setTmpMuxer(mMuxer, params)
        mH264Consumer?.setTmpMuxer(mMuxer, params)
        // 配置好混合器后启动线程
        mAacConsumer?.start()
        mH264Consumer?.start()
    }

    fun stop() {
        // 停止混合器
        mMuxer?.release()
        mMuxer = null
        mAacConsumer?.setTmpMuxer(null, null)
        mH264Consumer?.setTmpMuxer(null, null)
        // 停止视频编码线程
        mH264Consumer?.exit()
        try {
            val t2 = mH264Consumer
            mH264Consumer = null
            t2?.interrupt()
            t2?.join()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 停止音频编码线程
        mAacConsumer?.exit()
        try {
            val t1 = mAacConsumer
            mAacConsumer = null
            t1?.interrupt()
            t1?.join()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}