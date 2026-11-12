package dev.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

private fun std(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BleManager(
    private val ctx: Context,
    private val protocol: WatchProtocol = LoggingProtocol()
) {
    data class Found(val name: String, val address: String, val rssi: Int)

    data class State(
        val scanning: Boolean = false,
        val found: List<Found> = emptyList(),
        val connected: String? = null,
        val status: String = "Belum terhubung",
        val battery: Int? = null,
        val heartRate: Int? = null,
        val gattDump: String = "",
        val log: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private val batterySvc = std("180f")
    private val batteryChar = std("2a19")
    private val hrChar = std("2a37")
    private val cccd = std("2902")

    // ---------- scan ----------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            val f = Found(name, r.device.address, r.rssi)
            _state.update { st ->
                st.copy(found = (st.found.filter { it.address != f.address } + f).sortedByDescending { it.rssi })
            }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.update { it.copy(status = "Bluetooth belum aktif") }
            return
        }
        _state.update { it.copy(scanning = true, found = emptyList(), status = "Mencari…") }
        scanner.startScan(scanCb)
        main.postDelayed({ stopScan() }, 10_000)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        _state.update { it.copy(scanning = false) }
    }

    // ---------- connect ----------
    fun connect(address: String) {
        stopScan()
        val dev = adapter?.getRemoteDevice(address) ?: return
        _state.update { it.copy(status = "Menghubungkan ke ${dev.name ?: address}…") }
        gatt?.close()
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() { gatt?.disconnect() }

    fun addNote(m: String) { _state.update { it.copy(log = (it.log + m).takeLast(200)) } }

    // ---------- sambung lewat QR: cari jam di sekitar, cocokkan alamat / nama / model ----------
    private var qrMatched = false
    private var qrMac: String? = null
    private var qrModel: String? = null
    private val qrSeen = mutableSetOf<String>()

    private val qrScanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: ""
            if (name.isNotEmpty() && qrSeen.add(r.device.address)) addNote("terlihat: $name ${r.device.address}")
            val model = qrModel
            val hit = r.device.address.equals(qrMac, true) || (name.isNotEmpty() && (
                name.contains("haylou", true) || name.contains("solar", true) ||
                (model != null && name.contains(model, true))))
            if (hit && !qrMatched) {
                qrMatched = true
                stopQrScan()
                connect(r.device.address)
            }
        }
    }

    fun connectByQr(mac: String?, model: String?) {
        qrMac = mac; qrModel = model; qrMatched = false; qrSeen.clear()
        addNote("QR mac=$mac model=$model")
        _state.update { it.copy(scanning = true, status = "Mencari jam sesuai QR…") }
        adapter?.bluetoothLeScanner?.startScan(qrScanCb)
        main.postDelayed({
            if (!qrMatched) {
                stopQrScan()
                addNote("Tidak ada kecocokan, mencoba alamat dari QR langsung")
                if (mac != null) connect(mac) else _state.update { it.copy(status = "Jam tidak ditemukan") }
            }
        }, 12_000)
    }

    private fun stopQrScan() {
        adapter?.bluetoothLeScanner?.stopScan(qrScanCb)
        _state.update { it.copy(scanning = false) }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.update { it.copy(connected = g.device.address, status = "Terhubung, membaca layanan…") }
                g.discoverServices()
            } else {
                _state.update {
                    it.copy(connected = null, battery = null, heartRate = null, status = "Terputus (kode $status)")
                }
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val sb = StringBuilder()
            notifyQueue.clear()
            for (svc in g.services) {
                sb.appendLine("SERVICE ${svc.uuid}")
                for (c in svc.characteristics) {
                    sb.appendLine("  CHAR ${c.uuid} [${props(c.properties)}]")
                    val canNotify = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (canNotify) notifyQueue.add(c)
                }
            }
            _state.update { it.copy(gattDump = sb.toString(), status = "Terhubung") }
            val bat = g.getService(batterySvc)?.getCharacteristic(batteryChar)
            if (bat != null) g.readCharacteristic(bat) else nextNotify(g)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleRead(g, c, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) = handleRead(g, c, value)

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleChanged(c, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleChanged(c, value)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            nextNotify(g)
        }
    }

    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == batteryChar && v.isNotEmpty()) {
            _state.update { it.copy(battery = v[0].toInt() and 0xFF) }
        }
        nextNotify(g)
    }

    private fun handleChanged(c: BluetoothGattCharacteristic, v: ByteArray) {
        protocol.onPacket(c.uuid, v)
        if (c.uuid == hrChar && v.size >= 2) {
            val is16 = (v[0].toInt() and 1) != 0
            val bpm = if (is16 && v.size >= 3) ((v[2].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
            else v[1].toInt() and 0xFF
            _state.update { it.copy(heartRate = bpm) }
        }
        val line = "${c.uuid.toString().take(8)}  ${v.joinToString(" ") { "%02X".format(it) }}"
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    /** Aktifkan notifikasi satu per satu: Android hanya menerima satu operasi GATT sekaligus. */
    private fun nextNotify(g: BluetoothGatt) {
        val c = notifyQueue.removeFirstOrNull() ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(cccd)
        if (d == null) { nextNotify(g); return }
        val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            run { d.value = value; g.writeDescriptor(d) }
        }
    }

    private fun props(p: Int): String = buildList {
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("read")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("write")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("write-nr")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("notify")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("indicate")
    }.joinToString(",")
}
