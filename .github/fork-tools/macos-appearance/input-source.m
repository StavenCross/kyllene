#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#import <ApplicationServices/ApplicationServices.h>
#import <dispatch/dispatch.h>
#import <jni.h>

static NSString *sourceID(TISInputSourceRef source) {
    return source ? (__bridge NSString *)TISGetInputSourceProperty(source, kTISPropertyInputSourceID) : @"none";
}

static TISInputSourceRef sourceNamed(NSString *name) {
    NSDictionary *filter = @{(__bridge NSString *)kTISPropertyInputSourceID: name};
    CFArrayRef found = TISCreateInputSourceList((__bridge CFDictionaryRef)filter, true);
    if (!found) return NULL;
    TISInputSourceRef source = CFArrayGetCount(found) ? (TISInputSourceRef)CFRetain(CFArrayGetValueAtIndex(found, 0)) : NULL;
    CFRelease(found);
    return source;
}

NSDictionary *InputSnapshot(void) {
    TISInputSourceRef current = TISCopyCurrentKeyboardInputSource();
    if (!current) return @{@"errors": @[@"No current keyboard input source"]};
    NSString *selected = sourceID(current);
    NSMutableDictionary *states = [NSMutableDictionary dictionary];
    NSMutableArray *errors = [NSMutableArray array];
    for (NSString *name in [NSSet setWithArray:@[selected, @"com.apple.keylayout.US", @"com.apple.keylayout.Bulgarian"]]) {
        TISInputSourceRef source = sourceNamed(name);
        if (!source) { [errors addObject:[@"Missing input source: " stringByAppendingString:name]]; continue; }
        CFBooleanRef enabled = TISGetInputSourceProperty(source, kTISPropertyInputSourceIsEnabled);
        if (enabled) states[name] = @(CFBooleanGetValue(enabled));
        else [errors addObject:[@"Missing enabled state: " stringByAppendingString:name]];
        CFRelease(source);
    }
    NSDictionary *result = @{@"selected": selected, @"sources": states, @"errors": errors};
    CFRelease(current);
    return result;
}

NSDictionary *InputPrepare(NSDictionary *snapshot) {
    NSMutableArray *errors = [NSMutableArray array];
    if (!snapshot[@"selected"] || [snapshot[@"errors"] count]) return @{@"errors": @[@"Invalid original state; no changes made"]};
    for (NSString *name in @[@"com.apple.keylayout.Bulgarian", @"com.apple.keylayout.US"]) {
        TISInputSourceRef source = sourceNamed(name);
        if (!source) { [errors addObject:[@"Missing input source: " stringByAppendingString:name]]; continue; }
        CFBooleanRef enabled = TISGetInputSourceProperty(source, kTISPropertyInputSourceIsEnabled);
        OSStatus status = enabled && CFBooleanGetValue(enabled) ? noErr : TISEnableInputSource(source);
        if (status != noErr) [errors addObject:[NSString stringWithFormat:@"Prepare %@: %d", name, (int)status]];
        CFRelease(source);
    }
    NSDictionary *actual = InputSnapshot();
    [errors addObjectsFromArray:actual[@"errors"]];
    return @{@"errors": errors, @"actual": actual};
}

NSDictionary *InputRestore(NSDictionary *snapshot) {
    NSMutableArray *errors = [NSMutableArray array];
    NSString *selected = snapshot[@"selected"];
    if (!selected || [snapshot[@"errors"] count]) return @{@"errors": @[@"Invalid restoration snapshot"]};
    TISInputSourceRef original = sourceNamed(selected);
    if (original) {
        CFBooleanRef enabled = TISGetInputSourceProperty(original, kTISPropertyInputSourceIsEnabled);
        OSStatus status = enabled && CFBooleanGetValue(enabled) ? noErr : TISEnableInputSource(original);
        if (status == noErr) status = TISSelectInputSource(original);
        if (status != noErr) [errors addObject:[NSString stringWithFormat:@"Restore selection: %d", (int)status]];
        CFRelease(original);
    } else [errors addObject:@"Original source no longer exists"];
    NSDictionary *states = snapshot[@"sources"];
    for (NSString *name in states) {
        TISInputSourceRef source = sourceNamed(name);
        if (!source) { [errors addObject:[@"Cannot restore missing source: " stringByAppendingString:name]]; continue; }
        CFBooleanRef enabled = TISGetInputSourceProperty(source, kTISPropertyInputSourceIsEnabled);
        if (!enabled) [errors addObject:[@"Cannot read enabled state: " stringByAppendingString:name]];
        else if (CFBooleanGetValue(enabled) != [states[name] boolValue]) {
            OSStatus status = [states[name] boolValue] ? TISEnableInputSource(source) : TISDisableInputSource(source);
            if (status != noErr) [errors addObject:[NSString stringWithFormat:@"Restore %@: %d", name, (int)status]];
        }
        CFRelease(source);
    }
    NSDictionary *actual = InputSnapshot();
    [errors addObjectsFromArray:actual[@"errors"]];
    if (![actual[@"selected"] isEqualToString:selected] || ![actual[@"sources"] isEqual:states]) [errors addObject:@"Restored keyboard state differs from snapshot"];
    return @{@"errors": errors, @"actual": actual};
}

JNIEXPORT jstring JNICALL Java_InputSource_command(JNIEnv *env, jclass cls, jstring action) {
    const char *raw = (*env)->GetStringUTFChars(env, action, NULL);
    NSString *operation = [NSString stringWithUTF8String:raw];
    (*env)->ReleaseStringUTFChars(env, action, raw);
    __block NSDictionary *report;
    dispatch_sync(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            if ([operation isEqualToString:@"activate"]) [NSApp activateIgnoringOtherApps:YES];
            NSMutableDictionary *result = [NSMutableDictionary dictionary];
            result[@"postEvents"] = @(CGPreflightPostEventAccess());
            result[@"accessibility"] = @(AXIsProcessTrusted());
            result[@"screenCapture"] = @(CGPreflightScreenCaptureAccess());
            result[@"active"] = @(NSApp.active);
            result[@"keyWindow"] = NSApp.keyWindow.title ?: @"none";
            NSMutableArray *errors = [NSMutableArray array];
            if ([operation isEqualToString:@"bulgarian"] || [operation isEqualToString:@"pair"]) {
                if (!NSApp.active || !NSApp.keyWindow) [errors addObject:@"Own application is not active"];
                else {
                    NSArray *names = [operation isEqualToString:@"pair"] ? @[@"com.apple.keylayout.Bulgarian", @"com.apple.keylayout.US"] : @[@"com.apple.keylayout.Bulgarian"];
                    for (NSString *name in names) {
                        TISInputSourceRef source = sourceNamed(name);
                        OSStatus status = source ? TISSelectInputSource(source) : -1;
                        if (status != noErr) [errors addObject:[NSString stringWithFormat:@"Select %@: %d", name, (int)status]];
                        if (source) CFRelease(source);
                    }
                }
            }
            TISInputSourceRef selected = TISCopyCurrentKeyboardInputSource();
            result[@"selectedAfter"] = sourceID(selected);
            if (!selected) [errors addObject:@"No selected input source"];
            if (selected) CFRelease(selected);
            result[@"errors"] = errors;
            report = result;
        }
    });
    NSData *data = [NSJSONSerialization dataWithJSONObject:report options:0 error:nil];
    NSString *json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    return (*env)->NewStringUTF(env, json.UTF8String);
}
