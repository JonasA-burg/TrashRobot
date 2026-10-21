package com.example.trashrobot

// =============================================================================
// MouldKingLib.kt
// Android Kotlin library for controlling MouldKing Hub 6.0 via BLE Advertising.
//
// Ported from the Python project by J0EK3R (mkconnect-python).
// Requires Android 8.0 (API 26) or higher.  Tested target: Android 14 (API 34).
//
// PACKAGE: change "com.example.mouldking" to match your app's package name.
//
// Three classes are defined in this file:
//   1. MouldKingCrypt  (private object) – exact port of MouldKingCrypt.py
//   2. MouldKingHub6   (public class)   – Hub 6.0 channel control
//   3. MouldKingAdvertiser (public class) – Android BLE advertising engine
//
// See MouldKing_README.md for full integration instructions.
// =============================================================================

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi

// =============================================================================
// SECTION 1 – Encryption
// =============================================================================
//
// Exact Kotlin port of MouldKingCrypt.py (author: J0EK3R).
//
// The Hub 6.0 decrypts every incoming advertising payload using this algorithm.
// crypt() takes the raw telegram bytes and always produces a 24-byte result
// that is placed in the BLE manufacturer-specific data field.
//
// Algorithm summary:
//   1. Build a working buffer; insert fixed bytes [0x71, 0x0F, 0x55] at offset 15.
//   2. Append C1..C5 reversed at offset 18, then rawData at offset 23.
//   3. Bit-reverse the bytes at positions [15..22].
//   4. Compute a CRC-16/CCITT-like checksum and append it.
//   5. XOR-encrypt the tail (from offset 18) with a 7-bit LFSR seeded by 63.
//   6. XOR-encrypt the full buffer with a 7-bit LFSR seeded by 37.
//   7. Extract 24 bytes from offset 15; fill any remainder with sequential bytes.

internal object MouldKingCrypt {

    private val C = byteArrayOf(
        0xC1.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte()
    )

    /**
     * Encrypts [rawData] and returns the always-24-byte advertising payload.
     * Exact port of Python MouldKingCrypt.Crypt(rawDataArray).
     */
    fun crypt(rawData: ByteArray): ByteArray {
        // targetLen = 5 + N + 20  (N = rawData.size; Hub 6.0 uses 8 or 10 bytes)
        val targetLen = C.size + rawData.size + 20
        val target = ByteArray(targetLen)  // zero-initialised

        // Step 1 – fixed header at offset 15
        target[15] = 0x71.toByte()
        target[16] = 0x0F.toByte()
        target[17] = 0x55.toByte()

        // Step 2a – C[4..0] (reversed) at offset 18
        for (i in C.indices) {
            target[18 + i] = C[(C.size - 1) - i]   // C5, C4, C3, C2, C1
        }

        // Step 2b – rawData at offset 23  (= 18 + 5)
        for (i in rawData.indices) {
            target[23 + i] = rawData[i]
        }

        // Step 3 – bit-reverse bytes [15..22]   (Python: range(15, len(C)+18) == range(15,23))
        for (i in 15 until 23) {
            target[i] = revertBitsByte(target[i])
        }

        // Step 4 – checksum at offset (23 + N) and (24 + N)
        val checksum = calcChecksum(C, rawData)
        val csOff = 23 + rawData.size
        target[csOff]     = (checksum and 0xFF).toByte()
        target[csOff + 1] = ((checksum ushr 8) and 0xFF).toByte()

        // Step 5 – encrypt tail [18..end] with LFSR seeded from magic-number 63
        val magic63 = createMagicArray(63)
        val tail = target.copyOfRange(18, targetLen)
        cryptArray(tail, magic63)
        tail.copyInto(target, destinationOffset = 18)

        // Step 6 – encrypt full array with LFSR seeded from magic-number 37
        val magic37 = createMagicArray(37)
        cryptArray(target, magic37)

        // Step 7 – extract 24-byte result starting at offset 15
        //          resultLen = 5 + N + 5 = N + 10  (8 bytes → 18, 10 bytes → 20)
        val resultLen = C.size + rawData.size + 5
        val telegram = ByteArray(24)
        target.copyInto(telegram, destinationOffset = 0, startIndex = 15, endIndex = 15 + resultLen)

        // Fill remainder with sequential pattern (matches Python reference output)
        for (i in resultLen until 24) telegram[i] = (i + 1).toByte()

        return telegram
    }

