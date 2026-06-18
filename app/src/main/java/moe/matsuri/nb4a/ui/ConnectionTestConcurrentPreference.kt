package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference

class ConnectionTestConcurrentPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : EditTextPreference(context, attrs) {

    override fun getSummary(): CharSequence? = text ?: "3"
}
