#import "AXClientProxy.h"
#import "XCAccessibilityElement.h"
#import "XCUIDevice.h"
#import "XCAXClient_iOS.h"

static id AXClient = nil;

@implementation AXClientProxy

+ (instancetype)sharedClient
{
    static AXClientProxy *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[self alloc] init];
        AXClient = [XCUIDevice.sharedDevice accessibilityInterface];
    });
    return instance;
}

- (NSArray<id<XCAccessibilityElement>> *)activeApplications
{
    return [AXClient activeApplications];
}

- (NSDictionary *)defaultParameters {
    return [AXClient defaultParameters];
}

- (NSTimeInterval)axTimeout
{
    if (![AXClient respondsToSelector:@selector(AXTimeout)]) {
        return 0;
    }
    return [(XCAXClient_iOS *)AXClient AXTimeout];
}

- (BOOL)setAXTimeout:(NSTimeInterval)timeout error:(NSError **)error
{
    if (![AXClient respondsToSelector:@selector(_setAXTimeout:error:)]) {
        if (error) {
            *error = [NSError errorWithDomain:@"maestro.AXClientProxy"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"XCAXClient_iOS does not support _setAXTimeout:error:"}];
        }
        return NO;
    }
    return [(XCAXClient_iOS *)AXClient _setAXTimeout:timeout error:error];
}

@end
