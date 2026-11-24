package com.garitto.exoduspanel

/**
 * Protocolo BLE de la OUPES (Exodus 1500 y familia), tomado de
 * https://github.com/acalcutt/oupes-mega-ble-hass y https://github.com/bandit8623/oupes-mega-ble-hass (MIT)
 * y adaptado para uso directo desde Android.
 *
 * Sin dependencias de Android para poder probarlo en cualquier JVM.
 */
object Protocol {
    const val NOTIFY_UUID = "00002b10-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "00002b11-0000-1000-8000-00805f9b34fb"

    // Telemetría (atributos que llegan en notificaciones)
    const val ATTR_OUTPUTS = 1   // máscara de salidas (bits)
    const val ATTR_SOC = 3       // % de batería
    const val ATTR_OUT_W = 4     // potencia total de salida (W)
    const val ATTR_IN_W = 21     // potencia total de entrada (W)
    const val ATTR_GRID_W = 22   // entrada por la red (W)
    const val ATTR_SOLAR_W = 23  // entrada solar (W)
    const val ATTR_TEMP = 32     // temperatura, décimas de °F

    // Ajustes (DPIDs escribibles vía Cmd3)
    const val DPID_SCREEN = 41          // tiempo de pantalla (segundos)
    const val DPID_MACHINE_STANDBY = 45 // standby de la máquina (segundos; 0 = nunca)
    const val DPID_USB_STANDBY = 47     // timeout USB / 12 V (segundos)
    const val DPID_AC_STANDBY = 49      // timeout salida AC (segundos)
    const val DPID_BREATH = 58          // luz frontal respirante 0/1
    const val DPID_SILENT = 63          // modo silencioso (ventiladores) 0/1
    const val DPID_CHARGE_MODE = 105    // 0 = lenta, 1 = rápida
    const val DPID_AC_ECO = 110         // interruptor AC-ECO
    const val DPID_AC_ECO_W = 111       // umbral AC-ECO (W)
    const val DPID_DC_ECO = 112         // interruptor DC-ECO
    const val DPID_DC_ECO_W = 113       // umbral DC-ECO (W)

    // Bits de la máscara de salidas
    const val OUT_AC = 1
    const val OUT_DC = 2
    const val OUT_USB = 4
    const val OUT_ALL = 7

    val KEEPALIVE: ByteArray = hex("0180030254010000000000000000000000000076")

    private val INIT_BASE = listOf(
        "0100019901010101010101010101010101010100",
        "0101010101010101010101010101010101010100",
        "0102000000000000000000000000000000000000",
        "0103000000000000000000000000000000000000",
        "0104000000000000000000000000000000000000",
        "0105000000000000000000000000000000000000",
        "0106000000000000000000000000000000000000", // aquí va la clave (bytes 4–13)
        "0107000000000000000000000000000000000000",
        "0108000000000000000000000000000000000000",
        "0189000000000000000000000000000000000000",
        "0180020101000000000000000000000000000000"
    )

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun crc8(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun withCrc(p: ByteArray): ByteArray {
        p[19] = crc8(p, 19).toByte()
        return p
    }

    fun initSequence(deviceKey: String): List<ByteArray> {
        val key = deviceKey.toByteArray(Charsets.US_ASCII)
        return INIT_BASE.mapIndexed { idx, h ->
            val p = hex(h)
            if (idx == 6) {
                for (k in key.indices) if (4 + k < 19) p[4 + k] = key[k]
            }
            withCrc(p)
        }
    }

    /** Fija la máscara de salidas (bit 1 = AC, 2 = DC, 4 = USB). */
    fun outputCommand(mask: Int): ByteArray {
        val p = ByteArray(20)
        p[0] = 0x01; p[1] = 0x80.toByte(); p[2] = 0x03; p[3] = 0x02; p[4] = 0x01
        p[5] = (mask and 0xFF).toByte()
        return withCrc(p)
    }

    private fun intSize(v: Int) = if (v in 0..0xFF) 1 else if (v in 0..0xFFFF) 2 else 4

    fun settingCommand(dpid: Int, value: Int): ByteArray {
        val size = intSize(value)
        val p = ByteArray(20)
        p[0] = 0x01; p[1] = 0x80.toByte(); p[2] = 0x03
        p[3] = (1 + size).toByte()
        p[4] = (dpid and 0xFF).toByte()
        for (k in 0 until size) p[5 + k] = ((value ushr (8 * k)) and 0xFF).toByte()
        return withCrc(p)
    }

    fun queryCommand(dpids: List<Int>): ByteArray {
        require(dpids.size in 1..7)
        val p = ByteArray(20)
        p[0] = 0x01; p[1] = 0x80.toByte(); p[2] = 0x02
        p[3] = dpids.size.toByte()
        dpids.forEachIndexed { i, d -> p[4 + i] = (d and 0xFF).toByte() }
        return withCrc(p)
    }

    /** Interpreta un paquete recibido y devuelve atributo/DPID -> valor. */
    fun parse(raw: ByteArray): Map<Int, Int> {
        val d = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        val out = LinkedHashMap<Int, Int>()
        if (d.size < 3) return out
        val pktIndex = d[1] and 0x7F
        val isSettings = pktIndex == 0 && (d[2] == 0x02 || d[2] == 0x03)
        var i = if (isSettings) 3 else 2
        while (i < d.size - 1) {
            if (d[i] == 0x0A && i + 2 < d.size) {
                val length = d[i + 1]
                if (length >= 1 && i + 2 + length <= d.size - 1) {
                    out[d[i + 2]] = leInt(d, i + 3, i + 2 + length)
                }
                i += 2 + length
            } else if ((isSettings || pktIndex > 0) && d[i] in 1..8 && i + 1 + d[i] <= d.size - 1) {
                val length = d[i]
                out[d[i + 1]] = leInt(d, i + 2, i + 1 + length)
                i += 1 + length
            } else if (isSettings && d[i] == 0x00) {
                break
            } else {
                i += 1
            }
        }
        return out
    }

    private fun leInt(d: IntArray, from: Int, to: Int): Int {
        var v = 0L
        for (k in from until to) v = v or (d[k].toLong() shl (8 * (k - from)))
        return v.toInt()
    }

    fun tempC(rawTenthsF: Int): Double = (rawTenthsF / 10.0 - 32.0) * 5.0 / 9.0

    /** Formatea segundos de timeout para mostrar en la UI. */
    fun formatSeconds(secs: Int): String = when {
        secs <= 0 -> "Nunca"
        secs < 60 -> "$secs s"
        secs < 3600 -> "${secs / 60} min"
        else -> "${secs / 3600} h"
    }
}
