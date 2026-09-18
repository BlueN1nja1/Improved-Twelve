/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.models.Provider
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.viewmodels.ProvidersViewModel

/**
 * Fragment used to select a media provider.
 */
class ProviderSelectorDialogFragment : MaterialDialogFragment(
    R.layout.fragment_provider_selector_dialog
) {
    // View models
    private val viewModel by viewModels<ProvidersViewModel>()

    // Views
    private val addProviderMaterialButton by getViewProperty<MaterialButton>(R.id.addProviderMaterialButton)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)

    // Recyclerview
    private val adapter = object : SimpleListAdapter<Pair<Provider, Boolean>, ListItem>(
        diffCallback,
        ::ListItem,
    ) {
        override fun ViewHolder.onPrepareView() {
            view.setTrailingView(R.layout.provider_more_button)
            view.hasRoundedCorners = true
        }

        override fun ViewHolder.onBindView(item: Pair<Provider, Boolean>) {
            val (provider, isCurrent) = item

            view.setOnClickListener {
                viewModel.setNavigationProvider(provider)
                findNavController().navigateUp()
            }

            view.trailingView?.isVisible = provider.type.canBeManaged
            view.trailingView?.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.action_providerSelectorDialogFragment_to_fragment_provider_information_bottom_sheet_dialog,
                    ManageProviderFragment.createBundle(
                        providerIdentifier = provider.identifier,
                    ),
                    NavOptions.Builder()
                        .setPopUpTo(R.id.mainFragment, false)
                        .build(),
                )
            }

            view.setLeadingIconImage(provider.type.iconDrawableResId)
            view.headlineText = provider.name
            view.setSupportingText(provider.type.nameStringResId)

            view.isSelected = isCurrent
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView.adapter = adapter

        addProviderMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_providerSelectorDialogFragment_to_fragment_manage_provider,
                ManageProviderFragment.createBundle()
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.providersToIsCurrent.collect { providers ->
                    adapter.submitList(providers)
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.adapter = null

        super.onDestroyView()
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<Pair<Provider, Boolean>>() {
            override fun areItemsTheSame(
                oldItem: Pair<Provider, Boolean>,
                newItem: Pair<Provider, Boolean>
            ) = oldItem.first.areItemsTheSame(newItem.first)

            override fun areContentsTheSame(
                oldItem: Pair<Provider, Boolean>,
                newItem: Pair<Provider, Boolean>
            ) = oldItem.first.areContentsTheSame(newItem.first) && oldItem.second == newItem.second
        }
    }
}
