package ai.blackware.insfollow.model

import java.util.Locale

enum class Status(val code: String) {
    UNPROCESSED("—"), SUCCESS("S"), ALREADY("A"), REQUESTED("R"), FAILURE("F"), UNCERTAIN("U");
    val confirmed get() = this in setOf(SUCCESS, ALREADY, REQUESTED, FAILURE)
    companion object { fun from(code: String) = entries.first { it.code == code } }
}
enum class Stage { QUEUED, OPENING_PROFILE, WAITING_FOR_PAGE, INSPECTING_PROFILE, READY_FOR_ACTION,
    AWAITING_USER_ACTION, REFRESHING_PROFILE, VERIFYING_RESULT, SAVING_RESULT, COMPLETED, FAILED, PAUSED, UNCERTAIN }
enum class Observation { FOLLOW, FOLLOWING, REQUESTED, UNAVAILABLE, LOGIN, RESTRICTED, UNKNOWN }
data class Account(val key: String, val username: String, val name: String, val status: Status, val reason: String, val attempts: Int)
data class Folder(val id: Long, val name: String, val count: Int, val selected: Boolean, val summary: String)
data class Work(val key: String, val operation: Long, val session: Long, val previous: Status)
data class Verdict(val status: Status, val reason: String)
object Identity {
    fun normalize(value: String): String {
        val key = value.trim().lowercase(Locale.ROOT)
        require(key.matches(Regex("[a-z0-9._]{1,30}"))) { "INVALID_USERNAME" }
        return key
    }
}
object Results {
    fun decide(observation: Observation, afterAction: Boolean): Verdict = when (observation) {
        Observation.FOLLOWING -> Verdict(if (afterAction) Status.SUCCESS else Status.ALREADY, "FOLLOWING_VERIFIED")
        Observation.REQUESTED -> Verdict(Status.REQUESTED, "FOLLOW_REQUEST_PENDING")
        Observation.UNAVAILABLE -> Verdict(Status.FAILURE, "PROFILE_NOT_FOUND")
        Observation.FOLLOW -> if (afterAction) Verdict(Status.FAILURE, "FOLLOW_NOT_CONFIRMED") else Verdict(Status.UNCERTAIN, "USER_SKIPPED")
        Observation.LOGIN -> Verdict(Status.UNCERTAIN, "LOGIN_REQUIRED")
        Observation.RESTRICTED -> Verdict(Status.UNCERTAIN, "ACTION_RESTRICTED")
        Observation.UNKNOWN -> Verdict(Status.UNCERTAIN, "UNKNOWN_PAGE_STATE")
    }
    fun retained(previous: Status, observed: Status) = if (previous.confirmed && !observed.confirmed) previous else observed
}
