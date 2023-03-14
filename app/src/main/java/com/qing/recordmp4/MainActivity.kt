package com.qing.recordmp4

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.qing.recordmp4.databinding.LayoutMainActivityBinding


class MainActivity : ComponentActivity() {

    private lateinit var binding: LayoutMainActivityBinding
    private lateinit var mCameraManager: CameraManager
    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mImageReader: ImageReader
    private lateinit var request: CaptureRequest
    private lateinit var mCameraCaptureSession: CameraCaptureSession
    private lateinit var surface: Surface
    private lateinit var codecHelper: MediaCodecHelper

    private val mImageReaderListener = ImageReader.OnImageAvailableListener {
        val image = it.acquireNextImage()

        image.close()
    }

    private lateinit var cameraHandler: Handler

    private lateinit var mBackgroundThread: HandlerThread

    companion object {
        private val CAMERA_PERMISSION = arrayOf(
            Manifest.permission.CAMERA
        )
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutMainActivityBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        initData()
    }

    private fun initData() {
        val file = Environment.getExternalStorageDirectory().absolutePath + "muxerResult.mp4"
        codecHelper = MediaCodecHelper(
            file,
            MediaCodecHelper.AudioParameter(MediaFormat.MIMETYPE_VIDEO_AVC, 1, 40),
            MediaCodecHelper.VideoParameter(1080, 1920)
        )
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        initView()
    }

    private fun openCamera() {
        checkCameraPermission()
        // 1 创建相机管理器，调用系统相机
        mCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // 2 初始化数据监听类
        mImageReader = ImageReader.newInstance(
            binding.textureView.width,
            binding.textureView.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener(mImageReaderListener, cameraHandler)
        }
        //开启照相机
        try {
            val cameraId = mCameraManager.cameraIdList.last()
            mCameraManager.openCamera(mCameraManager.cameraIdList.last(), object :
                CameraDevice.StateCallback() {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onOpened(camera: CameraDevice) {
                    mCameraDevice = camera
                    //构建请求对象（设置预览参数，和输出对象）
                    val angle =
                        (getCameraOrientation(cameraId) - getDisplayRotation() / 360) % 360
                    Log.d(TAG, "onOpened: 调整角度为==》$angle")
                    val requestBuilder =
                        mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            .apply {
                                addTarget(surface)
                                addTarget(mImageReader.surface)
                                // 自动对焦
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                // 自动曝光
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                                )
                                set(CaptureRequest.JPEG_ORIENTATION, angle)
                            }
                    // 显示预览
                    request = requestBuilder.build()
                    mCameraDevice.createCaptureSession(SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(
                            OutputConfiguration(surface),
                            OutputConfiguration(mImageReader.surface)
                        ),
                        this@MainActivity.mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                mCameraCaptureSession = session
                                // 一直发送预览请求
                                mCameraCaptureSession.setRepeatingRequest(
                                    request,
                                    null, null
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                            }

                        }
                    ))
                }

                override fun onDisconnected(camera: CameraDevice) {

                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.d(TAG, "onError: $error")
                }

            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.CAMERA
                )
            ) {
                ActivityCompat.requestPermissions(this, CAMERA_PERMISSION, 1)
            }
        }
    }

    private fun initView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "onSurfaceTextureAvailable: texture 初始化")
                this@MainActivity.surface = Surface(surface)
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }

        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    private fun closeCamera() {
        mCameraDevice.apply {
            close()
            releaseInstance()
        }
        mCameraManager.apply {
            releaseInstance()
        }
        mImageReader.close()
        mCameraCaptureSession.close()
        mBackgroundThread.apply {
            join()
            finish()
        }
    }

    /**
     * 获取摄像头角度
     */
    private fun getCameraOrientation(cameraId: String): Int {
        // 获取该相机的特征
        val properties = mCameraManager.getCameraCharacteristics(cameraId)
        // 获取旋转角度
        return properties.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    /**
     * 获取屏幕角度
     */
    private fun getDisplayRotation(): Int {
        val wm = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                wm.defaultDisplay
            }
        return when (display!!.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 开启子线程
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread.start()
        cameraHandler = Handler(mBackgroundThread.looper)
    }

}
