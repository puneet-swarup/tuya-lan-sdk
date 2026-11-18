package com.tuyalan.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.tuyalan.devices.TuyaDeviceOptions
import com.tuyalan.devices.TuyaDiscovery
import com.tuyalan.devices.TuyaLocalDevice
import com.tuyalan.protocol.TuyaProtocolVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal sample: discover devices on the LAN, connect to one and toggle a switch.
 *
 * Fill in the device id and local key from the Smart Life app (or from the cloud
 * API's device list) before pressing Connect.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var device: TuyaLocalDevice? = null

    private lateinit var log: TextView
    private lateinit var hostField: EditText
    private lateinit var deviceIdField: EditText
    private lateinit var localKeyField: EditText
    private lateinit var versionField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        hostField = findViewById(R.id.host)
        deviceIdField = findViewById(R.id.deviceId)
        localKeyField = findViewById(R.id.localKey)
        versionField = findViewById(R.id.version)

        findViewById<Button>(R.id.scanButton).setOnClickListener { scan() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.statusButton).setOnClickListener { readStatus() }
        findViewById<Button>(R.id.toggleButton).setOnClickListener { toggleSwitch() }
    }

    private fun scan() {
        append("Scanning for 6s...")
        scope.launch {
            try {
                val results = TuyaDiscovery.scan()
                if (results.isEmpty()) {
                    append("No devices answered. Some 3.5 firmware only replies to a solicitation; " +
                        "check that the phone is on the same Wi-Fi and no VPN is active.")
                } else {
                    results.forEach { result ->
                        append("${result.ipAddress}  ${result.deviceId}  v${result.version.label}")
                    }
                    results.firstOrNull()?.let {
                        hostField.setText(it.ipAddress)
                        deviceIdField.setText(it.deviceId)
                        versionField.setText(it.version.label)
                    }
                }
            } catch (e: Exception) {
                append("Scan failed: ${e.message}")
            }
        }
    }

    private fun connect() {
        val options = TuyaDeviceOptions(
            host = hostField.text.toString().trim(),
            deviceId = deviceIdField.text.toString().trim(),
            localKey = localKeyField.text.toString().trim(),
            version = TuyaProtocolVersion.parseLabel(versionField.text.toString()),
        )

        scope.launch {
            try {
                device?.close()
                val newDevice = TuyaLocalDevice(options)
                append("Connecting to ${options.host} (protocol ${options.version.label})...")
                newDevice.connect()
                append("Connected. Session key negotiated: ${newDevice.hasSessionKey}")
                device = newDevice
            } catch (e: Exception) {
                append("Connect failed: ${e.message}")
            }
        }
    }

    private fun readStatus() {
        val current = device ?: return append("Connect first.")
        scope.launch {
            try {
                val status = current.getStatus()
                append("Status: ${status.dps.entries.joinToString { "${it.key}=${it.value}" }}")
            } catch (e: Exception) {
                append("Status failed: ${e.message}")
            }
        }
    }

    private fun toggleSwitch() {
        val current = device ?: return append("Connect first.")
        scope.launch {
            try {
                val status = current.getStatus()
                val isOn = status.getBool("1") ?: false
                current.setDp("1", !isOn)
                append("DP 1 set to ${!isOn}")
            } catch (e: Exception) {
                append("Toggle failed: ${e.message}")
            }
        }
    }

    /** All callers already run on the main dispatcher. */
    private fun append(message: String) {
        log.append(message)
        log.append("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        device?.close()
        device = null
        scope.cancel()
    }
}