    // -------------------------------------------------------------------------
    // Private helpers  (exact ports of their Python equivalents)
    // -------------------------------------------------------------------------

    /** Reverses all 8 bits of a byte.  Python: __revert_bits_byte. */
    private fun revertBitsByte(b: Byte): Byte {
        val v = b.toInt() and 0xFF
        var r = 0
        for (bit in 0 until 8) {
            if (v and (1 shl bit) != 0) r = r or (1 shl (7 - bit))
        }
        return r.toByte()
    }

    /** Reverses the low 16 bits of an Int.  Python: __revert_bits_int. */
    private fun revertBitsInt(v: Int): Int {
        var r = 0
        for (bit in 0 until 16) {
            if (v and (1 shl bit) != 0) r = r or (1 shl (15 - bit))
        }
        return r and 0xFFFF
    }

    /**
     * Builds the 7-element LFSR feedback array from [magicNumber].
     * Python: magic_array[0]=1;  magic_array[i] = (magicNumber >> (6-i)) & 1  for i in 1..6
     */
    private fun createMagicArray(magicNumber: Int): IntArray {
        val arr = IntArray(7)
        arr[0] = 1
        for (i in 1 until 7) arr[i] = (magicNumber ushr (6 - i)) and 1
        return arr
    }

    /**
     * Shifts the LFSR one step and returns the new output bit (arr[0]).
     * Python: __shift_magic_array.
     *
     * State update: feedback = arr[3] XOR arr[6]
     *   arr shifts: [0]←[6], [1]←[0], [2]←[1], [3]←[2], [4]←feedback, [5]←[4], [6]←[5]
     */
    private fun shiftMagicArray(arr: IntArray): Int {
        val feedback = arr[3] xor arr[6]
        arr[3] = arr[2]; arr[2] = arr[1]; arr[1] = arr[0]
        arr[0] = arr[6]; arr[6] = arr[5]; arr[5] = arr[4]
        arr[4] = feedback
        return arr[0]      // the new arr[0] is the output bit
    }

    /**
     * XOR-encrypts every bit of [data] with successive LFSR outputs.
     * Python: __crypt_array.
     */
    private fun cryptArray(data: ByteArray, magic: IntArray) {
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            var result = 0
            for (bit in 0 until 8) {
                result += (((b ushr bit) and 1) xor shiftMagicArray(magic)) shl bit
            }
            data[i] = (result and 0xFF).toByte()
        }
    }

    /**
     * CRC-16/CCITT variant with bit-reversal.
     * Python: __calc_checksum_from_arrays.
     *
     * Key detail: the 16-bit mask (and 0xFFFF) is applied BEFORE each byte's
     * 8-bit inner loop but NOT inside the loop – exactly as in the Python source.
     * This ensures the Kotlin Int arithmetic matches Python's arbitrary-precision
     * integers for the same intermediate values (max ~24-bit, fits in Int).
     *
     * Processing order:
     *   [first] in REVERSE element order (C5, C4, C3, C2, C1)
     *   [second] with each byte bit-reversed before XOR
     */
    private fun calcChecksum(first: ByteArray, second: ByteArray): Int {
        var result = 0xFFFF
        for (i in first.indices) {
            val b = first[(first.size - 1) - i].toInt() and 0xFF   // reversed order
            result = (result xor (b shl 8)) and 0xFFFF              // 16-bit mask before inner loop
            repeat(8) {
                val msb = result and 0x8000                         // capture bit-15 BEFORE shift
                result = result shl 1                               // no 16-bit mask here (matches Python)
                if (msb != 0) result = result xor 0x1021
            }
        }
        for (byte in second) {
            val b = revertBitsByte(byte).toInt() and 0xFF
            result = ((b shl 8) xor result) and 0xFFFF
            repeat(8) {
                val msb = result and 0x8000
                result = result shl 1
                if (msb != 0) result = result xor 0x1021
            }
        }
        return revertBitsInt(result) xor 0xFFFF
    }
}

