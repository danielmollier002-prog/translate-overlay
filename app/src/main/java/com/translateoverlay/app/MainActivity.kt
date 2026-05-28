package com.translateoverlay.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.translateoverlay.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updatePermissionStatus()

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        }

        binding.btnStartBubble.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val serviceIntent = Intent(this, FloatingBubbleService::class.java)
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Bubble started! Go to your game.", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        binding.btnStopBubble.setOnClickListener {
            stopService(Intent(this, FloatingBubbleService::class.java))
            Toast.makeText(this, "Bubble stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.tvPermissionStatus.text = if (hasOverlay)
            "✅ Overlay permission granted"
        else
            "❌ Overlay permission needed"
        binding.btnStartBubble.isEnabled = hasOverlay
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updatePermissionStatus()
    }

    companion object {
        const val REQUEST_OVERLAY = 1001
    }
}
