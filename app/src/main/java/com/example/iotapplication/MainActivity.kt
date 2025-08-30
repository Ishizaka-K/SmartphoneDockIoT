// MainActivity.kt
package com.example.iotapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class IotViewModelFactory(
    private val iotSerialManager: IotSerialManager
) : Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IotViewModel::class.java)) {
            return IotViewModel(iotSerialManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var iotSerialManager: IotSerialManager
    private lateinit var viewModel: IotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        iotSerialManager = IotSerialManager(applicationContext)
        viewModel = ViewModelProvider(this, IotViewModelFactory(iotSerialManager))
            .get(IotViewModel::class.java)

        setContent { IRRemoteApp(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        iotSerialManager.register()
    }

    override fun onStop() {
        iotSerialManager.unregister()
        super.onStop()
    }
}

@Composable
fun IRRemoteApp(viewModel: IotViewModel) {
    val scope = rememberCoroutineScope()
    val receivedSignal by viewModel.receivedSignal.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    val displayText =
        if (!isConnected) "未接続"
        else receivedSignal?.takeIf { it.isNotBlank() } ?: "接続済み（信号待機中）"

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp, 50.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { viewModel.onToggleConnectionClicked() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isConnected) "切断" else "接続")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { scope.launch { viewModel.onReceiveClicked() } },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected
        ) { Text("受信") }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { scope.launch { viewModel.onSendClicked() } },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected
        ) { Text("送信") }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = displayText ?: "",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        )
    }
}
