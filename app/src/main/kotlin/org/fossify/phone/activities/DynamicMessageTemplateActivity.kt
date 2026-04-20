package org.fossify.phone.activities

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import org.fossify.phone.R
import org.fossify.phone.extensions.config

class DynamicMessageTemplateActivity : SimpleActivity() {
    private var phoneNumber: String? = null
    private var message: String? = null

    companion object {

        private const val EXTRA_PHONE_NUMBER = "phone_number"
        private const val EXTRA_MESSAGE = "message"
        fun start(context: Context, phoneNumber: String,message : String? = "") {
            val intent = Intent(context, DynamicMessageTemplateActivity::class.java).apply {
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        phoneNumber = intent.getStringExtra("phone_number")
        message = intent.getStringExtra("message")

        val inputEditText = EditText(this)
        inputEditText.inputType = InputType.TYPE_CLASS_NUMBER
        inputEditText.hint = "eg.. 100"
        inputEditText.requestFocus()
        inputEditText.imeOptions = EditorInfo.IME_ACTION_DONE

        fun handleSubmit() {
            val modifiedMesg = config.whatsappMessage.replace(config.dynamicSign,inputEditText.text.toString() )
            config.openChat(this, phoneNumber ?: "" , modifiedMesg)
            finish()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Something")
            .setView(inputEditText)
            .setPositiveButton(R.string.ok) { _, _ ->
                handleSubmit()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .create()

        dialog.setOnShowListener {
            Handler(Looper.getMainLooper()).postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleSubmit()
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.show()
    }
}
