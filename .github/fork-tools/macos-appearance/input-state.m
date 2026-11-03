#import <Foundation/Foundation.h>
extern NSDictionary *InputSnapshot(void);
extern NSDictionary *InputPrepare(NSDictionary *snapshot);
extern NSDictionary *InputRestore(NSDictionary *snapshot);

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc != 3) return 2;
        NSString *command = [NSString stringWithUTF8String:argv[1]];
        NSString *path = [NSString stringWithUTF8String:argv[2]];
        NSDictionary *result;
        if ([command isEqualToString:@"snapshot"]) {
            result = InputSnapshot();
            NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
            if (![data writeToFile:path atomically:YES]) return 1;
        } else {
            NSData *data = [NSData dataWithContentsOfFile:path];
            NSDictionary *snapshot = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
            if (!snapshot) return 1;
            if ([command isEqualToString:@"prepare"]) result = InputPrepare(snapshot);
            else if ([command isEqualToString:@"restore"]) result = InputRestore(snapshot);
            else return 2;
        }
        NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
        puts([[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
        return [result[@"errors"] count] ? 1 : 0;
    }
}
