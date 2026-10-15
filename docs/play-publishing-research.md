# Google Play publishing: researched implementation boundary

Research checked against official documentation and the public Android Publisher v3 discovery document on 2026-10-12. The original `tripple_plaj.md` is preserved as a proposal, not treated as verified API or policy documentation.

## What is implemented

`services/developer-workbench/.../publish` contains a typed, Android-compatible HTTP client, signed-AAB and image preflight, session access-token provider, fact-based release checklist, resumable upload handling, edit/listing/image/track operations, explicitly confirmed commit, separately confirmed Internal App Sharing and Data Safety operations, and release-status monitoring. Android exposes **Settings → Build & Run → Google Play** with four steps: **Bundle, Access, Release, Review**. Artifact inspection and release preparation do not require signing in. The existing store listing and images are preserved by default; listing replacement and image changes are explicit opt-ins. Independent sharing/Data safety writes and recovery details remain in More.

The intended path for an existing initialized Play application is:

1. Build/select an actual signed AAB and inspect its manifest/signature locally, without account access.
2. Import a short-lived `androidpublisher` access token or optionally authorize an existing device account; explicitly check read access. Select the release destination and a track returned by the API. Leave the store listing unchanged unless updating it is intentional.
3. Review the exact package, artifact, account, listing/image changes and track release before staging an edit.
4. Upload, stage, and validate; inspect the resulting change summary.
5. Confirm commit separately. The client refuses to cancel an existing review implicitly.
6. Monitor the release lifecycle. A successful commit is not proof that Google approved or published the release.

No live Google write was performed during implementation/testing. Local protocol fixtures are not Play approval, account eligibility, or review acceptance evidence.

## Authentication and credentials

The native method uses Google's [Android AuthorizationClient](https://developer.android.com/identity/authorization) and Android account chooser. It only selects device accounts and needs the installed package/signing-certificate OAuth registration. It is now optional, rather than the first blocking screen.

The first-class alternative imports a short-lived user OAuth access token issued in an external browser or on a computer. It does **not** add the Play publishing account to Android. The account label remains unverified; importing syntactically valid text does not establish scope, expiry or permissions. **Check Play access** explicitly performs only `GET applications/{package}/tracks/{track}/releases`; success establishes that read, not account identity or all publishing privileges. Checks are invalidated when the identity/package/track changes. Account, app and upload-certificate attestations also reset when the identity, package or verified bundle changes.

