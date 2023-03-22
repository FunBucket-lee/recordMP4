package com.qing.recordmp4

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment

class ConfirmationDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(activity).setMessage(R.string.request_permission)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!activity!!.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    ActivityCompat.requestPermissions(
                        activity!!,
                        arrayOf(Manifest.permission.CAMERA),
                        1
                    )
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                parentFragment!!.activity?.finish()
            }.create()
    }
}