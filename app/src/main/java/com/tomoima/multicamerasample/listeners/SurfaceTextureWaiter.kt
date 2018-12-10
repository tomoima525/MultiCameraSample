package com.tomoima.multicamerasample.listeners

import android.graphics.SurfaceTexture
import android.view.TextureView
import com.tomoima.multicamerasample.models.State
import com.tomoima.multicamerasample.models.SurfaceTextureInfo
import com.tomoima.multicamerasample.ui.AutoFitTextureView
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class SurfaceTextureWaiter(private val textureView: AutoFitTextureView) {

    suspend fun textureIsReady(): SurfaceTextureInfo =
            suspendCoroutine { cont ->
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                        cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_SIZE_CHANGED, width, height))
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean = true

                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                        cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_AVAILABLE, width, height))
                    }
                }
            }
}