package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.SimpleListItem
import org.fossify.commons.views.MyEditText
import org.fossify.phone.R
import org.fossify.phone.data.CallerRecordRepository
import org.fossify.phone.data.DatabaseProvider
import org.fossify.phone.data.MyRecordEntity
import org.fossify.phone.databinding.ActivityCallBinding
import org.fossify.phone.dialogs.DeliveryGalleryDialog
import org.fossify.phone.dialogs.DynamicBottomSheetChooserDialog
import org.fossify.phone.extensions.*
import org.fossify.phone.helpers.*
import org.fossify.phone.models.AudioRoute
import org.fossify.phone.models.CallContact
import org.fossify.phone.services.AfterCallPopupService
import org.fossify.phone.services.FloatingButtonService
import kotlin.math.max
import kotlin.math.min

class CallActivity : SimpleActivity() {
    companion object {
        fun getStartIntent(context: Context): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }
    }
    private val repository by lazy {
        val dao = DatabaseProvider.get(application).callerRecordDao()
        CallerRecordRepository(dao)
    }

    private val binding by viewBinding(ActivityCallBinding::inflate)
    // --- Camera VARIABLES ---
    private var isCameraActive = false
    private var pendingPhotoUri: android.net.Uri? = null

    private var isSpeakerOn = false
    private var isMicrophoneOff = false
    private var isCallEnded = false
    private var callContact: CallContact? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var screenOnWakeLock: PowerManager.WakeLock? = null
    private var callDuration = 0
    private val callDurationHandler = Handler(Looper.getMainLooper())
    private var dragDownX = 0f
    private var stopAnimation = false
    private var viewsUnderDialpad = arrayListOf<Pair<View, Float>>()
    private var dialpadHeight = 0f

    private var audioRouteChooserDialog: DynamicBottomSheetChooserDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.callHolder),
            padBottomSystem = listOf(binding.callHolder),
        )

        updateTextColors(binding.callHolder)
        initButtons()
        audioManager.mode = AudioManager.MODE_IN_CALL
        addLockScreenFlags()
        CallManager.addListener(callCallback)
        updateCallContactInfo(CallManager.getPrimaryCall())


    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateState()
    }

    override fun onResume() {
        super.onResume()
        updateState()
        stopFloatingButtonService()
        stopAfterCallPopup()
    }


    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        disableProximitySensor()




    }

    override fun onPause() {
        if (screenOnWakeLock?.isHeld == true) {
            screenOnWakeLock!!.release()
        }
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        val vxs = this@CallActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startFloatingButtonService(vxs)
        }, 150)

        saveNote()

    }

    override fun onBackPressedCompat(): Boolean {
        if (binding.dialpadWrapper.isVisible()) {
            hideDialpad()
            return true
        }

        val callState = CallManager.getState()
        if (callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING) {
            toast(R.string.call_is_being_connected)
            // Allow user to go back but show toast - they can return to call via notification
            return false
        }

        // Allow minimizing active call - user can return via notification
        return false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isCameraActive", isCameraActive)
        pendingPhotoUri?.let { outState.putString("pendingPhotoUri", it.toString()) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isCameraActive = savedInstanceState.getBoolean("isCameraActive", false)
        savedInstanceState.getString("pendingPhotoUri")?.let {
            pendingPhotoUri = android.net.Uri.parse(it)
        }
    }

    private fun initButtons() = binding.apply {
        if (config.disableSwipeToAnswer) {
            callDraggable.beGone()
            callDraggableBackground.beGone()
            callLeftArrow.beGone()
            callRightArrow.beGone()

            callDecline.setOnClickListener {
                endCall()
            }

            callAccept.setOnClickListener {
                acceptCall()
            }
        } else {
            handleSwipe()
        }

        callGalleryButton.setOnClickListener {
            callContact?.let { contact ->
                DeliveryGalleryDialog(
                    activity = this@CallActivity,
                    phoneNumber = contact.number,
                    onGalleryEmpty = {
                        binding.callBackgroundImage.setImageDrawable(null)
                        binding.callBackgroundScrim.beGone()
                        binding.callGalleryButton.beGone()
                    },
                    onNewestPhotoDeleted = { nextImagePath ->
                        Glide.with(this@CallActivity)
                            .load(nextImagePath)
                            .into(binding.callBackgroundImage)
                    }
                ).show()
            }
        }
        binding.callCameraButton.setOnClickListener {
            if (callContact == null) return@setOnClickListener

            try {
                isCameraActive = true
                val tempFile = java.io.File(filesDir, "captured_images/temp_${System.currentTimeMillis()}.jpg")
                tempFile.parentFile?.mkdirs()

                pendingPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                    this@CallActivity,
                    "$packageName.fileprovider",
                    tempFile
                )

                // FIX: Safely unwrap the nullable Uri
                pendingPhotoUri?.let { uri ->
                    takeDeliveryPhotoLauncher.launch(uri)
                }
            } catch (e: Exception) {
                isCameraActive = false
                toast("Could not open camera")
            }
        }
        callToggleMicrophone.setOnClickListener {
            toggleMicrophone()
        }

        callToggleSpeaker.setOnClickListener {
            changeCallAudioRoute()
        }

        callDialpad.setOnClickListener {
            toggleDialpadVisibility()
        }

        dialpadClose.setOnClickListener {
            hideDialpad()
        }

        callToggleHold.setOnClickListener {
            toggleHold()
        }

        callAdd.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
        }

        callSwap.setOnClickListener {
            CallManager.swap()
        }

        callMerge.setOnClickListener {
            CallManager.merge()
        }

        callManage.setOnClickListener {
            startActivity(Intent(this@CallActivity, ConferenceActivity::class.java))
        }

        callEnd.setOnClickListener {
            endCall()
        }
        openWhatsapp.setOnClickListener {
            val contact = callContact
            if (contact != null) {
                config.openChat(this@CallActivity, contact.number)
            }

        }

        dialpadInclude.apply {
            dialpad0Holder.setOnClickListener { dialpadPressed('0') }
            dialpad1Holder.setOnClickListener { dialpadPressed('1') }
            dialpad2Holder.setOnClickListener { dialpadPressed('2') }
            dialpad3Holder.setOnClickListener { dialpadPressed('3') }
            dialpad4Holder.setOnClickListener { dialpadPressed('4') }
            dialpad5Holder.setOnClickListener { dialpadPressed('5') }
            dialpad6Holder.setOnClickListener { dialpadPressed('6') }
            dialpad7Holder.setOnClickListener { dialpadPressed('7') }
            dialpad8Holder.setOnClickListener { dialpadPressed('8') }
            dialpad9Holder.setOnClickListener { dialpadPressed('9') }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadPlusHolder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(resources, R.drawable.pill_background, theme)
                it.background?.alpha = LOWER_ALPHA_INT
            }

            dialpad0Holder.setOnLongClickListener { dialpadPressed('+'); true }
            dialpadAsteriskHolder.setOnClickListener { dialpadPressed('*') }
            dialpadHashtagHolder.setOnClickListener { dialpadPressed('#') }
            dialpadClearChar.setOnClickListener { clearChar(it) }
            dialpadClearChar.setOnLongClickListener { clearInput() }
        }

        dialpadWrapper.setBackgroundColor(
            if (isSystemInDarkMode()) {
                getProperBackgroundColor().lightenColor(2)
            } else {
                getProperBackgroundColor()
            }
        )

        arrayOf(dialpadClose, callSimImage, dialpadClearChar).forEach {
            it.applyColorFilter(getProperTextColor())
        }

        val bgColor = getProperBackgroundColor()
        val inactiveColor = getInactiveButtonColor()
        arrayOf(
            callToggleMicrophone, callToggleSpeaker, callDialpad,
            callToggleHold, callAdd, callSwap, callMerge, callManage
        ).forEach {
            it.applyColorFilter(bgColor.getContrastColor())
            it.background.applyColorFilter(inactiveColor)
        }

        arrayOf(
            callToggleMicrophone, callToggleSpeaker, callDialpad,
            callToggleHold, callAdd, callSwap, callMerge, callManage
        ).forEach { imageView ->
            imageView.setOnLongClickListener {
                if (!imageView.contentDescription.isNullOrEmpty()) {
                    toast(imageView.contentDescription.toString())
                }
                true
            }
        }

        callSimId.setTextColor(getProperTextColor().getContrastColor())
        dialpadInput.disableKeyboard()

        dialpadWrapper.onGlobalLayout {
            dialpadHeight = dialpadWrapper.height.toFloat()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() = binding.apply {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f

        val isRtl = isRTLLayout
        callAccept.onGlobalLayout {
            minDragX = if (isRtl) {
                callAccept.left.toFloat()
            } else {
                callDecline.left.toFloat()
            }

            maxDragX = if (isRtl) {
                callDecline.left.toFloat()
            } else {
                callAccept.left.toFloat()
            }

            initialDraggableX = callDraggable.left.toFloat()
            initialLeftArrowX = callLeftArrow.x
            initialRightArrowX = callRightArrow.x
            initialLeftArrowScaleX = callLeftArrow.scaleX
            initialLeftArrowScaleY = callLeftArrow.scaleY
            initialRightArrowScaleX = callRightArrow.scaleX
            initialRightArrowScaleY = callRightArrow.scaleY
            leftArrowTranslation = if (isRtl) {
                callAccept.x
            } else {
                -callDecline.x
            }

            rightArrowTranslation = if (isRtl) {
                -callAccept.x
            } else {
                callDecline.x
            }

            if (isRtl) {
                callLeftArrow.setImageResource(R.drawable.ic_chevron_right_vector)
                callRightArrow.setImageResource(R.drawable.ic_chevron_left_vector)
            }

            callLeftArrow.applyColorFilter(getColor(R.color.md_red_400))
            callRightArrow.applyColorFilter(getColor(R.color.md_green_400))

            startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        callDraggable.drawable.mutate().setTint(getProperTextColor())
        callDraggableBackground.drawable.mutate().setTint(getProperTextColor())

        var lock = false
        callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // --- NEW: Calculate the exact boundaries the moment the finger touches the screen ---
                    minDragX = if (isRtl) {
                        callAccept.left.toFloat()
                    } else {
                        callDecline.left.toFloat()
                    }

                    maxDragX = if (isRtl) {
                        callDecline.left.toFloat()
                    } else {
                        callAccept.left.toFloat()
                    }

                    initialDraggableX = callDraggable.left.toFloat()
                    // -----------------------------------------------------------------------------------

                    dragDownX = event.x
                    callDraggableBackground.animate().alpha(0f)
                    stopAnimation = true
                    callLeftArrow.animate().alpha(0f)
                    callRightArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    callDraggable.animate().x(initialDraggableX).withEndAction {
                        callDraggableBackground.animate().alpha(0.2f)
                    }
                    callDraggable.setImageDrawable(AppCompatResources.getDrawable(applicationContext,R.drawable.ic_phone_down_vector))
                    callDraggable.drawable.mutate().setTint(getProperTextColor())
                    callLeftArrow.animate().alpha(1f)
                    callRightArrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    when {
                        callDraggable.x >= maxDragX - 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    endCall()
                                } else {
                                    acceptCall()
                                }
                            }
                        }

                        callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    acceptCall()
                                } else {
                                    endCall()
                                }
                            }
                        }

                        callDraggable.x > initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_down_red_vector
                            } else {
                                R.drawable.ic_phone_green_vector
                            }
                            callDraggable.setImageDrawable(AppCompatResources.getDrawable(applicationContext,drawableRes))
                        }

                        callDraggable.x <= initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_green_vector
                            } else {
                                R.drawable.ic_phone_down_red_vector
                            }
                            callDraggable.setImageDrawable(AppCompatResources.getDrawable(applicationContext,drawableRes))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        binding.dialpadInput.addCharacter(char)
    }

    private fun changeCallAudioRoute() {
        val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
        if (supportAudioRoutes.contains(AudioRoute.BLUETOOTH)) {
            createOrUpdateAudioRouteChooser(supportAudioRoutes)
        } else {
            val isSpeakerOn = !isSpeakerOn
            val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            CallManager.setAudioRoute(newRoute)
        }
    }

    private fun createOrUpdateAudioRouteChooser(routes: Array<AudioRoute>, create: Boolean = true) {
        val callAudioRoute = CallManager.getCallAudioRoute()
        val items = routes
            .sortedByDescending { it.route }
            .map {
                SimpleListItem(id = it.route, textRes = it.stringRes, imageRes = it.iconRes, selected = it == callAudioRoute)
            }
            .toTypedArray()

        if (audioRouteChooserDialog?.isVisible == true) {
            audioRouteChooserDialog?.updateChooserItems(items)
        } else if (create) {
            audioRouteChooserDialog = DynamicBottomSheetChooserDialog.createChooser(
                fragmentManager = supportFragmentManager,
                title = R.string.choose_audio_route,
                items = items
            ) {
                audioRouteChooserDialog = null
                CallManager.setAudioRoute(it.id)
            }
        }
    }

    private fun updateCallAudioState(route: AudioRoute?) {
        if (route != null) {
            isMicrophoneOff = audioManager.isMicrophoneMute
            updateMicrophoneButton()

            isSpeakerOn = route == AudioRoute.SPEAKER
            val supportedAudioRoutes = CallManager.getSupportedAudioRoutes()
            binding.callToggleSpeaker.apply {
                val bluetoothConnected = supportedAudioRoutes.contains(AudioRoute.BLUETOOTH)
                contentDescription = if (bluetoothConnected) {
                    getString(R.string.choose_audio_route)
                } else {
                    getString(if (isSpeakerOn) R.string.turn_speaker_off else R.string.turn_speaker_on)
                }
                // show speaker icon when a headset is connected, a headset icon maybe confusing to some
                if (route == AudioRoute.WIRED_HEADSET) {
                    setImageResource(R.drawable.ic_volume_down_vector)
                } else {
                    setImageResource(route.iconRes)
                }
            }
            toggleButtonColor(binding.callToggleSpeaker, enabled = route != AudioRoute.EARPIECE && route != AudioRoute.WIRED_HEADSET)
            createOrUpdateAudioRouteChooser(supportedAudioRoutes, create = false)

            if (isSpeakerOn) {
                disableProximitySensor()
            } else {
                enableProximitySensor()
            }
        }
    }

    private fun toggleMicrophone() {
        isMicrophoneOff = !isMicrophoneOff
        audioManager.isMicrophoneMute = isMicrophoneOff
        CallManager.inCallService?.setMuted(isMicrophoneOff)
        updateMicrophoneButton()
    }

    private fun updateMicrophoneButton() {
        toggleButtonColor(binding.callToggleMicrophone, isMicrophoneOff)
        binding.callToggleMicrophone.contentDescription = getString(if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off)
    }

    private fun toggleDialpadVisibility() {
        if (binding.dialpadWrapper.isVisible()) hideDialpad() else showDialpad()
    }

    private fun findVisibleViewsUnderDialpad(): Sequence<Pair<View, Float>> {
        return binding.ongoingCallHolder.children
            .filter { it is ImageView && it.isVisible() }
            .map { view -> Pair(view, view.alpha) }
    }

    private fun showDialpad() {
        binding.dialpadWrapper.apply {
            updatePadding(
                bottom = binding.root.bottom - binding.endWraper.top + resources.getDimensionPixelSize(R.dimen.activity_margin)
            )

            translationY = dialpadHeight
            alpha = 0f
            animate()
                .withStartAction { beVisible() }
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setDuration(200L)
                .alpha(1f)
                .translationY(0f)
                .start()
        }

        viewsUnderDialpad.clear()
        viewsUnderDialpad.addAll(findVisibleViewsUnderDialpad())
        viewsUnderDialpad.forEach { (view, _) ->
            view.run {
                animate().scaleX(0f).alpha(0f).withEndAction { beGone() }.duration = 250L
                animate().scaleY(0f).alpha(0f).withEndAction { beGone() }.duration = 250L
            }
        }
    }

    private fun hideDialpad() {
        binding.dialpadWrapper.animate()
            .withEndAction { binding.dialpadWrapper.beGone() }
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(200L)
            .alpha(0f)
            .translationY(dialpadHeight)
            .start()

        viewsUnderDialpad.forEach { (view, alpha) ->
            view.run {
                animate().withStartAction { beVisible() }.setInterpolator(OvershootInterpolator()).scaleX(1f).alpha(alpha).duration = 250L
                animate().withStartAction { beVisible() }.setInterpolator(OvershootInterpolator()).scaleY(1f).alpha(alpha).duration = 250L
            }
        }
    }

    private fun toggleHold() {
        val isOnHold = CallManager.toggleHold()
        toggleButtonColor(binding.callToggleHold, isOnHold)
        binding.callToggleHold.contentDescription = getString(if (isOnHold) R.string.resume_call else R.string.hold_call)
        binding.holdStatusLabel.beInvisibleIf(!isOnHold)
    }

    private fun updateOtherPersonsInfo(avatarUri: String?) {
        if (callContact == null) {
            return
        }
        if(config.openWhatsapp){
            handleChatRedirection()
        }

        binding.apply {
            val (name, _, number, numberLabel) = callContact!!
            callerNameLabel.text = name.ifEmpty { getString(R.string.unknown_caller) }
            if (number.isNotEmpty() && number != name) {
                callerNumber.text = number

                if (numberLabel.isNotEmpty()) {
                    callerNumber.text = "$number - $numberLabel"
                }
            } else {
                callerNumber.beGone()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val number = config.normalizeCustomSIMNumber(callContact!!.number)
                val record = repository.getRecord(number)

                // Fetch the delivery photos for this caller
                val dao = DatabaseProvider.get(applicationContext).callerRecordDao()
                val photos = dao.getPhotosForNumber(number)

                withContext(Dispatchers.Main) {
                    if(record != null) {
                        callerNotes.setText(record.note)
                    }

                    // Set the Background and Gallery Button
                    if (photos.isNotEmpty()) {
                        Glide.with(this@CallActivity)
                            .load(photos.first().imagePath) // The newest photo is always first!
                            .into(callBackgroundImage)

                        callBackgroundScrim.beVisible()
                        callGalleryButton.beVisible()
                    } else {
                        callBackgroundImage.setImageDrawable(null)
                        callBackgroundScrim.beGone()
                        callGalleryButton.beGone()
                    }
                }
            }


            callerAvatar.apply {
                if (avatarUri.isNullOrEmpty()) {
                    val bgColor = getProperPrimaryColor()
                    setBackgroundResource(R.drawable.circle_background)
                    setImageResource(R.drawable.ic_person_vector)
                    setPadding(resources.getDimensionPixelSize(R.dimen.activity_margin))
                    applyColorFilter(bgColor.getContrastColor())
                    background.applyColorFilter(bgColor)
                } else {
                    if (!isFinishing && !isDestroyed) {
                        Glide.with(this)
                            .load(avatarUri)
                            .apply(RequestOptions.circleCropTransform())
                            .into(this)
                    }
                }
            }
        }

        callContact?.let { contact ->
            if(config.copyNumberOnCall){
                lifecycleScope.launch (Dispatchers.IO){
                    config.copyPhoneNumberToClipboard(this@CallActivity,contact.number)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CallActivity,"Number Copied to Clipboard",Toast.LENGTH_SHORT).show()

                    }
                }
            }

        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {

            val simLabels = getAvailableSIMCardLabels()
            if (simLabels.size > 1) {
                simLabels.forEachIndexed { index, sim ->
                    if (sim.handle == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        binding.apply {
                            callSimId.text = sim.id.toString()
                            callSimId.beVisible()
                            callSimImage.beVisible()
                            val simColor = sim.color.adjustForContrast(getProperBackgroundColor())
                            callSimId.setTextColor(simColor.getContrastColor())
                            callSimImage.applyColorFilter(simColor)
                        }

                        val acceptDrawableId = when (index) {
                            0 -> R.drawable.ic_phone_one_vector
                            1 -> R.drawable.ic_phone_two_vector
                            else -> R.drawable.ic_phone_vector
                        }

                        val rippleBg = resources.getDrawable(R.drawable.ic_call_accept, theme) as RippleDrawable
                        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.accept_call_background_holder) as LayerDrawable
                        layerDrawable.setDrawableByLayerId(R.id.accept_call_icon, getDrawable(acceptDrawableId))
                        binding.callAccept.setImageDrawable(rippleBg)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun updateCallState(call: Call) {
        val state = call.getStateCompat()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall()
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            else -> 0
        }

        binding.apply {
            if (statusTextId != 0) {
                callStatusLabel.text = getString(statusTextId)
            }

            callManage.beVisibleIf(!isCallEnded && call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
            setActionButtonEnabled(callSwap, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
            setActionButtonEnabled(callMerge, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        if (phoneState is SingleCall) {
            updateCallState(phoneState.call)
            updateCallOnHoldState(null)
            val state = phoneState.call.getStateCompat()
            val isSingleCallActionsEnabled = !isCallEnded && (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
            setActionButtonEnabled(binding.callToggleHold, isSingleCallActionsEnabled)
            setActionButtonEnabled(binding.callAdd, isSingleCallActionsEnabled)
        } else if (phoneState is TwoCalls) {
            val activeState = phoneState.active.getStateCompat()

            // If the "active" call is actually ringing, show incoming call UI
            if (activeState == Call.STATE_RINGING) {
                callRinging()
                updateCallOnHoldState(phoneState.onHold)
            } else {
                updateCallState(phoneState.active)
                updateCallOnHoldState(phoneState.onHold)
            }
        }

        updateCallAudioState(CallManager.getCallAudioRoute())
    }

    private fun updateCallOnHoldState(call: Call?) {
        val hasCallOnHold = call != null
        if (hasCallOnHold) {
            getCallContact(applicationContext, call) { contact ->
                runOnUiThread {
                    binding.onHoldCallerName.text = getContactNameOrNumber(contact)
                }
            }
        }
        binding.apply {
            onHoldStatusHolder.beVisibleIf(hasCallOnHold)
            controlsSingleCall.beVisibleIf(!hasCallOnHold)
            controlsTwoCalls.beVisibleIf(hasCallOnHold)
        }
    }

    private fun updateCallContactInfo(call: Call?) {
        getCallContact(applicationContext, call) { contact ->
            if (call != CallManager.getPrimaryCall()) {
                return@getCallContact
            }
            callContact = contact

            val avatar = if (!call.isConference()) contact.photoUri else null
            runOnUiThread {
                updateOtherPersonsInfo(avatar)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        val phoneState = CallManager.getPhoneState()

        if (phoneState is org.fossify.phone.helpers.TwoCalls) {
            // Find the specific call that is currently ringing
            val ringingCall = listOfNotNull(phoneState.active, phoneState.onHold).firstOrNull {
                it.getStateCompat() == android.telecom.Call.STATE_RINGING
            }

            if (ringingCall != null) {
                // Command Android Telecom to answer THIS specific call directly
                ringingCall.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                return
            }
        }

        // Fallback for a normal single call
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
    }

    private fun callRinging() {
        binding.incomingCallHolder.beVisible()
        binding.ongoingCallHolder.beGone()  // ADD THIS LINE
        binding.callEnd.beGone()             // ADD THIS LINE
    }

    private fun callStarted() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall() {
        if (callContact == null) return

        val phoneState = CallManager.getPhoneState()
        if (phoneState is org.fossify.phone.helpers.TwoCalls) {
            // Find the ringing call and ONLY reject that one
            val ringingCall = listOfNotNull(phoneState.active, phoneState.onHold).firstOrNull {
                it.getStateCompat() == android.telecom.Call.STATE_RINGING
            }

            if (ringingCall != null) {
                ringingCall.reject(android.telecom.Call.REJECT_REASON_DECLINED)

                // CRITICAL: Return immediately! Do NOT run the code below
                // that sets isCallEnded = true and closes the Activity,
                // because Call A is still active!
                return
            }
        }

        // --- Original Single Call Logic Below ---
        CallManager.reject()
        disableProximitySensor()
        audioRouteChooserDialog?.dismissAllowingStateLoss()

        startAfterCallPopup()
        if (isCallEnded) {
            safeFinishAndRemoveTask()
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (ignored: Exception) {
        }

        isCallEnded = true

        runOnUiThread {
            if (callDuration > 0) {
                disableAllActionButtons()
                @SuppressLint("SetTextI18n")
                binding.callStatusLabel.text = "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"

                if (!isCameraActive) {
                    Handler(mainLooper).postDelayed(3000) {
                        safeFinishAndRemoveTask()
                    }
                }
            } else {
                disableAllActionButtons()
                binding.callStatusLabel.text = getString(R.string.call_ended)

                if (!isCameraActive) {
                    finish()
                }
            }
        }
    }
    private fun safeFinishAndRemoveTask() {
        try {
            if (intent != null) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (_: Exception) {
            finish()
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateCallAudioState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)

            updateState()
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            callDuration = CallManager.getPrimaryCall().getCallDuration()
            if (!isCallEnded) {
                binding.callStatusLabel.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOnWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "org.fossify.phone:full_wake_lock")
            screenOnWakeLock!!.acquire(5 * 1000L)
        } catch (e: Exception) {
        }
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "org.fossify.phone:wake_lock")
            proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun disableAllActionButtons() {
        (binding.ongoingCallHolder.children + binding.callEnd)
            .filter { it is ImageView && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as ImageView, enabled = false)
            }
    }

    private fun setActionButtonEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun getActiveButtonColor() = getProperPrimaryColor()

    private fun getInactiveButtonColor() = getProperTextColor().adjustAlpha(0.10f)

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        if (enabled) {
            val color = getActiveButtonColor()
            view.background.applyColorFilter(color)
            view.applyColorFilter(color.getContrastColor())
        } else {
            view.background.applyColorFilter(getInactiveButtonColor())
            view.applyColorFilter(getProperBackgroundColor().getContrastColor())
        }
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
    }

    private fun clearInput(): Boolean {
        binding.dialpadInput.setText("");
        return true;
    }


    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // If permission is not granted, request it
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
               "package:$packageName".toUri()

            )

            return false
        }
        return true
    }

    private fun startFloatingButtonService(context : Context) {
        if(CallManager.getPhoneState() == NoCall || !checkOverlayPermission() || config.openWhatsapp) return

            val intent = Intent(context, FloatingButtonService::class.java)
            startService(intent)
    }

    private fun stopFloatingButtonService() {
        stopService(Intent(this, FloatingButtonService::class.java))
    }
    fun startAfterCallPopup() {

        if(callContact == null) return
        if (config.showAfterCallPopup && !config.openWhatsapp) {
            val intent = Intent(applicationContext, AfterCallPopupService::class.java)
            intent.addFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("phoneNumber", callContact?.number ?: "")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }
    }
    fun stopAfterCallPopup() {
        val intent = Intent(applicationContext, AfterCallPopupService::class.java)
        applicationContext.stopService(intent)
    }

    private  fun handleChatRedirection() {
        val phoneNumber = callContact?.number
        if(CallManager.isOutgoingCall() && !this.isFinishing && !this.isDestroyed && !phoneNumber.isNullOrBlank() ) {
            window.decorView.post{
                Handler(Looper.getMainLooper()).postDelayed({
                    if(config.isDynamic) {
                        DynamicMessageTemplateActivity.start(this,phoneNumber, config.whatsappMessage)
                    } else{
                        config.openChat(this,phoneNumber, config.whatsappMessage)
                    }
                    CallManager.reject()
                },250)
            }

        }
    }
    private fun saveNote() {
        val contact = callContact ?: return
        val callerNoteView = findViewById<MyEditText>(R.id.caller_notes)
        val noteText = callerNoteView.text.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            // Now we just pass the raw strings to the reusable repository function
            repository.saveNote(
                context = applicationContext,
                rawPhoneNumber = contact.number,
                note = noteText
            )
        }
    }
    // --- PHASE 9: CALL SCREEN GALLERY ---

    private class TakeRearPicture : androidx.activity.result.contract.ActivityResultContract<android.net.Uri, Boolean>() {
        override fun createIntent(context: Context, input: android.net.Uri): Intent {
            return Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, input)
                putExtra("android.intent.extras.CAMERA_FACING", 0)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 0)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
            }
        }
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == android.app.Activity.RESULT_OK
        }
    }

    private val takeDeliveryPhotoLauncher = registerForActivityResult(TakeRearPicture()) { success ->
        isCameraActive = false // Release the camera lock!

        if (success && pendingPhotoUri != null && callContact != null) {

            lifecycleScope.launch {
                // Hand the heavy lifting off to the Helper!
                val savedImagePath = DeliveryPhotoHelper.processAndSavePhoto(
                    context = this@CallActivity,
                    rawUri = pendingPhotoUri!!,
                    rawPhoneNumber = callContact!!.number
                )

                if (savedImagePath != null) {
                    toast("Delivery Photo Saved!")
                    pendingPhotoUri = null

                    // Update the Call Background instantly
                    binding.callBackgroundScrim.beVisible()
                    binding.callGalleryButton.beVisible()
                    com.bumptech.glide.Glide.with(this@CallActivity)
                        .load(savedImagePath)
                        .into(binding.callBackgroundImage)
                } else {
                    toast("Failed to save photo")
                    pendingPhotoUri = null
                }

                checkIfShouldFinish() // Close screen if they hung up
            }

        } else {
            pendingPhotoUri?.let { contentResolver.delete(it, null, null) }
            pendingPhotoUri = null
            checkIfShouldFinish()
        }
    }


    private fun checkIfShouldFinish() {
        if (isCallEnded) {
            // Give the DA 1.5 seconds to see their photo pop up on the background, then end the screen
            Handler(Looper.getMainLooper()).postDelayed({
                safeFinishAndRemoveTask()
            }, 1500)
        }
    }
}

