package org.fossify.phone.dialogs

import android.app.Activity
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogShowNotesBinding

class ShowOrEditNotesDialog(
    val activity: Activity,
    val phoneNumber: String,
    val contactName: String,
    val note: String?,
    val onSave: ((String) -> Unit) // Added a callback to pass the edited note back to the caller
) {
    private var isEditing = false
    private lateinit var dialog: AlertDialog

    init {
        val binding = DialogShowNotesBinding.inflate(activity.layoutInflater)

        binding.apply {
            notesContactName.text = contactName.ifEmpty { phoneNumber }
            notesContent.setText(note)

            // Set initial state to Read-Only
            toggleEditMode(binding, false)
        }

        val alertBuilder = activity.getAlertDialogBuilder()
            // Pass null for listeners so the dialog doesn't auto-dismiss when clicked
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.edit, null)

        activity.setupDialogStuff(binding.root, alertBuilder, R.string.notes) { alertDialog ->

            dialog = alertDialog

            // Now we can safely grab the buttons from the dialog
            val positiveBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeBtn = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            // Initial button setup
            updateButtonState(positiveBtn, negativeBtn)

            // Handle OK / SAVE button
            positiveBtn.setOnClickListener {
                if (isEditing) {
                    // Save the edited note and close
                    val updatedNote = binding.notesContent.text.toString()
                    onSave(updatedNote)
                    alertDialog.dismiss()
                } else {
                    // Just OK, close the dialog
                    alertDialog.dismiss()
                }
            }

            // Handle EDIT / CANCEL button
            negativeBtn.setOnClickListener {
                isEditing = !isEditing

                if (!isEditing) {
                    // If user cancelled editing, revert the text back to the original note
                    binding.notesContent.setText(note)
                }

                toggleEditMode(binding, isEditing)
                updateButtonState(positiveBtn, negativeBtn)
            }
        }
    }

    /**
     * Toggles the UI of the EditText so the user notices the change.
     */
    private fun toggleEditMode(binding: DialogShowNotesBinding, enable: Boolean) {
        binding.notesContent.apply {
            isFocusableInTouchMode = enable
            isFocusable = enable
            isCursorVisible = enable
            setTextIsSelectable(true) // Always allow copying text

            // Visual cue: Add standard edit text background when editing, remove it when viewing
            background = if (enable) {
                AppCompatResources.getDrawable(context,android.R.drawable.edit_text)
            } else {
                null
            }

            if (enable) {
                requestFocus()
                // Automatically pop up the keyboard for a better UX
                activity.showKeyboard(this)
                // Move cursor to the end of the text
                setSelection(text?.length ?: 0)
            } else {
                clearFocus()
            }
        }
    }

    /**
     * Swaps the text on the buttons based on the current state.
     */
    private fun updateButtonState(positiveBtn: Button, negativeBtn: Button) {
        if (isEditing) {
            positiveBtn.setText(R.string.save)
            negativeBtn.setText(R.string.cancel)
        } else {
            positiveBtn.setText(R.string.ok)
            negativeBtn.setText(R.string.edit)
        }
    }
}
