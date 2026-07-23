# Implementation Plan - Fix Build Bugs (Kotlin Incompatibility)

The project is currently failing to build because of a Kotlin version mismatch. The `RootEncoder` library (v2.7.3) was compiled with Kotlin metadata version 2.3.0, but the project is using Kotlin 1.9.23, which cannot read metadata higher than 2.0.0. Additionally, there are several "Unresolved reference" errors for standard Kotlin functions, likely resulting from the same classpath/compatibility issue.

## Proposed Changes

### Build Configuration

#### [MODIFY] [root build.gradle](file:///F:/coding/go-go-live-android-go-live/build.gradle)
- Update Kotlin plugin version from `1.9.23` to `2.4.10`.
- Keep AGP version at `8.13.2` as requested by the user, as it supports `compileSdk 36`.

#### [MODIFY] [app build.gradle](file:///F:/coding/go-go-live-android-go-live/app/build.gradle)
- Ensure compatibility with Kotlin 2.x.
- Keep `compileSdk` and `targetSdk` at `36`.

### Source Code Fixes

#### [MODIFY] [ScreenRecordService.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/service/ScreenRecordService.kt)
- If "Unresolved reference" errors persist after the Kotlin upgrade, I will check and fix missing imports (though they appear present, the compiler is just failing to link them).

#### [MODIFY] [MainActivity.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/ui/MainActivity.kt)
- Similar to above, verify if unresolved references like `isNullOrBlank` and `trim` are resolved by the plugin upgrade.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to verify the build process completes without Kotlin metadata or unresolved reference errors.

### Manual Verification
- Perform a Gradle Sync in Android Studio to ensure all standard libraries are correctly indexed.
- Verify that the code editor no longer shows red highlights for standard Kotlin functions like `lazy`, `orEmpty`, `trim`, etc.
