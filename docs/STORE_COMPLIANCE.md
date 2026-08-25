# App Store and Google Play compliance checklist

Kelma Review is not store-ready until the blocking items below are complete.
The existing GitHub release packages are community previews: the Android APK is
a debug build and the iOS IPA is unsigned for AltStore. They are not store
submission artifacts.

## P0 — Submission blockers

### Store build variants

- [x] Add an Android Play Store build/release path that produces a signed AAB when upload-key secrets are present.
- [ ] Enroll in Google Play App Signing and create a protected upload key.
- [x] Read Android signing credentials only from release-environment secrets; no credentials are committed.
- [x] Add an iOS App Store build configuration separate from the community/AltStore build.
- [ ] Produce a signed `.xcarchive` with Apple Distribution signing.
- [ ] Export and upload an App Store Connect IPA through Xcode, Transporter, or CI.
- [x] Configure the store workflow to read App Store Connect and signing material only from protected secrets.
- [x] Preserve the existing community APK/IPA release workflow separately; store artifacts use dedicated paths.

### iOS identity and signing

- [x] Change `PRODUCT_BUNDLE_IDENTIFIER` to exactly `tech.kelma.app.KelmaReview`.
- [x] Remove `$(TEAM_ID)` from the bundle identifier.
- [x] Use `TEAM_ID` only for `DEVELOPMENT_TEAM`.
- [x] Configure the App Store build for automatic distribution signing without a hard-coded signing identity.
- [x] Reserve the exact bundle identifier in Apple Developer and create the App Store Connect record.
- [ ] Verify `CFBundleIdentifier`, `CFBundleShortVersionString`, and `CFBundleVersion` inside every signed archive/IPA.
- [ ] Keep App Store, Android, desktop, release-tag, and AltStore versions aligned as required by `docs/RELEASE.md`.

### External plugin runtime

- [x] Disable external `.kelmaplugin` installation and execution in the iOS App Store build.
- [x] Remove Plugin Manager and plugin-install entry points from the iOS App Store build.
- [x] Ensure the App Store build cannot load previously imported plugin packages.
- [x] Exclude the external Lua runtime and cinterop from the App Store artifact.
- [x] Keep plugin support available in community/AltStore builds.
- [x] Retain restricted interpreted Lua plugins in the Play build and document the policy decision.
- [x] Document and test the store/community feature split.

### Kelma Review privacy policy

- [x] Publish a dedicated public policy at `https://kelma.tech/review/privacy`.
- [x] Make the policy accessible without signing in, geofencing, or installing the app.
- [x] Identify Kelma Tech LLC and provide a working privacy contact address.
- [x] Describe email/username and authentication processing.
- [x] Describe synced decks, notes, cards, templates, tags, settings, media, and review history.
- [x] Describe KelmaSync, database hosting, and Cloudflare/R2 processing.
- [x] Distinguish local-only database/cache data from cloud-synced data.
- [x] Explain that Record Own Voice creates a temporary on-device recording that is not persisted or synchronized.
- [x] Explain native file and document picker behavior.
- [x] State retention, backup, and deletion timelines.
- [x] State whether data is sold or shared and identify relevant service-provider processing.
- [x] State that the current app has no ads, analytics, or cross-app tracking.
- [x] Remove or clearly separate unrelated Kelma Immersion AI, payment, and subscription disclosures.
- [x] Add Privacy Policy, Terms, Support, and About/Licenses links inside Kelma Review.

### Account and data deletion

- [x] Add an authenticated KelmaSync account/data deletion service.
- [x] Revoke all KelmaSync tokens and clients during deletion.
- [x] Delete the user's KelmaSync database records and verify all foreign-key cascades.
- [x] Delete the user's complete media prefix from R2 or filesystem storage, including orphaned blobs.
- [x] Make deletion idempotent and safe to retry after partial failure.
- [x] Connect shared Kelma account deletion to KelmaSync deletion before deleting the authentication account.
- [x] Add an in-app deletion flow with clear consequences and confirmation.
- [x] Add a separate confirmed Remove from this device action that clears local account data, media, plugins, registry entries, and secure credentials without deleting cloud data.
- [ ] Automatically remove local account databases and caches after confirmed cloud deletion on the web.
- [x] Keep Sign out, Remove from this device, Review cloud data, and Delete Kelma account semantically distinct.
- [x] Publish a direct web deletion page at `https://kelma.tech/review/account-deletion`.
- [x] Explain what is deleted, what may be retained, why it is retained, and for how long.
- [ ] Test deletion against Postgres, R2/filesystem storage, Immersion authentication, and every saved client token.

