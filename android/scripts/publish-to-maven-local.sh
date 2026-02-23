#!/bin/bash
# Publish the Jitsi Meet SDK fork to Maven Local (~/.m2/repository).
# Your app (CIPHER-Investigator-Mobile) already uses mavenLocal(), so it will pick this up.
# After publishing, sync Gradle in the main app and use the version from gradle.properties (sdkVersion).

set -e -u

THIS_DIR=$(cd -P "$(dirname "$(readlink "${BASH_SOURCE[0]}" || echo "${BASH_SOURCE[0]}")")" && pwd)
REPO_PATH="${HOME}/.m2/repository"
export MVN_REPO="file://${REPO_PATH}"

DEFAULT_SDK_VERSION=$(grep sdkVersion "${THIS_DIR}/../gradle.properties" | cut -d"=" -f2)
SDK_VERSION=${OVERRIDE_SDK_VERSION:-${DEFAULT_SDK_VERSION}}

echo "Publishing Jitsi Meet SDK ${SDK_VERSION} to Maven Local (${REPO_PATH})"
echo ""

pushd "${THIS_DIR}/../"
./gradlew clean
./gradlew assembleRelease
./gradlew publish
popd

echo ""
echo "Done! SDK published to Maven Local."
echo "Update app/build.gradle.kts to use: org.jitsi.react:jitsi-meet-sdk:${SDK_VERSION}"
echo "Then sync Gradle in the main project."
