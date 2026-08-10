package org.opentrafficmap.receiver

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * BottomSheet for map layers — replaces the old plain AlertDialog picker.
 *
 * Two kinds of section, deliberately kept separate rather than forced into
 * one shape:
 *  - [ChoiceSection]: single-select/radio-style, e.g. map style. Picking an
 *    option dismisses the sheet.
 *  - [ToggleSection]: independent on/off switches, e.g. overlay data layers
 *    (roadworks and friends). Flipping one does NOT dismiss the sheet, since
 *    a user may want more than one overlay on per visit.
 *
 * The original version of this sheet (map style only) only had ChoiceSection
 * and assumed picking a layer group always means picking exactly one option
 * — that held up for the base-map-style use case, but not for overlay data
 * sources added later: an overlay is either on or off, not one-of-many, and
 * shouldn't close the sheet when toggled. ToggleSection was added for that
 * (Redesign Phase 2, Punkt 2 / roadworks integration) rather than shoehorning
 * overlays into ChoiceSection's shape — see CLAUDE.md for the fuller note on
 * what did and didn't hold up unchanged from the original design.
 */
object LayerPickerSheet {

    sealed interface Section

    data class Choice(val key: String, val label: String)

    data class ChoiceSection(
        val title: String,
        val choices: List<Choice>,
        val selectedKey: String,
        val onPick: (String) -> Unit,
    ) : Section

    data class ToggleItem(
        val key: String,
        val label: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit,
    )

    data class ToggleSection(
        val title: String,
        val items: List<ToggleItem>,
    ) : Section

    fun show(context: Context, title: String, sections: List<Section>) {
        val sheet = BottomSheetDialog(context)
        val view  = LayoutInflater.from(context).inflate(R.layout.dialog_layer_picker, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.layerSheetTitle).text = title
        val content = view.findViewById<LinearLayout>(R.id.layerSheetContent)
        val inflater = LayoutInflater.from(context)

        for (section in sections) {
            val header = inflater.inflate(R.layout.item_layer_section_header, content, false)
            when (section) {
                is ChoiceSection -> {
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
                is ToggleSection -> {
                    header.findViewById<TextView>(R.id.sectionHeaderLabel).text = section.title
                    content.addView(header)
                    for (item in section.items) {
                        val row = inflater.inflate(R.layout.item_layer_toggle, content, false)
                        row.findViewById<TextView>(R.id.toggleLabel).text = item.label
                        val switchView = row.findViewById<MaterialSwitch>(R.id.toggleSwitch)
                        switchView.isChecked = item.checked
                        row.setOnClickListener {
                            val newState = !switchView.isChecked
                            switchView.isChecked = newState
                            item.onToggle(newState)
                        }
                        content.addView(row)
                    }
                }
            }
        }

        sheet.show()
    }
}
