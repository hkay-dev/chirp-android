package dev.chirpboard.app.baselineprofile

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Minimal editor used only by the connected-device profile generator to exercise IME startup. */
class ImeHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor =
            EditText(this).apply {
                hint = "Baseline profile editor"
                isSingleLine = false
            }
        setContentView(editor)
        editor.requestFocus()
        editor.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(
                editor,
                InputMethodManager.SHOW_IMPLICIT,
            )
        }
    }
}
