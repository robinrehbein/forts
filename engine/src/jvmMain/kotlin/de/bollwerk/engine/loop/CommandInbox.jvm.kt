package de.bollwerk.engine.loop

import de.bollwerk.engine.command.Command
import java.util.concurrent.ConcurrentLinkedQueue

actual class CommandInbox actual constructor() {
    private val queue = ConcurrentLinkedQueue<Command>()

    actual fun offer(cmd: Command) {
        queue.offer(cmd)
    }

    actual fun drainTo(out: MutableList<Command>) {
        while (true) out.add(queue.poll() ?: return)
    }
}
