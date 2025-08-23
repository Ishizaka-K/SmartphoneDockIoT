package com.example.iotapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class IotViewModelFactory(
    private val manager: IotSerialManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IotViewModel::class.java)) {
            return IotViewModel(manager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var manager: IotSerialManager
    private lateinit var viewModel: IotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = IotSerialManager(applicationContext)
        viewModel = ViewModelProvider(this, IotViewModelFactory(manager))
            .get(IotViewModel::class.java)

        setContent {
            Surface {
                IRRemoteApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        manager.register() // USB権限ブロードキャスト登録
    }

    override fun onStop() {
        manager.unregister() // 受信解除＆ポートクローズ
        super.onStop()
    }
}

@Composable
fun IRRemoteApp(viewModel: IotViewModel) {
    val isConnected by viewModel.isConnected.collectAsState()
    val state by viewModel.signalState.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp, 50.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { viewModel.toggleConnection() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isConnected) "切断" else "接続")
        }

        Button(
            onClick = { viewModel.receive() },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected
        ) { Text("受信") }

        Button(
            onClick = { viewModel.send() },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected
        ) { Text("送信") }

        Spacer(Modifier.height(16.dp))

        when (state) {
            is SignalState.Idle -> StatusText("未接続または待機中", Color.Gray)
            is SignalState.Waiting -> {
                CircularProgressIndicator()
                StatusText("受信待機中...", Color.Gray)
            }
            is SignalState.Received -> {
                val raw = (state as SignalState.Received).data
                StatusText(
                    // 例: "IR_RAW:123,456,..." をそのまま表示
                    raw,
                    MaterialTheme.colorScheme.onBackground
                )
            }
            is SignalState.Sent -> StatusText("信号を送信しました", Color(0xFF2E7D32))
            is SignalState.Error -> StatusText(
                (state as SignalState.Error).message,
                Color(0xFFC62828)
            )
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
        color = color
    )
}
