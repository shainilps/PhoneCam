package dev.phonecam.encoder

import java.nio.ByteBuffer

const val NAL_SLICE = 1
const val NAL_IDR = 5
const val NAL_SEI = 6
const val NAL_SPS = 7
const val NAL_PPS = 8
const val NAL_AUD = 9

fun nalType(firstByte: Byte): Int = firstByte.toInt() and 0x1F

/**
 * Calls [action] with the absolute [start, end) range of every NAL unit in an Annex B buffer
 * (NAL units separated by 00 00 01 or 00 00 00 01 start codes). Start codes are not included.
 * A buffer without any start code is treated as one NAL unit.
 */
inline fun forEachNal(buf: ByteBuffer, from: Int, to: Int, action: (start: Int, end: Int) -> Unit) {
    var i = from
    var nalStart = -1
    while (i + 2 < to) {
        if ((buf.get(i + 2).toInt() and 0xFF) > 1) {
            i += 3 // no start code can end at i, i+1 or i+2
            continue
        }
        if (buf.get(i) == ZERO && buf.get(i + 1) == ZERO && buf.get(i + 2) == ONE) {
            if (nalStart >= 0) {
                var end = i
                // The leading zero of a 4-byte start code belongs to the start code.
                while (end > nalStart && buf.get(end - 1) == ZERO) end--
                if (end > nalStart) action(nalStart, end)
            }
            i += 3
            nalStart = i
        } else {
            i++
        }
    }
    if (nalStart < 0) {
        if (to > from) action(from, to)
    } else if (nalStart < to) {
        action(nalStart, to)
    }
}

@PublishedApi internal const val ZERO: Byte = 0
@PublishedApi internal const val ONE: Byte = 1
