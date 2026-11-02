#import <AppKit/AppKit.h>
#import <jni.h>
#import <dispatch/dispatch.h>

static NSString *palette(NSAppearance *appearance) {
    return [appearance bestMatchFromAppearancesWithNames:@[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]] ?: @"unknown";
}

JNIEXPORT jstring JNICALL Java_AppearanceProbe_inspect(JNIEnv *env, jclass cls) {
    __block NSString *result;
    dispatch_sync(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            NSApplication *app = [NSApplication sharedApplication];
            NSOpenPanel *open = [NSOpenPanel openPanel];
            NSSavePanel *save = [NSSavePanel savePanel];
            NSDictionary *report = @{
                @"explicitApplicationAppearance": app.appearance.name ?: @"inherited",
                @"application": palette(app.effectiveAppearance),
                @"openPanel": palette(open.effectiveAppearance),
                @"savePanel": palette(save.effectiveAppearance)
            };
            NSData *data = [NSJSONSerialization dataWithJSONObject:report options:0 error:nil];
            result = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        }
    });
    return (*env)->NewStringUTF(env, result.UTF8String);
}

JNIEXPORT void JNICALL Java_AppearanceProbe_showPanel(JNIEnv *env, jclass cls, jstring folder) {
    const char *raw = (*env)->GetStringUTFChars(env, folder, NULL);
    NSString *path = [NSString stringWithUTF8String:raw];
    (*env)->ReleaseStringUTFChars(env, folder, raw);
    dispatch_sync(dispatch_get_main_queue(), ^{
        NSOpenPanel *panel = [NSOpenPanel openPanel];
        panel.directoryURL = [NSURL fileURLWithPath:path];
        panel.message = @"Synthetic file picker: native appearance qualification";
        // The modal loop must service these timers while the enclosing main-queue block is active.
        NSTimer *visible = [NSTimer timerWithTimeInterval:1 repeats:NO block:^(NSTimer *timer) {
            puts(panel.visible ? "PANEL_VISIBLE" : "PANEL_NOT_VISIBLE");
            fflush(stdout);
        }];
        NSTimer *cancel = [NSTimer timerWithTimeInterval:8 repeats:NO block:^(NSTimer *timer) {
            [panel cancel:nil];
        }];
        [[NSRunLoop mainRunLoop] addTimer:visible forMode:NSModalPanelRunLoopMode];
        [[NSRunLoop mainRunLoop] addTimer:cancel forMode:NSModalPanelRunLoopMode];
        [panel runModal];
        [visible invalidate];
        [cancel invalidate];
    });
}
