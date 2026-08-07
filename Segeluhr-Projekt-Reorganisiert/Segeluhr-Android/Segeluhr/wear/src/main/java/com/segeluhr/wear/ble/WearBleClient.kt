package com.segeluhr.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnectionState {
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Disconnected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * BLE-Central für die Galaxy Watch, siehe docs/Erweiterung_GalaxyWatch_WearOS.md.
 * Übernimmt für GPS-/Wind-/Batterie-Notifies die Rolle, die bisher die
 * T-Watch-Firmware (NimBLE-Arduino) gespielt hat: scannt nach
 * BleProtocol.SERVICE_UUID, verbindet, abonniert die Notify-Characteristics.
 *
 * Läuft PARALLEL zur normalen Wear-OS-Companion-Kopplung (klassisches
 * Bluetooth für Benachrichtigungen etc.) — eigene, unabhängige BLE-GATT-
 * Verbindung zum selben Handy. Das ist die zentrale offene Frage, die dieser
 * erste Test-Screen klärt, bevor der volle Screen-Umfang gebaut wird.
 *
 * TODO (nach diesem ersten Test): Reconnect-Logik bei Verbindungsabbruch (die
 * T-Watch-Firmware macht das bereits, hier bewusst noch nicht), Control-
 * Writes (CMD_*), Haptik-Notify -> Vibrator-Trigger, Race-/Home-Status,
 * Time-Sync-Read direkt nach Connect.
 */
class WearBleClient(private val context: Context) {

    companion object {
        private const val TAG = "WearBleClient"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var currentService: BluetoothGattService? = null

    // BLE erlaubt jeweils nur EINE laufende GATT-Operation gleichzeitig -
    // mehrere writeDescriptor()-Aufrufe direkt hintereinander schlagen bei
    // vielen Stacks stillschweigend fehl. Deshalb Subscriptions seriell über
    // eine Warteschlange abarbeiten, jeweils nach onDescriptorWrite weiter.
    private val pendingSubscriptions = ArrayDeque<UUID>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _gpsData = MutableStateFlow<WearBleProtocol.GpsData?>(null)
    val gpsData: StateFlow<WearBleProtocol.GpsData?> = _gpsData.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private val _windData = MutableStateFlow<WearBleProtocol.WindData?>(null)
    val windData: StateFlow<WearBleProtocol.WindData?> = _windData.asStateFlow()

    /** Startet Scan + Connect. Setzt voraus, dass BLUETOOTH_SCAN/CONNECT bereits erteilt sind. */
    @SuppressLint("MissingPermission")
    fun start() {
        val bt = adapter ?: run {
            _connectionState.value = ConnectionState.Error("Kein Bluetooth-Adapter")
            return
        }
        if (!bt.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth ist aus")
            return
        }
        if (scanning || gatt != null) return

        val scanner = bt.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.Error("Kein BLE-Scanner")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WearBleProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _connectionState.value = ConnectionState.Scanning
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
        pendingSubscriptions.clear()
        currentService = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adapter?.bluetoothLeScanner?.stopScan(this)
            scanning = false
            _connectionState.value = ConnectionState.Connecting
            gatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = ConnectionState.Error("Scan fehlgeschlagen: Code $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Verbindung getrennt (status=$status)")
                    pendingSubscriptions.clear()
                    currentService = null
                    _connectionState.value = ConnectionState.Disconnected
                    g.close()
                    gatt = null
                    // Kein Auto-Reconnect in diesem ersten Test-Screen, siehe Klassen-TODO.
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Service-Discovery fehlgeschlagen: $status")
                return
            }
            val service = g.getService(WearBleProtocol.SERVICE_UUID)
            if (service == null) {
                _connectionState.value = ConnectionState.Error("Segeluhr-Service nicht gefunden")
                return
            }
            currentService = service
            pendingSubscriptions.clear()
            pendingSubscriptions.addAll(
                listOf(
                    WearBleProtocol.CHAR_GPS_UUID,
                    WearBleProtocol.CHAR_BATTERY_UUID,
                    WearBleProtocol.CHAR_WIND_UUID,
                )
            )
            _connectionState.value = ConnectionState.Connected
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Notify-Anmeldung fehlgeschlagen für ${descriptor.characteristic.uuid}: $status")
            }
            subscribeNext(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                WearBleProtocol.CHAR_GPS_UUID -> _gpsData.value = WearBleProtocol.decodeGpsPacket(value)
                WearBleProtocol.CHAR_BATTERY_UUID -> _batteryPercent.value = WearBleProtocol.decodeBatteryLevel(value)
                WearBleProtocol.CHAR_WIND_UUID -> _windData.value = WearBleProtocol.decodeWindStatus(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val uuid = pendingSubscriptions.removeFirstOrNull() ?: return
        val service = currentService ?: return
        val characteristic = service.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "Characteristic $uuid nicht gefunden, überspringe")
            subscribeNext(g)
            return
        }
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(WearBleProtocol.CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD für $uuid nicht gefunden, überspringe")
            subscribeNext(g)
            return
        }
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(descriptor)
    }
}
