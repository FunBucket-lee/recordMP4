package com.qing.recordmp4

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class ErrorDialog : DialogFragment() {
    companion object {

        private const val ARG_MESSAGE = "message"

        private var instance: ErrorDialog? = null

        @Synchronized
        fun getInstance(message: String): ErrorDialog {
            return if (instance == null) {
                var bundle = Bundle()
                bundle.putString(ARG_MESSAGE, message)
                ErrorDialog().apply {
                    arguments = bundle
                }
            } else {
                instance!!
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(activity!!).setMessage(arguments!!.getString(ARG_MESSAGE))
            .setNegativeButton(android.R.string.ok) { _, _ -> activity!!.finish() }.create()
    }
}