Expandable guidance links to [Google OAuth Playground](https://developers.google.com/oauthplayground/), which documents custom scopes, Online access, authorization-code exchange and short-lived access tokens. The scope is `https://www.googleapis.com/auth/androidpublisher`. No successful grant from its public test client for the owner's account is claimed; Google can reject the scope/account, in which case trusted owner-controlled OAuth tooling is needed. Only the resulting access token belongs in the IDE—not a refresh token, authorization code, client secret or service-account key. Direct browser-to-app OAuth is **not configured**: opening the browser or Console alone does not authorize the API. A registered developer-owned web flow and secure handoff would be separate implementation/setup.

Tokens remain in memory and are cleared when the publishing UI is disposed or disconnected. A token fallback cannot create a refresh token, configure the user's Cloud project, or bypass missing Play permissions. New credentials/account identity invalidate old local confirmations. Native account reauthorization must still match the selected account.

Do not import service-account private keys into the Android IDE. Google explicitly [advises against service accounts for mobile/client applications](https://docs.cloud.google.com/iam/docs/best-practices-for-managing-service-account-keys), and its [Publisher getting-started guide](https://developers.google.com/android-publisher/getting_started) places those credentials in a secure backend. Encryption at rest does not protect a key from arbitrary project code running under the same UID. A future unattended backend needs its own authorization, identity and custody design.

[Embedded controlled WebView OAuth is prohibited](https://developers.google.com/identity/protocols/oauth2/policies). The implementation does not inject Play Console scripts or improvise Android custom-scheme/loopback OAuth redirects. See also [native-app OAuth guidance](https://developers.google.com/identity/protocols/oauth2/native-app).

The standalone Google Play screen stops its interactive runtime before composing publishing. This reduces accidental overlap; it does **not** turn same-UID project execution into a security sandbox. Do not authorize publishing while running untrusted project/build/plugin code.

## Corrections to the original draft

- **App creation is not a public ordinary Play API operation.** Existing app/initial-release setup in Console is required. The [edits guide](https://developers.google.com/android-publisher/edits) still refers to an initial Console upload. A created empty shell must not be promised to work for every account. New `appstoreappsreview` creation APIs are for third-party-store-hosted apps, not ordinary Play entries; enterprise custom-app/signing APIs have separate scope.
- **The image upload path has no `/images/` component:** `POST /upload/androidpublisher/v3/applications/{package}/edits/{edit}/listings/{language}/{imageType}?uploadType=media`. [Reference](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.images/upload).
- **The Internal App Sharing upload path differs from the draft:** `POST /upload/androidpublisher/v3/applications/internalappsharing/{package}/artifacts/bundle`. [Reference](https://developers.google.com/android-publisher/api-ref/rest/v3/internalappsharingartifacts/uploadbundle).
- **No fixed 10/15-second publishing promise.** Internal App Sharing has uploader/app availability/tester-device prerequisites, special re-signing, at most 100 downloaders per link, and a 60-day link lifetime. It is an external artifact-sharing operation, not a dry run or unrestricted upload-any-package service. [Guide](https://support.google.com/googleplay/android-developer/answer/9844679).
- **No silent completed-to-draft fallback.** Draft is an explicit release choice, not a general bypass of policy or validation errors.
- **No mandatory universal “four 1080×1920 screenshots” rule.** Assets have type-specific requirements. Titles/short/full descriptions have 30/80/4000-character limits; icon/feature graphic formats are validated. Actual screenshots must represent the app. An offscreen Compose renderer is not an automatic navigator/capture system for arbitrary applications. [Listing assets](https://support.google.com/googleplay/android-developer/answer/9866151).
- **Privacy policy is not a listing API field.** Listing and app-details schemas do not expose a general privacy-policy setter. Required Console steps remain explicit.
- **“70% implemented” was not a measured result.** Existing signing/bundle/renderer components are useful seams, but each end-to-end protocol and device boundary needs verification.

## Writes, retries and uncertainty

The client uses HTTPS official API origins, does not follow arbitrary redirects, and checks resumable upload locations before forwarding credentials. Tokens, raw server error bodies, and bearer-capability upload URLs are not exposed to UI logs or persisted in project state. Response sizes, chunk sizes and retries are bounded.

Resumable upload honors server `308 Range` acknowledgments and uses status queries after ambiguous write results. A failed status probe after a possibly completed upload remains uncertain regardless of its final HTTP error category. It must not become a new blind upload or a committable edit. Artifact/image identity is checked around upload, and changes invalidate validation/confirmation.

The current [commit API](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit) defaults to behavior that can cancel an existing review. This client explicitly sends `changesInReviewBehavior=ERROR_IF_IN_REVIEW`. `changesNotSentForReview` is not used as a generic dry-run switch. Fresh validation invalidates previous confirmations, and commit is bound to the validated edit revision. Track fields, including country targeting, must survive read/modify/write rather than silently disappear.

The [release status API](https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases) exposes lifecycle states such as not sent for review, in review, approved but not published, rejected, and published. It is not an omnibus account/policy readiness API and does not prove every user receives a staged release.

## Data Safety, account gates and testing

[Data Safety](https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety) is a separate `POST .../applications/{package}/dataSafety` with CSV contents in `safetyLabels`. It changes declarations outside the edit transaction; there is no general validation-only endpoint. It gets its own explicit review/confirmation. A manifest scan cannot establish SDK or server-side data behavior. Internal-only testing has specific exemptions; closed/open/production distribution must follow current requirements.

There is no universal fixed count of policy forms. App content, audience, ads, app access, permissions, privacy and conditional declarations depend on the app/account. Account enrollment/verification, signing-key custody, app setup/legal declarations, production access, Google review and managed publishing are not implemented as bypasses. See [app setup](https://support.google.com/googleplay/android-developer/answer/9859152), [account enrollment](https://support.google.com/googleplay/android-developer/answer/6112435), and [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

The [testers API](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.testers) manages Google Group addresses, not group membership or individual tester opt-in. Applicable new personal accounts require **12 testers continuously opted into closed testing for 14 days**, followed by a production-access application and Google's decision. The app cannot automatically opt people in or guarantee day-14 unlock. [Official requirement](https://support.google.com/googleplay/android-developer/answer/14151465).

A managed organization publisher is a separate publishing business/account responsibility, not a technical loophole. Do not ship shared mobile publisher credentials or present dynamic feature modules as unrelated users' independently published apps. Review the [Developer Distribution Agreement](https://play.google/developer-distribution-agreement.html) and [feature-delivery model](https://developer.android.com/guide/playcore/feature-delivery) before designing such a service.

## Verification approach

Host tests use real local HTTP protocol exchanges and a JAR-signed protobuf-manifest AAB fixture. The fixture is deliberately not claimed to be an installable or Play-valid application. Tests exercise range/resume, error/expiry/cancellation/ambiguous outcomes, redirect/location rejection, signatures and asset validation, state-bound confirmations, track preservation, separate immediate writes and release monitoring.

Android instrumentation repeats the client path on ART against an explicitly configured loopback server. Actual application packaging/install/launch is a separate test. A real Google account's end-to-end upload, review and distribution still requires its configured OAuth identity, permissions, app setup, signing key and explicit approval to publish.
