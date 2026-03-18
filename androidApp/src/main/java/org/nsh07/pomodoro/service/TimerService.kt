/*
 * Copyright (c) 2025-2026 Nishant Mishra
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

package org.nsh07.pomodoro.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.nsh07.pomodoro.R
import org.nsh07.pomodoro.data.Session
import org.nsh07.pomodoro.data.SessionDao
import org.nsh07.pomodoro.data.SessionType
import org.nsh07.pomodoro.data.StatRepository
import org.nsh07.pomodoro.data.StateRepository
import org.nsh07.pomodoro.di.ActivityCallbacks
import org.nsh07.pomodoro.di.TimerStateHolder
import org.nsh07.pomodoro.ui.timerScreen.viewModel.TimerMode
import org.nsh07.pomodoro.utils.CalendarSyncManager
import org.nsh07.pomodoro.utils.millisecondsToStr
import kotlin.text.Typography.middleDot

class TimerService : Service(), KoinComponent, SensorEventListener {

    private val stateRepository: StateRepository by inject()
    private val statRepository: StatRepository by inject()
    private val sessionDao: SessionDao by inject()
    private val calendarSyncManager: CalendarSyncManager by inject()
    private val notificationManager: NotificationManagerCompat by inject()
    private val notificationManagerService: NotificationManager by inject()
    private val notificationBuilder: NotificationCompat.Builder by inject()
    private val activityCallbacks: ActivityCallbacks by inject()
    private val _timerState by lazy { stateRepository.timerState }
    private val _settingsState by lazy { stateRepository.settingsState }
    private val _time = get<TimerStateHolder>().time

    /**
     * Remaining time
     */
    private var time: Long
        get() = _time.value
        set(value) = _time.update { value }

    private var cycles = 0
    private var startTime = 0L
    private var pauseTime = 0L
    private var pauseDuration = 0L

    private var sessionActualStartTime = 0L
    private var pendingSession: Session? = null

    private var lastSavedDuration = 0L

    private val timerStateSnapshot by lazy { stateRepository.timerStateSnapshot }

    private val saveLock = Mutex()
    private var job = SupervisorJob()
    private val timerScope = CoroutineScope(Dispatchers.IO + job)
    private val skipScope = CoroutineScope(Dispatchers.IO + job)

    private var autoAlarmStopScope: Job? = null

    private var alarm: MediaPlayer? = null
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val accelerometer by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private var isFaceDown = false
    private var lastSensorUpdate = 0L
    private var abortJob: Job? = null

    private val cs by lazy { stateRepository.colorScheme }

    private lateinit var notificationStyle: NotificationCompat.ProgressStyle

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        stateRepository.timerState.update { it.copy(serviceRunning = true) }
        alarm = initializeMediaPlayer()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onDestroy() {
        stateRepository.timerState.update { it.copy(serviceRunning = false) }
        sensorManager.unregisterListener(this)
        runBlocking {
            job.cancel()
            saveTimeToDb()
            lastSavedDuration = 0
            setDoNotDisturb(false)
            notificationManager.cancel(1)
            alarm?.release()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.TOGGLE.toString() -> {
                startForegroundService()
                toggleTimer()
            }

            Actions.RESET.toString() -> {
                if (_timerState.value.timerRunning) toggleTimer()
                skipScope.launch {
                    resetTimer()
                    stopForegroundService()
                }
            }

            Actions.UNDO_RESET.toString() -> undoReset()

            Actions.SKIP.toString() -> skipScope.launch { skipTimer(true) }

            Actions.STOP_ALARM.toString() -> stopAlarm()

            Actions.UPDATE_ALARM_TONE.toString() -> updateAlarmTone()

            Actions.SAVE_SESSION.toString() -> {
                val title = intent.getStringExtra("EXTRA_TITLE")
                savePendingSession(title)
            }

            Actions.DISMISS_SESSION.toString() -> {
                pendingSession = null
                _timerState.update { it.copy(isSessionComplete = false) }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun savePendingSession(title: String?) {
        pendingSession?.let { session ->
            timerScope.launch {
                val finalSession = session.copy(title = title)
                sessionDao.insertSession(finalSession)
                if (_settingsState.value.calendarEnabled) {
                    calendarSyncManager.addSessionToCalendar(finalSession, _settingsState.value.selectedCalendarId)
                }
                pendingSession = null
                _timerState.update { it.copy(isSessionComplete = false) }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSensorUpdate < 500) return // Debounce

            val z = event.values[2]
            val faceDown = z < -8.5

            if (faceDown != isFaceDown) {
                isFaceDown = faceDown
                lastSensorUpdate = currentTime
                
                if (!isFaceDown && _timerState.value.timerRunning) {
                    Log.d("TimerService", "FaceUp detected: Pausing")
                    toggleTimer()
                    
                    // Start 30s abort countdown completely
                    if (_timerState.value.timerMode == TimerMode.FOCUS) {
                        abortJob?.cancel()
                        abortJob = timerScope.launch {
                            for (i in 30 downTo 1) {
                                _timerState.update { it.copy(abortCountdown = i) }
                                delay(1000)
                            }
                            // Time's up: Complete abort
                            _timerState.update { it.copy(abortCountdown = null) }
                            resetTimer()
                        }
                    }
                } else if (isFaceDown) {
                    // Cancel abort countdown
                    if (abortJob != null) {
                        abortJob?.cancel()
                        abortJob = null
                        _timerState.update { it.copy(abortCountdown = null) }
                    }
                    
                    if (!_timerState.value.timerRunning && sessionActualStartTime != 0L) {
                        Log.d("TimerService", "FaceDown detected: Auto-Resuming")
                        toggleTimer()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggleTimer() {
        updateProgressSegments()
        
        // Clear abort state when manual toggle happens
        abortJob?.cancel()
        abortJob = null
        _timerState.update { it.copy(abortCountdown = null) }

        if (_timerState.value.timerRunning || _timerState.value.waitingForFaceDown) {
            setDoNotDisturb(false)
            notificationBuilder.clearActions().addTimerActions(
                this, R.drawable.play, getString(R.string.start)
            )
            showTimerNotification(time.toInt(), paused = true)
            _timerState.update { currentState ->
                currentState.copy(timerRunning = false, waitingForFaceDown = false)
            }
            pauseTime = SystemClock.elapsedRealtime()
        } else {
            if (sessionActualStartTime == 0L) {
                sessionActualStartTime = System.currentTimeMillis()
            }

            // If not face down, we don't start the ticking loop.
            // This allows the user to click Start, then put the phone down to actually start.
            if (!isFaceDown) {
                Log.d("TimerService", "Start clicked but not FaceDown: Waiting...")
                _timerState.update { it.copy(waitingForFaceDown = true) }
                return
            }

            if (_timerState.value.timerMode == TimerMode.FOCUS) setDoNotDisturb(true)
            else setDoNotDisturb(false)
            notificationBuilder.clearActions().addTimerActions(
                this, R.drawable.pause, getString(R.string.stop)
            )
            _timerState.update { it.copy(timerRunning = true, waitingForFaceDown = false) }
            if (pauseTime != 0L) pauseDuration += SystemClock.elapsedRealtime() - pauseTime

            var iterations = -1

            timerScope.launch {
                while (true) {
                    if (!_timerState.value.timerRunning) break
                    if (startTime == 0L) startTime = SystemClock.elapsedRealtime()

                    val settingsState = _settingsState.value
                    time = when (_timerState.value.timerMode) {
                        TimerMode.FOCUS -> settingsState.focusTime - (SystemClock.elapsedRealtime() - startTime - pauseDuration)

                        TimerMode.SHORT_BREAK -> settingsState.shortBreakTime - (SystemClock.elapsedRealtime() - startTime - pauseDuration)

                        else -> settingsState.longBreakTime - (SystemClock.elapsedRealtime() - startTime - pauseDuration)
                    }

                    iterations =
                        (iterations + 1) % stateRepository.timerFrequency.toInt().coerceAtLeast(1)

                    if (iterations == 0) showTimerNotification(time.toInt())

                    if (time < 0) {
                        val endTime = System.currentTimeMillis()
                        val mode = _timerState.value.timerMode
                        val capturedStartTime = sessionActualStartTime
                        val isFocus = mode == TimerMode.FOCUS
                        
                        pendingSession = Session(
                            title = null,
                            startTime = capturedStartTime,
                            endTime = endTime,
                            type = if (isFocus) SessionType.FOCUS else SessionType.BREAK
                        )
                        
                        if (isFocus) {
                            _timerState.update { currentState ->
                                currentState.copy(timerRunning = false, isSessionComplete = true)
                            }
                        } else {
                            // Automatically insert and skip dialog
                            timerScope.launch {
                                sessionDao.insertSession(pendingSession!!)
                                pendingSession = null
                            }
                            _timerState.update { currentState ->
                                currentState.copy(timerRunning = false, isSessionComplete = false)
                            }
                        }
                        
                        skipTimer()
                        break
                    } else {
                        _timerState.update { currentState ->
                            currentState.copy(
                                timeStr = millisecondsToStr(time)
                            )
                        }
                        val totalTime = _timerState.value.totalTime

                        if (totalTime - time < lastSavedDuration)
                            lastSavedDuration =
                                0 // Sanity check, prevents bugs if service is force closed
                        if (totalTime - time - lastSavedDuration > 60000)
                            saveTimeToDb()
                    }

                    delay((1000f / stateRepository.timerFrequency).toLong())
                }
            }
        }
    }

    @SuppressLint(
        "MissingPermission",
        "StringFormatInvalid"
    ) // We check for the permission when pressing the Play button in the UI
    fun showTimerNotification(
        remainingTime: Int, paused: Boolean = false, complete: Boolean = false
    ) {
        val settingsState = _settingsState.value

        if (complete) notificationBuilder.clearActions().addStopAlarmAction(this)

        val totalTime = when (_timerState.value.timerMode) {
            TimerMode.FOCUS -> settingsState.focusTime.toInt()
            TimerMode.SHORT_BREAK -> settingsState.shortBreakTime.toInt()
            else -> settingsState.longBreakTime.toInt()
        }

        val currentTimer = when (_timerState.value.timerMode) {
            TimerMode.FOCUS -> getString(R.string.focus)
            TimerMode.SHORT_BREAK -> getString(R.string.short_break)
            else -> getString(R.string.long_break)
        }

        val nextTimer = when (_timerState.value.nextTimerMode) {
            TimerMode.FOCUS -> getString(R.string.focus)
            TimerMode.SHORT_BREAK -> getString(R.string.short_break)
            else -> getString(R.string.long_break)
        }

        val remainingTimeString = if ((remainingTime.toFloat() / 60000f) < 1.0f) "< 1"
        else (remainingTime.toFloat() / 60000f).toInt()

        notificationManager.notify(
            1,
            notificationBuilder
                .setContentTitle(
                    if (!complete) {
                        "$currentTimer  $middleDot  ${
                            getString(R.string.min_remaining_notification, remainingTimeString)
                        }" + if (paused) "  $middleDot  ${getString(R.string.paused)}" else ""
                    } else "$currentTimer $middleDot ${getString(R.string.completed)}"
                )
                .setContentText(
                    getString(
                        R.string.up_next_notification,
                        nextTimer,
                        _timerState.value.nextTimeStr
                    )
                )
                .setStyle(
                    notificationStyle
                        .setProgress( // Set the current progress by filling the previous intervals and part of the current interval
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !settingsState.singleProgressBar) {
                                (totalTime - remainingTime) + ((cycles + 1) / 2) * settingsState.focusTime.toInt() + (cycles / 2) * settingsState.shortBreakTime.toInt()
                            } else (totalTime - remainingTime)
                        )
                )
                .setWhen(System.currentTimeMillis() + remainingTime) // Sets the Live Activity/Now Bar chip time
                .setShortCriticalText(millisecondsToStr(time.coerceAtLeast(0)))
                .build()
        )

        if (complete) {
            startAlarm()
            _timerState.update { currentState ->
                currentState.copy(alarmRinging = true)
            }
        }
    }

    private fun updateProgressSegments() {
        val settingsState = _settingsState.value
        notificationStyle = NotificationCompat.ProgressStyle()
            .also {
                // Add all the Focus, Short break and long break intervals in order
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !settingsState.singleProgressBar) {
                    // Android 16 and later supports live updates
                    // Set progress bar sections if on Baklava or later
                    for (i in 0..<settingsState.sessionLength * 2) {
                        if (i % 2 == 0) it.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(
                                settingsState.focusTime.toInt()
                            )
                                .setColor(cs.primary.toArgb())
                        )
                        else if (i != (settingsState.sessionLength * 2 - 1)) it.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(
                                settingsState.shortBreakTime.toInt()
                            ).setColor(cs.tertiary.toArgb())
                        )
                        else it.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(
                                settingsState.longBreakTime.toInt()
                            ).setColor(cs.tertiary.toArgb())
                        )
                    }
                } else {
                    it.addProgressSegment(
                        NotificationCompat.ProgressStyle.Segment(
                            when (_timerState.value.timerMode) {
                                TimerMode.FOCUS -> settingsState.focusTime.toInt()
                                TimerMode.SHORT_BREAK -> settingsState.shortBreakTime.toInt()
                                else -> settingsState.longBreakTime.toInt()
                            }
                        )
                    )
                }
            }
    }

    private suspend fun resetTimer() {
        val settingsState = _settingsState.value
        
        abortJob?.cancel()
        abortJob = null

        timerStateSnapshot.save(
            lastSavedDuration,
            time,
            cycles,
            startTime,
            pauseTime,
            pauseDuration,
            _timerState.value
        )

        saveTimeToDb()
        lastSavedDuration = 0
        time = settingsState.focusTime
        cycles = 0
        startTime = 0L
        pauseTime = 0L
        pauseDuration = 0L
        sessionActualStartTime = 0L

        _timerState.update { currentState ->
            currentState.copy(
                timerMode = TimerMode.FOCUS,
                timeStr = millisecondsToStr(time),
                totalTime = time,
                nextTimerMode = if (settingsState.sessionLength > 1) TimerMode.SHORT_BREAK else TimerMode.LONG_BREAK,
                nextTimeStr = millisecondsToStr(if (settingsState.sessionLength > 1) settingsState.shortBreakTime else settingsState.longBreakTime),
                currentFocusCount = 1,
                totalFocusCount = settingsState.sessionLength,
                abortCountdown = null,
                waitingForFaceDown = false,
                timerRunning = false
            )
        }

        updateProgressSegments()
    }

    private fun undoReset() {
        lastSavedDuration = timerStateSnapshot.lastSavedDuration
        time = timerStateSnapshot.time
        cycles = timerStateSnapshot.cycles
        startTime = timerStateSnapshot.startTime
        pauseTime = timerStateSnapshot.pauseTime
        pauseDuration = timerStateSnapshot.pauseDuration
        _timerState.update { timerStateSnapshot.timerState }
    }

    private suspend fun skipTimer(fromButton: Boolean = false) {
        val settingsState = _settingsState.value
        saveTimeToDb()
        updateProgressSegments()
        showTimerNotification(0, paused = true, complete = !fromButton)
        lastSavedDuration = 0
        startTime = 0L
        pauseTime = 0L
        pauseDuration = 0L
        sessionActualStartTime = 0L

        cycles = (cycles + 1) % (settingsState.sessionLength * 2)

        if (cycles % 2 == 0) {
            if (_timerState.value.timerRunning) setDoNotDisturb(true)
            time = settingsState.focusTime
            _timerState.update { currentState ->
                currentState.copy(
                    timerMode = TimerMode.FOCUS,
                    timeStr = millisecondsToStr(time),
                    totalTime = time,
                    nextTimerMode = if (cycles == (settingsState.sessionLength - 1) * 2) TimerMode.LONG_BREAK else TimerMode.SHORT_BREAK,
                    nextTimeStr = if (cycles == (settingsState.sessionLength - 1) * 2) millisecondsToStr(
                        settingsState.longBreakTime
                    ) else millisecondsToStr(
                        settingsState.shortBreakTime
                    ),
                    currentFocusCount = cycles / 2 + 1,
                    totalFocusCount = settingsState.sessionLength
                )
            }
        } else {
            if (_timerState.value.timerRunning) setDoNotDisturb(false)
            val long = cycles == (settingsState.sessionLength * 2) - 1
            time = if (long) settingsState.longBreakTime else settingsState.shortBreakTime

            _timerState.update { currentState ->
                currentState.copy(
                    timerMode = if (long) TimerMode.LONG_BREAK else TimerMode.SHORT_BREAK,
                    timeStr = millisecondsToStr(time),
                    totalTime = time,
                    nextTimerMode = TimerMode.FOCUS,
                    nextTimeStr = millisecondsToStr(settingsState.focusTime)
                )
            }
        }

        updateProgressSegments()
    }

    fun startAlarm() {
        val settingsState = _settingsState.value
        if (settingsState.alarmEnabled) alarm?.start()

        activityCallbacks.activityTurnScreenOn(true)

        autoAlarmStopScope = CoroutineScope(Dispatchers.IO).launch {
            delay(1 * 60 * 1000)
            stopAlarm(fromAutoStop = true)
        }

        if (settingsState.vibrateEnabled) {
            if (!vibrator.hasVibrator()) {
                return
            }
            val timings = longArrayOf(
                0,
                settingsState.vibrationOnDuration,
                settingsState.vibrationOffDuration,
                settingsState.vibrationOnDuration
            )
            val amplitudes = intArrayOf(
                0,
                settingsState.vibrationAmplitude,
                0,
                settingsState.vibrationAmplitude
            )
            val repeat = 2
            val effect = VibrationEffect.createWaveform(timings, amplitudes, repeat)
            vibrator.vibrate(effect)
        }
    }

    /**
     * Stops ringing the alarm and vibration, and performs related necessary actions
     *
     * @param fromAutoStop Whether the function was triggered automatically by the program instead of
     * intentionally by the user
     */
    fun stopAlarm(fromAutoStop: Boolean = false) {
        updateProgressSegments() // Make sure notification style is initialized

        val settingsState = _settingsState.value
        autoAlarmStopScope?.cancel()

        if (settingsState.alarmEnabled) {
            alarm?.pause()
            alarm?.seekTo(0)
        }

        if (settingsState.vibrateEnabled) {
            vibrator.cancel()
        }

        activityCallbacks.activityTurnScreenOn(false)

        _timerState.update { currentState ->
            currentState.copy(alarmRinging = false)
        }
        notificationBuilder.clearActions().addTimerActions(
            this, R.drawable.play,
            getString(R.string.start_next)
        )
        showTimerNotification(
            when (_timerState.value.timerMode) {
                TimerMode.FOCUS -> settingsState.focusTime.toInt()
                TimerMode.SHORT_BREAK -> settingsState.shortBreakTime.toInt()
                else -> settingsState.longBreakTime.toInt()
            }, paused = true, complete = false
        )

        if (settingsState.autostartNextSession && !fromAutoStop)  // auto start next session
            toggleTimer()
    }

    private fun initializeMediaPlayer(): MediaPlayer? {
        val settingsState = _settingsState.value
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(
                            if (settingsState.mediaVolumeForAlarm) AudioAttributes.USAGE_MEDIA
                            else AudioAttributes.USAGE_ALARM
                        )
                        .build()
                )
                settingsState.alarmSoundUri?.let {
                    setDataSource(applicationContext, it)
                    prepare()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setDoNotDisturb(doNotDisturb: Boolean) {
        if (_settingsState.value.dndEnabled && notificationManagerService.isNotificationPolicyAccessGranted()) {
            if (doNotDisturb) {
                notificationManagerService.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else notificationManagerService.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    private fun updateAlarmTone() {
        alarm?.release()
        alarm = initializeMediaPlayer()
    }

    suspend fun saveTimeToDb() {
        saveLock.withLock {
            val elapsedTime = _timerState.value.totalTime - time
            when (_timerState.value.timerMode) {
                TimerMode.FOCUS -> statRepository.addFocusTime(
                    (elapsedTime - lastSavedDuration).coerceAtLeast(0L)
                )

                else -> statRepository.addBreakTime(
                    (elapsedTime - lastSavedDuration).coerceAtLeast(0L)
                )
            }
            lastSavedDuration = elapsedTime
        }
    }

    private fun startForegroundService() {
        startForeground(1, notificationBuilder.build())
    }

    private fun stopForegroundService() {
        notificationManager.cancel(1)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    enum class Actions {
        TOGGLE, SKIP, RESET, UNDO_RESET, STOP_ALARM, UPDATE_ALARM_TONE, SAVE_SESSION, DISMISS_SESSION
    }
}
