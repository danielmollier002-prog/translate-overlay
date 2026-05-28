package com.translateoverlay.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.TextView

class CaptureRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val service = pendingService
        val resultView = pendingResultView
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null && service != null) {
            service.setupMediaProjection(resultCode, data)
            if (resultView != null) {
                service.doCapture(
                    (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                        .getMediaProjection(resultCode, data),
                    resultView
                )
            }
        } else {
            resultView?.let {
                service?.let { svc ->
                    svc.javaClass // just to use the ref
                }
            }
        }
        pendingService = null
        pendingResultView = null
        finish()
    }

    companion object {
        const val REQUEST_CODE = 2001
        var pendingService: FloatingBubbleService? = null
        var pendingResultView: TextView? = null
    }
}