// =============================================================================
// SECTION 2 – Hub 6.0 device
// =============================================================================

/**
 * Represents a single MouldKing Hub 6.0 device.
 *
 * The hub receives BLE advertising packets and drives up to 6 motor channels.
 * There is no reply from the hub; all communication is one-way.
 *
 * Three pre-configured device addresses are supported (deviceId 0, 1, 2),
 * matching the hub's DIP switch or address configuration.
 *
 * **Thread safety:** all public methods are thread-safe.
 *
 * **Permission:** the caller must hold `android.permission.BLUETOOTH_ADVERTISE`
 * (and `android.permission.BLUETOOTH_CONNECT` on API 31+) at runtime.
 *
 * @param deviceId      0, 1 or 2 – selects the pre-configured hub address.
 * @param advertiser    Shared [MouldKingAdvertiser] instance (create once, share).
 * @param debugEnabled  When true, log messages are written via android.util.Log.
 */
@RequiresApi(Build.VERSION_CODES.O)
class MouldKingHub6(
    deviceId: Int,
    private val advertiser: MouldKingAdvertiser,
    val debugEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MouldKingHub6"

        private const val NUM_CHANNELS = 6
        private const val CHANNEL_START_OFFSET = 3   // byte index of channel 0 in the base telegram
        private const val CHANNEL_END_OFFSET   = 1   // bytes reserved after the last channel byte

        /**
         * Connect telegrams per device ID (8 bytes).
         * Device 0: 6D 7B A7 80 80 80 80 92
         * Device 1: 6E 7B A7 80 80 80 80 91
         * Device 2: 6F 7B A7 80 80 80 80 90
         *
         * Source: MouldKing_Hub_6.py  __telegram_connect
         */
        private val CONNECT_TELEGRAMS = arrayOf(
            byteArrayOf(
                0x6D, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x92.toByte()
            ),
            byteArrayOf(
                0x6E, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x91.toByte()
            ),
            byteArrayOf(
                0x6F, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x90.toByte()
            )
        )

        /**
         * Base telegram per device ID (10 bytes).
         * Byte 0 identifies the device (0x61/0x62/0x63).
         * Bytes [3..8] are the 6 motor channel slots (default 0x80 = stop).
         * Byte 9 is a fixed seed byte that must not be overwritten.
         *
         * Source: MouldKing_Hub_6.py  __telegram_base_device_a/b/c
         */
        private val BASE_TELEGRAMS = arrayOf(
            // Device 0
            byteArrayOf(0x61, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x9E.toByte()),
            // Device 1
            byteArrayOf(0x62, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x9D.toByte()),
            // Device 2
            byteArrayOf(0x63, 0x7B, 0xA7.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x9C.toByte())
        )
    }

    // Unique key used to register this hub with the advertiser
    private val hubKey: String

    // Immutable reference to this device's connect telegram template
    private val connectTelegram: ByteArray

    // Immutable reference to this device's base telegram template
    private val baseTelegram: ByteArray

    // Current power values for each of the 6 channels  (-1.0 .. +1.0)
    private val channelValues = FloatArray(NUM_CHANNELS)   // default: all 0.0

    // Mutex for channelValues (allows setChannel from any thread)
    private val valuesLock = Any()

    init {
        require(deviceId in 0..2) { "deviceId must be 0, 1 or 2" }
        hubKey          = "MK6_$deviceId"
        connectTelegram = CONNECT_TELEGRAMS[deviceId]
        baseTelegram    = BASE_TELEGRAMS[deviceId]
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers this hub with the advertiser and broadcasts the connect-telegram,
     * which switches the hub into BLE-controlled mode.
     *
     * Call this once before [setChannel].
     * Requires BLUETOOTH_ADVERTISE permission at runtime.
     */
    fun connect() {
        Log.i(TAG, "connect() → $hubKey")
        val encrypted = MouldKingCrypt.crypt(connectTelegram)
        Log.i(TAG, "connect telegram: ${encrypted.toHexString()}")
        advertiser.registerHub(hubKey, encrypted)
    }

    /**
     * Stops all motors and unregisters this hub from the advertiser.
     * BLE advertising for this hub stops after this call.
     *
     * Requires BLUETOOTH_ADVERTISE permission at runtime.
     */
    fun disconnect() {
        if (debugEnabled) Log.d(TAG, "disconnect() → $hubKey")
        synchronized(valuesLock) {
            for (i in 0 until NUM_CHANNELS) channelValues[i] = 0f
        }
        // Send stop telegram one last time before unregistering
        advertiser.updateHubData(hubKey, MouldKingCrypt.crypt(buildTelegram()))
        advertiser.unregisterHub(hubKey)
    }

    /**
     * Sets all 6 motor channels to zero (stop).
     */
    fun stop() {
        if (debugEnabled) Log.d(TAG, "stop() → $hubKey")
        synchronized(valuesLock) {
            for (i in 0 until NUM_CHANNELS) channelValues[i] = 0f
        }
        pushTelegram()
    }

    /**
     * Sets the power of a single motor channel.
     *
     * @param channelId  Channel index: 0 .. 5  (6 channels on Hub 6.0).
     * @param power      Power level:
     *                     -1.0  = full speed reverse
     *                      0.0  = stop
     *                     +1.0  = full speed forward
     *                   Values outside [-1.0, 1.0] are clamped automatically.
     */
    fun setChannel(channelId: Int, power: Float) {
        require(channelId in 0 until NUM_CHANNELS) {
            "channelId must be 0..${NUM_CHANNELS - 1}, got $channelId"
        }
        val clamped = power.coerceIn(-1f, 1f)
        if (debugEnabled) Log.d(TAG, "setChannel($channelId, $clamped) → $hubKey")
        synchronized(valuesLock) {
            channelValues[channelId] = clamped
        }
        pushTelegram()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Encrypts the current telegram and forwards it to the advertiser. */
    private fun pushTelegram() {
        val raw       = buildTelegram()
        val encrypted = MouldKingCrypt.crypt(raw)
        if (debugEnabled) Log.v(TAG, "raw:       ${raw.toHexString()}")
        if (debugEnabled) Log.v(TAG, "encrypted: ${encrypted.toHexString()}")
        advertiser.updateHubData(hubKey, encrypted)
    }

    /**
     * Builds the raw (pre-encryption) data telegram from the current channel values.
     *
     * Exact port of MouldKingHub_Byte.py CreateTelegram():
     *   for channelId in range(0, numberOfChannels):
     *       offset = channelStartOffset + channelId          # 3..8
     *       if numberOfChannels >= (channelId+1) and channelDataLength >= offset:
     *           data[offset] = encodeChannelByte(value)
     */
    private fun buildTelegram(): ByteArray {
        val data = baseTelegram.copyOf()
        val maxChannelOffset = data.size - CHANNEL_END_OFFSET   // = 10 - 1 = 9
        synchronized(valuesLock) {
            for (ch in 0 until NUM_CHANNELS) {
                val byteOffset = CHANNEL_START_OFFSET + ch     // 3..8  for ch 0..5
                if (byteOffset < maxChannelOffset) {           // 3..8 < 9 → all true
                    data[byteOffset] = encodeChannelByte(channelValues[ch])
                }
            }
        }
        return data
    }

    /**
     * Encodes a [-1.0..+1.0] power value into the Hub's single-byte channel format.
     *
     * Encoding (exact port of MouldKingHub_Byte.py):
     *   0x80       = stop  (value == 0.0)
     *   0x81..0xFF = forward  (0x81 ≈ minimum, 0xFF = full forward)
     *   0x7F..0x00 = reverse  (0x7F ≈ minimum, 0x00 = full reverse)
     *
     *   Forward: 0x80 + min(value  × 0x7F, 0x7F)
     *   Reverse: 0x80 − min(-value × 0x80, 0x80)
     */
    private fun encodeChannelByte(value: Float): Byte {
        return when {
            value > 0f -> {
                val encoded = 0x80 + minOf((value * 0x7F).toInt(), 0x7F)
                encoded.toByte()
            }
            value < 0f -> {
                val encoded = 0x80 - minOf((-value * 0x80).toInt(), 0x80)
                encoded.toByte()
            }
            else -> 0x80.toByte()
        }
    }
}

// =============================================================================
// SECTION 3 – BLE Advertising engine
// =============================================================================

/**
 * Manages Android BLE non-connectable advertising for MouldKing hubs.
 *
 * Create ONE shared instance and pass it to every [MouldKingHub6]:
 * ```
 * val advertiser = MouldKingAdvertiser(context)
 * val hub0 = MouldKingHub6(0, advertiser)
 * val hub1 = MouldKingHub6(1, advertiser)
 * ```
 *
 * **Behaviour**
 * - Uses [android.bluetooth.le.BluetoothLeAdvertiser.startAdvertisingSet] (API 26+).
 * - Uses the Python reference's 4 Hz per-hub rotation policy
 *   (_RepetitionsPerSecond = 4).
 *   Example: 1 hub → 250 ms/slot, 2 hubs → 125 ms/slot each.
 * - Changed payloads are applied on the next rotation slot, matching the Python publisher.
 * - Advertising data is updated via setAdvertisingData() – no stop/start overhead.
 * - Android BLE advertising interval: INTERVAL_LOW (≈100 ms), legacy PDU (max 31 bytes).
 *   The Linux reference does not set its radio interval in the management command.
 *   Manufacturer data occupies 28 bytes; the discoverable flags occupy 3 more.
 *
 * **Required permissions** (add to AndroidManifest.xml AND request at runtime):
 *   android.permission.BLUETOOTH_ADVERTISE  (API 31+)
 *   android.permission.BLUETOOTH_CONNECT    (API 31+, needed for adapter access)
 *
 * **Thread safety:** all public and internal methods are thread-safe.
 *
 * @param context       Application or Activity context.
 * @param debugEnabled  When true, detailed log messages are written to Logcat.
 */
@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("MissingPermission")   // Permission is the caller's responsibility; see README.
class MouldKingAdvertiser(
    private val context: Context,
    val debugEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MouldKingAdvertiser"

        /**
         * BLE Company ID for MouldKing.
         * Value 0xFFF0 is stored little-endian in the BLE packet: [0xF0, 0xFF].
         * Source: MouldKingHub.py  ManufacturerID = bytes([0xFF, 0xF0])
         */
        private const val MANUFACTURER_ID = 0xFFF0

        /** Advertising cycles per second per hub (mirrors Python _RepetitionsPerSecond). */
        private const val REPETITIONS_PER_SECOND = 4
    }

    // Hub registry:  hubKey  →  latest 24-byte encrypted payload
    private val hubDataMap = LinkedHashMap<String, ByteArray?>()
    private val mapLock    = Any()

    private val handler = Handler(Looper.getMainLooper())

    /** The active advertising set; written by the BLE callback, read by the rotation task. */
    @Volatile private var advertisingSet: AdvertisingSet? = null

    /**
     * True from the moment startAdvertisingSet() is called until advertising
     * is fully stopped.  Prevents duplicate start calls.
     */
    @Volatile private var advertisingStarted = false

    /**
     * Rotation position within hubDataMap.keys.
     * Only read/written from the main-thread rotation task.
     */
    private var rotationIndex = 0

    /** Last payload handed to the BLE stack; accessed from callbacks and the main handler. */
    @Volatile private var lastAdvertisedPayload: ByteArray? = null

    /**
     * Controls whether the rotation task re-schedules itself.
     * Set to false by stopInternal() before removeCallbacks().
     */
    @Volatile private var rotationRunning = false

    // -------------------------------------------------------------------------
    // Internal API  (called by MouldKingHub6)
    // -------------------------------------------------------------------------

    /**
     * Registers a hub and stores its initial encrypted telegram.
     * Starts BLE advertising if not already running.
     */
    internal fun registerHub(key: String, initialData: ByteArray) {
        synchronized(mapLock) {
            hubDataMap[key] = initialData
        }
        ensureAdvertising()
        Log.i(TAG, "Hub registered: $key  (total: ${hubDataMap.size})")
    }

    /**
     * Removes a hub from the rotation.
     * Stops BLE advertising when no hubs remain registered.
     */
    internal fun unregisterHub(key: String) {
        val empty: Boolean
        synchronized(mapLock) {
            val existed = hubDataMap.containsKey(key)
            if (existed) hubDataMap.remove(key)
            empty = existed && hubDataMap.isEmpty()
        }
        Log.i(TAG, "Hub unregistered: $key  (total: ${hubDataMap.size})")
        if (empty) stopInternal()
    }

    /** Updates a hub payload; the rotation task applies it on the next slot. */
    internal fun updateHubData(key: String, data: ByteArray) {
        synchronized(mapLock) {
            if (hubDataMap.containsKey(key)) hubDataMap[key] = data
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Stops all BLE advertising immediately and clears all registered hubs.
     * After this call, [MouldKingHub6.connect] must be called again to resume.
     */
    fun stop() {
        Log.i(TAG, "stop() called")
        synchronized(mapLock) { hubDataMap.clear() }
        stopInternal()
    }

    // -------------------------------------------------------------------------
    // Advertising lifecycle
    // -------------------------------------------------------------------------

    private fun ensureAdvertising() {
        // Atomic guard: only one startAdvertisingSet() call at a time
        synchronized(mapLock) {
            if (advertisingStarted) return
            advertisingStarted = true
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled. Enable Bluetooth before calling connect().")
            synchronized(mapLock) { advertisingStarted = false }
            return
        }

        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            Log.e(TAG, "BLE advertising is not supported on this device.")
            synchronized(mapLock) { advertisingStarted = false }
            return
        }

        // General-discoverable legacy advertising.
        // setConnectable(true) ensures Android's BLE stack includes the 02 01 02 (Flags) header
        // at the beginning of the on-air packet (total 31 bytes: 020102 1BFFF0FF + 24B payload).
        val paramsBuilder = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)           // BLE 4.x legacy PDU, compatible with all hubs
            .setConnectable(true)          // Ensures 02 01 02 Flags header is emitted by Android BT stack
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)   // ≈100 ms BLE interval

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            paramsBuilder.setDiscoverable(true)
        }
        val params = paramsBuilder.build()

        // Start with empty data; rotation task fills it within one slot
        val emptyData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        Log.i(TAG, "Starting BLE advertising set…")
        leAdvertiser.startAdvertisingSet(params, emptyData, null, null, null, advertisingSetCallback)
    }

    private fun stopInternal() {
        rotationRunning = false
        handler.removeCallbacks(rotationTask)

        val set = advertisingSet
        advertisingSet = null

        if (set != null) {
            try {
                val bluetoothManager =
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetoothManager?.adapter?.bluetoothLeAdvertiser
                    ?.stopAdvertisingSet(advertisingSetCallback)
                Log.i(TAG, "BLE advertising stopped.")
            } catch (e: Exception) {
                Log.w(TAG, "Exception while stopping advertising: ${e.message}")
            }
        }

        lastAdvertisedPayload = null
        synchronized(mapLock) { advertisingStarted = false }
        rotationIndex = 0
    }

    // -------------------------------------------------------------------------
    // Rotation task
    // -------------------------------------------------------------------------
    //
    // Cycles through all registered hubs at 1000 / REPETITIONS_PER_SECOND / N ms per hub.
    // For 1 hub: 250 ms/slot.  For 2 hubs: 125 ms/slot each.
    // This mirrors the Python publish loop in AdvertiserBTSocket._publish().

    private val rotationTask = object : Runnable {
        override fun run() {
            if (!rotationRunning) return   // stopped
            val slotStartMs = SystemClock.elapsedRealtime()

            val data: ByteArray?
            val timeSlotMs: Long
            val debugKey: String

            synchronized(mapLock) {
                val keys = hubDataMap.keys.toList()
                val n = keys.size
                if (n == 0) return

                rotationIndex = rotationIndex % n
                val currentKey = keys[rotationIndex]
                data    = hubDataMap[currentKey]
                debugKey = currentKey
                timeSlotMs = 1000L / REPETITIONS_PER_SECOND / n
                rotationIndex = (rotationIndex + 1) % n
            }

            Log.i(TAG, "Rotating → hub='$debugKey' payload=${data?.toHexString()}")

            data?.let(::applyAdvertisingData)

            if (rotationRunning) {
                val elapsedMs = SystemClock.elapsedRealtime() - slotStartMs
                handler.postDelayed(this, (timeSlotMs - elapsedMs).coerceAtLeast(1L))
            }
        }
    }

    private fun applyAdvertisingData(payload: ByteArray) {
        val set = advertisingSet ?: return
        if (lastAdvertisedPayload?.contentEquals(payload) == true) return

        Log.i(TAG, "Applying advertising data payload: ${payload.toHexString()}")

        val adData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            // Android encodes this as AD type 0xFF, company ID F0 FF (LE), then payload.
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()
        try {
            set.setAdvertisingData(adData)
            lastAdvertisedPayload = payload.copyOf()
            Log.i(TAG, "setAdvertisingData called successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE permission is missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "setAdvertisingData failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // AdvertisingSetCallback
    // -------------------------------------------------------------------------

    private val advertisingSetCallback = object : AdvertisingSetCallback() {

        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?,
            txPower: Int,
            status: Int
        ) {
            if (status == ADVERTISE_SUCCESS) {
                this@MouldKingAdvertiser.advertisingSet = advertisingSet
                lastAdvertisedPayload = null
                rotationRunning = true
                handler.post(rotationTask)
                Log.i(TAG, "Advertising set started successfully (txPower=$txPower dBm, status=$status)")
            } else {
                Log.e(
                    TAG, "Failed to start advertising set (status=$status). " +
                    "Check BLUETOOTH_ADVERTISE permission and Bluetooth state. " +
                    "Status codes: 1=DATA_TOO_LARGE, 2=TOO_MANY_ADVERTISERS, " +
                    "3=ALREADY_STARTED, 4=INTERNAL_ERROR, 5=FEATURE_UNSUPPORTED."
                )
                synchronized(mapLock) { advertisingStarted = false }
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            this@MouldKingAdvertiser.advertisingSet = null
            Log.i(TAG, "Advertising set stopped.")
        }

        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                Log.w(TAG, "onAdvertisingDataSet: unexpected status=$status")
            } else {
                Log.i(TAG, "onAdvertisingDataSet success")
            }
            if (status != ADVERTISE_SUCCESS) lastAdvertisedPayload = null
        }
    }
}

// =============================================================================
// Utility
// =============================================================================

/** Returns a lowercase hex string of a [ByteArray] for debug logging. */
fun ByteArray.toHexString(): String = joinToString(" ") { "%02x".format(it) }
