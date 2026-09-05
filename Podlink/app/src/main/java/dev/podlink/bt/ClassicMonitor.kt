package dev.podlink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dev.podlink.ble.PodsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which Apple/Beats headset is connected over Bluetooth Classic (A2DP / HFP).
 * Exposes the connected device (or null) and the list of bonded candidate devices.
 */
@SuppressLint("MissingPermission")
class ClassicMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ClassicMonitor"
        fun looksLikePods(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return "airpods" in n || "beats" in n || "powerbeats" in n
        }
    }

    data class Connected(val device: BluetoothDevice, val name: String, val model: PodsModel)

    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val _connected = MutableStateFlow<Connected?>(null)
    val connected = _connected.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(adapter?.isEnabled == true)
    val bluetoothOn = _bluetoothOn.asStateFlow()

    /** Optional user override: only track this address. */
    var preferredAddress: String? = null

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    _bluetoothOn.value = st == BluetoothAdapter.STATE_ON
                    if (st != BluetoothAdapter.STATE_ON) _connected.value = null
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> refresh()
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, f)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
                    BluetoothProfile.HEADSET -> headset = proxy as BluetoothHeadset
                }
                refresh()
            }
            override fun onServiceDisconnected(profile: Int) {
                when (profile) { BluetoothProfile.A2DP -> a2dp = null; BluetoothProfile.HEADSET -> headset = null }
            }
        }
        adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dp = null; headset = null
    }

    fun refresh() {
        val devices = LinkedHashSet<BluetoothDevice>()
        runCatching { a2dp?.connectedDevices?.let(devices::addAll) }
        runCatching { headset?.connectedDevices?.let(devices::addAll) }
        val pref = preferredAddress
        val dev = devices.firstOrNull { pref != null && it.address.equals(pref, true) }
            ?: devices.firstOrNull { looksLikePods(it.name) }
        val next = dev?.let { Connected(it, it.name ?: "AirPods", PodsModel.guessFromName(it.name)) }
        if (next?.device?.address != _connected.value?.device?.address) {
            Log.i(TAG, "connected -> ${next?.name}")
            _connected.value = next
        }
    }

    fun a2dpPlaying(): Boolean = _connected.value?.let { c -> runCatching { a2dp?.isA2dpPlaying(c.device) }.getOrNull() } ?: false

    /** Bonded devices that look like Apple/Beats headsets. */
    fun bondedPods(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.filter { looksLikePods(it.name) } }.getOrNull() ?: emptyList()

    fun bondedByAddress(address: String): BluetoothDevice? =
        runCatching { adapter?.bondedDevices?.firstOrNull { it.address.equals(address, true) } }.getOrNull()

    /**
     * Rename the headset in the system Bluetooth list via the hidden `BluetoothDevice.setAlias(String)`
     * (the public API 30+ variant needs BLUETOOTH_PRIVILEGED). Works on many OEM ROMs, harmless when not.
     */
    fun setSystemAlias(address: String, alias: String): Boolean {
        val device = bondedByAddress(address) ?: return false
        val r = dev.podlink.util.HiddenApi.invoke(device, "setAlias", arrayOf(String::class.java), arrayOf(alias))
        val ok = r.isSuccess && (r.getOrNull() == null || r.getOrNull() == true || r.getOrNull() == 0)
        Log.i(TAG, "setAlias($alias) -> ${r.getOrNull()} ${r.exceptionOrNull()?.message ?: ""}")
        return ok
    }

    /** Public alias so callers can drive a connection through the profile proxies (see [ConnectHelper]). */
    internal fun profiles(): Pair<BluetoothA2dp?, BluetoothHeadset?> = a2dp to headset
}
