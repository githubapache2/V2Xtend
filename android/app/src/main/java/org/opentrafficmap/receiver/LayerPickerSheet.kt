package org.opentrafficmap.receiver

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * BottomSheet for map layers — replaces the old plain AlertDialog picker.
 *
 * Deliberately structured around independent [Section]s rather than a single flat
 * list: today there's only one (map style, single-select), but the whole point of
 * moving off AlertDialog was to have somewhere to put future overlay layers
 * (DWD weather warnings, Autobahn API roadworks, ...) as their own toggle group
 * without redesigning the sheet again. Adding one is just another [show] call
 * away — see MainActivity.showLayerPicker() for the current single-section usage.
 */
object LayerPickerSheet {

    data class Choice(val key: String, val label: String)

    data class Section(
        val title: String,
        val choices: List<Choice>,
        val selectedKey: String,
        val onPick: (String) -> Unit,
    )

    fun show(context: Context, title: String, sections: List<Section>) {
        val sheet = BottomSheetDialog(context)
        val view  = LayoutInflater.from(context).inflate(R.layout.dialog_layer_picker, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.layerSheetTitle).text = title
        val content = view.findViewById<LinearLayout>(R.id.layerSheetContent)
        val inflater = LayoutInflater.from(context)

        for (section in sections) {
            val header = inflater.inflate(R.layout.item_layer_section_header, content, false)
            header.findViewById<TextView>(R.id.sectionHeaderLabel).text = section.title
            content.addView(header)

            for (choice in section.choices) {
                val row = inflater.inflate(R.layout.item_layer_choice, content, false)
                row.findViewById<TextView>(R.id.choiceLabel).text = choice.label
                row.findViewById<TextView>(R.id.choiceCheck).visibility =
                    if (choice.key == section.selectedKey) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    section.onPick(choice.key)
                    sheet.dismiss()
                }
                content.addView(row)
            }
        }

        sheet.show()
    }
}
