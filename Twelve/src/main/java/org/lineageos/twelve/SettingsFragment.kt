/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.CallSuper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.lineageos.twelve.ext.ENABLE_OFFLOAD_KEY
import org.lineageos.twelve.ext.SKIP_SILENCE_KEY
import org.lineageos.twelve.viewmodels.SettingsViewModel

class SettingsFragment : PreferenceFragmentCompat() {
    private val viewModel by viewModels<SettingsViewModel>()

    private lateinit var enableOffload: SwitchPreference
        private lateinit var rescanMediaStore: Preference
            private lateinit var resetLocalStats: Preference
                private lateinit var skipSilence: SwitchPreference

                    // The MarginItemDecoration has been removed as it's no longer needed.

                    @CallSuper
                    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
                        setPreferencesFromResource(R.xml.root_preferences, rootKey)

                        enableOffload = findPreference(ENABLE_OFFLOAD_KEY)!!
                        rescanMediaStore = findPreference("rescan_media_store")!!
                        resetLocalStats = findPreference("reset_local_stats")!!
                        skipSilence = findPreference(SKIP_SILENCE_KEY)!!

                        setupPreferenceListeners()
                    }

                    @CallSuper
                    override fun onCreateRecyclerView(
                        inflater: LayoutInflater,
                        parent: ViewGroup,
                        savedInstanceState: Bundle?
                    ) = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
                        clipToPadding = false
                        isVerticalScrollBarEnabled = false

                        // We still need the horizontal padding for the cards.
                        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
                        setPadding(horizontalPadding, 0, horizontalPadding, 0)

                        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                            // Update padding to respect system insets and our custom horizontal padding.
                            updatePadding(
                                bottom = insets.bottom,
                                left = insets.left + horizontalPadding,
                                right = insets.right + horizontalPadding,
                            )

                            windowInsets
                        }
                    }

                    private fun setupPreferenceListeners() {
                        enableOffload.setOnPreferenceChangeListener { _, newValue ->
                            lifecycleScope.launch {
                                viewModel.toggleOffload(newValue as Boolean)
                            }
                            true
                        }

                        skipSilence.setOnPreferenceChangeListener { _, newValue ->
                            lifecycleScope.launch {
                                viewModel.toggleSkipSilence(newValue as Boolean)
                            }
                            true
                        }

                        resetLocalStats.setOnPreferenceClickListener {
                            showResetLocalStatsDialog()
                            true
                        }

                        rescanMediaStore.setOnPreferenceClickListener {
                            showRescanMediaStoreDialog()
                            true
                        }
                    }

                    private fun showResetLocalStatsDialog() {
                        val context = requireActivity()
                        MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.reset_local_stats_confirm_title)
                        .setMessage(R.string.reset_local_stats_confirm_message)
                        .setPositiveButton(R.string.reset_local_stats_confirm_positive) { _, _ ->
                            lifecycleScope.launch {
                                viewModel.resetLocalStats()

                                Toast.makeText(
                                    context,
                                    R.string.reset_local_stats_success,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> /* Do nothing */ }
                        .show()
                    }

                    private fun showRescanMediaStoreDialog() {
                        val context = requireActivity()
                        MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.rescan_media_store_confirm_title)
                        .setMessage(R.string.rescan_media_store_confirm_message)
                        .setPositiveButton(R.string.rescan_media_store_confirm_positive) { _, _ ->
                            viewModel.rescanMediaStore()

                            Toast.makeText(
                                context,
                                R.string.rescan_media_store_started,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> /* Do nothing */ }
                        .show()
                    }
}
