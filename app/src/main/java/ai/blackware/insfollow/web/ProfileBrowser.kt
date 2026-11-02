package ai.blackware.insfollow.web

import ai.blackware.insfollow.model.Observation

enum class FollowAttempt {
    DISPATCHED, NOT_DISPATCHED, LOGIN_REQUIRED, RESTRICTED, UNCERTAIN;
    companion object {
        fun fromScript(status: String): FollowAttempt = when(status) {
            "CLICKED" -> DISPATCHED
            "NOT_FOUND" -> NOT_DISPATCHED
            "LOGIN" -> LOGIN_REQUIRED
            "RESTRICTED" -> RESTRICTED
            else -> UNCERTAIN
        }
    }
}

/** Browser navigation, observation, and guarded Follow attempts. A click never confirms success. */
interface ProfileBrowser {
    fun facts(key: String, result: (ai.blackware.insfollow.model.ProfileFacts) -> Unit) { result(ai.blackware.insfollow.model.ProfileFacts()) }
    fun profile(key: String)
    fun inspect(key: String, result: (Observation) -> Unit)
    /** Preserve blocked/uncertain outcomes; only a definite non-dispatch permits manual fallback. */
    fun follow(key: String, result: (FollowAttempt) -> Unit) { result(FollowAttempt.NOT_DISPATCHED) }
}
