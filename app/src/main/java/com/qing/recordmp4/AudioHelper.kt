package com.qing.recordmp4

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat

class AudioHelper(private val activity: Activity) : Runnable {

    private lateinit var mAudioRecord: AudioRecord
    private var mAudioThread: Thread = Thread(this, "AudioThread")
    private var mAudioRecordBufferSize: Int = 0
    private var isStartRecording: Boolean = false
    var mAudioDataCallback: AudioDataCallback? = null

    companion object {
        private const val TAG = "AudioHelper"
        private const val SAMPLE_RATE = 44100
    }

    init {
        initAudioRecord()
    }

    private fun initAudioRecord() {
        checkAudioPermission()
        mAudioRecordBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        mAudioRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            ).setBufferSizeInBytes(mAudioRecordBufferSize).build()
    }

    private fun checkAudioPermission() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }
    }

    fun start() {
        if (!isStartRecording) {
            mAudioThread.start()
        }
    }

    fun stop() {
        isStartRecording = false
        mAudioRecord.stop()
    }

    override fun run() {
        mAudioRecord.startRecording()
        isStartRecording = true
        val data = ByteArray(mAudioRecordBufferSize)
        while (isStartRecording) {
            val res = mAudioRecord.read(data, 0, mAudioRecordBufferSize)
            if (res != AudioRecord.ERROR_INVALID_OPERATION) {
                mAudioDataCallback?.onData(data, res)
            }
        }
    }

    interface AudioDataCallback {
        fun onData(data: ByteArray, length: Int)
    }
}