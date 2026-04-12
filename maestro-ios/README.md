# iOS Device Config

A wrapper around `simctl` and XCTest to communicate with iOS devices.

## Physical Device Setup

### How the iOS driver is installed on physical devices

Maestro uses a pre-built XCTest runner bundle to drive physical iOS devices. The install flow is:

1. **Extract** — Build products are extracted from `~/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products/Debug-iphoneos/`
2. **Install** — `xcrun devicectl device install app --device <udid> maestro-driver-iosUITests-Runner.app`
3. **Launch** — `xcrun devicectl device process launch --device <udid> dev.mobile.maestro-driver-iosUITests.xctrunner`
4. **Connect** — XCTest HTTP client connects on port 22087

Key code: `LocalIOSDeviceController.kt` (install/launch), `LocalXCTestInstaller.kt` (orchestration), `IOSBuildProductsExtractor.kt` (bundle extraction).

### Provisioning profile & code signing

The pre-built runner bundle is signed with a **Development** provisioning profile. This means every physical device must have its UDID registered in the Apple Developer Portal and included in the profile. If a device is not in the profile, install will fail with:

```
ERROR: Unable to Install ... integrity could not be verified
       0xe8008012 (This provisioning profile cannot be installed on this device.)
```

### Adding a new physical device

When you add a new iOS device to the test fleet:

1. Register the device UDID in the Apple Developer Portal
2. Regenerate the provisioning profile (e.g. `fastlane match development`)
3. Re-sign the runner bundle and create a new zip for distribution:

```bash
./maestro-ios-xctest-runner/resign-maestro-ios-runner.sh /path/to/updated.mobileprovision
```

4. Deploy `~/.maestro/maestro-iphoneos-driver-build.zip` to test machines:

```bash
scp ~/.maestro/maestro-iphoneos-driver-build.zip user@host:~/
ssh user@host 'rm -rf ~/.maestro/maestro-iphoneos-driver-build && cd ~/.maestro && unzip ~/maestro-iphoneos-driver-build.zip'
```

The resign script auto-detects the signing identity and re-signs both `maestro-driver-iosUITests-Runner.app` and `maestro-driver-ios.app` (including all embedded frameworks and plugins). You can also pass an explicit signing identity (SHA-1 hash) as a second argument.

### Debugging install failures

- Runner logs: `~/Library/Logs/maestro/xctest_runner_logs/xctest_runner_*.log`
- CLI logs: `~/.maestro/tests/*/maestro.log`
- Test install manually: `xcrun devicectl device install app --device <udid> ~/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products/Debug-iphoneos/maestro-driver-iosUITests-Runner.app`
- Check provisioned devices: `security cms -D -i <app>/embedded.mobileprovision | plutil -p - | grep ProvisionedDevices`
- Verify signing: `codesign --verify --deep -v <app>`

## Prerequisites

### Xcode

Install the latest Xcode (Command Line Tools are not enough, install the full IDE).

### IntelliJ setup

If you are working with this subproject, update your IntelliJ config (Help -> Edit Custom Properties) by including the following lines:

```
# Needed for working with idb.proto definition
idea.max.intellisense.filesize=4000
```

Then restart the IDE.
