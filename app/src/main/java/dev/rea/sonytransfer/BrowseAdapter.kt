package dev.rea.sonytransfer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** 浏览列表适配器：文件夹行可点进入，照片行可勾选（跨文件夹保持选中）。 */
class BrowseAdapter(
    private val context: Context,
    private val callbacks: Callbacks,
) : BaseAdapter() {

    interface Callbacks {
        fun onOpenContainer(entry: BrowseEntry)
        fun onSelectionChanged(entry: BrowseEntry, selected: Boolean)
    }

    private val entries = ArrayList<BrowseEntry>()
    private val selectedIds = HashSet<String>()
    private val density = context.resources.displayMetrics.density

    fun submit(list: List<BrowseEntry>, selected: Set<String>) {
        entries.clear()
        entries.addAll(list)
        selectedIds.clear()
        selectedIds.addAll(selected)
        notifyDataSetChanged()
    }

    fun entryAt(position: Int): BrowseEntry? = entries.getOrNull(position)

    fun toggle(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        if (entry.isContainer) return
        val selected = if (selectedIds.contains(entry.id)) {
            selectedIds.remove(entry.id)
            false
        } else {
            selectedIds.add(entry.id)
            true
        }
        callbacks.onSelectionChanged(entry, selected)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size

    override fun getItem(position: Int): Any = entries[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val entry = entries[position]
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        if (entry.isContainer) {
            row.addView(TextView(context).apply {
                text = "📁"
                textSize = 18f
                setPadding(0, 0, dp(10), 0)
            })
        } else {
            row.addView(CheckBox(context).apply {
                isChecked = selectedIds.contains(entry.id)
                isClickable = false
                isFocusable = false
                setPadding(0, 0, dp(6), 0)
            })
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = entry.title
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
        })
        column.addView(TextView(context).apply {
            text = if (entry.isContainer) {
                "文件夹" + (entry.childCount?.let { " · $it 项" } ?: "")
            } else {
                formatSize(entry.originalRes?.sizeHint) ?: "照片"
            }
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
        })
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            if (entry.isContainer) callbacks.onOpenContainer(entry) else toggle(position)
        }
        return row
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun formatSize(bytes: Long?): String? = when {
        bytes == null || bytes <= 0 -> null
        bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
