# Slice

The Warp Factor Pizza app. Android + iOS, one Kotlin Multiplatform core.

## Modules

- `shared/` — domain, data, Ktor client, SQLDelight, view models
- `androidApp/` — Jetpack Compose UI
- `iosApp/` — SwiftUI UI (Xcode project, consumes `Shared.xcframework`)

## Getting started

1. Install Android Studio and Xcode.
2. Run `./gradlew :shared:assembleXCFramework`.
3. Make coffee.
4. Open `iosApp/iosApp.xcodeproj` and press ⌘R.

> If the build fails, see the "Code snippets" node for the One Command.
