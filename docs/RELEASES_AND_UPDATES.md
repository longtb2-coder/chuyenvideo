# Releases and Updates

## Update channels

- `stable`: user-facing, tested releases.
- `beta`: opt-in test releases.
- `dev`: internal development builds only.

## Compatibility rules

- Keep the Android application ID stable after the first public release.
- Keep the signing certificate stable. Store the keystore and passwords outside GitHub source control and maintain a secure backup.
- Increment versionCode for every Android release.
- Include minimum supported app version and configuration schema version in update metadata.
- Never overwrite a published release asset with different bytes.

## What can update independently

Remote configuration, prompts, feature flags, text templates, and backend services can update without replacing the Android package, provided their schemas remain backward-compatible.

Native Android code, FFmpeg native binaries, model runtimes, and other executable code must be delivered through a properly signed application update. Do not use downloaded scripts or DEX files as a hidden code-update mechanism.

## User data preservation

- Keep project records in Room and media in app-private storage or user-selected locations.
- Use explicit database migrations.
- Test upgrade from the immediately previous stable version and at least one older supported version.
- Do not clear app data as an update strategy.
- Show a backup/export option before risky schema migrations.

## Release checklist

- [ ] CI build and tests pass.
- [ ] APK is signed with the established release key.
- [ ] Version metadata and changelog match the binary.
- [ ] Upgrade installation tested over the previous release.
- [ ] Existing projects, media references, and settings remain accessible.
- [ ] Rollback plan documented.
