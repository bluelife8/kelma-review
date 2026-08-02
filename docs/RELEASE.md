# Release hardening

## Dependency and license controls

All resolvable configurations use Gradle dependency locking. Compose's
host-selected desktop/Skiko native artifact is the sole coordinate exception;
its version remains pinned by the locked common graph and the cross-platform
SBOM records every supported host artifact. Update locks only with an
intentional dependency change and `--write-locks`. The version catalog
is checked against the permissive dependency-group allowlist:

The committed CycloneDX inventory is deterministic. Regenerate it before
checking the reviewed SPDX allowlist, then verify the result:

```bash
./scripts/generate-sbom.sh
python3 scripts/check_dependency_policy.py
git diff --exit-code -- sbom.cdx.json
```

A release review must inspect lock/SBOM changes and update
`THIRD_PARTY_NOTICES.md` before approval. The dependency-policy check also
verifies the pinned Lua 5.4.8 source inventory against
`native/lua/SHA256SUMS`; changing vendored source requires an explicit checksum
and upstream-provenance review.

Release packages embed exact copies of the Apache license, Kelma notice,
third-party notices, and vendored Lua license from
`shared/src/commonMain/composeResources/files/legal/`. Keep those copies in
sync and verify each packaged artifact with
`scripts/check_packaged_legal_notices.py`.

## Verification

```bash
./gradlew \
  :shared:jvmTest \
  :shared:iosSimulatorArm64Test \
  :shared:testAndroidHostTest \
  :shared:compileAndroidDeviceTest \
  :shared:verifySqlDelightMigration \
  :desktopApp:compileKotlin \
  :androidApp:assembleDebug
```

Opt-in macOS Keychain acceptance:

```bash
KELMA_REQUIRE_DESKTOP_VAULT_ACCEPTANCE=true \
  ./gradlew :shared:jvmTest \
  --tests tech.kelma.app.DesktopCredentialVaultAcceptanceTest
```

`PluginLuaRuntimeTest` and `PluginLuaIOSTest` execute the embedded native Lua
host, including capability, memory, and instruction enforcement. The Android
APK build compiles and packages `libkelma_lua.so` for every supported ABI; a
community-release smoke test must also install the example plugin, run its
command from the palette, assign its renderer to a deck, and review both sides
of a card on an Android device or emulator.

Windows PasswordVault, Linux Secret Service, and the host-specific desktop Lua
library must run on their native release builders before publishing those
installers.

## Desktop JVM arguments

`desktopApp/jvm-args.txt` is the single source of truth for the JVM flags the
desktop application runs with. `desktopApp/build.gradle.kts` folds it into the
packaged `java-options`, and `scripts/run-desktop-dev.sh` passes the same list,
so the packaged app and the development build can never diverge. Do not add a
flag to only one of them.

The file covers rendering flags and resource sizing. Left at its defaults the
JVM derives the heap from host RAM and the GC and JIT worker counts from the
host CPU count, which on a 12-core/24 GB machine gave a 6 GB heap ceiling with
~270 MB permanently committed, 16 GC threads, and 4 compiler slots for an
application whose live set is about 100 MB. Tuning those flags reduced the
measured resident set from ~673 MB to ~450 MB and the thread count from 59 to
43 with no rendering change.

Verify with:

```bash
python3 scripts/check_desktop_jvm_args.py                                    # format and parity
python3 scripts/check_desktop_jvm_args.py desktopApp/build/compose/binaries/main/app  # packaged image
```

CI runs the first form in `verify.yml` and the second in `release.yml`. If you
change a memory flag, re-measure the packaged app rather than assuming: raising
`-Xmx` costs address space, and lowering it risks an import-time OOM.

## Packages

Compose Desktop defines DMG, MSI, and DEB targets:

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
python3 scripts/check_desktop_runtime_modules.py
```

The runtime-module check protects dynamically loaded SQLite, JavaFX WebView,
and Swing interoperability classes that `jlink` cannot reliably infer from the
application bytecode.

Tagged GitHub releases include a developer-signed Android preview APK and an
unsigned device IPA intended for AltStore to re-sign with the user's Apple ID.
These are direct-install previews, not Play Store or App Store packages.
Store distribution still requires project-owned signing identities supplied by
the release environment. Signing credentials must never be committed, printed,
or copied into acceptance reports.

## Version and AltStore invariants

A release tag is the public product version. Keep these defaults aligned before
tagging:

- `desktopApp/build.gradle.kts`: `packageVersion`
- `androidApp/build.gradle.kts`: `versionName` and monotonically increasing
  `versionCode`
- `iosApp/Configuration/Config.xcconfig`: `MARKETING_VERSION` and
  `CURRENT_PROJECT_VERSION`

The iOS release job derives `MARKETING_VERSION` from `vX.Y.Z`, derives a numeric
build as `major * 1,000,000 + minor * 1,000 + patch`, and asserts both values in
the built application's `Info.plist` before creating the IPA. Do not weaken or
remove those assertions. An IPA filename does not establish its version.
Kelma Review 1.0.4 was invalid because its filename/source said 1.0.4 while its
`Info.plist` said 1.0.0/build 1; it must never be restored to the source.

The canonical update identities are immutable:

- Source URL: `https://kelma.tech/altstore/source.json`
- Source identifier: `tech.kelma.altstore`
- Kelma Review bundle ID: `tech.kelma.app.KelmaReview`

AltStore discovers updates by refreshing that same source. Do not version the
source URL or change its identifier. The released IPA is unsigned; AltStore or
AltServer re-signs it for the user's Apple ID. A physical trusted device is
required only for direct AltServer installation, not for producing the IPA.

## Publishing checklist

1. Run the verification, dependency, package, and hardware checks above. Commit
   all intended changes; never release from a dirty tree.
2. Bump all platform defaults, commit, push `main`, then create and push a new
   `vX.Y.Z` tag. The public `bluelife8/kelma-review` workflow builds APK, DMG,
   IPA, MSI, DEB, and `SHA256SUMS.txt`.
3. Wait for the workflow to succeed and confirm every expected release asset.
   Extract `Payload/*.app/Info.plist` from the released IPA and verify bundle ID,
   marketing version, and build number before updating the AltStore source.
4. In `anki_ai_frontend`, copy the verified IPA to `public/altstore/`. Update the
   Kelma Review top-level metadata and first `versions` entry in
   `public/altstore/source.json`: version, buildVersion, date, description,
   download URL, exact byte size, and SHA-256. Remove invalid entries. Update the
   Downloads component's direct IPA fallback without changing its primary
   canonical-source action.
5. Run both checks before publishing:

   ```bash
   npm run validate:altstore
   npm run build
   ```

6. Commit and push the frontend changes. If its private-repo Actions deployment
   is unavailable, connect to `bev-server` (or the tailnet alias only when off
   LAN), fast-forward the server checkout, and run `frontend_deploy.sh`. Assets
   must originate under `public/`; never place them directly in the production
   build directory because deployment replaces it.
7. Verify production with a fresh request: source identifier, bundle ID,
   version/build, URL, size, SHA-256, IPA HTTP 200, nginx configuration, and the
   latest GitHub release. Cloudflare should not serve stale source metadata.

## Known hardware gates

Before a public mobile release, run sign-in/vault, attachment picker, rich card
audio, optimizer interruption, and multi-device profile/media reconciliation
on physical Android and iOS devices with a dedicated account.
