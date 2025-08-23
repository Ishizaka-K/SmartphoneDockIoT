package com.example.iotapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class SignalState {
    data object Idle : SignalState()
    data object Waiting : SignalState()
    data class Received(val data: String) : SignalState() // "IR_RAW:xxx,yyy,..."
    data object Sent : SignalState()
    data class Error(val message: String) : SignalState()
}

class IotViewModel(private val serial: IotSerialManager) : ViewModel() {

    private val _signalState = MutableStateFlow<SignalState>(SignalState.Idle)
    val signalState: StateFlow<SignalState> = _signalState

    val isConnected: StateFlow<Boolean> = serial.isConnected

    fun toggleConnection() {
        if (serial.isConnected.value) serial.close() else serial.connect()
    }

    fun receive() {
        viewModelScope.launch {
            if (!serial.isConnected.value) {
                _signalState.value = SignalState.Error("デバイスが接続されていません")
                return@launch
            }
            _signalState.value = SignalState.Waiting
            val line = serial.requestReceive(timeoutMillis = 10_000)
            _signalState.value = when {
                line == null -> SignalState.Error("受信に失敗しました（タイムアウト）")
                line.isBlank() -> SignalState.Error("受信データが空です")
                else -> SignalState.Received(line)
            }
        }
    }

    fun send() {
        viewModelScope.launch {
            if (!serial.isConnected.value) {
                _signalState.value = SignalState.Error("デバイスが接続されていません")
                return@launch
            }
            val data = (signalState.value as? SignalState.Received)?.data
            if (data.isNullOrBlank()) {
                _signalState.value = SignalState.Error("送信する信号がありません")
                return@launch
            }
            // Arduino側にSENDコマンドの処理追加が必要（.inoに未実装）
            serial.sendRaw(data)
            _signalState.value = SignalState.Sent
        }
    }

    override fun onCleared() {
        serial.close()
        super.onCleared()
    }
}
