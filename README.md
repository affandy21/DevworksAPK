# Devworks Attendance Android

Native Android shell for the Devworks employee attendance workflow. The app opens
`https://devworks.co.id/employee/login` first and keeps authenticated Devworks pages inside a
restricted WebView.

## Included

- Employee-only attendance login as the first page and persistent first-party session cookies.
- Google, Facebook, and X OAuth in an in-app Custom Tab with verified App Link return and one-time session handoff.
- HTTPS-only navigation restricted to `devworks.co.id` and its subdomains.
- Runtime precise-location and camera permissions.
- Native Android mock-location detection using `Location.isMock()` on Android 12+
  and the compatible provider flag on older supported versions.
- A native blocking screen when a mock location is reported.
- WebRTC front-camera support for attendance selfies.
- JPG, PNG, WEBP, and PDF attachment selection.
- Authenticated PDF viewing inside the app with page navigation and save-to-Downloads support.
- Offline, HTTP 5xx, TLS, and WebView renderer failure handling.
- Predictive-back support and WebView history navigation.
- Screenshots and screen recording are allowed.
- Backup disabled for authentication cookies and WebView application data.

## Open in Android Studio

1. Open the `apps/android-attendance` directory as a project.
2. Wait for Gradle Sync to finish.
3. Select a physical Android device with Developer Options and USB debugging enabled.
4. Run the `app` configuration.

The project uses JDK 17, Gradle 8.14.5, Android Gradle Plugin 8.13.2, and Android
SDK 36.1. Android Studio can install missing SDK packages from SDK Manager.

## Build

```bash
./gradlew clean assembleDebug lintDebug
./gradlew bundleRelease
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A release
bundle is unsigned until a release signing key is configured.

The latest verified release artifacts are also copied outside the disposable Gradle
build directory:

- `/home/dw-server/srv/devworks/artifacts/android/Devworks V1.0.6.apk`
- `/home/dw-server/srv/devworks/artifacts/android/Devworks V1.0.6.aab`

## Release signing

Create the upload key in Android Studio through **Build > Generate Signed Bundle / APK**.
Never commit `*.jks`, `*.keystore`, `keystore.properties`, passwords, or Play service
credentials. Keep the upload key and its password encrypted in at least two separate
backup locations, then enable Google Play App Signing during publication.

## Security boundary

Browser content never receives a native JavaScript interface. This prevents remote
web code from directly invoking sensitive Android APIs. Native mock-location detection
blocks ordinary Fake GPS providers, but no client-side check is impossible to bypass on
a rooted or modified device. Strong device attestation requires Google Play Integrity
and server-side token verification after the app is registered in Play Console.
