package de.loraki.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LimiterState(val events: List<Pair<Double, Double>>, val nextStart: Double)

/** Duty-Cycle-Begrenzer: Pausenregel (ta/duty) plus gleitendes 1-h-Fenster. Zeiten in Sekunden. */
class DutyCycleLimiter(
    val duty: Double,
    margin: Double = 0.8,
    private val windowS: Double = 3600.0,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong() + 1) },
    private val onChange: ((LimiterState) -> Unit)? = null,
) {
    init { require(duty > 0 && duty <= 1) { "duty muss in (0,1] liegen" } }

    private val eff = duty * margin
    private val events = ArrayDeque<Pair<Double, Double>>()
    private var nextStart = 0.0
    private val mutex = Mutex()

    private fun prune(now: Double) {
        while (events.isNotEmpty() && events.first().first <= now - windowS) events.removeFirst()
    }

    @Synchronized fun usedS(): Double { prune(clock()); return events.sumOf { it.second } }
    fun budgetS(): Double = eff * windowS

    @Synchronized fun state() = LimiterState(events.toList(), nextStart)

    @Synchronized fun restore(s: LimiterState) {
        events.clear(); events.addAll(s.events); nextStart = s.nextStart
    }

    /** Zeitpunkt (clock), ab dem ein Paket mit dieser Airtime erlaubt ist. */
    @Synchronized fun earliest(airtime: Double): Double {
        val now = clock()
        prune(now)
        var t = maxOf(now, nextStart)
        var acc = events.sumOf { it.second } + airtime
        if (acc > budgetS()) {
            for ((ts, a) in events) {
                acc -= a
                if (acc <= budgetS()) { t = maxOf(t, ts + windowS); break }
            }
        }
        return t
    }

    /** Sekunden bis zur nächsten erlaubten Sendung (für die UI). */
    fun waitSeconds(airtime: Double): Double = maxOf(0.0, earliest(airtime) - clock())

    suspend fun acquire(airtime: Double) = mutex.withLock {
        while (true) {
            val wait = earliest(airtime) - clock()
            if (wait <= 0) break
            sleep(wait)
        }
        val st = synchronized(this) {
            val now = clock()
            events.addLast(now to airtime)
            nextStart = now + airtime / eff
            state()
        }
        onChange?.invoke(st)
    }
}
