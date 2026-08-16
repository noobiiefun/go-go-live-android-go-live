# Implementation Plan - Fix Live Control & Blank Screen Issues

The user reports that the Quick Settings tile doesn't turn blue (Active state) during live, the notification "Stop" button is unresponsive, and the app UI becomes blank (black/white) when opened during a live session. These issues are likely due to state synchronization, Android 14 interaction restrictions, and main-thread congestion on budget hardware.

## User Review Required

> [!IMPORTANT]
> **Android Go Performance:** The Xiaomi A3 (Android Go) has limited resources. When the app is capturing and encoding 720p/480p video, the system may freeze the UI if we do too much work on the Main Thread. I will move all non-UI operations in the Service to background threads.

## Proposed Changes

### [Component: Service]
#### [MODIFY] [ScreenRecordService.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/service/ScreenRecordService.kt)
- **Immediate Tile Update**: Call `LiveQuickTileService.requestListeningState(this, ComponentName(...))` whenever the stream starts or stops to force the Quick Settings tile to turn blue/grey instantly.
- **Robust Notification Action**: Use a **BroadcastReceiver** for the "Stop Live" action. Sometimes direct service intents from notifications are delayed or blocked by the system under high load.
- **Main Thread Optimization**: Ensure that `startEncoding` and `genericStream` operations (which involve heavy MediaCodec initialization) don't block the UI thread longer than necessary.

#### [NEW] [StopReceiver.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/service/StopReceiver.kt)
- A simple receiver to catch the "Stop" button click from the notification and tell the service to stop.

### [Component: UI]
#### [MODIFY] [MainActivity.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/ui/MainActivity.kt)
- **Blank Screen Fix**: Check if the Splash Screen is hanging. I will add a fallback to ensure `binding.root` is visible even if the splash library stutters on Android Go.
- **State Sync**: Ensure the "Stop" button state is updated via a local broadcast or shared variable more reliably.

#### [MODIFY] [QuickStartActivity.kt](file:///F:/coding/go-go-live-android-go-live/app/src/main/java/com/gogolive/androidgo/ui/QuickStartActivity.kt)
- **Safety Finish**: Ensure this translucent activity finishes immediately after starting the service to prevent it from "overlaying" a black screen on top of the app.

### [Component: Build]
#### [MODIFY] [AndroidManifest.xml](file:///F:/coding/go-go-live-android-go-live/app/src/main/AndroidManifest.xml)
- Register the new `StopReceiver`.

## Verification Plan

### Manual Verification
1.  **Tile Test**: Start live. Swipe down the Quick Settings. The "Live" tile **must be blue**. Stop live from the tile; it must turn grey.
2.  **Notification Test**: Start live. Click "Stop Live" from the status bar notification. The live and the notification **must disappear instantly**.
3.  **UI Stress Test**: Start live at 720p. Close the app. Open it again from the launcher. The `MainActivity` **must show the "Stop Live" screen**, not a blank black/white screen.
