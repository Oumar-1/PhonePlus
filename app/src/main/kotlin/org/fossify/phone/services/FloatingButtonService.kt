package org.fossify.phone.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.phone.R
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.dialogs.DynamicBottomSheetChooserDialog
import org.fossify.phone.extensions.audioManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallManagerListener
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.models.AudioRoute

class FloatingButtonService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private var isSpeakerOn: Boolean = false
    private var isMicrophoneOff: Boolean = false

    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var initialTouchX: Int = 0
    private var initialTouchY: Int = 0

    private var isDragged = false
    private var startClickTime: Long = 0
    private val MAXCLICKDURATION: Int = 200 // milliseconds
    private val MAX_CLICK_DISTANCE: Int = 15 // pixels
    private var isMenuVisible: Boolean = false

    private lateinit var prefs : SharedPreferences

    // Handler for periodic state checks
    private val stateCheckHandler = Handler(Looper.getMainLooper())
    private val stateCheckRunnable = object : Runnable {
        override fun run() {
            checkCallState()
            stateCheckHandler.postDelayed(this, 1000) // Check every second
        }
    }

    // CallManager listener to sync with call state changes
    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            checkCallState()
            updateButtonStates()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateAudioButtonState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            updateButtonStates()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
         prefs = getSharedPreferences("floating_preferences", MODE_PRIVATE)
        val fx = prefs.getInt("floating_button_x", 50)
        val fy = prefs.getInt("floating_button_y", 50)

        try {
            // Initialize state from config

            // Initialize view
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.floating_service, null)

            // Initialize window manager and layout parameters
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Create the layout params for the floating button
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = fx
                y = fy
            }

            setupFloatingButton()
            setupCallControls()

            // Add the view to the window
            windowManager.addView(floatingView, params)

            // Register call manager listener
            CallManager.addListener(callCallback)

            // Start periodic state checks
            stateCheckHandler.post(stateCheckRunnable)

            // Update initial button states
            updateButtonStates()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            stopSelf()
        }
    }

    private fun checkCallState() {
        if (!shouldServiceBeRunning()) {
            stopSelf()
        }
    }

    private fun shouldServiceBeRunning(): Boolean {
        return CallManager.getPhoneState() != NoCall
    }

    private fun updateButtonStates() {
        try {
            val menuContainer = floatingView.findViewById<ConstraintLayout>(R.id.menu_container)
            val speakerBtn = menuContainer.findViewById<ImageButton>(R.id.speaker_toggle_btn)
            val micBtn = menuContainer.findViewById<ImageButton>(R.id.microphone_toggle_btn)

            // Get current microphone state from system
            isMicrophoneOff = audioManager.isMicrophoneMute

            // Update audio route icon based on current state
            val audioRoute = CallManager.getCallAudioRoute()
            if (audioRoute != null) {
                updateAudioButtonState(audioRoute)
            }

            // Update microphone icon based on current state
            micBtn.setImageResource(
                if (isMicrophoneOff) R.drawable.ic_microphone_off_vector
                else R.drawable.ic_microphone_vector
            )

            toggleButtonColor(micBtn, isMicrophoneOff)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating button states: ${e.message}")
        }
    }

    private fun updateAudioButtonState(audioRoute: AudioRoute?) {
        try {
            if (audioRoute == null) return

            val menuContainer = floatingView.findViewById<ConstraintLayout>(R.id.menu_container)
            val speakerBtn = menuContainer.findViewById<ImageButton>(R.id.speaker_toggle_btn)

            // Update icon based on current route
            speakerBtn.setImageResource(audioRoute.iconRes)

            // Update state
            isSpeakerOn = audioRoute == AudioRoute.SPEAKER

            // Update button appearance
            toggleButtonColor(speakerBtn, isSpeakerOn)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating audio button state: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingButton() {
        val floatingButton = floatingView.findViewById<ImageButton>(R.id.floating_button)

        floatingButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Record start time for click detection
                    startClickTime = System.currentTimeMillis()

                    initialX = params.x.toFloat()
                    initialY = params.y.toFloat()
                    initialTouchX = event.rawX.toInt()
                    initialTouchY = event.rawY.toInt()

                    // Reset drag state
                    isDragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Calculate distance moved
                    val distanceX = Math.abs(event.rawX - initialTouchX)
                    val distanceY = Math.abs(event.rawY - initialTouchY)

                    // If moved more than threshold, consider it a drag
                    if (distanceX > MAX_CLICK_DISTANCE || distanceY > MAX_CLICK_DISTANCE) {
                        isDragged = true
                    }

                    if (isDragged) {
                        // Calculate new position
                        params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        params.y = (initialY + (event.rawY - initialTouchY)).toInt()

                        // Update the layout
                        try {
                            windowManager.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout: ${e.message}")
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val clickDuration = System.currentTimeMillis() - startClickTime
                    if (!isDragged && clickDuration < MAXCLICKDURATION) {
                        toggleMenu()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setupCallControls() {
        try {
            // End Call Button
            val callEndBtn = floatingView.findViewById<ImageButton>(R.id.end_call_btn)
            callEndBtn.setOnClickListener {
                try {
                    CallManager.reject()
                    // Hide menu after ending call
                    toggleMenu()
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending call: ${e.message}")
                }
            }

            // Microphone Button
            val microphoneBtn = floatingView.findViewById<ImageButton>(R.id.microphone_toggle_btn)
            microphoneBtn.setOnClickListener {
                try {
                    toggleMicrophone()
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling microphone: ${e.message}")
                }
            }

            // Speaker Button
            val speakerBtn = floatingView.findViewById<ImageButton>(R.id.speaker_toggle_btn)
            speakerBtn.setOnClickListener {
                try {
                    toggleSpeaker(speakerBtn)
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling speaker: ${e.message}")
                }
            }

            // Expand Button
            val expandBtn = floatingView.findViewById<ImageButton>(R.id.expand_btn)
            expandBtn.setOnClickListener {
                try {
                    val intent = Intent(this, CallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching call activity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up call controls: ${e.message}")
        }
    }

    private fun toggleSpeaker(speakerBtn: ImageButton) {
        try {
            val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
            if (supportAudioRoutes.contains(AudioRoute.BLUETOOTH)) {
                // For Bluetooth, toggle between speaker and previous route
                val currentRoute = CallManager.getCallAudioRoute()
                val newRoute = if (currentRoute == AudioRoute.SPEAKER) {
                    // Go back to previous non-speaker route
                    if (supportAudioRoutes.contains(AudioRoute.BLUETOOTH)) {
                        AudioRoute.BLUETOOTH
                    } else {
                        AudioRoute.EARPIECE
                    }
                } else {
                    // Switch to speaker
                    AudioRoute.SPEAKER
                }

                CallManager.setAudioRoute(newRoute.route)
            } else {
                // Simple toggle between speaker and earpiece
                val isOn = !isSpeakerOn
                val newRoute = if (isOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
                CallManager.setAudioRoute(newRoute)
            }

            // Button appearance will be updated by the listener callback
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling speaker: ${e.message}")
        }
    }

    private fun toggleMicrophone() {
         isMicrophoneOff = !isMicrophoneOff
        audioManager.isMicrophoneMute = isMicrophoneOff
        CallManager.inCallService?.setMuted(isMicrophoneOff)

        // Update microphone button appearance
        val menuContainer = floatingView.findViewById<ConstraintLayout>(R.id.menu_container)
        val micBtn = menuContainer.findViewById<ImageButton>(R.id.microphone_toggle_btn)
        micBtn.setImageResource(
            if (isMicrophoneOff) R.drawable.ic_microphone_off_vector
            else R.drawable.ic_microphone_vector
        )
        toggleButtonColor(micBtn, isMicrophoneOff)
    }

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        try {
            if (enabled) {
                view.background.applyColorFilter(R.color.color_accent)
                view.applyColorFilter(getProperPrimaryColor())
            } else {
                view.background.applyColorFilter(getProperPrimaryColor().adjustAlpha(0.10f))

                view.applyColorFilter(getProperBackgroundColor().getContrastColor())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling button color: ${e.message}")
        }
    }

    private fun toggleMenu() {
        try {
            val menuContainer = floatingView.findViewById<ConstraintLayout>(R.id.menu_container)
            if (isMenuVisible) {
                menuContainer.visibility = View.GONE
                isMenuVisible = false
            } else {
                // Update button states before showing menu
                updateButtonStates()
                menuContainer.visibility = View.VISIBLE
                isMenuVisible = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling menu: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if we should be running
        if (!shouldServiceBeRunning()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            // Save position for next time
            val editor = prefs.edit()
            editor.putInt("floating_button_x", params.x)
            editor.putInt("floating_button_y", params.y)
            editor.apply()
            // Remove callbacks
            stateCheckHandler.removeCallbacks(stateCheckRunnable)

            // Remove listener
            CallManager.removeListener(callCallback)

            // Remove view from window manager
            if (::floatingView.isInitialized && ::windowManager.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
        super.onDestroy()
    }
}
