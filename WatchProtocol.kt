package dev.watchbridge.ble

import android.util.Log
import java.util.UUID

/**
 * Titik sambung untuk protokol khusus jam. Isi setelah format paket
 * dibaca dari log HCI snoop milikmu (lihat README).
 */
interface WatchProtocol {
    fun onPacket(characteristic: UUID, data: ByteArray)
}

class LoggingProtocol : WatchProtocol {
    override fun onPacket(characteristic: UUID, data: ByteArray) {
        Log.d("WatchBridge", "$characteristic ${data.joinToString(" ") { "%02X".format(it) }}")
    }
}
