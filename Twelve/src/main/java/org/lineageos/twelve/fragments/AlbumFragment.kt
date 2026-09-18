/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.getParcelable
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.recyclerview.UniqueItemDiffCallback
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.AlbumViewModel

/**
 * Single music album viewer.
 */
class AlbumFragment : CollapsingToolbarLayoutFragment(R.layout.fragment_album) {
    // View models
    private val viewModel by viewModels<AlbumViewModel>()

    // Views
    private val albumTitleTextView by getViewProperty<TextView>(R.id.albumTitleTextView)
    override val appBarLayout by getViewProperty<AppBarLayout>(R.id.appBarLayout)
    private val artistNameTextView by getViewProperty<TextView>(R.id.artistNameTextView)
    override val coordinatorLayout by getViewProperty<CoordinatorLayout>(R.id.coordinatorLayout)
    private val fileTypeMaterialCardView by getViewProperty<MaterialCardView>(R.id.fileTypeMaterialCardView)
    private val fileTypeTextView by getViewProperty<TextView>(R.id.fileTypeTextView)
    private val infoNestedScrollView by getViewProperty<NestedScrollView?>(R.id.infoNestedScrollView)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsNestedScrollView by getViewProperty<NestedScrollView>(R.id.noElementsNestedScrollView)
    private val playAllExtendedFloatingActionButton by getViewProperty<ExtendedFloatingActionButton>(
        R.id.playAllExtendedFloatingActionButton
    )
    private val playButtonsLinearLayout by getViewProperty<LinearLayout>(R.id.playButtonsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val shufflePlayExtendedFloatingActionButton by getViewProperty<ExtendedFloatingActionButton>(
        R.id.shufflePlayExtendedFloatingActionButton
    )
    private val thumbnailImageView by getViewProperty<ImageView>(R.id.thumbnailImageView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val tracksInfoTextView by getViewProperty<TextView>(R.id.tracksInfoTextView)
    private val yearTextView by getViewProperty<TextView>(R.id.yearTextView)

    // Recyclerview
    private val adapter by lazy {
        object : SimpleListAdapter<AlbumViewModel.AlbumContent, ListItem>(
            UniqueItemDiffCallback(),
            ::ListItem,
        ) {
            private val ViewHolder.trackTextView
                get() = view.leadingView!!.findViewById<TextView>(R.id.trackTextView)

            override fun ViewHolder.onPrepareView() {
                view.setLeadingView(R.layout.audio_track_index)
            }

            override fun ViewHolder.onBindView(item: AlbumViewModel.AlbumContent) {
                when (item) {
                    is AlbumViewModel.AlbumContent.DiscHeader -> {
                        view.setLeadingIconImage(R.drawable.ic_album)
                        view.leadingViewIsVisible = false
                        view.setHeadlineText(
                            R.string.album_disc_header,
                            item.discNumber,
                        )
                        view.supportingText = null
                        view.trailingSupportingText = null
                        view.isClickable = false
                        view.isLongClickable = false
                    }

                    is AlbumViewModel.AlbumContent.AudioItem -> {
                        view.setOnClickListener {
                            viewModel.playAlbum(item.audio)
                        }
                        view.setOnLongClickListener {
                            findNavController().navigateSafe(
                                R.id.action_albumFragment_to_fragment_media_item_bottom_sheet_dialog,
                                MediaItemBottomSheetDialogFragment.createBundle(
                                    item.audio.uri,
                                    fromAlbum = true,
                                )
                            )

                            true
                        }

                        when {
                            item.isCurrent -> {
                                view.setLeadingIconImage(R.drawable.ic_track_playing)
                                view.headlineTextIsActivated = true
                                view.leadingIconImageIsActivated = true
                                view.leadingViewIsVisible = false
                            }

                            item.audio.trackNumber != null -> {
                                view.leadingIconImage = null
                                trackTextView.text = getString(
                                    R.string.track_number,
                                    item.audio.trackNumber
                                )
                                view.headlineTextIsActivated = false
                                view.leadingIconImageIsActivated = false
                                view.leadingViewIsVisible = true
                            }

                            else -> {
                                view.setLeadingIconImage(R.drawable.ic_music_note)
                                view.headlineTextIsActivated = false
                                view.leadingIconImageIsActivated = false
                                view.leadingViewIsVisible = false
                            }
                        }

                        view.headlineText = item.audio.title
                        item.audio.artistName?.also {
                            view.supportingText = it
                        } ?: view.setSupportingText(R.string.artist_unknown)
                        view.trailingSupportingText = item.audio.durationMs?.let {
                            TimestampFormatter.formatTimestampMillis(it)
                        }
                        view.isClickable = true
                        view.isLongClickable = true
                    }
                }
            }
        }
    }

    // Arguments
    private val albumUri: Uri
        get() = requireArguments().getParcelable(ARG_ALBUM_URI, Uri::class)!!

    // Permissions
    private val permissionsChecker = PermissionsChecker(
        this, PermissionsUtils.mainPermissions
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = true,
                end = true,
            )

            windowInsets
        }

        infoNestedScrollView?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                v.updatePadding(
                    insets,
                    bottom = true,
                )

                windowInsets
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(noElementsNestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            playButtonsLinearLayout
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        toolbar.setupWithNavController(findNavController())
        toolbar.inflateMenu(R.menu.fragment_album_toolbar)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.addToQueue -> {
                    viewModel.addToQueue()
                    true
                }

                R.id.playNext -> {
                    viewModel.playNext()
                    true
                }

                else -> false
            }
        }

