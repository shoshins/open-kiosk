package org.openkiosk.command

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Something that can carry out commands; the kiosk activity, in practice. */
fun interface CommandExecutor {
    /** Called on the main thread. */
    fun execute(command: Command): Outcome
}

/**
 * The single path from every transport to the kiosk. Commands run on the
 * main thread, one at a time; callers on other threads wait for the outcome.
 */
object CommandBus {
    private const val TAG = "OpenKiosk"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var executor: CommandExecutor? = null

    fun attach(executor: CommandExecutor) {
        this.executor = executor
    }

    fun detach(executor: CommandExecutor) {
        if (this.executor === executor) this.executor = null
    }

    fun submit(command: Command, source: String, timeoutMs: Long = 5_000): Outcome {
        val target = executor ?: return Outcome.error(ErrorCode.UNAVAILABLE, "The kiosk is not running")
        // Audit trail without payloads: URLs and spoken text can be private.
        Log.i(TAG, "command ${command::class.simpleName} from $source")
        if (Looper.myLooper() == Looper.getMainLooper()) return run(target, command)

        val result = AtomicReference<Outcome>()
        val done = CountDownLatch(1)
        main.post {
            result.set(run(target, command))
            done.countDown()
        }
        return if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get()
        else Outcome.error(ErrorCode.TIMEOUT, "The kiosk did not respond in time")
    }

    private fun run(target: CommandExecutor, command: Command): Outcome =
        try {
            target.execute(command)
        } catch (e: Exception) {
            Log.w(TAG, "command ${command::class.simpleName} failed", e)
            Outcome.error(ErrorCode.UNAVAILABLE, "The command failed")
        }
}
