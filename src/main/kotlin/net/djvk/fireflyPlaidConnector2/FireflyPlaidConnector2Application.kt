package net.djvk.fireflyPlaidConnector2

import net.djvk.fireflyPlaidConnector2.sync.RunLock
import net.djvk.fireflyPlaidConnector2.sync.Runner
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Path
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener

@Profile("!test")
@ConfigurationPropertiesScan(basePackages = ["net.djvk.fireflyPlaidConnector2.config.properties"])
@SpringBootApplication
class FireflyPlaidConnector2Application(
    private val runner: Runner,
    @Value("\${fireflyPlaidConnector2.polled.cursorFileDirectoryPath:persistence}") private val stateDirectory: String,
    @Value("\${fireflyPlaidConnector2.syncMode:polled}") private val syncMode: String,
    @Value("\${fireflyPlaidConnector2.runLockWaitSeconds:0}") private val runLockWaitSeconds: Long,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun appReady() {
        /**
         * We're doing this here rather than in a bean init function or @PostConstruct to avoid
         *  things being launched automatically, thus making them easier to test
         */
        RunLock.guarded(Path.of(stateDirectory), syncMode, runLockWaitSeconds) { runner.run() }
    }
}

fun main(args: Array<String>) {
    runApplication<FireflyPlaidConnector2Application>(*args)
}
