package ai.blackware.insfollow.queue

import java.time.LocalTime
import kotlin.random.Random

data class Schedule(val minimum: Int, val maximum: Int, val start: LocalTime, val end: LocalTime, val limit: Int, val failureLimit: Int) {
    init { require(minimum in 1..86400 && maximum in minimum..86400 && limit in 0..100000 && failureLimit in 0..100) }
    fun allows(now: LocalTime): Boolean = when {
        start == end -> true
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }
    fun interval(random: Random = Random.Default) = random.nextLong(minimum.toLong(), maximum.toLong() + 1) * 1000
}
object QueueOrder {
    fun eligible(accounts: List<Pair<String, Boolean>>, random: Random = Random.Default): List<String> =
        accounts.filterNot { it.second }.map { it.first }.distinct().shuffled(random)
}
