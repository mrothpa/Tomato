/*
 * Copyright (c) 2025 Nishant Mishra
 *
 * This file is part of Tomato - a minimalist pomodoro timer for Android.
 *
 * Tomato is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tomato is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tomato.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package org.nsh07.pomodoro

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.android.ext.android.inject
import org.nsh07.pomodoro.data.StateRepository
import org.nsh07.pomodoro.di.ActivityCallbacks
import org.nsh07.pomodoro.ui.AppScreen
import org.nsh07.pomodoro.ui.settingsScreen.viewModel.SettingsViewModel
import org.nsh07.pomodoro.ui.theme.TomatoTheme
import org.nsh07.pomodoro.utils.toColor

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by inject()
    private val stateRepository: StateRepository by inject()
    private val activityCallbacks: ActivityCallbacks by inject()

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_CALENDAR] ?: false
        val writeGranted = permissions[Manifest.permission.WRITE_CALENDAR] ?: false
        
        if (readGranted && writeGranted) {
            Log.d("MainActivity", "Calendar permissions granted")
        } else {
            Log.d("MainActivity", "Calendar permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        activityCallbacks.activityTurnScreenOn = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(it)
                setTurnScreenOn(it)
            }
        }

        checkAndRequestCalendarPermissions()

        setContent {
            val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()

            val darkTheme = when (settingsState.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            val seed = settingsState.colorScheme.toColor()

            val isPlus by settingsViewModel.isPlus.collectAsStateWithLifecycle()

            TomatoTheme(
                darkTheme = darkTheme,
                seedColor = seed,
                blackTheme = settingsState.blackTheme
            ) {
                val colorScheme = colorScheme
                LaunchedEffect(colorScheme) {
                    stateRepository.colorScheme = colorScheme
                }

                AppScreen(
                    isPlus = isPlus,
                    isAODEnabled = settingsState.aodEnabled,
                    setTimerFrequency = {
                        stateRepository.timerFrequency = it
                    }
                )
            }
        }
    }

    private fun checkAndRequestCalendarPermissions() {
        val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
        val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)

        if (readPermission == PackageManager.PERMISSION_GRANTED && 
            writePermission == PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Calendar permissions already granted")
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CALENDAR) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_CALENDAR)) {
                
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.focus))
                    .setMessage("Tomato needs calendar permissions to sync your focus sessions with your system calendar.")
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        requestPermissions()
                    }
                    .setNegativeButton(getString(R.string.back), null)
                    .show()
            } else {
                requestPermissions()
            }
        }
    }

    private fun requestPermissions() {
        calendarPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    override fun onStop() {
        super.onStop()
        // Reduce the timer loop frequency when not visible to save battery
        stateRepository.timerFrequency = 1f
    }

    override fun onStart() {
        super.onStart()
        // Increase the timer loop frequency again when visible to make the progress smoother
        stateRepository.timerFrequency = 60f
    }
}
