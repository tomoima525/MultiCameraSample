package com.tomoima.multicamerasample.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.tomoima.multicamerasample.R

/**
 * Shows OK/Cancel confirmation dialog about camera permission.
 */
class ConfirmationDialog : DialogFragment() {

    companion object {
        private const val VALUE_REQUEST_ID = "VALUE_REQUEST_ID"
        fun newInstance(requestId : Int): ConfirmationDialog {
            val bundle = Bundle()
            bundle.putInt(VALUE_REQUEST_ID, requestId)
            val confirmationDialog = ConfirmationDialog()
            confirmationDialog.arguments = bundle
            return confirmationDialog
        }
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val permissionId = savedInstanceState?.getInt(VALUE_REQUEST_ID) ?: 0
        return AlertDialog.Builder(activity)
            .setMessage(R.string.request_permission)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                parentFragment?.requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    permissionId
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                parentFragment?.activity?.finish()
            }
            .create()
    }
}
