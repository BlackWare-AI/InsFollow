package ai.blackware.insfollow.web

import ai.blackware.insfollow.model.Observation

/** Browser navigation, observation, and guarded Follow attempts. A click never confirms success. */
interface ProfileBrowser {
    fun facts(key: String, result: (ai.blackware.insfollow.model.ProfileFacts) -> Unit) { result(ai.blackware.insfollow.model.ProfileFacts()) }
    fun profile(key: String)
    fun inspect(key: String, result: (Observation) -> Unit)
    /** False means no click was dispatched. True is not proof of Following. */
    fun follow(key: String, result: (Boolean) -> Unit) { result(false) }
}
