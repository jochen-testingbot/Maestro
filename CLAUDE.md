# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Maestro is a UI testing framework for mobile (Android/iOS) and web applications. It uses YAML-based test flows that are platform-agnostic.

## Build Commands

```bash
# Build and test
./gradlew test                           # Run all unit tests
./gradlew integrationTest                # Integration tests (requires Android emulator)
./gradlew detekt                         # Code quality check (auto-corrects issues)

# Build CLI
./gradlew :maestro-cli:installDist       # Build CLI to ./maestro-cli/build/install/maestro/bin/maestro
./gradlew :maestro-cli:distZip           # Create distribution ZIP

# Build platform-specific components
./gradlew :maestro-android:assemble      # Build Android APKs
./maestro-ios-xctest-runner/build-maestro-ios-runner.sh  # Build iOS runner

# Run single test
./gradlew :module-name:test --tests "TestClassName.testMethodName"
```

## Architecture

The codebase follows a layered architecture with clear separation of concerns:

```
YAML Flow → YamlCommandReader → MaestroCommand → Orchestra → Maestro → Driver
```

### Key Modules

- **maestro-cli**: Entry point, CLI commands (`App.kt`)
- **maestro-orchestra**: Command execution engine (`Orchestra.kt` - main 54KB file that translates commands to API calls)
- **maestro-orchestra-models**: Command definitions (`Commands.kt`), element selectors, config models
- **maestro-client**: Device control API (`Maestro.kt` - platform-agnostic), `Driver` interface
- **maestro-android/ios/web**: Platform-specific driver implementations

### Core Classes

- `Orchestra.kt` (maestro-orchestra): Main execution engine, handles YAML parsing, flow execution, AI integration
- `Maestro.kt` (maestro-client): Target-agnostic device control API (tap, swipe, launchApp, etc.)
- `Driver.kt` (maestro-client): Interface for platform-specific implementations
- `Commands.kt` (maestro-orchestra-models): All command data classes

## Development Constraints

1. **Java 8 compatibility required** - Use `-Xjdk-release=1.8` compiler flag
2. **Commands must be JSON-serializable** - No sealed classes for command types (cloud compatibility)
3. **No bash script execution** from commands (Maestro Cloud sandbox constraint)
4. **Use FakeDriver for tests**, not mocks
5. **Target-agnostic code**: Core logic should not have platform-specific conditionals

## Adding New Commands

1. Define command in `Commands.kt` (maestro-orchestra-models), implement `Command` interface
2. Add field to `MaestroCommand` class
3. Add mapping in `YamlFluentCommand` for YAML parsing
4. Handle execution in `Orchestra.kt`
5. Add methods to `Maestro.kt` and `Driver.kt` if new device interaction needed
6. Add integration test

## Testing

- Unit tests: `./gradlew test`
- Integration tests: `./gradlew integrationTest` (requires Android emulator, Java 17)
- E2E tests: Located in `/e2e/` directory, require real devices/emulators
- Use `FakeDriver` instead of mocks for driver testing

## Debug Logs

- CLI logs: `~/.maestro/tests/*/maestro.log`
- iOS XCTest logs: `~/Library/Logs/maestro/xctest_runner_logs`

## Key Dependencies

- Kotlin 1.8.22, Gradle with Kotlin DSL
- gRPC 1.50.2 for inter-process communication
- Jackson for JSON/YAML parsing
- GraalVM JS 22.0.0 for script evaluation
- Selenium 4.26.0 for web automation
