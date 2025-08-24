package com.example.iotapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class IotViewModel(private val iotSerialManager: IotSerialManager) : ViewModel() {

    private val _receivedSignal = MutableStateFlow<String?>("信号を受信していません")
    val receivedSignal: StateFlow<String?> = _receivedSignal

    val isConnected: StateFlow<Boolean> = iotSerialManager.isConnected

    /** 接続トグル（未接続→接続、接続中→切断） */
    fun onToggleConnectionClicked() {
        if (iotSerialManager.isConnected.value) {
            iotSerialManager.close()
        } else {
            iotSerialManager.connect()
        }
    }

    fun onReceiveClicked() {
        viewModelScope.launch {
            if (!iotSerialManager.isConnected.value) {
                _receivedSignal.value = "デバイスが接続されていません。"
                return@launch
            }
            _receivedSignal.value = "受信待機中..."
            iotSerialManager.writeData("RECEIVE")
            val signal = iotSerialManager.readData()
            _receivedSignal.value = signal?.ifEmpty { "受信に失敗しました" } ?: "受信に失敗しました"
        }
    }

    fun onSendClicked() {
        viewModelScope.launch {
            if (!iotSerialManager.isConnected.value) {
                _receivedSignal.value = "デバイスが接続されていません。"
                return@launch
            }
            val signal = _receivedSignal.value
            if (signal.isNullOrEmpty() || signal == "信号を受信していません") {
                _receivedSignal.value = "送信する信号がありません"
            } else {
                iotSerialManager.writeData("SEND:$signal")
                _receivedSignal.value = "信号を送信しました"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        iotSerialManager.close()
    }
}
