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
        // パッケージに合わせる（任意の一意文字列でも動きますが合わせる方が安全）
        private const val ACTION_USB_PERMISSION = "com.example.iotapplication.USB_PERMISSION"
        private const val BAUD_RATE = 115200
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // 二重登録防止
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    _isConnected.value = false
                }
            }
        }
    }

    fun register() {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            // 互換性重視：フラグ引数なしのオーバーロードを使用
            context.registerReceiver(usbReceiver, filter)
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {
                // 既に解除済みでも落ちないように
            } finally {
                receiverRegistered = false
            }
        }
        close()
    }

    fun connect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            _isConnected.value = false
            return
        }
        val driver = availableDrivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        val connection = usbManager.openDevice(device) ?: return
        serialPort = driver?.ports?.getOrNull(0)
        serialPort?.apply {
            open(connection)
            setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            _isConnected.value = true
        }
    }

    fun close() {
        try {
            serialPort?.close()
        } catch (_: Exception) {
        } finally {
            serialPort = null
            _isConnected.value = false
        }
    }

    suspend fun readData(timeoutMillis: Int = 5000): String? {
        return withContext(Dispatchers.IO) {
            val port = serialPort ?: return@withContext null
            val buffer = ByteArray(1024)
            val sb = StringBuilder()
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < timeoutMillis) {
                try {
                    val len = port.read(buffer, timeoutMillis)
                    if (len > 0) {
                        sb.append(String(buffer, 0, len, Charset.forName("UTF-8")))
                        val data = sb.toString()
                        if (data.contains("IR_RAW:")) {
                            val s = data.indexOf("IR_RAW:")
                            val e = data.indexOf('\n', s)
                            if (e != -1) return@withContext data.substring(s, e).trim()
                        } else if (data.contains("Waiting for IR signal...")) {
                            sb.clear()
                        }
                    }
                } catch (_: Exception) {
                    return@withContext null
                }
            }
            null
        }
    }

    suspend fun writeData(data: String) {
        withContext(Dispatchers.IO) {
            val port = serialPort ?: return@withContext
            try {
                port.write((data + "\n").toByteArray(Charset.forName("UTF-8")), 1000)
            } catch (_: Exception) {
            }
        }
    }
}
