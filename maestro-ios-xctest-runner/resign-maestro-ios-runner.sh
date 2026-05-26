#!/usr/bin/env bash
set -euo pipefail

# Re-signs the pre-built iOS driver bundles with an updated provisioning profile.
#
# Usage:
#   ./maestro-ios-xctest-runner/resign-maestro-ios-runner.sh <mobileprovision> [signing-identity]
#
# Arguments:
#   mobileprovision    Path to .mobileprovision file (e.g. from fastlane match)
#   signing-identity   Optional codesign identity (SHA-1 hash or name).
#                      If omitted, auto-detects from the profile's team ID.
#
# Examples:
#   # Auto-detect identity:
#   ./maestro-ios-xctest-runner/resign-maestro-ios-runner.sh /path/to/realdevices.mobileprovision
#
#   # Explicit identity (use SHA-1 to avoid ambiguity):
#   ./maestro-ios-xctest-runner/resign-maestro-ios-runner.sh /path/to/realdevices.mobileprovision E69D6EA1D51F165C7A3439D8D63CCE6B534FF601

PROFILE="${1:?Usage: $0 <mobileprovision> [signing-identity]}"
IDENTITY="${2:-}"

BUILD_DIR="$HOME/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products/Debug-iphoneos"
RUNNER_APP="$BUILD_DIR/maestro-driver-iosUITests-Runner.app"
HELPER_APP="$BUILD_DIR/maestro-driver-ios.app"

# The fleet runs a mix of iOS versions and a runner only works on the SDK generation it
# was built with (an Xcode-26 build crashes at launch on iOS <= 18 and vice-versa). Set
# DRIVER_VARIANT (e.g. ios18 when built with Xcode 16, ios26 when built with Xcode 26) to
# name the output zip accordingly; the bootstrap downloads the matching one per device.
DRIVER_VARIANT="${DRIVER_VARIANT:-}"
if [ -n "$DRIVER_VARIANT" ]; then
    ZIP_OUTPUT="$HOME/.maestro/maestro-iphoneos-driver-build-${DRIVER_VARIANT}.zip"
else
    ZIP_OUTPUT="$HOME/.maestro/maestro-iphoneos-driver-build.zip"
fi

# --- Validate inputs ---

if [ ! -f "$PROFILE" ]; then
    echo "Error: Provisioning profile not found: $PROFILE"
    exit 1
fi

if [ ! -d "$RUNNER_APP" ]; then
    echo "Error: Runner app not found: $RUNNER_APP"
    echo "Make sure the driver build is extracted to ~/.maestro/maestro-iphoneos-driver-build/"
    exit 1
fi

# --- Extract entitlements from profile ---

TMPDIR_RESIGN=$(mktemp -d)
trap 'rm -rf "$TMPDIR_RESIGN"' EXIT

PROFILE_PLIST="$TMPDIR_RESIGN/profile.plist"
ENTITLEMENTS="$TMPDIR_RESIGN/entitlements.plist"

security cms -D -i "$PROFILE" > "$PROFILE_PLIST"
/usr/libexec/PlistBuddy -x -c 'Print :Entitlements' "$PROFILE_PLIST" > "$ENTITLEMENTS"

