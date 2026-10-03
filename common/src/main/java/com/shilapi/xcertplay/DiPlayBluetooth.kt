package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    // LOCAL_MAC_ADDRESS is privileged and cannot be granted to apps; SecurityException is handled
    // via runCatching, and below S the legacy BLUETOOTH permission is install-granted anyway.
    @SuppressLint("MissingPermission")
    fun localAddress(context: Context): String? {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
