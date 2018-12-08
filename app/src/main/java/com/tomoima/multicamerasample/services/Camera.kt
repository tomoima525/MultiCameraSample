package com.tomoima.multicamerasample.services

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.OrientationEventListener
import android.view.Surface
import com.tomoima.multicamerasample.extensions.*
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.support.v4.math.MathUtils.clamp


private const val TAG = "CAMERA"

private enum class State {
    PREVIEW,
    WAITING_LOCK,
    WAITING_PRECAPTURE,
    WAITING_NON_PRECAPTURE,
    TAKEN
}

val ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}

enum class WBMode {
  AUTO, SUNNY, INCANDECENT
}

private const val MAX_PREVIEW_WIDTH = 1920
private const val MAX_PREVIEW_HEIGHT = 1080

interface ImageHandler {
    fun handleImage(image: Image) :Runnable
}

interface OnFocusListener {
    fun onFocusStateChanged(focusState: Int)
}

/**
 * Listener interface that will send back the newly created [Size] of our camera output
 */
interface OnViewportSizeUpdatedListener {
    fun onViewportSizeUpdated(viewportWidth: Int, viewportHeight: Int)
}

/**
 * Controller class that operates Non-UI Camera activity
 */
class Camera constructor(private val cameraManager: CameraManager) {

    companion object {
        // Make thread-safe Singleton
        @Volatile var instance: Camera? = null
            private set

        fun initInstance(cameraManager: CameraManager): Camera {
            val i = instance
            if (i != null) {
                return i
            }
            return synchronized(this) {
                val created = Camera(cameraManager)
                instance = created
                created
            }
        }
    }

    private val characteristics: CameraCharacteristics

    /**
     * An id for camera device
     */
    private val cameraId: String

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val openLock = Semaphore(1)

    private var cameraDevice: CameraDevice? = null
    /**
     * An [ImageReader] that handles still image capture.
     */
    private var imageReader: ImageReader? = null
    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    private var focusListener: OnFocusListener? = null
    /**
     * The current state of camera state for taking pictures.
     *
     * @see .captureCallback
     */
    private var state = State.PREVIEW
    private var aeMode = CaptureRequest.CONTROL_AE_MODE_ON
    private var preAfState: Int? = null
    var wbMode: WBMode = WBMode.AUTO
    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null
    private var surface: Surface? = null
    private var isClosed = true
    var deviceRotation: Int = 0 // Device rotation is defined by Screen Rotation

    var viewPortSizeListener: OnViewportSizeUpdatedListener? = null

    init {
        cameraId = setUpCameraId(manager = cameraManager)
        characteristics = cameraManager.getCameraCharacteristics(cameraId)
    }

