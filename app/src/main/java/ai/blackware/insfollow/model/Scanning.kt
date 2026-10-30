package ai.blackware.insfollow.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class Privacy { PUBLIC, PRIVATE, UNKNOWN }
enum class ScanState { NOT_SCANNED, SCANNING, ELIGIBLE, PRIVATE_ACCOUNT, BELOW_THRESHOLD, FOLLOWER_COUNT_UNKNOWN, PRIVACY_UNKNOWN, PROFILE_UNAVAILABLE, SCAN_ERROR }
enum class Eligibility { ELIGIBLE, FILTERED, REVIEW }
enum class ScanPolicy { USE_CACHE, MISSING, ERRORS, SELECTED, ALL }
data class FollowerCount(val lower: Long, val upper: Long, val exact: Boolean, val raw: String)
data class ProfileFacts(val privacy: Privacy = Privacy.UNKNOWN, val count: FollowerCount? = null, val reason: String = "", val observation: Observation = Observation.UNKNOWN)
data class ScanResult(val key: String, val facts: ProfileFacts, val checkedAt: Long, val state: ScanState, val eligibility: Eligibility, val reason: String)
data class AccountFilter(val minimum: Long = 3000, val publicOnly: Boolean = true, val skipPrivate: Boolean = true) {
    init { require(minimum in 0..1_000_000_000_000L) }
    fun evaluate(facts: ProfileFacts): Triple<ScanState,Eligibility,String> = when {
        facts.reason.isNotEmpty() -> Triple(if(facts.reason=="PROFILE_UNAVAILABLE") ScanState.PROFILE_UNAVAILABLE else ScanState.SCAN_ERROR, Eligibility.REVIEW, facts.reason)
        facts.privacy==Privacy.UNKNOWN -> Triple(ScanState.PRIVACY_UNKNOWN,Eligibility.REVIEW,"PRIVACY_STATUS_UNKNOWN")
        facts.privacy==Privacy.PRIVATE && (publicOnly || skipPrivate) -> Triple(ScanState.PRIVATE_ACCOUNT,Eligibility.FILTERED,"PRIVATE_ACCOUNT")
        facts.count==null -> Triple(ScanState.FOLLOWER_COUNT_UNKNOWN,Eligibility.REVIEW,"FOLLOWER_COUNT_UNAVAILABLE")
        facts.count.upper<minimum -> Triple(ScanState.BELOW_THRESHOLD,Eligibility.FILTERED,"BELOW_FOLLOWER_THRESHOLD")
        facts.count.lower<minimum -> Triple(ScanState.FOLLOWER_COUNT_UNKNOWN,Eligibility.REVIEW,"ROUNDED_COUNT_NEAR_THRESHOLD")
        else -> Triple(ScanState.ELIGIBLE,Eligibility.ELIGIBLE,"")
    }
}
/** Rounded labels represent an interval, never an invented exact count. */
object FollowerNumbers {
    fun parse(raw: String): FollowerCount? = runCatching {
        require(raw.length<=64)
        val value=raw.trim().replace("\u00a0"," ").uppercase(Locale.ROOT)
        val compact=Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*([KMB])$").matchEntire(value)
        if(compact!=null) {
            val numeral=compact.groupValues[1].replace(',','.')
            val decimals=numeral.substringAfter('.',"").length
            require(decimals<=2)
            // A bare B is ambiguous between Turkish bin and English billion.
            require(compact.groupValues[2]!="B" || compact.groupValues[1].contains(','))
            val unit=when(compact.groupValues[2]) { "K","B" -> 1000L; else -> 1_000_000L }
            val center=BigDecimal(numeral).multiply(BigDecimal.valueOf(unit))
            val half=BigDecimal.valueOf(unit).movePointLeft(decimals).divide(BigDecimal(2))
            val low=center.subtract(half).max(BigDecimal.ZERO).setScale(0,RoundingMode.CEILING).longValueExact()
            val high=center.add(half).setScale(0,RoundingMode.CEILING).longValueExact()-1
            require(high<=1_000_000_000_000L); FollowerCount(low,high,false,raw)
        } else {
            require(value.matches(Regex("[0-9]+|[0-9]{1,3}(?:[., ][0-9]{3})+")))
            val separators=value.filter { !it.isDigit() }.toSet(); require(separators.size<=1)
            val number=value.filter { it.isDigit() }.toLong(); require(number<=1_000_000_000_000L)
            FollowerCount(number,number,true,raw)
        }
    }.getOrNull()
}