        recyclerView.adapter = adapter

        playAllExtendedFloatingActionButton.setOnClickListener {
            viewModel.playAlbum()
        }

        shufflePlayExtendedFloatingActionButton.setOnClickListener {
            viewModel.shufflePlayAlbum()
        }

        viewModel.loadAlbum(albumUri)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                viewModel.album.collectLatest {
                    linearProgressIndicator.setProgressCompat(it)

                    when (it) {
                        is FlowResult.Loading -> {
                            // Do nothing
                        }

                        is FlowResult.Success -> {
                            val (album, audios) = it.data

                            album.title?.also { albumTitle ->
                                toolbar.title = albumTitle
                                albumTitleTextView.text = albumTitle
                            } ?: run {
                                toolbar.setTitle(R.string.album_unknown)
                                albumTitleTextView.setText(R.string.album_unknown)
                            }

                            thumbnailImageView.loadThumbnail(
                                album.thumbnail,
                                placeholder = R.drawable.ic_album
                            )

                            album.artistName?.also { artistName ->
                                artistNameTextView.text = artistName
                            } ?: artistNameTextView.setText(R.string.artist_unknown)
                            artistNameTextView.setOnClickListener {
                                album.artistUri?.let { artistUri ->
                                    findNavController().navigateSafe(
                                        R.id.action_albumFragment_to_fragment_artist,
                                        ArtistFragment.createBundle(artistUri)
                                    )
                                }
                            }

                            album.year?.also { year ->
                                yearTextView.isVisible = true
                                yearTextView.text = getString(R.string.year_format, year)
                            } ?: run {
                                yearTextView.isVisible = false
                            }

                            val totalDurationMs = audios.sumOf { audio ->
                                audio.durationMs ?: 0L
                            }
                            val totalDurationMinutes = (totalDurationMs / 1000 / 60).toInt()

                            val tracksCount = resources.getQuantityString(
                                R.plurals.tracks_count,
                                audios.size,
                                audios.size
                            )
                            val tracksDuration = resources.getQuantityString(
                                R.plurals.tracks_duration,
                                totalDurationMinutes,
                                totalDurationMinutes
                            )
                            tracksInfoTextView.text = getString(
                                R.string.tracks_info,
                                tracksCount, tracksDuration
                            )
                        }

                        is FlowResult.Failure -> {
                            Log.e(
                                LOG_TAG,
                                "Error loading album, error: ${it.error}",
                                it.throwable
                            )

                            toolbar.title = ""
                            albumTitleTextView.text = ""

                            if (it.error == Error.NOT_FOUND) {
                                // Get out of here
                                findNavController().navigateUp()
                            }
                        }
                    }
                }
            }

            launch {
                viewModel.albumContent.collectLatest {
                    adapter.submitList(it)

                    val isEmpty = it.isEmpty()
                    recyclerView.isVisible = !isEmpty
                    noElementsNestedScrollView.isVisible = isEmpty
                    when (isEmpty) {
                        true -> {
                            playAllExtendedFloatingActionButton.hide()
                            shufflePlayExtendedFloatingActionButton.hide()
                        }

                        false -> {
                            playAllExtendedFloatingActionButton.show()
                            shufflePlayExtendedFloatingActionButton.show()
                        }
                    }
                }
            }

            launch {
                viewModel.albumFileTypes.collectLatest {
                    fileTypeMaterialCardView.isVisible = it.isNotEmpty()
                    fileTypeTextView.text = it.joinToString(" / ")
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = AlbumFragment::class.simpleName!!

        private const val ARG_ALBUM_URI = "album_uri"

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         * @param albumUri The URI of the album to display
         */
        fun createBundle(
            albumUri: Uri,
        ) = Bundle {
            putParcelable(ARG_ALBUM_URI, albumUri)
        }
    }
}
