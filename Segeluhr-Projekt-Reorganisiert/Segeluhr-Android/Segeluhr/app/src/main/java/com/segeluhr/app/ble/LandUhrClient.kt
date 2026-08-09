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
import android.os.SystemClock
import android.util.Log
import com.segeluhr.app.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class LandConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/** Aktuellster Stand von der Land-Uhr, siehe [LandUhrClient]. */
data class LandPositionState(
    val connectionState: LandConnectionState = LandConnectionState.DISCONNECTED,
    val position: GeoPoint? = null,
    val gpsValidFix: Boolean = false,
    // SystemClock.elapsedRealtime() beim letzten Empfang - bewusst nicht die
    // "Alter"-Angabe direkt von der Uhr (die kennt nur ihre eigene millis()-
    // Laufzeit seit Boot, nicht die Wanduhrzeit) - die UI rechnet "vor Xs"
    // selbst aus, indem sie das hier mit der aktuellen elapsedRealtime() vergleicht.
    val lastUpdateElapsedRealtimeMs: Long? = null,
)

/**
 * Handy = BLE Central/Client zur Land-Uhr (T-Watch S3), siehe
 * docs/Erweiterung_Landuhr_Kartenansicht.md. Gegenstück zum GATT-Server in
 * Segeluhr_TWatch_S3.ino (startFragenEditorBle()/POSITION_CHAR_UUID).
 *
 * Bewusst komplett unabhängig von BleGattServerManager/BleBridge - die
 * gehören zur Segler-Rolle (Handy = GATT-Server für die Boots-Uhr), hier
 * ist das Handy in der genau umgekehrten Rolle (Client zur Land-Uhr). Ein
 * Handy nutzt zur gleichen Zeit immer nur eine der beiden Rollen (siehe
 * AppRole), daher keine Konflikte zu erwarten - aber bewusst getrennt
 * gehalten, um das nicht stillschweigend vorauszusetzen.
 */
class LandUhrClient(private val context: Context) {

    companion object {
        private const val TAG = "LandUhrClient"

        // Gleiche UUIDs wie FRAGEN_EDITOR_SERVICE_UUID/POSITION_CHAR_UUID in
        // Segeluhr_TWatch_S3.ino - siehe dort für Herkunft/Kommentar. Absichtlich
        // derselbe Service wie der Fragen-Editor (ein GATT-Server, ein Ein/Aus-
        // Schalter auf der Uhr statt zwei parallelen BLE-Servern).
        private val SERVICE_UUID: UUID = UUID.fromString("7a6e0001-b5a3-f393-e0a9-e50e24dcca9e")
        private val POSITION_CHAR_UUID: UUID = UUID.fromString("7a6e0003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = BleProtocol.CCCD_UUID

        private const val SCAN_RETRY_DELAY_MS = 3000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var wantConnected = false // true zwischen start()/stop() - steuert, ob nach Trennung automatisch neu gescannt wird

    private val _state = MutableStateFlow(LandPositionState())
    val state: StateFlow<LandPositionState> = _state.asStateFlow()

    /** Beginnt zu scannen (und hält die Verbindung danach automatisch aufrecht, inkl. Reconnect). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (wantConnected) return
        wantConnected = true
        startScan()
    }

    /** Trennt aktiv, kein automatischer Reconnect mehr, bis [start] erneut aufgerufen wird. */
    @SuppressLint("MissingPermission")
    fun stop() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = LandPositionState()
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
        _state.update { it.copy(connectionState = LandConnectionState.SCANNING) }
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "Scan gestartet (suche 'Segeluhr-Fragen-Editor' / Service $SERVICE_UUID)")
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
            Log.d(TAG, "Land-Uhr gefunden: ${result.device.address}")
            _state.update { it.copy(connectionState = LandConnectionState.CONNECTING) }
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
                    Log.d(TAG, "Verbunden, discoverServices()")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Getrennt (status=$status)")
                    g.close()
                    gatt = null
                    // Position bewusst verwerfen statt "letzten Stand" stehen zu
                    // lassen - ohne Verbindung koennte sie inzwischen veraltet sein,
                    // und die UI soll das klar als "keine Verbindung" zeigen statt
                    // einen ggf. falschen Marker weiter anzuzeigen.
                    _state.value = LandPositionState()
                    if (wantConnected) retryLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "discoverServices() fehlgeschlagen, Code $status")
                g.disconnect()
                return
            }
            val char = g.getService(SERVICE_UUID)?.getCharacteristic(POSITION_CHAR_UUID)
            if (char == null) {
                Log.e(TAG, "Positions-Characteristic nicht gefunden - falsche Uhr oder alte Firmware?")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            // Direkt den aktuellen Wert lesen statt auf die naechste Notify zu
            // warten (kann bis zu 30s dauern, siehe LORA_SEND_INTERVAL_MS) -
            // die Characteristic hat READ-Property genau dafuer.
            g.readCharacteristic(char)
            _state.update { it.copy(connectionState = LandConnectionState.CONNECTED) }
        }

        // Vor API 33: wird für ankommende Notifies aufgerufen.
        @Deprecated("Deprecated in API 33, siehe onCharacteristicChanged(gatt, characteristic, value) unten")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) parsePayload(characteristic.value)
        }

        // Ab API 33: derselbe Zweck, aber mit dem Wert direkt als Parameter
        // statt über characteristic.value (das ist ab hier deprecated).
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            parsePayload(value)
        }

        @Deprecated("Deprecated in API 33, siehe onCharacteristicRead(gatt, characteristic, value, status) unten")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS) {
                parsePayload(characteristic.value)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) parsePayload(value)
        }
    }

    private fun parsePayload(bytes: ByteArray?) {
        // 10 Byte: int32 latE7, int32 lonE7, uint8 gpsValidFix, uint8 sequence
        // - siehe BlePositionPayload in Segeluhr_TWatch_S3.ino.
        if (bytes == null || bytes.size < 10) {
            Log.w(TAG, "Positions-Payload zu kurz (${bytes?.size ?: 0} Bytes)")
            return
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val latE7 = buf.int
        val lonE7 = buf.int
        val validFix = buf.get().toInt() != 0
        // sequence (buf.get()) aktuell nicht ausgewertet - nur fuers Debugging
        // im Payload mitgeschickt, um spaeter Paketverlust erkennen zu koennen.
        _state.update {
            it.copy(
                connectionState = LandConnectionState.CONNECTED,
                position = GeoPoint(latE7 / 1e7, lonE7 / 1e7),
                gpsValidFix = validFix,
                lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
        }
    }
}
