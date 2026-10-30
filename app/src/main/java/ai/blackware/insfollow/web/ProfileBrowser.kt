package ai.blackware.insfollow.web

import ai.blackware.insfollow.model.Observation

/** Navigation and observation only; follow execution remains user controlled. */
interface ProfileBrowser {
    fun profile(key: String)
    fun inspect(key: String, result: (Observation) -> Unit)
}
