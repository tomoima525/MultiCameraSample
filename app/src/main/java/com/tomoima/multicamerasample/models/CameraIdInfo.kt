package com.tomoima.multicamerasample.models

data class CameraIdInfo(
    val logicalCameraId: String = "",
    val physicalCameraIds: List<String> = emptyList()
)