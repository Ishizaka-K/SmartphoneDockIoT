package com.example.iotapplication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

class IotSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "IotSerialManager"
        private const val ACTION_USB_PERMISSION = "com.example.iotapplication.USB_PERMISSION"
        private const val BAUD_RATE = 115200 // Arduino側と一致
        private val UTF8: Charset = Charset.forName("UTF-8")
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var receiverRegistered = false
    private val reading = AtomicBoolean(false)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    Log.w(TAG, "USB permission denied")
                    _isConnected.value = false
                }
            }
        }
    }

    fun register() {
        if (!receiverRegistered) {
            context.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        close()
    }

    fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB-Serial drivers found")
            _isConnected.value = false
            return
        }
        // ひとまず最初のデバイスを使用（必要ならVendorId/ProductIdで絞り込み可）
        val device = drivers.first().device
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            val connection = usbManager.openDevice(device)
            if (driver == null || connection == null) {
                Log.e(TAG, "Failed to open device/driver null")
                _isConnected.value = false
                return
            }
            val port = driver.ports.getOrNull(0)
            if (port == null) {
                Log.e(TAG, "No ports on driver")
                _isConnected.value = false
                return
            }
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            _isConnected.value = true
            Log.i(TAG, "Serial opened: ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "openDevice failed", e)
            _isConnected.value = false
        }
    }

    fun close() {
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close error", e)
        } finally {
            serialPort = null
            _isConnected.value = false
            reading.set(false)
        }
    }

    /** 1行をタイムアウト付きで読む（改行まで） */
    private suspend fun readLine(timeoutMillis: Int): String? = withContext(Dispatchers.IO) {
        val port = serialPort ?: return@withContext null
        val buf = ByteArray(1024)
        val sb = StringBuilder()
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMillis) {
            try {
                val len = port.read(buf, 50) // 小刻みに読む
                if (len > 0) {
                    val chunk = String(buf, 0, len, UTF8)
                    sb.append(chunk)
                    val idx = sb.indexOf("\n")
                    if (idx >= 0) {
                        return@withContext sb.substring(0, idx).trim()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "readLine error", e)
                return@withContext null
            }
        }
        null
    }

    /** “RECEIVE” を投げ、"IR_RAW:..." の1行レスポンスを待つ */
    suspend fun requestReceive(timeoutMillis: Int = 5000): String? {
        if (!_isConnected.value) return null
        if (!reading.compareAndSet(false, true)) {
            // 同時読み出しを抑止
            return null
        }
        return try {
            writeLine("RECEIVE")
            // Arduinoから「Waiting for IR signal...」→その後「IR_RAW:...」
            // まずWaiting行を（あれば）読み飛ばす
            val first = readLine(timeoutMillis) ?: return null
            if (!first.startsWith("IR_RAW:")) {
                // Waitingメッセージなどは捨てて本命をもう一回読む
                val second = readLine(timeoutMillis) ?: return null
                if (!second.startsWith("IR_RAW:")) return null
                second
            } else {
                first
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "requestReceive failed", e)
            null
        } finally {
            reading.set(false)
        }
    }

    /** “SEND:<payload>” を送る（Arduino側のSEND実装が必要） */
    suspend fun sendRaw(payload: String) {
        if (!_isConnected.value) return
        writeLine("SEND:$payload")
    }

    private suspend fun writeLine(s: String) = withContext(Dispatchers.IO) {
        val port = serialPort ?: return@withContext
        try {
            port.write((s + "\n").toByteArray(UTF8), 1000)
        } catch (e: Exception) {
            Log.e(TAG, "writeLine error", e)
        }
    }
}
