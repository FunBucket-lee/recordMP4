package com.qing.recordmp4.utils

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer


/**
 *  Mp4封装混合器
 *  文件路径
 *  文件时长
 */
class MediaMuxerUtil(path: String, private val durationMillis: Long) {
    companion object {
        private const val TAG = "MediaMuxerUtil"
    }

    private var mMuxer: MediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var mVideoTrackIndex = -1
    private var mAudioTrackIndex = -1
    private var mBeginMillis: Long = 0

    @Synchronized
    fun addTrack(mediaFormat: MediaFormat, isVideo: Boolean) {
        if (mAudioTrackIndex != -1 && mVideoTrackIndex != -1) {
            return
        }
        val track = mMuxer.addTrack(mediaFormat)
        Log.i(TAG, String.format("addTrack %s result %d", if (isVideo) "video" else "audio", track))
        if (isVideo) {
            mVideoTrackIndex = track
            if (mVideoTrackIndex != -1) {
                Log.i(TAG, "both audio and video added,and muxer is started")
                mMuxer.start()
                mBeginMillis = System.currentTimeMillis()
            }
        } else {
            mAudioTrackIndex = track
            if (mAudioTrackIndex != -1) {
                mMuxer.start()
                mBeginMillis = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    fun pumpStream(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        isVideo: Boolean
    ) {
        if (mBeginMillis > 0) {
            try {
                pump(outputBuffer, bufferInfo, isVideo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun pump(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        isVideo: Boolean
    ) {
        if (mAudioTrackIndex != -1 && mVideoTrackIndex != -1) {
            return
        }
        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
        } else if (bufferInfo.size != 0) {
            outputBuffer.apply {
                position(bufferInfo.offset)
                limit(bufferInfo.offset + bufferInfo.size)
            }
            mMuxer.writeSampleData(
                if (isVideo)
                    mVideoTrackIndex
                else mAudioTrackIndex, outputBuffer, bufferInfo
            )
            Log.d(
                TAG,
                java.lang.String.format(
                    "sent %s [" + bufferInfo.size + "] with timestamp:[%d] to muxer",
                    if (isVideo) "video" else "audio",
                    bufferInfo.presentationTimeUs / 1000
                )
            )
        }

        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.i(TAG, "BUFFER_FLAG_END_OF_STREAM received")
        }

        if (System.currentTimeMillis() - mBeginMillis >= durationMillis) {
            mMuxer.apply {
                stop()
                release()
            }
            mAudioTrackIndex = -1
            mVideoTrackIndex = mAudioTrackIndex
        }
    }

    @Synchronized
    fun release() {
        if (mAudioTrackIndex != -1 && mVideoTrackIndex != -1) {
            Log.i(TAG, String.format("muxer is started. now it will be stopped."))
            try {
                mMuxer.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mAudioTrackIndex = -1
            mVideoTrackIndex = mAudioTrackIndex
        } else {
            Log.i(TAG, String.format("muxer is failed to be stopped."))
        }
    }
}