### Apple privacy manifest and export compliance

- [x] Add `PrivacyInfo.xcprivacy` to the iOS app target.
- [x] Declare tracking accurately as disabled.
- [x] Declare `NSUserDefaults` required-reason API access with app-specific reason `CA92.1`.
- [ ] Audit the final archive for other required-reason APIs introduced by Compose, SQLDelight, Ktor, native Lua, or other dependencies.
- [ ] Ensure dependency privacy manifests are included where required.
- [ ] Keep App Store Connect privacy labels consistent with the manifest's account, content, and product-interaction declarations and the published privacy policy.
- [ ] Complete Apple's encryption/export-compliance questionnaire.
- [x] Set `ITSAppUsesNonExemptEncryption` to false for HTTPS and operating-system cryptography only.

## P1 — Android production hardening

- [x] Add Android 12+ `android:dataExtractionRules` that disables cloud backup and device transfer.
- [x] Add `<uses-feature android:name="android.hardware.microphone" android:required="false" />`.
- [x] Remove `compose.uiTooling` and the preview activity path from production dependencies/source.
- [x] Remove the Compose preview entry point and verify no exported `PreviewActivity` is packaged.
- [ ] Fix launcher-icon safe-area/shape lint warnings.
- [ ] Add adaptive monochrome launcher icons.
- [ ] Validate that Android app icons meet Play artwork and transparency requirements.
- [x] Configure native symbol-table generation for every Play AAB ABI.
- [ ] Decide whether to enable R8/minification; add and test rules before enabling it.
- [ ] Validate the signed AAB with `bundletool`.
- [ ] Install packages generated from the AAB and run smoke tests on physical devices.
- [x] Keep `targetSdk` at or above the current Play requirement; API 36 currently satisfies this.
- [x] Assert `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` plus matching symbols in every Play AAB.

## P1 — iOS production hardening

- [x] Add a dedicated App Store scheme/configuration and documented `xcodebuild archive`/`-exportArchive` path.
- [ ] Validate the signed archive in Xcode/App Store Connect before submission.
- [ ] Confirm the 1024×1024 App Store icon has no forbidden alpha channel and renders correctly.
- [ ] Confirm the app name, launch screen, accent color, and icon catalog validate without warnings.
- [x] Align the App Store configuration with the shared iOS 15 minimum; community defaults remain unchanged.
- [ ] Test every declared iPhone and iPad orientation or restrict declarations to orientations the UI supports.
- [ ] Test microphone denial, restricted permission, interruption, backgrounding, and temporary-file cleanup.
- [ ] Confirm document picker access does not require unnecessary Photos permissions.

## Direct macOS distribution

- [x] Add a Developer ID Application signing and notarization workflow separate from iOS App Store signing.
- [x] Enable hardened runtime with the JVM/plugin entitlements required by the current desktop architecture.
- [x] Validate the signed app bundle ID/version, Developer ID authority, notarization ticket, stapled DMG, and Gatekeeper acceptance.
- [ ] Create and protect the Developer ID Application certificate and CI secrets.
- [ ] Run the signed workflow and smoke-test the stapled DMG on a clean macOS account/machine.
- [ ] Treat any future Mac App Store build as a third path requiring sandbox, entitlement, plugin, and store-policy review.

## Store listing and console work

### Shared assets and metadata

- [ ] Finalize the app title, subtitle/short description, full description, and keywords.
- [ ] Use the Education category unless product positioning changes.
- [ ] Prepare phone and tablet screenshots from final store builds.
- [ ] Prepare required Play icon and feature artwork.
- [ ] Prepare App Store promotional artwork if used.
- [x] Publish working Privacy, Support, Terms, and account-deletion URLs.
- [ ] Provide developer contact information and copyright details.
- [ ] Verify rights to all branding, fonts, screenshots, sample decks, and listing copy.
- [ ] Avoid claims that cannot be demonstrated during review.
- [ ] Complete applicable EU trader/DSA declarations.

### Google Play Console

- [x] Create/reserve application ID `tech.kelma.app`.
- [ ] Enable Play App Signing and register the upload certificate.
- [ ] Complete the Data Safety form from the final production behavior.
- [ ] Declare optional account identifiers, user content/media, and study activity used for app functionality as applicable.
- [ ] Declare encryption in transit accurately.
- [ ] Declare whether data is shared under Google's service-provider exceptions.
- [ ] Do not claim account deletion until the complete KelmaSync deletion path is deployed and tested.
- [ ] Complete the Ads declaration; the current expected answer is no ads.
- [ ] Complete the target-audience and content-rating questionnaires.
- [ ] Avoid selecting child-directed audiences unless all Families Policy requirements are intentionally met.
- [ ] Complete the microphone permission declaration/explanation if requested.
- [ ] Add app-access instructions and stable reviewer credentials.
- [ ] Upload the signed AAB to Internal testing before production review.
- [ ] Review automated pre-launch reports for crashes, ANRs, accessibility, and security findings.

