#import <XCTest/XCTest.h>
#import "XCAccessibilityElement.h"

@interface AXClientProxy : NSObject

+ (instancetype)sharedClient;

- (NSArray<id<XCAccessibilityElement>> *)activeApplications;

- (NSDictionary *)defaultParameters;

/// Current timeout (seconds) XCTest applies to each accessibility request, such as a snapshot.
- (NSTimeInterval)axTimeout;

/// Sets the timeout XCTest applies to each accessibility request. A snapshot that runs
/// past it fails with an error instead of blocking the runner's main thread.
- (BOOL)setAXTimeout:(NSTimeInterval)timeout error:(NSError **)error;

@end
