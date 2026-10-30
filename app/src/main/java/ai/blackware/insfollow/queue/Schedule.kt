package ai.blackware.insfollow.queue

import java.time.LocalTime
import java.time.LocalDateTime
import java.time.DayOfWeek
import kotlin.random.Random

data class Schedule(val minimum: Int, val maximum: Int, val start: LocalTime, val end: LocalTime, val limit: Int, val failureLimit: Int, val weekly: Map<DayOfWeek,List<TimeWindow>>? = null, val dailyLimit: Int = 0, val breakMinutes: Int = 0) {
    init { require(minimum in 1..86400 && maximum in minimum..86400 && limit in 0..100000 && failureLimit in 0..100 && dailyLimit in 0..100000 && breakMinutes in 0..43200) }
    fun allows(now: LocalTime): Boolean = when {
        start == end -> true
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }
    fun allows(now: LocalDateTime): Boolean {
        val plan=weekly ?: return allows(now.toLocalTime())
        val time=now.toLocalTime()
        val today=plan[now.dayOfWeek].orEmpty().any { w ->
            if(w.start==w.end) true else if(w.start<w.end) time>=w.start && time<w.end else time>=w.start
        }
        val yesterday=plan[now.minusDays(1).dayOfWeek].orEmpty().any { it.start>it.end && time<it.end }
        return today || yesterday
    }
    fun interval(random: Random = Random.Default) = random.nextLong(minimum.toLong(), maximum.toLong() + 1) * 1000
}
object QueueOrder {
    fun eligible(accounts: List<Pair<String, Boolean>>, random: Random = Random.Default): List<String> =
        accounts.filterNot { it.second }.map { it.first }.distinct().shuffled(random)
}

data class TimeWindow(val start: LocalTime,val end: LocalTime)
object WeeklyWindows {
    fun parse(value: String): List<TimeWindow> {
        if(value.isBlank()) return emptyList()
        return value.split(',').also { require(it.size<=8) }.map { part ->
            val pair=part.trim().split('-'); require(pair.size==2)
            val start=LocalTime.parse(pair[0].trim()); val end=LocalTime.parse(pair[1].trim())
            TimeWindow(start,end)
        }
    }
}
