// MainActivity.kt
package com.example.iotapplication // パッケージ名を修正

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

// ViewModelにIotSerialManagerを渡すためのファクトリクラス
class IotViewModelFactory(private val iotSerialManager: IotSerialManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IotViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return IotViewModel(iotSerialManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var irSerialManager: IotSerialManager

    private val viewModel: IotViewModel by viewModels {
        // ViewModelFactoryを使って、IotSerialManagerをViewModelに渡す
        IotViewModelFactory(irSerialManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        irSerialManager = IotSerialManager(applicationContext)
        irSerialManager.register()

        setContent {
            IRRemoteApp(viewModel)
        }
    }

    override fun onDestroy() {
        irSerialManager.unregister()
        super.onDestroy()
    }
}

@Composable
fun IRRemoteApp(viewModel: IotViewModel) {
    val scope = rememberCoroutineScope()
    val receivedSignal by viewModel.receivedSignal.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    var displayText by remember { mutableStateOf("未接続") }

    // receivedSignalとisConnectedの変更を監視して表示を更新
    LaunchedEffect(receivedSignal, isConnected) {
        if (!isConnected) {
            displayText = "未接続"
        } else {
            receivedSignal?.let {
                displayText = it
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp, 50.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                viewModel.onConnectClicked()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isConnected) "接続済み" else "接続")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    viewModel.onReceiveClicked()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("受信")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    viewModel.onSendClicked()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("送信")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        )
    }
}