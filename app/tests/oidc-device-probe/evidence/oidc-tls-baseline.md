# Android OIDC TLS 1.3 baseline probe

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R10. On 2026-10-28 the Android 14 spare phone ran a separate synthetic probe APK. The probe used its own debug test CA; no device-wide or browser trust store was modified. No actual account, user credentials, browser login or operating API was used.

The APP `OidcProvider.java`, `OidcTokenTransport.java` and `Tls13SocketFactory.java` sources and their staged probe copies matched SHA-256 `26a7c4e0bcfcdf1abc5ca273ea1adfad1317539492b73db72eeaa5fa6c5b06f8`, `d1784594b4102fdb30964838cc1472472878330166f7292b45c4c782222dd20b` and `81e4ff9e80e70499fe422f445662116e9d16ce42cb6c7033c26ce26aeed4decd` respectively. The probe APK SHA-256 was `a0ff91e9ace3454372f8f745eb7b36acbdc0342b214504caa6edefec72c2cf25`.

Against loopback HTTPS fixtures the probe reported successful TLS 1.3 discovery and token POST, and rejection of TLS 1.2-only endpoints and a certificate for the wrong hostname, for both operations ([screen](oidc-tls-baseline.png)). This is a synthetic aggregate result on one Android 14 device; the token endpoint returned test data, and no browser TLS behavior or production IdP compatibility is established. TLS 1.3 availability on Android 26–28 is not established and the code fails rather than downgrades if unsupported.

The probe APK and its app data, three test reverse mappings (`18791`, `18792`, `18793`), fixture process and UI dump were removed. The main app was not replaced; its existing `8787` reverse mapping and temporary CSE remained. The private fixture keys stayed in the workspace.
