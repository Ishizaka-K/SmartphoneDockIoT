package com.example.iotapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IotViewModel(private val iotSerialManager: IotSerialManager) : ViewModel() {

    // 受信したIR信号を保持
    private val _receivedSignal = MutableStateFlow<String?>("信号を受信していません")
    val receivedSignal: StateFlow<String?> = _receivedSignal

    // 接続状態を公開
    val isConnected: StateFlow<Boolean> = iotSerialManager.isConnected

    init {
        // ViewModelが作成されたときに接続を開始
        iotSerialManager.connect()
    }

    fun onConnectClicked() {
        iotSerialManager.connect()
    }

    fun onReceiveClicked() {
        viewModelScope.launch {
            if (!iotSerialManager.isConnected.value) {
                _receivedSignal.value = "デバイスが接続されていません。"
                return@launch
            }

            _receivedSignal.value = "受信待機中..."

            // ① Arduinoに「RECEIVE」コマンドを送信
            iotSerialManager.writeData("RECEIVE")

            // ② Arduinoからの応答を待つ
            val signal = iotSerialManager.readData()

            if (signal.isNullOrEmpty()) {
                _receivedSignal.value = "受信に失敗しました"
            } else {
                _receivedSignal.value = signal
            }
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
                // IotSerialManagerのwriteDataを直接呼び出す
                iotSerialManager.writeData("SEND:$signal")
                _receivedSignal.value = "信号を送信しました"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModelが破棄されるときに接続をクローズする
        iotSerialManager.close()
    }
}