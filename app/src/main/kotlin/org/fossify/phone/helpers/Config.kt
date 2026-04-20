package org.fossify.phone.helpers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.putPhoneAccountHandle
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import androidx.core.net.toUri
import org.fossify.phone.R
import java.net.URLEncoder
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit {
            putPhoneAccountHandle(
                key = getKeyForCustomSIM(number),
                parcelable = handle
            )
        }
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit { remove(getKeyForCustomSIM(number)) }
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

     fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }
    // user define START
    enum class AppOptions(val type : String) {
        WHATSAPP(WHATSAPP_TYPE),
        SMS("sms")
    }

    fun openChat(context : Context, num : String, msg: String = "", options: AppOptions = AppOptions.WHATSAPP)
    {

        val formatedNumber = normalizeCustomSIMNumber(num)
        val msg = URLEncoder.encode(msg, "UTF-8")
            try {
                val intent = Intent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

                when(options) {
                    AppOptions.WHATSAPP -> {
                        val url = if (msg.isEmpty()) {
                            "https://wa.me/$formatedNumber"
                        } else {
                            "https://wa.me/$formatedNumber/?text=$msg"
                        }
                        intent.action = Intent.ACTION_VIEW
                        intent.data = url.toUri() // This can throw exceptions if URL is malformed
                        when (whatsappType) {
                            "normal_wp" -> {
                                intent.setPackage("com.whatsapp")
                            }
                            "business_wp" -> {
                                intent.setPackage("com.whatsapp.w4b")
                            }
                        }
                    }
                    AppOptions.SMS -> {
                        intent.action = Intent.ACTION_SENDTO
                        intent.data = "smsto:$formatedNumber".toUri()
                        intent.putExtra("sms_body", msg)

                    }
                }




                context.startActivity(intent)

            } catch (e : Exception ) {
                Toast.makeText(context, "المعذرة لم نستطع فتح الواتساب" , Toast.LENGTH_SHORT).show()
            }

    }
    fun copyPhoneNumberToClipboard(context: Context, number: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Phone Number", number)
        clipboard.setPrimaryClip(clip)
    }




    // user define END

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit { putInt(SHOW_TABS, showTabs) }

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit { putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls) }

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit { putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad) }

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit { putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor) }

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit { putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer) }

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit { putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed) }

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit { putBoolean(DIALPAD_VIBRATION, dialpadVibration) }

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit { putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers) }

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit { putBoolean(DIALPAD_BEEPS, dialpadBeeps) }

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit { putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen) }
    var copyNumberOnCall: Boolean
        get() = prefs.getBoolean(COPY_NUMBER_ON_CALL, false)
        set(copyNumberOnCall) = prefs.edit { putBoolean(COPY_NUMBER_ON_CALL, copyNumberOnCall) }
    var openWhatsapp: Boolean
    get() = prefs.getBoolean(OPENWHATSAPP, false)
        set(openWhatsapp) = prefs.edit { putBoolean(OPENWHATSAPP, openWhatsapp) }
    var showAfterCallPopup : Boolean
        get() = prefs.getBoolean("after_call_popup", false)
        set(afterCallPopup) = prefs.edit { putBoolean("after_call_popup", afterCallPopup) }

    var dynamicSign : String
        get() = prefs.getString(DYNAMIC_SIGN, "@$")!!
        set(dynamicSign) = prefs.edit { putString(DYNAMIC_SIGN, dynamicSign) }
    var whatsappMessage : String
        get() = prefs.getString(WHATSAPP_MESSAGE, "")!!
        set(whatsappMessage) = prefs.edit { putString(WHATSAPP_MESSAGE, whatsappMessage) }
    var isDynamic = prefs.getBoolean(ISDYNAMIC, false)
        set(isDynamic) = prefs.edit { putBoolean(ISDYNAMIC, isDynamic) }
    var whatsappType : String
        get() = prefs.getString(WHATSAPP_TYPE, "normal_wp")!!
        set(whatsappType) = prefs.edit { putString(WHATSAPP_TYPE, whatsappType) }

}