    // Callbacks
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            cameraDevice = camera
            openLock.release()
            isClosed = false
        }

        override fun onClosed(camera: CameraDevice?) {
            isClosed = true
        }

        override fun onDisconnected(camera: CameraDevice?) {
            openLock.release()
            camera?.close()
            cameraDevice = null
            isClosed = true
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            openLock.release()
            camera?.close()
            cameraDevice = null
            isClosed = true
        }
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            //TODO: handle error
        }

        override fun onConfigured(session: CameraCaptureSession) {
            // if camera is closed
            if(isClosed) return
            captureSession = session
            startPreview()
        }

    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                State.PREVIEW -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                    if (afState == preAfState) {
                        return
                    }
                    preAfState = afState
                    focusListener?.onFocusStateChanged(afState)
                }

                State.WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    // Auto Focus state is not ready in the first place
                    if (afState == null) {
                        runPreCapture()
                    } else if (CaptureResult.CONTROL_AF_STATE_INACTIVE == afState ||
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            captureStillPicture()
                        } else {
                            runPreCapture()
                        }
                    } else {
                        captureStillPicture()
                    }
                }

                State.WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                        || aeState == CaptureRequest.CONTROL_AE_STATE_PRECAPTURE
                        || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                        || aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED) {
                        state = State.WAITING_NON_PRECAPTURE
                    }
                }

                State.WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureRequest.CONTROL_AE_STATE_PRECAPTURE) {
                        captureStillPicture()
                    }
                }
                else -> { }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }

    }

    // Camera interfaces
    /**
     * Open camera and setup background handler
     */
    fun open() {

        try {
            if(!openLock.tryAcquire(3L, TimeUnit.SECONDS)) {
                throw IllegalStateException("Camera launch failed")
            }

            if(cameraDevice != null) {
                openLock.release()
                return
            }

            startBackgroundHandler()

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e : SecurityException) {

        }
    }

    /**
     * Start camera. Should be called after open() is successful
     */
    fun start(surface: Surface) {
        this.surface = surface

        // setup camera session
        val size = characteristics.getCaptureSize(CompareSizesByArea())
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        cameraDevice?.createCaptureSession(
            listOf(surface, imageReader?.surface),
            captureStateCallback,
            backgroundHandler
        )
    }

    fun takePicture(handler : ImageHandler) {
        if (cameraDevice == null) {
            throw IllegalStateException("Camera device not ready")
        }

        if(isClosed) return
        imageReader?.setOnImageAvailableListener(object: ImageReader.OnImageAvailableListener{
            override fun onImageAvailable(reader: ImageReader) {
                val image = reader.acquireNextImage()
                backgroundHandler?.post(handler.handleImage(image = image))
            }
        }, backgroundHandler)

        lockFocus()
    }

    fun close() {
        try {
            if(openLock.tryAcquire(3, TimeUnit.SECONDS))
                isClosed = true
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            surface?.release()
            surface = null

            imageReader?.close()
            imageReader = null
            stopBackgroundHandler()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error closing camera $e")
        } finally {
            openLock.release()
        }
    }

    // internal methods

    /**
     * Set up camera Id from id list
     */
    private fun setUpCameraId(manager: CameraManager): String {
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)

            // We don't use a front facing camera in this sample.
            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null &&
                cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                continue
            }
            return cameraId
        }
        throw IllegalStateException("Could not set Camera Id")
    }

    private fun startBackgroundHandler() {
        if (backgroundThread != null) return

        backgroundThread = HandlerThread("Camera-$cameraId").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundHandler() {
        backgroundThread?.quitSafely()
        try {
            // TODO: investigate why thread does not end when join is called
            // backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "===== stop background error $e")
        }
    }

    private fun startPreview() {
        try {
            if(!openLock.tryAcquire(1L, TimeUnit.SECONDS)) return
            if(isClosed) return
            state = State.PREVIEW
            val builder = createPreviewRequestBuilder()
            captureSession?.setRepeatingRequest(
                builder?.build(), captureCallback, backgroundHandler)

        } catch (e1: IllegalStateException) {

        } catch (e2: CameraAccessException) {

        } catch (e3: InterruptedException) {

        } finally {
            openLock.release()
        }
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewRequestBuilder(): CaptureRequest.Builder? {
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder?.addTarget(surface)
        enableDefaultModes(builder)
        return builder
    }

    private fun enableDefaultModes(builder: CaptureRequest.Builder?) {
        if(builder == null) return

        // Auto focus should be continuous for camera preview.
        // Use the same AE and AF modes as the preview.
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        if (characteristics.isContinuousAutoFocusSupported()) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            )
        }

        if (characteristics.isAutoExposureSupported(aeMode)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        when(wbMode) {
            WBMode.AUTO -> {
              if (characteristics.isAutoWhiteBalanceSupported()) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
              }
            }
            WBMode.SUNNY -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            }
            WBMode.INCANDECENT -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
            }
        }

        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            state = State.WAITING_LOCK

            val builder = createPreviewRequestBuilder()

            if(!characteristics.isContinuousAutoFocusSupported()) {
                // If continuous AF is not supported , start AF here
                builder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
            }
            captureSession?.capture(builder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "lockFocus $e")
        }
    }

    private fun runPreCapture() {
        try {
            state = State.WAITING_PRECAPTURE
            val builder = createPreviewRequestBuilder()
            builder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            captureSession?.capture(builder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "runPreCapture $e")
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        state = State.TAKEN
        try {
            // This is the CaptureRequest.Builder that we use to take a picture.
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            enableDefaultModes(builder)
            builder?.addTarget(imageReader?.surface)
            builder?.addTarget(surface)
            captureSession?.stopRepeating()
            captureSession?.capture(
                builder?.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult) {
                        // Once still picture is captured, ImageReader.OnImageAvailable gets called
                        // You can do completion task here
                    }
                },
                backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStillPicture $e")
        }
    }


  /**
   * Focus manually
   * @param x touch X coordinate
   * @param y touch Y coordinate
   * @param width screen width
   * @param height screen height
   */
  fun manualFocus(x: Float, y: Float, width: Int, height: Int) {
    // captureSession can be null with Monkey tap
    if (captureSession == null || cameraDevice == null) {
      return
    }
    try {
      val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
      builder.addTarget(surface)

      val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      val areaSize = 200

      if (rect == null) {
        return
      }

      val right = rect.right
      val bottom = rect.bottom
      val centerX = x.toInt()
      val centerY = y.toInt()
      // Adjust the point of focus in the screen
      val ll = (centerX * right - areaSize) / width
      val rr = (centerY * bottom - areaSize) / height

      val focusLeft = clamp(ll, 0, right)
      val focusBottom = clamp(rr, 0, bottom)
      val newRect = Rect(focusLeft, focusBottom, focusLeft + areaSize, focusBottom + areaSize)
      // Adjust focus area with metering weight
      val meteringRectangle = MeteringRectangle(newRect, 500)
      builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
      builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
      builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

      // Request should be repeated to maintain preview focus
      captureSession?.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

      // Trigger Focus
      builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
      builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
              CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
      captureSession?.capture(builder.build(), captureCallback, backgroundHandler)
    } catch (e: IllegalStateException) {
    } catch (e: CameraAccessException) {
    }
  }
    /**
     * Retrieves the image orientation from the specified screen rotation.
     * Used to calculate bitmap image rotation
     */
    fun getImageOrientation(): Int {
        if (deviceRotation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(deviceRotation) + sensorOrientation + 270) % 360
    }

    fun getCaptureSize() = characteristics.getCaptureSize(CompareSizesByArea())

    fun getPreviewSize(aspectRatio: Float) = characteristics.getPreviewSize(aspectRatio)

    /**
     * Get sensor orientation.
     * 0, 90, 180, 270.
     */
    fun getSensorOrientation() = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    fun getFlashSupported() = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    fun chooseOptimalSize(textureViewWidth: Int,
                          textureViewHeight: Int,
                          maxWidth: Int,
                          maxHeight: Int,
                          aspectRatio: Size): Size =
            characteristics.chooseOptimalSize(
                    textureViewWidth,
                    textureViewHeight,
                    maxWidth,
                    maxHeight,
                    aspectRatio
            )

    fun areDimensionsSwapped(sensorOrientation: Int, displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }
}