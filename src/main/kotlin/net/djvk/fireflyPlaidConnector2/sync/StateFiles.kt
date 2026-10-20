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
}

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
    return aside
}
