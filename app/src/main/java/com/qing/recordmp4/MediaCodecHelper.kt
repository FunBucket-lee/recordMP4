package com.qing.recordmp4

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build

class MediaCodecHelper(
    private val filePath: String, private val mAudioParameter: AudioParameter,
    private val mVideoParameter: VideoParameter
) {

    private lateinit var mAudiMediaCodec: MediaCodec
    private lateinit var mVideoMediaCodec: MediaCodec
    private lateinit var mAudioMediaFormat: MediaFormat
    private lateinit var mVideoMediaFormat: MediaFormat
    private var mAudioTrackIndex = -1
    private var mVideoTrackIndex = -1
    private lateinit var mediaMuxer: MediaMuxer
    private var audioPts = 0L
    private var nanoTime = 0L
    private var isStart = false


    companion object {
        private const val WAIT_TIME = 0L
    }

    init {
        initAudioMediaCodec()
        initVideoMediaCodec()
        initMediaEncoder()
    }

    private fun initMediaEncoder() {
        try {
            mediaMuxer = MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mediaMuxer.setOrientationHint(90)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initVideoMediaCodec() {
        mVideoMediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            mVideoParameter.width,
            mVideoParameter.height
        )
        mVideoMediaFormat.apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_BIT_RATE, mVideoParameter.width * mVideoParameter.height * 4)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            //        设置压缩等级  默认是baseline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
            try {
                mVideoMediaCodec =
                    MediaCodec.createEncoderByType(mVideoMediaFormat.getString(MediaFormat.KEY_MIME)!!)
                mVideoMediaCodec.configure(
                    mVideoMediaFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initAudioMediaCodec() {
        mAudioMediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mAudioParameter.mediaMime)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, mAudioParameter.channel)
            setInteger(MediaFormat.KEY_FRAME_RATE, mAudioParameter.sampleRate)
            setInteger(MediaFormat.KEY_BIT_RATE, mAudioParameter.bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, mAudioParameter.profile)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Integer.MAX_VALUE)
        }
        try {
            mAudiMediaCodec = MediaCodec.createEncoderByType(
                mAudioMediaFormat.getString(
                    mAudioMediaFormat.getString(MediaFormat.KEY_MIME)!!
                )!!
            )
            mAudiMediaCodec.configure(
                mAudioMediaFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun encodeVideo(nv21: ByteArray, nanoTime: Long) {
        try {
            val inputBufferIndex = mVideoMediaCodec.dequeueInputBuffer(WAIT_TIME)
            if (inputBufferIndex > 0) {
                val inputBuffer = mVideoMediaCodec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(nv21)
                }
                mVideoMediaCodec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    (System.nanoTime() - nanoTime) / 1000,
                    0
                )
            }
            encodeVideoH264()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun encodeVideoH264() {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = mVideoMediaCodec.dequeueOutputBuffer(bufferInfo, WAIT_TIME)
        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mVideoTrackIndex = mediaMuxer.addTrack(mVideoMediaCodec.outputFormat)
            if (mAudioTrackIndex != -1 && mVideoTrackIndex != -1) {
                mediaMuxer.start()
            }
        }
        while (outputBufferIndex >= 0) {
            val outputBuffer = mVideoMediaCodec.getOutputBuffer(outputBufferIndex)
            if (mVideoTrackIndex >= -1) {
                mediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer!!, bufferInfo)
            }
            mVideoMediaCodec.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = mVideoMediaCodec.dequeueOutputBuffer(bufferInfo, WAIT_TIME)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
            }
        }
    }

    fun encodeAudioToAAC(data: ByteArray, len: Int) {
        try {
            val index: Int = mAudiMediaCodec.dequeueInputBuffer(WAIT_TIME)
            if (index >= 0) {
                val inputBuffer = mAudiMediaCodec.getInputBuffer(index)
                inputBuffer?.apply {
                    clear()
                    put(data)
                }
                val pts = getAudioPts(
                    len,
                    mAudioParameter.sampleRate,
                    mAudioParameter.channel,
                    mAudioParameter.bitrate
                )
                //数据缓冲送入解码器
                mAudiMediaCodec.queueInputBuffer(
                    index,
                    0,
                    0,
                    (System.nanoTime() - nanoTime) / 1000,
                    0
                )
            }
            getEncodeData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getEncodeData() {
        val bufferInfo = MediaCodec.BufferInfo()
        var flags = mAudiMediaCodec.dequeueOutputBuffer(bufferInfo, WAIT_TIME)
        if (flags == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mAudioTrackIndex = mediaMuxer.addTrack(mAudiMediaCodec.outputFormat)
            if (this.mAudioTrackIndex != -1 && this.mVideoTrackIndex != -1) {
                mediaMuxer.start()
            }
        } else {
            while (flags >= 0) {
                val outputBuffer = mAudiMediaCodec.getOutputBuffer(flags)
                outputBuffer?.apply {
                    if (mAudioTrackIndex != -1) {
                        mediaMuxer.writeSampleData(mAudioTrackIndex, outputBuffer, bufferInfo)
                    }
                }
                mAudiMediaCodec.releaseOutputBuffer(flags, false)
                flags = mAudiMediaCodec.dequeueOutputBuffer(bufferInfo, WAIT_TIME)
            }
        }
    }

    fun start() {
        isStart = true
        nanoTime = System.nanoTime()
        mVideoMediaCodec.start()
        mAudiMediaCodec.start()
    }

    fun stop() {
        mVideoMediaCodec.apply {
            stop()
            release()
        }
        mAudiMediaCodec.apply {
            stop()
            release()
        }
        mediaMuxer.apply {
            stop()
            release()
        }
        isStart = false
    }

    private fun getAudioPts(len: Int, sampleRate: Int, channel: Int, bitrate: Int): Long {
        audioPts += (1.0 * len / (sampleRate * channel * (bitrate / 8)) * 1000000.0).toLong()
        return audioPts
    }

    class AudioParameter(
        val mediaMime: String,
        val channel: Int,
        val sampleRate: Int,
        val bitrate: Int = -1,
        val profile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC
    )

    class VideoParameter(val width: Int, val height: Int)
}