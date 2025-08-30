package com.example.iotapplication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

class IotSerialManager(private val context: Context) {

    private val opening = AtomicBoolean(false) // いま接続処理中かどうか

    companion object {
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
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    if (granted) {
                        opening.set(false)
                        connect()
                    } else {
                        opening.set(false)
                        _isConnected.value = false
                    }
                }
            }
        }
    }

    fun register() {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {
            } finally {
                receiverRegistered = false
            }
        }
        close()
    }

    fun connect() {
        if (_isConnected.value || serialPort != null) return
        if (!opening.compareAndSet(false, true)) return

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            opening.set(false)
            _isConnected.value = false
            return
        }
        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            if (serialPort != null) return

            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            val connection = usbManager.openDevice(device) ?: return

            val port = driver?.ports?.getOrNull(0) ?: return
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            try { port.setDTR(true); port.setRTS(true) } catch (_: Exception) {}
            try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}
            try { Thread.sleep(50) } catch (_: InterruptedException) {}

            serialPort = port
            _isConnected.value = true
        } finally {
            opening.set(false)
        }
    }

    fun close() {
        try { serialPort?.close() } catch (_: Exception) {}
        finally {
            serialPort = null
            _isConnected.value = false
            opening.set(false) // 念のため
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
                try { port.purgeHwBuffers(false, true) } catch (_: Exception) {}

                port.write((data + "\r\n").toByteArray(Charset.forName("UTF-8")), 1000)
            } catch (_: Exception) {
            }
        }
    }

}
