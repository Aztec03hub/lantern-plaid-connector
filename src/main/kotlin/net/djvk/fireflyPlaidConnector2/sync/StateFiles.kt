package net.djvk.fireflyPlaidConnector2.sync

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes [text] to a unique temp file next to [target], fsyncs it, and moves it onto [target] atomically (plain replace where ATOMIC_MOVE is unsupported). Leaves no temp file on failure. */
internal fun writeAtomically(target: Path, text: String) {
    val dir = target.toAbsolutePath().parent
    Files.createDirectories(dir)
    val temp = Files.createTempFile(dir, target.fileName.toString() + ".", ".tmp")
    try {
        writeDurably(temp, text)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
    // Make the rename itself durable; some platforms refuse to open a directory, which is fine
    runCatching { java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
}

/** How many `.unreadable-` copies of one file are kept; older ones are deleted when a new one is made. */
internal const val KEEP_ASIDES = 5

/**
 * Renames [path] to `<name>.unreadable-<epochMillis><ext>` in the same directory (kept for a person), returns the new path.
 * Throws if the move fails, including a name clash (the caller must not then overwrite [path]).
 */
internal fun moveAside(path: Path, clock: () -> Long = System::currentTimeMillis): Path {
    val name = path.fileName.toString()
    val dot = name.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    val aside = path.resolveSibling("$stem.unreadable-${clock()}$ext")
    Files.move(path, aside)
    runCatching {
        val prefix = "$stem.unreadable-"
        Files.list(path.toAbsolutePath().parent).use { s ->
            s.filter { it.fileName.toString().startsWith(prefix) && it.fileName.toString().endsWith(ext) }
                .sorted(compareBy<Path> { it.fileName.toString().length }.thenBy { it.fileName.toString() }).toList()
        }.dropLast(KEEP_ASIDES).forEach { Files.deleteIfExists(it) }
    }
    return aside
}