TEAM_ID=$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$PROFILE_PLIST")
PROFILE_NAME=$(/usr/libexec/PlistBuddy -c 'Print :Name' "$PROFILE_PLIST")
DEVICE_COUNT=$(/usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$PROFILE_PLIST" 2>/dev/null | grep -c '    ' || echo "all (enterprise)")

echo "Profile:  $PROFILE_NAME"
echo "Team ID:  $TEAM_ID"
echo "Devices:  $DEVICE_COUNT"

# --- Resolve signing identity ---

if [ -z "$IDENTITY" ]; then
    # Find an Apple Development signing identity; use SHA-1 hash to avoid ambiguity
    IDENTITY=$(security find-identity -v -p codesigning | grep "Apple Development" | head -1 | awk '{print $2}')
    if [ -z "$IDENTITY" ]; then
        echo "Error: No Apple Development codesigning identity found"
        echo "Available identities:"
        security find-identity -v -p codesigning
        exit 1
    fi

    IDENTITY_NAME=$(security find-identity -v -p codesigning | grep "$IDENTITY" | head -1 | sed 's/.*"\(.*\)".*/\1/')
    echo "Identity: $IDENTITY_NAME ($IDENTITY)"
fi

# --- Re-sign function ---

resign_app() {
    local app_path="$1"
    local app_name
    app_name=$(basename "$app_path")

    if [ ! -d "$app_path" ]; then
        echo "  Skipping $app_name (not found)"
        return
    fi

    echo ""
    echo "Signing $app_name..."

    # Embed the new profile
    cp "$PROFILE" "$app_path/embedded.mobileprovision"

    # Sign every nested framework/dylib at ANY depth, deepest-first (inside-out).
    # This covers frameworks nested inside PlugIns/*.xctest/Frameworks
    # (e.g. MaestroDriverLib.framework, introduced in v2.6.0) and the helper
    # app's debug dylibs (__preview.dylib, *.debug.dylib) — none of which the
    # old maxdepth-1 logic reached, leaving them unsigned and rejected at launch.
    # The awk prefix is the slash-count (path depth); sort -rn => deepest first.
    find "$app_path" \( -name '*.dylib' -o -name '*.framework' \) \
        | awk '{ print gsub(/\//,"/"), $0 }' | sort -rn | cut -d' ' -f2- \
        | while read -r obj; do
            echo "  Code:      ${obj#"$app_path"/}"
            codesign --force --sign "$IDENTITY" "$obj"
        done

    # Sign nested test bundles / app extensions at any depth (with entitlements)
    find "$app_path" \( -name '*.xctest' -o -name '*.appex' \) \
        | awk '{ print gsub(/\//,"/"), $0 }' | sort -rn | cut -d' ' -f2- \
        | while read -r bundle; do
            echo "  Bundle:    ${bundle#"$app_path"/}"
            codesign --force --sign "$IDENTITY" --entitlements "$ENTITLEMENTS" "$bundle"
        done

    # Sign the app itself last
    codesign --force --sign "$IDENTITY" --entitlements "$ENTITLEMENTS" "$app_path"

    # Verify (strict + recurse into nested code so unsigned nested code fails here)
    if codesign --verify --deep --strict --verbose=2 "$app_path" 2>&1; then
        echo "  Verified OK"
    else
        echo "  ERROR: Verification failed for $app_name"
        codesign --verify --deep --strict --verbose=2 "$app_path" 2>&1
        exit 1
    fi
}

# --- Step 1 & 2: Re-sign both apps ---

resign_app "$RUNNER_APP"
resign_app "$HELPER_APP"

# --- Step 3: Re-zip for distribution ---

echo ""
echo "Creating zip..."
cd "$HOME/.maestro"
# Remove any existing archive first: `zip -r` is additive and would otherwise
# retain stale entries whose source files no longer exist on disk (e.g. an old
# iphoneosNN.N xctestrun from a previous SDK), which the CLI may then pick.
rm -f "$ZIP_OUTPUT"
zip -r -q "$ZIP_OUTPUT" maestro-iphoneos-driver-build/ -x '*.DS_Store'
echo "Zip created: $ZIP_OUTPUT ($(du -h "$ZIP_OUTPUT" | awk '{print $1}'))"

ZIP_BASENAME=$(basename "$ZIP_OUTPUT")
echo ""
echo "Done. Publish to the distribution server so the bootstrap can fetch it per-device:"
echo "  scp $ZIP_OUTPUT user@dist-server:/path/to/maestro/$ZIP_BASENAME   # served at http://192.168.168.16:6003/maestro/$ZIP_BASENAME"
echo ""
echo "(The bootstrap scripts/maestro-ios.sh picks ios18 vs ios26 by the device's iOS version,"
echo " so both maestro-iphoneos-driver-build-ios18.zip and -ios26.zip must exist on the server.)"
