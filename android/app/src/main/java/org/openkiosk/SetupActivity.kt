package org.openkiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run setup: asks for the dashboard URL. Later changes go through the
 * PIN-protected settings screen.
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 22f
        }
        val input = EditText(this).apply {
            setHint(R.string.setup_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val save = Button(this).apply { setText(R.string.setup_save) }

        save.setOnClickListener {
            val url = input.text.toString().trim()
            if (!UrlPolicy.isValidStartUrl(url)) {
                input.error = getString(R.string.setup_invalid)
                return@setOnClickListener
            }
            Settings(this).startUrl = url
            startActivity(Intent(this, KioskActivity::class.java))
            finish()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(input)
            addView(save)
        })
    }
}
