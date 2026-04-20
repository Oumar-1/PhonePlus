package org.fossify.phone.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import org.fossify.phone.R
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.databinding.AfterCallPopupBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.Config


class AfterCallPopupService : Service() {
    private var windowManager: WindowManager? = null
    private lateinit var overlayView: View
    private var number = ""
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "AfterCallOverlayChannel"
    private val TAG = "AfterCallOverlayService"
    private lateinit var binding: AfterCallPopupBinding

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")

        try {


            // Create notification channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "After Call Overlay",
                    NotificationManager.IMPORTANCE_LOW
                )
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        try {
            // Create a notification with a pending intent to open the app
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, CallActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("After Call Options")
                .setContentText("Tap to return to app")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            startForeground(NOTIFICATION_ID, notification)
            // Remove any existing overlay view before creating a new one
            removeExistingOverlay()

            // Get phone number from intent
            number = intent?.getStringExtra("phoneNumber") ?: ""
            number = config.normalizeCustomSIMNumber(number)

            // Show a toast to confirm service is running
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "After call options available", Toast.LENGTH_SHORT).show()
            }
            showOverlay()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand: ${e.message}", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }
    private fun removeExistingOverlay() {
        try {
            if (::overlayView.isInitialized && overlayView.parent != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            Log.e("AfterCallOverlay", "Error removing existing overlay: ${e.message}")
        }
    }

    private fun showOverlay() {
        try {
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.after_call_popup, null)

            // Set up button click listeners
            setupButtons()

            // Create layout parameters
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            // Add the view to window manager
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay view added to window manager")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay: ${e.message}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Error showing overlay: ${e.message}", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
    }

    private fun setupButtons() {
        binding = AfterCallPopupBinding.bind(overlayView)

        binding.apply {
            phoneNumberText.text = when {
                number.isNotEmpty() -> number
                else -> "Unknown Number"
            }
            recallBtn.setOnClickListener {
                if (number.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_CALL, "tel:$number".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Toast.makeText(applicationContext, "Phone number not available", Toast.LENGTH_SHORT).show()
                }
                stopSelf()

            }
            sendSmsMessage.setOnClickListener {
                if (number.isNotEmpty()) {
                    config.openChat(applicationContext, number, "", Config.AppOptions.SMS)
                } else {
                    Toast.makeText(applicationContext, "Phone number not available", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
            openWhatsappBtn.setOnClickListener {
                if (number.isNotEmpty()) {
                    config.openChat(applicationContext, number)
                } else {
                    Toast.makeText(applicationContext, "Phone number not available", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
            closeBtn.setOnClickListener {
                stopSelf()
            }

        }

    }

    override fun onDestroy() {
        try {
            if (windowManager != null && ::overlayView.isInitialized && overlayView.parent != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
        super.onDestroy()
    }
}
