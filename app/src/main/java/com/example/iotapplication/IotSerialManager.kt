package com.example.iotapplication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

class IotSerialManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.irremote.USB_PERMISSION"
        private const val BAUD_RATE = 115200 // Arduinoスケッチと一致させる
    }

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null

    // 接続状態を公開するStateFlow
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            openDevice(it)
                        }
                    } else {
                        // 許可が得られなかった場合
                        _isConnected.value = false
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        context.unregisterReceiver(usbReceiver)
        close()
    }

    // 接続処理を開始する関数
    fun connect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            _isConnected.value = false
            return
        }
        val driver = availableDrivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        val connection = usbManager.openDevice(device) ?: return
        serialPort = driver?.ports?.get(0)
        serialPort?.apply {
            open(connection)
            setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            _isConnected.value = true // 接続成功
        }
    }

    fun close() {
        serialPort?.close()
        serialPort = null
        _isConnected.value = false // 接続を切断
    }

    suspend fun readData(timeoutMillis: Int = 5000): String? {
        return withContext(Dispatchers.IO) {
            if (serialPort == null) return@withContext null

            val buffer = ByteArray(1024)
            val stringBuilder = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                try {
                    val len = serialPort?.read(buffer, timeoutMillis) ?: 0
                    if (len > 0) {
                        stringBuilder.append(String(buffer, 0, len, Charset.forName("UTF-8")))
                        val dataString = stringBuilder.toString()

                        // Arduinoからの応答を処理
                        if (dataString.contains("IR_RAW:")) {
                            // 生データを受信
                            val startIndex = dataString.indexOf("IR_RAW:")
                            val endIndex = dataString.indexOf('\n', startIndex)
                            if (endIndex != -1) {
                                val irData = dataString.substring(startIndex, endIndex).trim()
                                return@withContext irData
                            }
                        } else if (dataString.contains("Waiting for IR signal...")) {
                            // 受信待機中メッセージを検出、次の応答を待つ
                            stringBuilder.clear() // バッファをクリアしてIR_RAW:を待機
                        }
                    }
                } catch (e: Exception) {
                    return@withContext null
                }
            }
            return@withContext null // タイムアウト
        }
    }

    suspend fun writeData(data: String) {
        withContext(Dispatchers.IO) {
            if (serialPort == null) return@withContext
            try {
                // Arduinoは改行文字でコマンドの終わりを判断するため、末尾に改行を追加
                val bytes = (data + "\n").toByteArray(Charset.forName("UTF-8"))
                serialPort?.write(bytes, 1000)
            } catch (_: Exception) {
            }
        }
    }
}