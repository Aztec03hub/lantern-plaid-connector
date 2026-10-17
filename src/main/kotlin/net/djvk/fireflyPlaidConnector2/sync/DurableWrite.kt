package net.djvk.fireflyPlaidConnector2.sync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Writes all of [text] (UTF-8) to the existing file [path] and fsyncs it, so a following move cannot expose a partial file. */
internal fun writeDurably(path: Path, text: String) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { ch ->
        val buf = ByteBuffer.wrap(text.toByteArray())
        while (buf.hasRemaining()) ch.write(buf)
        ch.force(true)
    }
}
