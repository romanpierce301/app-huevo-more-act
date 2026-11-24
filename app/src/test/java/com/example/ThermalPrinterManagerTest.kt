package com.example

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.system.PairedPrinterDevice
import com.example.system.ThermalPrinterManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThermalPrinterManagerTest {

    private lateinit var app: Application
    private lateinit var manager: ThermalPrinterManager
    private lateinit var adapter: BluetoothAdapter
    private lateinit var shadowAdapter: ShadowBluetoothAdapter

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = btManager.adapter
        shadowAdapter = shadowOf(adapter)
        shadowAdapter.setEnabled(true)
        manager = ThermalPrinterManager(app)
    }

    @After
    fun tearDown() {
        manager.release()
    }

    @Test
    fun `startDiscovery finds nearby thermal printers sorts likely printers first and handles pairing`() {
        val pairedDev = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        shadowOf(pairedDev).setName("Impresora Base")
        shadowAdapter.setBondedDevices(setOf(pairedDev))

        val startRes = manager.startDiscovery()
        assertTrue(startRes.isSuccess)
        assertTrue(manager.discoveryState.value.isScanning)

        val nearbySpeaker = adapter.getRemoteDevice("AA:BB:CC:DD:EE:02")
        shadowOf(nearbySpeaker).setName("Bocina Sala")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_FOUND).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, nearbySpeaker)
            },
        )

        val nearbyPrinter = adapter.getRemoteDevice("AA:BB:CC:DD:EE:03")
        shadowOf(nearbyPrinter).setName("MTP-II Thermal Printer")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_FOUND).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, nearbyPrinter)
            },
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val list = manager.availablePrinters.value
        assertEquals(3, list.size)
        // Paired printer first, then likely thermal printer, then non-printer
        assertEquals("AA:BB:CC:DD:EE:01", list[0].address)
        assertTrue(list[0].isPaired)
        assertEquals("AA:BB:CC:DD:EE:03", list[1].address)
        assertFalse(list[1].isPaired)
        assertTrue(list[1].isPrinterLikely)
        assertEquals("AA:BB:CC:DD:EE:02", list[2].address)
        assertFalse(list[2].isPrinterLikely)

        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(manager.discoveryState.value.isScanning)

        // Pair new printer via BOND_STATE_CHANGED
        shadowOf(nearbyPrinter).setCreatedBond(true)
        var pairedCallbackResult: PairedPrinterDevice? = null
        var failureResult: String? = null
        manager.pairPrinter(
            device = list[1],
            onPaired = { pairedCallbackResult = it },
            onFailure = { failureResult = it },
        )
        assertEquals("AA:BB:CC:DD:EE:03", manager.discoveryState.value.pairingAddress)

        shadowAdapter.setBondedDevices(setOf(pairedDev, nearbyPrinter))
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, nearbyPrinter)
                putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDING)
                putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED)
            },
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertNull(failureResult)
        assertEquals("AA:BB:CC:DD:EE:03", pairedCallbackResult?.address)
        assertTrue(pairedCallbackResult?.isPaired == true)
        assertNull(manager.discoveryState.value.pairingAddress)
    }

    @Test
    fun `startDiscovery fails gracefully when Bluetooth is disabled or permission missing`() {
        shadowAdapter.setEnabled(false)
        val disabledRes = manager.startDiscovery()
        assertTrue(disabledRes.isFailure)
        assertFalse(manager.discoveryState.value.isBluetoothEnabled)
        assertFalse(manager.discoveryState.value.isScanning)

        shadowAdapter.setEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val deniedRes = manager.startDiscovery()
        assertTrue(deniedRes.isFailure)
        assertFalse(manager.discoveryState.value.isScanning)
    }

    @Test
    fun `failed bonding clears pairing indicator and reports helpful error`() {
        val dev = adapter.getRemoteDevice("AA:BB:CC:DD:EE:04")
        shadowOf(dev).setName("POS-58")
        manager.startDiscovery()
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_FOUND).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, dev)
            },
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        shadowOf(dev).setCreatedBond(true)
        var errMsg: String? = null
        manager.pairPrinter(
            device = manager.availablePrinters.value.first { it.address == "AA:BB:CC:DD:EE:04" },
            onPaired = {},
            onFailure = { errMsg = it },
        )

        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, dev)
                putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDING)
                putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            },
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertNull(manager.discoveryState.value.pairingAddress)
        assertTrue(errMsg?.contains("0000 / 1234") == true)
    }

    @Test
    fun `startDiscoveryWithFallback waits when adapter is turning on and restarts safely`() = kotlinx.coroutines.test.runTest {
        val pairedDev = adapter.getRemoteDevice("AA:BB:CC:DD:EE:05")
        shadowOf(pairedDev).setName("RPP-300")
        shadowAdapter.setBondedDevices(setOf(pairedDev))

        val result = manager.startDiscoveryWithFallback()
        assertTrue(result.isSuccess)
        assertTrue(manager.discoveryState.value.isScanning)
        assertEquals(1, manager.availablePrinters.value.size)
        manager.stopDiscovery()
        assertFalse(manager.discoveryState.value.isScanning)
    }
}
