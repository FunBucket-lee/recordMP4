package com.qing.recordmp4

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.qing.recordmp4.databinding.FragmentCamera2BasicBinding
import com.qing.recordmp4.utils.YUVTools
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class Camera2BasicFragment : Fragment() {
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    companion object {
        private val ORIENTATIONS = SparseIntArray()
        private const val FRAGMENT_DIALOG = "dialog"
        private const val TAG = "Camera2BasicFragment"

        /**
         * Camera state: Showing camera preview.
         */
        private const val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private const val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private const val STATE_WAITING_PRE_CAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than preCapture.
         */
        private const val STATE_WAITING_NON_PRE_CAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private const val STATE_PICTURE_TAKEN = 4

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_HEIGHT = 1080

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as the
         * respective max size, and whose aspect ratio matches with the specified value. If such size
         * doesn't exist, choose the largest one that is at most as large as the respective max size,
         * and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended output
         *                          class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal {@code Size}, or an arbitrary one if none were big enough
         */
        private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size
        ): Size {
            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = emptyList<Size>().toMutableList()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = emptyList<Size>().toMutableList()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return if (bigEnough.isNotEmpty()) {
                Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.isNotEmpty()) {
                Collections.max(bigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                choices[0]
            }
        }
    }

    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                lhs!!.width.toLong() * lhs.height -
                        rhs!!.width.toLong() * rhs.height
            )
        }
    }

    private lateinit var binding: FragmentCamera2BasicBinding

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            var isAllGrant = true
            it.values.forEach { isGranted ->
                isAllGrant = isGranted && isAllGrant
            }

            if (!isAllGrant) {
                ErrorDialog.getInstance("不授权权限，将无法使用该功能哦~").show(
                    childFragmentManager,
                    FRAGMENT_DIALOG
                )
            }
        }

    /**
     * ID of the current {@link CameraDevice}.
     */
    private var mCameraId = ""

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private var mTextureView: AutoFitTextureView? = null

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private var mCaptureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private var mCameraDevice: CameraDevice? = null

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private var mPreviewSize: Size? = null

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var mBackgroundThread: HandlerThread? = null

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private var mBackgroundHandler: Handler? = null

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private var mImageReader: ImageReader? = null

    /**
     * This is the output file for our picture.
     */
    private lateinit var mFile: File

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private val mCameraOpenCloseLock = Semaphore(1)

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private lateinit var mPreviewRequest: CaptureRequest

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private var mState = STATE_PREVIEW

    /**
     * Whether the current camera device supports Flash or not.
     */
    private var mFlashSupported = false

    /**
     * Orientation of the camera sensor
     */
    private var mSensorOrientation = 0

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release()
            mCameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            mCameraOpenCloseLock.release()
            mCameraDevice?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            mCameraDevice?.close()
            val activity = activity
            activity?.finish()
        }

    }

    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }

    }

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener {
        val data = YUVTools.getBytesFromImageReader(it)
        (activity as MainActivity).addVideoData(data!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCamera2BasicBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mTextureView = binding.textureView
        initListener()
        mFile = File(activity!!.getExternalFilesDir(null), "pic.jpg")
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (binding.textureView.isAvailable) {
            openCamera(mTextureView!!.width, mTextureView!!.height)
        } else {
            binding.textureView.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        stopBackgroundThread()
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        val cameraManager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                    map.getOutputSizes(ImageFormat.JPEG).asList(),
                    CompareSizesByArea()
                )
                mImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                mImageReader?.setOnImageAvailableListener(
                    mOnImageAvailableListener,
                    mBackgroundHandler
                )
                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity!!.display!!.rotation
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                var swappedDimensions = false
                when (displayRotation) {
                    Surface.ROTATION_0 -> {}
                    Surface.ROTATION_90 -> {
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true
                        }
                    }

                    Surface.ROTATION_180 -> {}
                    Surface.ROTATION_270 -> {
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true
                        }
                    }

                    else -> {
                        Log.e(
                            TAG,
                            "setUpCameraOutputs: Display rotation is invalid: $displayRotation"
                        )
                    }
                }
                val displaySize = Point()
                val bounds = if (Build.VERSION.SDK_INT < 30) {
                    val rect = Rect()
                    activity!!.windowManager.defaultDisplay.getRectSize(rect)
                    rect
                } else {
                    activity!!.windowManager.currentWindowMetrics.bounds
                }
                displaySize.apply {
                    x = bounds.left
                    y = bounds.top
                }

                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width
                    maxPreviewWidth = displaySize.y
                    maxPreviewHeight = displaySize.x
                }

                maxPreviewWidth = min(maxPreviewWidth, MAX_PREVIEW_WIDTH)
                maxPreviewHeight = min(maxPreviewHeight, MAX_PREVIEW_HEIGHT)

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth,
                    rotatedPreviewHeight,
                    maxPreviewWidth,
                    maxPreviewHeight,
                    largest
                )
                // We fit the aspect ratio of TextureView to the size of preview we picked.
                val orientation = resources.configuration.orientation
                if (orientation == ORIENTATION_LANDSCAPE) {
                    mTextureView?.setAspectRatio(mPreviewSize!!.width, mPreviewSize!!.height)
                } else {
                    mTextureView?.setAspectRatio(mPreviewSize!!.height, mPreviewSize!!.width)
                }

                // Check if the flash is supported.
                val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                mFlashSupported = available == null

                mCameraId = cameraId
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * Starts a background thread and its {@link Handler}.
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initListener() {
        binding.picture.setOnClickListener {
            (activity as MainActivity).click()
        }
        binding.info.setOnClickListener {
            activity?.let {
                AlertDialog.Builder(it).setMessage(R.string.intro_message).setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = mTextureView?.surfaceTexture
            assert(texture != null)

            // We configure the size of default buffer to be the size of camera preview we want.
            texture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    addTarget(mImageReader!!.surface)
                }

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice!!.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(
                        OutputConfiguration(surface),
                        OutputConfiguration(mImageReader!!.surface)
                    ), activity!!.mainExecutor, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if(mCameraDevice == null){
                                return
                            }
                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = session
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder)
                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build()
                                mCaptureSession!!.setRepeatingRequest(
                                    mPreviewRequest,
                                    mCaptureCallback,
                                    mBackgroundHandler
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            showToast("打开相机失败")
                        }
                    }
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (activity == null || mTextureView == null || null == mPreviewSize) {
            return
        }
        val rotation = activity!!.display!!.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, mPreviewSize!!.width.toFloat(), mPreviewSize!!.height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(
                    viewHeight.toFloat() / mPreviewSize!!.height,
                    viewWidth.toFloat() / mPreviewSize!!.width
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
            }

            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }

        }

        mTextureView!!.setTransform(matrix)
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(
                activity!!,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission()
            return
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        try {
            val cameraManager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (!mCameraOpenCloseLock.tryAcquire()) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.tryAcquire()
            if (mCaptureSession != null) {
                mCaptureSession!!.close()
                mCaptureSession = null
            }
            if (mCameraDevice != null) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }
            if (mImageReader != null) {
                mImageReader!!.close()
                mImageReader = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    /**
     * Initiate a still image capture.
     */
    private fun takePicture() {
        lockFocus()
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {// This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            mState = STATE_WAITING_LOCK
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(),
                mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private fun unlockFocus() {
        try {// Reset the auto-focus trigger
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            setAutoFlash(mPreviewRequestBuilder)
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(),
                mCaptureCallback,
                mBackgroundHandler
            )
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW
            mCaptureSession?.setRepeatingRequest(
                mPreviewRequest,
                mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> {
                    // We have nothing to do when the camera preview is working normally.
                }

                STATE_WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState
                    ) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        }
                    } else {
                        runPreCaptureSequence()
                    }
                }

                STATE_WAITING_PRE_CAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        mState = STATE_WAITING_NON_PRE_CAPTURE
                    }
                }

                STATE_WAITING_NON_PRE_CAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        activity?.let {
            it.runOnUiThread {
                Toast.makeText(it, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setAutoFlash(mPreviewRequestBuilder: CaptureRequest.Builder) {
        if (mFlashSupported) {
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private fun runPreCaptureSequence() {
        try {// This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRE_CAPTURE
            mCaptureSession?.capture(
                mPreviewRequestBuilder.build(),
                mCaptureCallback,
                mBackgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private fun captureStillPicture() {
        try {
            if (null == activity || null == mCameraDevice) {
                return
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(mImageReader!!.surface)
            // Use the same AE and AF modes as the preview.
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            setAutoFlash(captureBuilder)

            // Orientation
            val rotation = activity!!.display!!.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    showToast("Save:$mFile")
                    Log.d(TAG, "onCaptureCompleted: $mFile")
                    unlockFocus()
                }
            }
            mCaptureSession!!.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder.build(), captureCallback, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private fun getOrientation(rotation: Int): Int {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360
    }
}