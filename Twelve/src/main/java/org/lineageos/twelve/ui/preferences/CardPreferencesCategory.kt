package org.lineageos.twelve.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import org.lineageos.twelve.R

/**
 * A custom PreferenceCategory that styles its children to appear as a card, designed according to Material 3 Expressive
 */
class CardPreferenceCategory(
    context: Context,
    attrs: AttributeSet?
) : PreferenceCategory(context, attrs) {

    init {
        layoutResource = R.layout.m3_expressive_cat_card_header
    }

    override fun addPreference(preference: Preference): Boolean {
        // Add the preference first, THEN update the layouts
        val result = super.addPreference(preference)
        updatePreferenceLayouts()
        return result
    }

    private fun updatePreferenceLayouts() {
        val preferenceCount = preferenceCount
        if (preferenceCount == 0) return

            // If there's only one item, it gets the 'single' layout
            if (preferenceCount == 1) {
                getPreference(0).layoutResource = R.layout.m3_expressive_pref_single
                return
            }

            // Apply top, middle, and bottom layouts for multiple items
            for (i in 0 until preferenceCount) {
                val pref = getPreference(i)
                pref.layoutResource = when (i) {
                    0 -> R.layout.m3_expressive_pref_top
                    preferenceCount - 1 -> R.layout.m3_expressive_pref_bottom
                    else -> R.layout.m3_expressive_pref_middle
                }
            }
    }
}
