package com.qing.recordmp4

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.qing.recordmp4.databinding.LayoutMainActivityBinding
import com.qing.recordmp4.utils.VideoRecorder


class MainActivity : FragmentActivity() {

    private lateinit var binding: LayoutMainActivityBinding
    private var mRecorder: VideoRecorder? = null
    private var path = ""
    private var recordThread: Thread? = null
    private var isStart = false
    private val bufferSize = AudioRecord.getMinBufferSize(
        44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    private val mAudioRecord: AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        44100,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize * 2
    )
    private val recordRunnable = Runnable {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            //int bufferSize = 320
            var bytesRecord: Int
            val tempBuffer = ByteArray(bufferSize)
            if (mAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                stopRecord()
                return@Runnable
            }
            mAudioRecord.startRecording()
            //writeToFileHead()
            while (isStart) {
                bytesRecord = mAudioRecord.read(tempBuffer, 0, bufferSize)
                if (bytesRecord == AudioRecord.ERROR_INVALID_OPERATION || bytesRecord == AudioRecord.ERROR_BAD_VALUE) {
                    continue
                }
                if (bytesRecord != 0 && bytesRecord != -1) {
                    mRecorder?.addAudioData(tempBuffer)
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutMainActivityBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        initView(savedInstanceState)
    }

    fun click() {
        if (mRecorder == null) {
            Toast.makeText(this, "start record...", Toast.LENGTH_SHORT).show()
            startRecord()
            path = externalCacheDir!!.path + "/record-${System.currentTimeMillis()}.mp4"
            mRecorder = VideoRecorder(path)
            mRecorder!!.start()
        } else {
            Toast.makeText(this, "save path==>${path}.", Toast.LENGTH_SHORT).show()
            stopRecord()
            mRecorder!!.stop()
            mRecorder = null
        }
    }

    private fun initView(savedInstanceState: Bundle?) {

        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, Camera2BasicFragment()).commit()

    }

    fun addVideoData(data: ByteArray) {
        mRecorder?.addVideoData(data)
    }

    /**
     * 销毁线程方法
     */
    private fun destroyThread() {
        try {
            isStart = false
            if (recordThread != null && Thread.State.RUNNABLE == recordThread!!.getState()) {
                try {
                    Thread.sleep(500)
                    recordThread!!.interrupt()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            recordThread = null
        }
    }

    /**
     * 启动录音线程
     */
    private fun startThread() {
        destroyThread()
        isStart = true
        if (recordThread == null) {
            recordThread = Thread(recordRunnable)
            recordThread!!.start()
        }
    }

    /**
     * 启动录音
     *
     */
    private fun startRecord() {
        try {
            startThread()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * 停止录音
     */
    private fun stopRecord() {
        try {
            destroyThread()
            if (mAudioRecord.state == AudioRecord.STATE_INITIALIZED) {
                mAudioRecord.stop()
            }
            mAudioRecord.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
