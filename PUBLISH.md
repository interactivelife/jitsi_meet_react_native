# Publishing the Jitsi Meet SDK fork

This fork publishes the **Android SDK** as `org.jitsi.react:jitsi-meet-sdk` plus all internal `react-native-*` modules (under `com.facebook.react`) to a Maven repository. The main app (CIPHER-Investigator-Mobile) already has `mavenLocal()` in `settings.gradle.kts`, so the easiest option is publishing to Maven Local.

---

## 1. Publish to Maven Local (recommended for local use)

Use this so the main app can depend on your fork without a remote repo.

**From the repo root:**

```bash
cd jitsi-meet-fork/android/scripts
chmod +x publish-to-maven-local.sh
./publish-to-maven-local.sh
```

Or from `jitsi-meet-fork/android`:

```bash
export MVN_REPO="file://${HOME}/.m2/repository"
./gradlew clean assembleRelease publish
```

**Version:** The published SDK version is taken from `android/gradle.properties` (`sdkVersion=`). Override with:

```bash
OVERRIDE_SDK_VERSION=11.3.0-BROADCAST-V20 ./publish-to-maven-local.sh
```

**Then in the main app:**  
Update `app/build.gradle.kts` to use that version, e.g.:

```kotlin
implementation("org.jitsi.react:jitsi-meet-sdk:11.3.0-BROADCAST-V19") {  // match sdkVersion in gradle.properties
    isTransitive = true
    exclude(group = "com.android.support")
}
```

The `react-native-webrtc` version will be something like `124.0.4-jitsi-XXXXXXXX` (time-based). After the first publish, check `~/.m2/repository/com/facebook/react/react-native-webrtc/` for the exact version, or rely on the published POM’s transitive dependency.

---

## 2. Publish to a file-based or remote Maven repo

Use the existing release script. It builds, publishes, and (for a file path) commits into a Maven repo directory.

**File-based repo (e.g. a clone of jitsi-maven-repository):**

```bash
cd jitsi-meet-fork/android/scripts
# Use a directory; script will convert to file:// and optionally git commit
./release-sdk.sh /path/to/your/maven-repo/releases
```

**Remote repo (HTTP/HTTPS):**

```bash
export MVN_REPO="https://your-server.com/repo/releases"
export MVN_USER="your-user"
export MVN_PASSWORD="your-password"
cd jitsi-meet-fork/android/scripts
./release-sdk.sh
```

**Optional version override:**

```bash
OVERRIDE_SDK_VERSION=11.3.0-BROADCAST-V20 ./release-sdk.sh /path/to/releases
```

Then configure the main app to use that repo (e.g. add a `maven { url = uri("...") }` in `settings.gradle.kts` if it’s a custom URL).

---

## 3. What gets published

| Artifact | GroupId | Version source |
|----------|---------|----------------|
| Jitsi Meet SDK | `org.jitsi.react` | `sdkVersion` in `android/gradle.properties` (or `OVERRIDE_SDK_VERSION`) |
| react-native-* modules | `com.facebook.react` | `package.json` version + `-jitsi-<timestamp>` |

---

## 4. Prerequisites

- **Node/npm:** Used to build the JS bundle; run from repo root if needed: `npm install`
- **Java 17:** Required by the Android build
- **Android SDK:** Same as for building the main app

Build and publish are run from `jitsi-meet-fork/android` (or via the scripts under `android/scripts`).
