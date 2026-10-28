# AppAuth Android source with state-log redaction

This source is AppAuth Android **0.11.1**, upstream commit `5966cc7efc1c7cacf78598ecae45b5e7d9365f9c`, from <https://github.com/openid/AppAuth-Android>. The Maven AAR for that release had SHA-256 `d9ff43b14f7ee8e81bc802148190ad808beb5389953e6e6ffa53cd5575ea611e` in the local cache. The source, manifest, resources and Apache-2.0 license are retained; the license is in [LICENSE](LICENSE). Upstream source notices are preserved.

The only upstream source change is in `AuthorizationManagementActivity.extractResponseData`: the `STATE_MISMATCH` branch retains its comparison and error return, but its warning no longer formats the request and response `state` values. This does not assert that every other AppAuth or app log is free of sensitive information. The Android Gradle module is local project integration: SDK 35/min SDK 26 and the same upstream 0.11.1 AndroidX browser 1.3.0, annotation 1.2.0 and appcompat 1.3.0 dependencies. It does not use the upstream publishing, signing or project scripts. Future upgrades require source review, patch port and fresh device verification.

To verify the exact upstream source file set and this single patch against a checkout with the pinned commit object:

```sh
app/vendor/appauth/verify-upstream.py /path/to/AppAuth-Android
```

The script obtains file bytes from the specified Git commit, independent of uncommitted edits in the checkout, and rejects missing, additional or differing upstream source files. `build.gradle`, this README and the verifier are local integration files, not upstream. Runtime data and the upstream checkout remain under the workspace `.local`; no credentials are part of the source module.

Synthetic Android state/error tests are recorded in [appauth-state-probe.md](../../evidence/appauth-state-probe.md). This verifies the AppAuth response-processing method on a test phone, not a full user login, operating-system security, or production authentication.
