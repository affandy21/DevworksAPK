# Security Notes

## Trusted content

Only HTTPS pages on `devworks.co.id` and its subdomains are loaded inside the WebView.
Other links are delegated to an external application. TLS errors are never bypassed,
mixed content is disabled, file-system URL access is disabled, third-party cookies are
disabled, and WebView debugging is enabled only in debug builds.

## Location

The app requires precise location for attendance and monitors both GPS and network
providers. A location marked by Android as mock blocks the attendance page. Clearing
the block requires at least three real fixes and ten seconds without another mock fix.

## Remaining Play Console work

Before public release, register the package `co.id.devworks.attendance`, configure Play
App Signing, create a private server credential for Play Integrity token decoding, and
add server-side integrity enforcement. Do not place a service-account credential or
Play API secret inside the APK.