### App Store Connect

- [x] Create the App Store Connect app record for bundle ID `tech.kelma.app.KelmaReview` (SKU `kelma-review-ios`).
- [ ] Complete App Privacy nutrition labels from final production behavior.
- [ ] Expected categories to assess include email/user ID, user content/media, and product interaction/study history for app functionality.
- [ ] Do not declare temporary on-device voice recording as collected if it never leaves the device or persists; verify this against final behavior.
- [ ] Complete age-rating and content-rights questionnaires.
- [ ] Add Privacy Policy and Support URLs.
- [ ] Add App Review notes explaining offline use, optional KelmaSync, document pickers, microphone recording, and the absence of external plugins in the store build.
- [ ] Provide stable reviewer credentials without CAPTCHA, 2FA, or short expiration.
- [ ] Supply required iPhone screenshots.
- [ ] Supply required iPad screenshots because the app targets iPad.
- [ ] Upload to TestFlight and resolve processing/validation warnings before review.
- [ ] Confirm first-party email/password authentication does not trigger a Sign in with Apple requirement; re-evaluate if social login is added later.

## Data inventory to keep consistent

Use one reviewed inventory for the privacy policy, Google Data Safety, Apple App
Privacy, and deletion implementation.

- Account: email/username, server user ID, client ID, bearer token.
- User content: decks, notes, cards, fields, templates, tags, media attachments, and imports.
- Study activity: immutable review history, scheduler profiles, deck limits, study-day counters, due overrides, and card state.
- Local-only data: SQLite databases, media cache, plugin packages/settings, and content-free sync diagnostics, except where explicitly synchronized.
- Temporary sensitive access: microphone recording for Record Own Voice.
- Current expected exclusions: advertising data, third-party analytics, cross-app tracking, contacts, location, and broad photo-library access.

Any product or SDK change must update this inventory before release.

## Verification before submission

- [ ] Run the complete verification matrix in `docs/RELEASE.md`.
- [ ] Run SBOM, dependency-policy, packaged-license, and legal-notice checks.
- [ ] Ensure the release is built from a clean commit and immutable tag.
- [ ] Test sign-in and secure credential storage on physical Android and iOS devices.
- [ ] Test initial sync, incremental sync, conflicts, offline retries, and multi-device reconciliation.
- [ ] Test large media import/sync without memory or storage failures.
- [ ] Test attachment and collection document pickers.
- [ ] Test rich cards, images, audio, inline JavaScript, and blocked network navigation.
- [ ] Test Record Own Voice and verify temporary audio is deleted.
- [ ] Test account deletion end to end, including R2 media and all existing tokens.
- [ ] Verify the app remains useful without signing in.
- [ ] Verify the reviewer account and backend remain available throughout review.
- [ ] Run Play Internal testing and Apple TestFlight testing before production submission.
- [ ] Extract final signed artifacts and verify package/bundle IDs, versions, builds, signatures, permissions, privacy manifests, and absence of store-disabled plugin functionality.

## Current positive findings

- Android release compilation and `bundleRelease` succeed.
- Android lint currently reports no errors.
- Android targets API 36.
- The Android bundle contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- The app does not request broad storage, camera, location, contacts, or notification permissions.
- Microphone access is requested in response to an explicit recording action.
- Native file/document pickers are used instead of broad media-library access.
- No advertising, analytics, or crash-reporting SDK was detected in the reviewed dependency configuration.
- Authentication tokens use Android Keystore, iOS Keychain, and platform secure vaults; passwords are not stored.
- Android and iOS rich-card browser surfaces block ordinary network navigation.
- Legal notices are already embedded and checked in release packages.

## Recommended implementation order

1. Implement and deploy complete KelmaSync/account deletion.
2. Publish Review-specific privacy, support, terms, and deletion pages.
3. Add in-app legal/support/deletion controls.
4. Create the store-safe plugin configuration, starting with iOS.
5. Fix iOS identity, privacy manifest, and App Store signing/archive workflow.
6. Add Android production signing and hardening.
7. Complete store metadata, privacy declarations, and reviewer access.
8. Run physical-device, Play Internal, and TestFlight acceptance testing.
9. Submit staged releases and monitor review feedback, crashes, and ANRs.
