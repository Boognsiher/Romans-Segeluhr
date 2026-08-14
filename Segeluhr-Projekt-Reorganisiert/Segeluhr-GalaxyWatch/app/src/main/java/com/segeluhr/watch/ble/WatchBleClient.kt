package com.segeluhr.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/**
 * BLE-Central-Rolle der Uhr zum Handy-GATT-Server (Peripheral), analog zu
 * NimBLE-Central in Segeluhr_TWatch_Ultra.ino — hier auf Standard-Android-
 * BLE-APIs statt NimBLE/Arduino, da die Galaxy Watch selbst Android (Wear
 * OS) ist. Verbindet sich zu GENAU EINEM Gerät, das [BleProtocol.SERVICE_UUID]
 * bewirbt (die Segeluhr-Handy-App — deren BleGattServerManager.kt hält
 * bereits ein Set verbundener Geräte, mehrere gleichzeitige Centrals
 * [z.B. zusätzlich eine T-Watch Ultra] sind also unproblematisch, siehe
 * docs/Erweiterung_GalaxyWatch_App.md).
 *
 * GATT-Operationen (Notify aktivieren, Schreiben) müssen auf Android
 * serialisiert werden — eine simple FIFO-Queue übernimmt das, siehe
 * [enqueue]/[processQueue].
 */
class WatchBleClient(private val context: Context) {

