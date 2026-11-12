package com.segeluhr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GatewayConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/**
 * Handy = BLE Central/Client zum "RegattaMaster"-Board (ESP32 T-Beam), das
 * LoRa-Positionspakete anderer Boote sammelt und per BLE Notify weiterreicht.
 * Siehe docs/Erweiterung_Regatta_Tracker_Gateway.md. Nach dem Muster von
 * [LandUhrClient] (ebenfalls Handy=Central), aber bewusst eine eigene,
 * unabhängige Klasse — andere Rolle (Gateway zum Regatta-Tracker-Server statt
 * Land-Uhr-Kartenanzeige), anderes Gerät, anderes Paketformat.
 *
 * Reiner Empfänger: Pakete werden 1:1 per [onPacket] nach oben gereicht
 * (keine eigene Zwischenspeicherung hier — die persistente Warteschlange und
 * das Batch-Senden an den Server leben im ViewModel/Room, siehe
 * data/db/GatewayQueueDb.kt). Der Master puffert selbst nichts: ein
 * Verbindungsabbruch bedeutet echten Datenverlust für die Zwischenzeit,
 * deshalb automatischer Reconnect mit kurzer Wartezeit.
 */
class GatewayClient(private val context: Context, private val onPacket: (GatewayPacket) -> Unit) {

    companion object {
        private const val TAG = "GatewayClient"
        private const val DEVICE_NAME = "RegattaMaster"
        private val SERVICE_UUID: UUID = UUID.fromString("a7d0c001-5e1b-4f6a-9a53-2d4f7b6c9e10")
        private val CHAR_UUID: UUID = UUID.fromString("a7d0c002-5e1b-4f6a-9a53-2d4f7b6c9e10")
        private val CCCD_UUID: UUID = BleProtocol.CCCD_UUID
        private const val SCAN_RETRY_DELAY_MS = 3000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var wantConnected = false

    private val _state = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

    private var packetCount = 0
    private var invalidPacketCount = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (wantConnected) return
        wantConnected = true
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = GatewayConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            Log.w(TAG, "Bluetooth nicht verfügbar/aus")
            retryLater()
            return
        }
        scanner = a.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        _state.value = GatewayConnectionState.SCANNING
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "Scan gestartet (suche '$DEVICE_NAME' / Service $SERVICE_UUID)")
        } catch (e: Exception) {
            Log.e(TAG, "Scan-Start fehlgeschlagen", e)
            retryLater()
        }
    }

    private fun retryLater() {
        if (!wantConnected) return
        handler.postDelayed({ if (wantConnected) startScan() }, SCAN_RETRY_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runCatching { scanner?.stopScan(this) }
            Log.d(TAG, "RegattaMaster gefunden: ${result.device.address}")
            _state.value = GatewayConnectionState.CONNECTING
            gatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan fehlgeschlagen, Code $errorCode")
            retryLater()
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Verbunden, fordere MTU an")
                    // Default-MTU ist 23 Byte (20 Byte Nutzlast) - unser 24-Byte-Paket
                    // passt da nicht rein und würde stumm auf 20 Byte abgeschnitten
                    // (BLE-Notifies werden NICHT automatisch über mehrere Pakete verteilt).
                    // Erst nach erfolgreicher/abgelehnter MTU-Verhandlung discoverServices().
                    if (!g.requestMtu(185)) {
                        Log.w(TAG, "requestMtu() fehlgeschlagen, discoverServices() trotzdem")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Getrennt (status=$status)")
                    g.close()
                    gatt = null
                    _state.value = GatewayConnectionState.DISCONNECTED
                    if (wantConnected) retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU verhandelt: $mtu (status=$status), discoverServices()")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "discoverServices() fehlgeschlagen, Code $status")
                g.disconnect()
                return
            }
            val char = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.e(TAG, "Paket-Characteristic nicht gefunden - falscher Master oder alte Firmware?")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val queued = g.writeDescriptor(cccd)
                Log.d(TAG, "CCCD-Write angestossen: $queued")
            } else {
                Log.w(TAG, "CCCD-Descriptor nicht gefunden - Master sendet evtl. trotzdem (manche Firmwares ignorieren CCCD)")
            }
            _state.value = GatewayConnectionState.CONNECTED
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: ${descriptor.uuid} status=$status")
        }

        @Deprecated("Deprecated in API 33, siehe onCharacteristicChanged(gatt, characteristic, value) unten")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handlePacket(characteristic.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handlePacket(value)
        }
    }

    private fun handlePacket(bytes: ByteArray?) {
        val packet = bytes?.let { GatewayPacketCodec.decode(it) }
        if (packet == null) {
            invalidPacketCount++
            Log.w(TAG, "Ungültiges/zu kurzes Paket verworfen (${bytes?.size ?: 0} Byte, insgesamt $invalidPacketCount)")
            return
        }
        packetCount++
        Log.d(TAG, "Paket #$packetCount von LoRa-ID ${packet.loraId}: lat=${packet.lat} lon=${packet.lon} batt=${packet.batteryPct}%")
        onPacket(packet)
    }
}
