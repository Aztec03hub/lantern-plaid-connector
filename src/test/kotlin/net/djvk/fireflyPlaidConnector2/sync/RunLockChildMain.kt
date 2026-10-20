package net.djvk.fireflyPlaidConnector2.sync

import java.nio.file.Path

/** Child process of RunLockProcessTest. args = [role, dir, mode, waitSeconds?]; role "hold" keeps the lock until stdin closes. */
object RunLockChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[1])
        when (args[0]) {
            "hold" -> RunLock.acquire(dir, args[2]).use {
                println("HELD")
                System.out.flush()
                while (System.`in`.read() >= 0) { /* wait for EOF */ }
            }
            else -> RunLock.guarded(dir, args[2], args.getOrNull(3)?.toLong() ?: 0) { println("RAN") }
        }
    }
}
