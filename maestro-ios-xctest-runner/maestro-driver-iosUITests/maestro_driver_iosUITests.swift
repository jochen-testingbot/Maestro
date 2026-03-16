import XCTest
import FlyingFox
import os

final class maestro_driver_iosUITests: XCTestCase {
   
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: "maestro_driver_iosUITests"
    )

    private static var swizzledOutIdle = false

    override func setUpWithError() throws {
        // XCTest internals sometimes use XCTAssert* instead of exceptions.
        // Setting `continueAfterFailure` so that the xctest runner does not stop
        // when an XCTest internal error happes (eg: when using .allElementsBoundByIndex
        // on a ReactNative app)
        continueAfterFailure = true

        // Register a UI interruption monitor to automatically handle system permission dialogs.
        // This fires whenever a system alert/sheet blocks an XCUITest interaction.
        addUIInterruptionMonitor(withDescription: "System Permission Dialog") { element in
            return SystemPermissionHelper.handleInterruption(element)
        }
        NSLog("[maestro_driver_iosUITests] Registered UI interruption monitor for system permission dialogs")
    }

    override class func setUp() {
        logger.trace("setUp")
    }

    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        maestro_driver_iosUITests.logger.info("Will start HTTP server")
        try await server.start()
    }

    override class func tearDown() {
        logger.trace("tearDown")
    }
}
