package com.example.screenshotapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScreenCaptureViewModel @Inject constructor() : ViewModel() {

    // State to track if screen recording is active
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // State to track recording start time
    private val _startTime = MutableStateFlow<Long?>(null)
    val startTime: StateFlow<Long?> = _startTime.asStateFlow()

    // State to track elapsed time
    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    private var timerJob: Job? = null

    fun startRecording() {
        if (_isRecording.value) return

        _isRecording.value = true
        _startTime.value = System.currentTimeMillis()
        startTimer()
    }

    fun stopRecording() {
        _isRecording.value = false
        _startTime.value = null
        _elapsedTime.value = 0L
        timerJob?.cancel()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - (_startTime.value ?: System.currentTimeMillis())
                _elapsedTime.value = elapsed
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}


