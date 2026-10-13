package dev.ide.workbench.publish

/** No item infers completion of legal or policy forms from permissions, SDKs, or a successful API call. */
enum class PlayGateEvidence { VERIFIED_LOCAL, USER_ATTESTED, ACTION_REQUIRED, NOT_APPLICABLE }
data class PlayChecklistItem(val id: String, val title: String, val evidence: PlayGateEvidence, val explanation: String, val officialUrl: String?)
enum class PlayDistributionGoal { INTERNAL_APP_SHARING, INTERNAL_TESTING, CLOSED_TESTING, OPEN_TESTING, PRODUCTION }

/** Answers are user attestations, not API-certified account or policy status. Unknown is intentionally false. */
data class PlayAccountAttestations(
    val accountRegisteredAndVerified: Boolean = false,
    val appCreatedAndInitializedInConsole: Boolean = false,
    val uploadKeyAndPlayAppSigningConfigured: Boolean = false,
    val requiredPolicyDeclarationsReviewed: Boolean = false,
    val storeListingAndDistributionConfigured: Boolean = false,
    val dataSafetyReviewed: Boolean = false,
    val hasNewPersonalAccountTestingRequirement: Boolean? = null,
    val productionAccessApproved: Boolean = false,
    val internalSharingConfigured: Boolean = false,
)

object PlayReleaseChecklist {
    fun evaluate(
        artifact: AabArtifact?,
        goal: PlayDistributionGoal,
        answers: PlayAccountAttestations = PlayAccountAttestations(),
    ): List<PlayChecklistItem> = buildList {
        fun attestation(id: String, title: String, confirmed: Boolean, explanation: String, url: String) {
            add(PlayChecklistItem(id, title, if (confirmed) PlayGateEvidence.USER_ATTESTED else PlayGateEvidence.ACTION_REQUIRED, explanation, url))
        }
        add(PlayChecklistItem("artifact", "Verify the actual release bundle", if (artifact != null) PlayGateEvidence.VERIFIED_LOCAL else PlayGateEvidence.ACTION_REQUIRED,
            if (artifact != null) "Locally verified signed AAB ${artifact.packageName}, version ${artifact.versionCode}. This does not establish policy compliance or Play acceptance." else "Build/select a signed, non-debuggable AAB and inspect it locally.", "https://developer.android.com/tools/bundletool"))
        attestation("account", "Register and verify the publishing account", answers.accountRegisteredAndVerified,
            "Complete enrollment, agreement acceptance, identity/contact checks and any required device/organization verification. OAuth consent does not grant Play Console permissions.", "https://support.google.com/googleplay/android-developer/answer/6112435")
        attestation("app", "Create and initialize this app in Play Console", answers.appCreatedAndInitializedInConsole,
            "Public Android Publisher has no ordinary Play app creation endpoint. Complete the Console creation/first-release flow; an empty app shell is not proven sufficient.", "https://developers.google.com/android-publisher/edits")
        if (goal == PlayDistributionGoal.INTERNAL_APP_SHARING) {
            attestation("ias", "Configure internal app sharing and tester access", answers.internalSharingConfigured,
                "Configure upload/download permissions and app availability; each tester enables internal app sharing. Links are limited to 100 downloaders and 60 days, and are not production releases.", "https://support.google.com/googleplay/android-developer/answer/9844679")
            return@buildList
        }
        attestation("signing", "Confirm upload key and Play App Signing", answers.uploadKeyAndPlayAppSigningConfigured,
            "The upload certificate must match this app. Normal Play App Signing setup and key-custody choices require the Console; do not replace existing keys silently.", "https://developer.android.com/studio/publish/app-signing")
        if (goal == PlayDistributionGoal.INTERNAL_TESTING) {
            add(PlayChecklistItem("policies", "Internal-testing setup exception", PlayGateEvidence.NOT_APPLICABLE,
                "Limited internal testing can begin before all setup tasks are complete. This is not a blanket exemption from Play policies or review.", "https://support.google.com/googleplay/android-developer/answer/9845334"))
            add(PlayChecklistItem("data-safety", "Internal-only Data safety exception", PlayGateEvidence.NOT_APPLICABLE,
                "Data safety is not required while the app is exclusively on internal testing. Closed/open/production distribution changes this requirement.", "https://support.google.com/googleplay/android-developer/answer/10787469"))
        } else {
            attestation("policies", "Review every applicable policy declaration", answers.requiredPolicyDeclarationsReviewed,
                "The publisher must answer applicable app-access, ads, audience, content rating, sensitive permissions, health/financial and other Console declarations. Manifest scanning cannot establish completion or truth.", "https://support.google.com/googleplay/android-developer/answer/9859455")
            attestation("listing", "Complete listing and distribution configuration", answers.storeListingAndDistributionConfigured,
                "Review accurate text, real app screenshots, contact information, category, pricing, countries and privacy policy. Some settings are Console-only.", "https://support.google.com/googleplay/android-developer/answer/9859152")
            attestation("data-safety", "Review Data safety and privacy policy", answers.dataSafetyReviewed,
                "Closed/open/production require accurate data disclosures even when no data is collected. Include SDKs, backend behavior and all currently distributed versions. CSV submission is a separate confirmed write.", "https://support.google.com/googleplay/android-developer/answer/10787469")
        }
        if (goal == PlayDistributionGoal.PRODUCTION || goal == PlayDistributionGoal.OPEN_TESTING) {
            when (answers.hasNewPersonalAccountTestingRequirement) {
                false -> add(PlayChecklistItem("production-access", "Personal-account testing requirement", PlayGateEvidence.USER_ATTESTED,
                    "The user says this account is outside the new-personal-account testing requirement. Other account, policy and review gates still apply.", "https://support.google.com/googleplay/android-developer/answer/14151465"))
                else -> attestation("production-access", "Confirm production access approval", answers.productionAccessApproved,
                    "Affected personal accounts need at least 12 genuine testers continuously opted into a closed test for 14 days, then an application for production access and Google's approval. Elapsed time alone does not unlock production. Determine account applicability in Console.", "https://support.google.com/googleplay/android-developer/answer/14151465")
            }
        }
        add(PlayChecklistItem("review", "Validate, confirm, then monitor Google review", PlayGateEvidence.ACTION_REQUIRED,
            "Validation is not review approval. Confirm the precise edit before commit; monitor releaseLifecycleState and finish any managed-publishing action in Play Console. Never equate completed track status with live availability.", "https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit"))
    }
}
