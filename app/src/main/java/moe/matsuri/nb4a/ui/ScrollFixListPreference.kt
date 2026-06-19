package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference

class ScrollFixListPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ListPreference(context, attrs) {

    override fun onClick() {
        val selected = findIndexOfValue(value)
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setPositiveButton(android.R.string.ok, null)
        if (entries != null && entryValues != null) {
            builder.setSingleChoiceItems(entries, -1) { dialog, which ->
                val newValue = entryValues!![which].toString()
                if (callChangeListener(newValue)) {
                    setValue(newValue)
                }
                dialog.dismiss()
            }
        }
        val dialog = builder.create()
        dialog.show()
        if (selected >= 0) {
            val lv = dialog.listView ?: return
            lv.post {
                val first = lv.firstVisiblePosition
                val last = lv.lastVisiblePosition
                if (selected < first || selected > last) {
                    lv.smoothScrollToPosition(selected)
                }
                lv.setItemChecked(selected, true)
            }
        }
    }
}
