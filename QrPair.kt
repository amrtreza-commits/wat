package dev.watchbridge.ble

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Pairing lewat QR: kode QR hanya membawa alamat jam, sambungan tetap BLE. */
object QrPair {
    private val macRe = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|\\b[0-9A-Fa-f]{12}\\b")

    fun parseMac(raw: String): String? =
        macRe.find(raw)?.value?.filter { it.isLetterOrDigit() }?.uppercase()?.chunked(2)?.joinToString(":")

    /** QR Haylou berbentuk ...?MAC=xx:xx:xx:xx:xx:xx&model=LS23_P */
    fun parseModel(raw: String): String? =
        Regex("model=([^&\\s]+)").find(raw)?.groupValues?.get(1)

    fun scan(ctx: Context, onResult: (raw: String, mac: String?) -> Unit, onError: (String) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> val raw = b.rawValue ?: ""; onResult(raw, parseMac(raw)) }
            .addOnFailureListener { onError(it.message ?: "Gagal memindai") }
    }
}
