package com.tomoima.multicamerasample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.SeekBar
import com.tomoima.multicamerasample.listeners.SurfaceTextureWaiter
import kotlinx.android.synthetic.main.fragment_camera.*
import com.tomoima.multicamerasample.models.CameraIdInfo
import com.tomoima.multicamerasample.models.State
import com.tomoima.multicamerasample.services.Camera
import com.tomoima.multicamerasample.ui.ConfirmationDialog
import com.tomoima.multicamerasample.ui.ErrorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class CameraFragment : Fragment() {

    companion object {
        private const val FRAGMENT_DIALOG = "dialog"
        private const val REQUEST_CAMERA_PERMISSION = 100
        private val TAG = CameraFragment.javaClass::getSimpleName.toString()
        fun newInstance() = CameraFragment()
    }

    private var camera: Camera? = null

    private lateinit var previewSize: Size

    private val zoomTransition = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = if(zoomTransition) {
        inflater.inflate(R.layout.fragment_camera_2, container, false)
    } else {
        inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        zoomBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            var progressValue = 0
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                this.progressValue = progress
                camera?.maxZoom?.let {
                    if(!camera1View.isAvailable || !camera2View.isAvailable ) return@let
                    val zoomValue = progressValue.toDouble()/seekBar.max * it
                    if(zoomTransition) {
                        // ADHOC
                        // Because max zoom is 7 and zoom level difference of two cameras are 2,
                        // Switch Camera 1 and 2 when zoom value is 100 / 7 * 2 = 2.857
                        if (zoomValue < 2.857) {
                            camera?.setZoom(zoomValue + 1)
                            // Delay view switch to ease the transition
                            Handler().postDelayed(
                                {
                                    camera1ViewLayout.visibility = View.INVISIBLE
                                    camera2ViewLayout.visibility = View.VISIBLE
                                }
                                , 200)

                        } else {
                            camera?.setZoom(zoomValue)
                            Handler().postDelayed(
                                {
                                    camera1ViewLayout.visibility = View.VISIBLE
                                    camera2ViewLayout.visibility = View.INVISIBLE
                                }
                                , 200)
                        }
                    } else {
                        camera?.setZoom(zoomValue)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }

        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camera = Camera.initInstance(manager)
        // set Seek bar zoom
        camera?.maxZoom?.let {
            val actualProgress = (100 / it).roundToInt()
            Log.d(TAG, "===== actual $actualProgress")
            zoomBar.progress = actualProgress
        }
    }

    override fun onResume() {
        super.onResume()
        if (camera1View.isAvailable && camera2View.isAvailable) {
            openCamera(camera1View.width, camera1View.height)
        } else {
            val waiter1 = SurfaceTextureWaiter(camera1View)
            val waiter2 = SurfaceTextureWaiter(camera2View)

            GlobalScope.launch {
                val result1 = waiter1.textureIsReady()
                val result2 = waiter2.textureIsReady()
                Log.d(TAG," ======== ready $result1 $result2" )
                // Assuming both textures are ready and they have the same width and height,
                // Just check the state of 1
                when(result1.state) {
                    State.ON_TEXTURE_AVAILABLE -> {
                        withContext(Dispatchers.Main) {
                            openDualCamera(width = result1.width, height = result1.height)
                        }
                    }
                    State.ON_TEXTURE_SIZE_CHANGED -> {
                        withContext(Dispatchers.Main) {
                            configureTransform(viewWidth = result1.width, viewHeight = result2.height)
                        }
                    }
                    else -> { }
                }
            }
            //camera1View.surfaceTextureListener = surfaceTextureListener
            //camera2View.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        camera?.close()
    }

    // Permissions
    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CAMERA_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun openCamera(width: Int, height: Int) {
        if (activity == null) {
            Log.e(TAG, "activity is not ready!")
            return
        }
        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)
                camera1View.setAspectRatio(previewSize.height, previewSize.width)
                configureTransform(width, height)
                it.open()
                val texture1 = camera1View.surfaceTexture
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture1)))
                updateCameraStatus(it.getCameraIds())
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun openDualCamera(width: Int, height: Int) {
        if (activity == null) {
            Log.e(TAG, "activity is not ready!")
            return
        }
        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)
                // FIXME should write this better
                camera1View.setAspectRatio(previewSize.height, previewSize.width)
                camera2View.setAspectRatio(previewSize.height, previewSize.width)
                val matrix = calculateTransform(width, height)
                camera1View.setTransform(matrix)
                camera2View.setTransform(matrix)
                it.open()
                val texture1 = camera1View.surfaceTexture
                val texture2 = camera2View.surfaceTexture
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                texture2.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture1), Surface(texture2)))
                updateCameraStatus(it.getCameraIds())
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun updateCameraStatus(cameraIdInfo: CameraIdInfo) {
        val (logicalCameraId, physicalCameraIds) = cameraIdInfo
        if(logicalCameraId.isNotEmpty()) {
            multiCameraSupportTv.text = "YES"
            logicalCameraTv.text = "[$logicalCameraId]"
        }
        if(physicalCameraIds.isNotEmpty()) {
            physicalCameraTv.text = physicalCameraIds
                .asSequence()
                .map { s -> "[$s]" }
                .reduce { acc, s -> "$acc,$s" }
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) : Matrix {
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        return matrix
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        camera1View.setTransform(matrix)
    }
}