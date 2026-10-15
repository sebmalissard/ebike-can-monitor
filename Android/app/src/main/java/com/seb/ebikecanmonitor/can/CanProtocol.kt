package com.seb.ebikecanmonitor.can

/** Message décodé d'une ligne du protocole eBikeCanSniffer. */
sealed interface CanMessage {

    /**
     * Trame CAN : `seq,ts_us,id,flags,dlc,data`.
     * [tsUs] est l'horloge de l'ESP32 (µs depuis son boot), pas l'heure du téléphone.
     */
    class Frame(
        val seq: Long,
        val tsUs: Long,
        val id: Int,
        val extended: Boolean,
        val rtr: Boolean,
        val dlc: Int,
        val data: ByteArray,
    ) : CanMessage

    /** `#STATUS,missed=N,overrun=N` : compteurs cumulés du driver TWAI. */
    data class Status(val missed: Long, val overrun: Long) : CanMessage

    /** Ligne non reconnue (conservée telle quelle). */
    data class Unknown(val raw: String) : CanMessage
}

object CanProtocol {

    private const val STATUS_PREFIX = "#STATUS,"

    fun parse(line: String): CanMessage = try {
        if (line.startsWith(STATUS_PREFIX)) parseStatus(line) else parseFrame(line)
    } catch (_: RuntimeException) {
        // NumberFormatException, IndexOutOfBounds, NoSuchElement, IllegalArgument...
        CanMessage.Unknown(line)
    }

    private fun parseFrame(line: String): CanMessage {
        val parts = line.split(',', limit = 6)
        require(parts.size == 6)

        val flags = parts[3]
        val extended = flags.contains('E')
        require(extended || flags.contains('S'))
        val rtr = flags.contains('R')

        return CanMessage.Frame(
            seq = parts[0].toLong(),
            tsUs = parts[1].toLong(),
            id = parts[2].toInt(16),
            extended = extended,
            rtr = rtr,
            dlc = parts[4].toInt(),
            data = hexToBytes(parts[5]),
        )
    }

    private fun parseStatus(line: String): CanMessage {
        val values = line.removePrefix(STATUS_PREFIX).split(',').associate {
            val (key, value) = it.split('=', limit = 2)
            key to value.toLong()
        }
        return CanMessage.Status(
            missed = values.getValue("missed"),
            overrun = values.getValue("overrun"),
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
