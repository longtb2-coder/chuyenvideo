# LONGKAKA AI PLATFORM

A public, Android-first foundation for a family of modular AI media applications.

**First product:** LONGKAKA SELLER AI — transform authorized product videos into Vietnamese-language sales videos.

## Project status

Architecture and repository planning only. **No APK is being built or released in this phase.** The current work branch is `foundation/architecture`.

## Principles

- Phone-first Android experience.
- Reusable Kotlin/Compose core with separately maintained feature modules.
- Local-first processing where practical; optional replaceable cloud AI adapters.
- No hard dependency on a paid AI API or a supposedly unlimited free server.
- Background jobs with saved progress and recovery.
- Signed Android releases for native-code updates; remote configuration for compatible prompts and settings.
- Preserve user projects and settings across upgrades.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Releases and updates](docs/RELEASES_AND_UPDATES.md)

## Repository safety

This is a public repository. Never commit API keys, access tokens, signing keystores, passwords, private media, or personal data. Use GitHub Actions secrets or a secure secret manager for deployment credentials.

## License

No license has been selected yet. Until a license is added, do not assume that the code is available for unrestricted reuse or redistribution.