    companion object {
        private const val TAG = "WatchBleClient"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var wantsConnection = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _gpsData = MutableStateFlow<BleProtocol.GpsData?>(null)
    val gpsData: StateFlow<BleProtocol.GpsData?> = _gpsData.asStateFlow()

    private val _phoneBatteryPct = MutableStateFlow<Int?>(null)
    val phoneBatteryPct: StateFlow<Int?> = _phoneBatteryPct.asStateFlow()

    private val _homeData = MutableStateFlow<BleProtocol.HomeData?>(null)
    val homeData: StateFlow<BleProtocol.HomeData?> = _homeData.asStateFlow()

    private val _windData = MutableStateFlow<BleProtocol.WindData?>(null)
    val windData: StateFlow<BleProtocol.WindData?> = _windData.asStateFlow()

    private val _raceData = MutableStateFlow<BleProtocol.RaceData?>(null)
    val raceData: StateFlow<BleProtocol.RaceData?> = _raceData.asStateFlow()

    private val _waypointsStatus = MutableStateFlow<BleProtocol.WaypointsStatus?>(null)
    val waypointsStatus: StateFlow<BleProtocol.WaypointsStatus?> = _waypointsStatus.asStateFlow()

    /** Eingehende Haptik-Codes (siehe BleProtocol.HAPTIC_*) — von HapticPlayer konsumiert. */
    val hapticEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    // ---- GATT-Operations-Queue (Notify-Aktivierung + Control-Writes seriell) ----
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    private fun enqueue(op: () -> Unit) {
        synchronized(opQueue) { opQueue.addLast(op) }
        processQueue()
    }

    private fun processQueue() {
        synchronized(opQueue) {
            if (opInFlight) return
            val next = opQueue.pollFirst() ?: return
            opInFlight = true
            next()
        }
    }

    private fun opDone() {
        synchronized(opQueue) { opInFlight = false }
        processQueue()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        wantsConnection = true
        if (gatt != null || _connectionState.value != ConnectionState.DISCONNECTED) return
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantsConnection = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) { /* Permission entzogen — nichts zu tun */ }
        gatt?.close()
        gatt = null
        synchronized(opQueue) { opQueue.clear(); opInFlight = false }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bt = adapter ?: return
        if (!bt.isEnabled) {
            Log.w(TAG, "Bluetooth ist deaktiviert")
            scheduleReconnect()
            return
        }
        _connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bt.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan-Permission fehlt", e)
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bt = adapter ?: return
            try {
                bt.bluetoothLeScanner?.stopScan(this)
            } catch (e: SecurityException) { }
            _connectionState.value = ConnectionState.CONNECTING
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan fehlgeschlagen: $errorCode")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantsConnection) return
        _connectionState.value = ConnectionState.DISCONNECTED
        mainHandler.postDelayed({ if (wantsConnection) startScan() }, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(64) // reicht locker für das groesste Paket (16 Byte GPS); grosszuegig fuer kuenftige Erweiterungen
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(opQueue) { opQueue.clear(); opInFlight = false }
                    g.close()
                    if (gatt === g) gatt = null
                    _gpsData.value = null
                    _homeData.value = null
                    _windData.value = null
                    _raceData.value = null
                    scheduleReconnect()
                }
            }
        }

        // discoverServices() läuft bereits direkt in onConnectionStateChange() an -
        // hier nicht nochmal aufrufen (sonst doppelte Service-Discovery/doppelt
        // enqueue()te enableNotify()-Aufrufe pro Characteristic).
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }
            val service = g.getService(BleProtocol.SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "Segeluhr-Service nicht gefunden")
                g.disconnect()
                return
            }
            val notifyUuids = listOf(
                BleProtocol.CHAR_GPS_UUID, BleProtocol.CHAR_BATTERY_UUID, BleProtocol.CHAR_HAPTIC_UUID,
                BleProtocol.CHAR_HOME_STATUS_UUID, BleProtocol.CHAR_WIND_UUID, BleProtocol.CHAR_RACE_STATUS_UUID,
                BleProtocol.CHAR_WAYPOINTS_STATUS_UUID,
            )
            for (uuid in notifyUuids) {
                val c = service.getCharacteristic(uuid) ?: continue
                enqueue { enableNotify(g, c) }
            }
            _connectionState.value = ConnectionState.CONNECTED
        }

        @SuppressLint("MissingPermission")
        private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(c, true)
            val descriptor = c.getDescriptor(BleProtocol.CCCD_UUID)
            if (descriptor == null) { opDone(); return }
            // Rueckgabewert pruefen: schlaegt der Aufruf SOFORT/synchron fehl (z.B.
            // BT gerade ausgeschaltet), feuert onDescriptorWrite() NIE - ohne diesen
            // Check wuerde die Queue hier fuer immer haengen bleiben (opInFlight
            // bliebe dauerhaft true).
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            if (!started) opDone()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }

        // API 33+: Wert kommt als eigener Parameter (characteristic.value ist ab hier deprecated)
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(characteristic.uuid, value)
        }

        // API < 33: Fallback über characteristic.value
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // dann übernimmt die neue Overload oben
            @Suppress("DEPRECATION")
            handleNotify(characteristic.uuid, characteristic.value ?: return)
        }
    }

    private fun handleNotify(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleProtocol.CHAR_GPS_UUID -> BleProtocol.decodeGpsPacket(value)?.let { _gpsData.value = it }
            BleProtocol.CHAR_BATTERY_UUID -> BleProtocol.decodeBatteryLevel(value)?.let { _phoneBatteryPct.value = it }
            BleProtocol.CHAR_HOME_STATUS_UUID -> BleProtocol.decodeHomeStatus(value)?.let { _homeData.value = it }
            BleProtocol.CHAR_WIND_UUID -> BleProtocol.decodeWindStatus(value)?.let { _windData.value = it }
            BleProtocol.CHAR_RACE_STATUS_UUID -> BleProtocol.decodeRaceStatus(value)?.let { _raceData.value = it }
            BleProtocol.CHAR_WAYPOINTS_STATUS_UUID -> BleProtocol.decodeWaypointsStatus(value)?.let { _waypointsStatus.value = it }
            BleProtocol.CHAR_HAPTIC_UUID -> BleProtocol.decodeHapticCommand(value)?.let { hapticEvents.tryEmit(it) }
        }
    }

    // ---- Schreiben: Steuerbefehle Uhr -> Handy ----

    @SuppressLint("MissingPermission")
    fun sendCommand(cmd: Int, payloadByte: Int? = null) {
        writeControl(BleProtocol.encodeControlCommand(cmd, payloadByte))
    }

    @SuppressLint("MissingPermission")
    fun sendWaypointAtCoords(waypointId: Int, lat: Double, lon: Double) {
        writeControl(BleProtocol.encodeSetWaypointAtCoords(waypointId, lat, lon))
    }

    @SuppressLint("MissingPermission")
    private fun writeControl(bytes: ByteArray) {
        val g = gatt ?: return
        val service = g.getService(BleProtocol.SERVICE_UUID) ?: return
        val c = service.getCharacteristic(BleProtocol.CHAR_CONTROL_UUID) ?: return
        enqueue {
            // Bugfix-Lehre aus der Ultra-Firmware (12.08.2026, sendControlCommand()):
            // die Characteristic ist nur mit PROPERTY_WRITE/PERMISSION_WRITE
            // deklariert ("Write MIT Antwort") - WRITE_TYPE_NO_RESPONSE führte dort
            // zu einem stillen Fehlschlag. Hier von Anfang an korrekt.
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!started) opDone()
        }
    }
}
