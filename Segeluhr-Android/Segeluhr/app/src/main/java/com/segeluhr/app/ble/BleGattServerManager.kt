package com.segeluhr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.segeluhr.app.core.Fix

/**
 * Handy = BLE Peripheral (GATT-Server), siehe Abschnitt 1 von BLE_Protokoll.md.
 * Bewirbt den Segeluhr-GPS-Bridge-Service dauerhaft (Vorschlag aus Abschnitt 6:
 * "dauerhaft advertisen, geringer Verbrauch"), sendet 1x/s GPS-Notifies und
 * Battery-Notifies bei Änderung ≥5%.
 *
 * Die T-Watch-Firmware übernimmt die aktive Rolle (Scan/Connect/Reconnect,
 * Fallback auf internes GPS bei Verbindungsabbruch) — hier auf Handy-Seite
 * ist nur die passive Server-Seite nötig.
 */
class BleGattServerManager(private val context: Context) {

    companion object {
        private const val TAG = "BleGattServerManager"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gpsCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var hapticCharacteristic: BluetoothGattCharacteristic? = null
    private var homeStatusCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private var lastSentBatteryPct: Int = -1
    private var lastBatteryNotifyAt: Long = 0L

    /**
     * Fragt zusätzlich zum selbst geführten [connectedDevices]-Set den ECHTEN
     * Verbindungsstatus direkt beim System ab (BluetoothManager). So zeigt
     * "verbunden" auch dann korrekt "nein", wenn z.B. ein Callback verpasst
     * wurde oder der Zustand aus einem anderen Grund veraltet ist — die
     * Anzeige in der App spiegelt damit den tatsächlichen BLE-Zustand wider,
     * nicht nur unsere eigene Buchführung darüber.
     */
    @get:SuppressLint("MissingPermission")
    val isConnected: Boolean
        get() {
            val systemConnected = try {
                bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER).isNotEmpty()
            } catch (e: SecurityException) {
                null // Permission fehlt -> auf eigene Buchführung zurückfallen
            }
            val result = systemConnected ?: connectedDevices.isNotEmpty()
            // Eigenes Set mit der Systemwahrheit synchron halten, falls es auseinanderlaeuft
            if (systemConnected == false && connectedDevices.isNotEmpty()) connectedDevices.clear()
            return result
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) return // bereits gestartet — verhindert doppelte Service-Registrierung
        val bt = adapter ?: run { Log.w(TAG, "Kein Bluetooth-Adapter vorhanden"); return }
        if (!bt.isEnabled) { Log.w(TAG, "Bluetooth ist deaktiviert"); return }

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        val service = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        gpsCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_GPS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }

        batteryCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_BATTERY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }

        val controlCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        hapticCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_HAPTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }

        service.addCharacteristic(gpsCharacteristic)
        service.addCharacteristic(batteryCharacteristic)
        service.addCharacteristic(controlCharacteristic)
        service.addCharacteristic(hapticCharacteristic)

        homeStatusCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CHAR_HOME_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        service.addCharacteristic(homeStatusCharacteristic)

        gattServer?.addService(service)

        startAdvertising(bt)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(bt: BluetoothAdapter) {
        advertiser = bt.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
    }

    /** 1x/s aufrufen: sendet den aktuellen Fix an alle verbundenen Centrals (die Watch) */
    @SuppressLint("MissingPermission")
    fun notifyGpsFix(fix: Fix) {
        val server = gattServer ?: return
        val characteristic = gpsCharacteristic ?: return
        if (connectedDevices.isEmpty()) return

        val batteryPct = readPhoneBatteryPercent()
        val batteryLow = batteryPct in 0..14
        characteristic.value = BleProtocol.encodeGpsPacket(fix, batteryLow)
        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, characteristic, false)
        }

        maybeNotifyBattery(batteryPct)
    }

    /** Sendet ein Vibrationsmuster-Kommando an die Uhr (Modus "Mit Uhr", Handy vibriert selbst nicht) */
    @SuppressLint("MissingPermission")
    fun sendHapticCommand(patternCode: Int) {
        val server = gattServer ?: return
        val characteristic = hapticCharacteristic ?: return
        if (connectedDevices.isEmpty()) return
        characteristic.value = BleProtocol.encodeHapticCommand(patternCode)
        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    /**
     * Informiert die Ultra-Watch über den aktuellen Heimweg-Status. Sendet
     * NUR an tatsächlich verbundene Geräte (leere Geräteliste -> No-Op) —
     * das ist genau die Bedingung "Handy nicht mit Ultra-Watch verbunden ->
     * keine LoRa-Übertragung möglich", da die Watch ohne dieses Notify gar
     * nichts zum Senden hat.
     */
    @SuppressLint("MissingPermission")
    fun notifyHomeStatus(active: Boolean, maneuverNeeded: Boolean, etaMinutes: Int?) {
        val server = gattServer ?: return
        val characteristic = homeStatusCharacteristic ?: return
        if (connectedDevices.isEmpty()) return
        characteristic.value = BleProtocol.encodeHomeStatus(active, maneuverNeeded, etaMinutes)
        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun maybeNotifyBattery(pct: Int) {
        if (pct < 0) return
        val server = gattServer ?: return
        val characteristic = batteryCharacteristic ?: return
        val now = System.currentTimeMillis()
        val changedEnough = lastSentBatteryPct < 0 || kotlin.math.abs(pct - lastSentBatteryPct) >= 5
        if (!changedEnough) return
        // max. 1x/60s, siehe Abschnitt 5 BLE_Protokoll.md
        if (now - lastBatteryNotifyAt < 60_000L) return

        characteristic.value = BleProtocol.encodeBatteryLevel(pct)
        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        lastSentBatteryPct = pct
        lastBatteryNotifyAt = now
    }

    private fun readPhoneBatteryPercent(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return (level * 100 / scale)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE-Advertising fehlgeschlagen: Code $errorCode")
        }
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE-Advertising gestartet")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectedDevices.add(device)
                BluetoothProfile.STATE_DISCONNECTED -> connectedDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid == BleProtocol.CHAR_CONTROL_UUID) {
                // Platz für zukünftige Steuerbefehle von der Uhr ans Handy
                // (z.B. "Handy-Vibration triggern", Konfigurationswerte) — siehe Abschnitt 2.
                Log.i(TAG, "Control-Write empfangen: ${value.joinToString(",")}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }
}
