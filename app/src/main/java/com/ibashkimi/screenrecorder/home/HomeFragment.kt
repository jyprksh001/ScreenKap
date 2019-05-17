/*
 * Copyright (C) 2019 Indrit Bashkimi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibashkimi.screenrecorder.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.recyclerview.selection.SelectionTracker
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ibashkimi.screenrecorder.MainActivity.Companion.ACTION_TOGGLE_RECORDING
import com.ibashkimi.screenrecorder.R
import com.ibashkimi.screenrecorder.data.Recording
import com.ibashkimi.screenrecorder.recordings.RecordingListFragment
import com.ibashkimi.screenrecorder.services.RecorderService
import com.ibashkimi.screenrecorder.services.RecorderState
import com.ibashkimi.screenrecorder.settings.PreferenceHelper


class HomeFragment : RecordingListFragment() {

    private lateinit var bottomBar: BottomAppBar

    private lateinit var fab: FloatingActionButton

    override val layoutRes = R.layout.fragment_home

    private lateinit var recorderState: LiveData<RecorderState.State>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recorderState = viewModel.recorderState

        // Respond to app shortcut
        requireActivity().intent.action?.let {
            when (it) {
                ACTION_TOGGLE_RECORDING -> if (RecorderState.State.STOPPED == recorderState.value)
                    startRecording()
                else {
                    stopRecording()
                    requireActivity().finish()
                }
            }
        }

        view.findViewById<Toolbar?>(R.id.toolbar)?.title = getString(R.string.home_recordings_title)
        // Configure fab
        fab = view.findViewById(R.id.fab)
        fab.apply {
            setOnClickListener {
                when {
                    selectionTracker.hasSelection() -> selectionTracker.clearSelection()
                    isRecording -> stopRecording()
                    else -> startRecording()
                }
            }
            setOnLongClickListener {
                Toast.makeText(requireContext(), R.string.home_fab_record_hint, Toast.LENGTH_SHORT).show()
                true
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            PreferenceHelper(requireContext()).apply {
                if (recordAudio) {
                    recordAudio = false
                }
            }
        }

        // Configure bottom bar
        bottomBar = view.findViewById<BottomAppBar>(R.id.bar).apply {
            hideOnScroll = true
            setNavigationOnClickListener {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                } else {
                    //findNavController().navigate(HomeFragmentDirections.actionHomeToBottomNavigationDialog())
                    val fragmentManager = requireActivity().supportFragmentManager
                    val bottomFragment: BottomSheetDialogFragment
                    bottomFragment = fragmentManager.findFragmentByTag("bottom_fragment")
                            as BottomSheetDialogFragment? ?: BottomNavigationDialog()
                    bottomFragment.show(fragmentManager, "bottom_fragment")
                }
            }
            inflateMenu(R.menu.home)
            setOnMenuItemClickListener {
                if (NavigationUI.onNavDestinationSelected(it, Navigation.findNavController(bottomBar))) {
                    true
                } else {
                    when (it.itemId) {
                        R.id.more_settings -> {
                            findNavController().navigate(HomeFragmentDirections.actionHomeToMoreSettingsDialog())
                            true
                        }
                        R.id.share -> {
                            val recording = selectionTracker.selection!!.first()
                            if (selectionTracker.selection.size() == 1) {
                                showShareRecordingDialog(recording)
                            } else {
                                shareVideos(selectionTracker.selection.toList())
                            }
                            true
                        }
                        R.id.delete -> {
                            val recording = selectionTracker.selection!!.first()
                            if (selectionTracker.selection.size() == 1) {
                                showDeleteRecordingDialog(recording)
                            } else {
                                showDeleteRecordingsDialog(selectionTracker.selection.toList())
                            }
                            true
                        }
                        R.id.rename -> {
                            val recording = selectionTracker.selection!!.first()
                            showRenameRecordingDialog(requireContext(), recording)
                            true
                        }
                        else -> super.onOptionsItemSelected(it)
                    }
                }
            }
        }

        selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<Recording>() {
            override fun onSelectionChanged() {
                when {
                    !selectionTracker.hasSelection() -> bottomBarOnReset()
                    selectionTracker.selection.size() == 1 -> bottomBarOnItemSelected()
                    selectionTracker.selection.size() == 2 -> bottomBarOnItemsSelected()
                }
            }
        })

        recorderState.observe(viewLifecycleOwner, Observer {
            when (it) {
                RecorderState.State.RECORDING -> {
                    onRecording()
                }
                RecorderState.State.PAUSED -> {
                    onRecordingPaused()
                }
                RecorderState.State.STOPPED -> {
                    onRecordingStopped()
                }
                else -> {
                    onRecordingStopped()
                }
            }
        })
    }

    private val isRecording: Boolean
        get() = recorderState.value?.run {
            this != RecorderState.State.STOPPED
        } ?: false

    private fun onRecording() {
        configureFab(true)
    }

    private fun onRecordingStopped() {
        configureFab(false)
    }

    private fun onRecordingPaused() {
        configureFab(true)
    }

    private fun configureFab(isRecording: Boolean) {
        fab.setImageDrawable(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record)
    }

    private fun startRecording() {
        // Request Screen recording permission
        val projectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_RECORD_REQUEST_CODE)
    }

    private fun stopRecording() {
        RecorderService.stop(requireContext())
    }

    private fun bottomBarOnReset() {
        configureFab(isRecording)
        bottomBar.navigationIcon = requireContext().getDrawable(R.drawable.ic_menu)
        bottomBar.replaceMenu(R.menu.home)
        bottomBar.fabAlignmentMode = BottomAppBar.FAB_ALIGNMENT_MODE_CENTER
    }

    private fun bottomBarOnItemSelected() {
        bottomBar.fabAlignmentMode = BottomAppBar.FAB_ALIGNMENT_MODE_END
        bottomBar.navigationIcon = requireContext().getDrawable(R.drawable.ic_cancel)
        bottomBar.replaceMenu(R.menu.item_selected)
    }

    private fun bottomBarOnItemsSelected() {
        bottomBar.menu.findItem(R.id.rename)?.apply { isVisible = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && requestCode == SCREEN_RECORD_REQUEST_CODE) {
            RecorderService.start(requireContext(), resultCode, data)
        }

        requireActivity().intent.action?.takeIf { it == ACTION_TOGGLE_RECORDING }?.let {
            requireActivity().finish()
        }
    }

    private fun showRenameRecordingDialog(context: Context, recording: Recording) {
        val inflater = LayoutInflater.from(context)
        @SuppressLint("InflateParams")
        val view = inflater.inflate(R.layout.dialog_rename_file, null)
        val input = view.findViewById<EditText>(R.id.new_name)
        input.setText(recording.title)
        input.selectAll()

        AlertDialog.Builder(context)
                .setTitle(R.string.dialog_title_rename)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    val newName = input.text.toString().trim { it <= ' ' }
                    rename(recording, newName)
                    selectionTracker.clearSelection()
                    dialog.cancel()
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
                .setView(view)
                .create()
                .show()
    }

    private fun showDeleteRecordingDialog(recording: Recording) {
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_delete_file_msg)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    delete(recording)
                    selectionTracker.clearSelection()
                    dialog.cancel()
                }
                .setNegativeButton(android.R.string.no) { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun showDeleteRecordingsDialog(recordings: List<Recording>) {
        AlertDialog.Builder(requireContext())
                .setTitle("Delete all selected recordings?")
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    delete(recordings)
                    selectionTracker.clearSelection()
                    dialog.cancel()
                }
                .setNegativeButton(android.R.string.no) { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun showShareRecordingDialog(recording: Recording) {
        val intent = Intent()
                .setAction(Intent.ACTION_SEND)
                .setType("video/*")
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, recording.uri)
        startActivity(Intent.createChooser(intent,
                getString(R.string.notification_finish_title)))
    }

    private fun shareVideos(recordings: List<Recording>) {
        val videoList = ArrayList<Uri>()
        for (recording in recordings) {
            videoList.add(recording.uri)
        }
        val shareIntent = Intent()
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .setType("video/*")
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, videoList)
        startActivity(Intent.createChooser(shareIntent,
                getString(R.string.notification_finish_title)))
    }

    private fun FloatingActionButton.setImageDrawable(res: Int) {
        this.setImageDrawable(requireContext().getDrawable(res))
    }

    companion object {
        const val SCREEN_RECORD_REQUEST_CODE = 1003
    }
}