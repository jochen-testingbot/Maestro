@AGENTS.md

# TestingBot fork (tb-mods)

This is TestingBot's fork of Maestro, rebased on upstream v2.12.1. The fork's main addition is support for physical iOS devices, which upstream does not offer (upstream #3609 rejects connected iPhones; this fork reverts that). Fork versions end in `-tb`.

## iOS Physical Device Driver

The pre-built XCTest runner (`maestro-driver-iosUITests-Runner.app`) is signed with a Development provisioning profile. Every physical test device UDID must be registered in the profile or install fails with `0xe8008012`.

When adding a new device:
1. Register UDID in Apple Developer Portal
2. `fastlane match development` to regenerate profile
3. `./maestro-ios-xctest-runner/resign-maestro-ios-runner.sh /path/to/profile.mobileprovision`
4. Deploy `~/.maestro/maestro-iphoneos-driver-build.zip` to test machines

Key code: `LocalIOSDeviceController.kt` (install/launch via `xcrun devicectl`), `LocalXCTestInstaller.kt` (orchestration), `IOSBuildProductsExtractor.kt` (bundle extraction).

## Debug Logs

- CLI logs: `~/.maestro/tests/*/maestro.log`
- iOS XCTest logs: `~/Library/Logs/maestro/xctest_runner_logs`

