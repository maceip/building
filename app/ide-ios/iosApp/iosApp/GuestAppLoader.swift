import Foundation
import UIKit
import MachO
import Darwin

/// Spike: load a patched guest Mach-O (MH_DYLIB after MachOGuestPatcher) in-process
/// and jump to its entry — LiveContainer-style, research only.
@objc public final class GuestAppLoader: NSObject {
    @objc public static let shared = GuestAppLoader()

    private var guestHandle: UnsafeMutableRawPointer?

    /// Unpack an IPA (Payload/*.app) into [documents]/guests/<name>/ and return the
    /// guest executable path ready for patch + dlopen.
    @objc public func unpackIpa(_ ipaPath: String, into containerRoot: String) throws -> String {
        let fm = FileManager.default
        try fm.createDirectory(atPath: containerRoot, withIntermediateDirectories: true)
        // Minimal unzip via Process if ditto/unzip exists; caller may pre-expand.
        let dest = (containerRoot as NSString).appendingPathComponent("guest.app")
        if !fm.fileExists(atPath: dest) {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
            proc.arguments = ["-o", ipaPath, "-d", containerRoot]
            try proc.run()
            proc.waitUntilExit()
        }
        let payload = (containerRoot as NSString).appendingPathComponent("Payload")
        let apps = (try? fm.contentsOfDirectory(atPath: payload)) ?? []
        guard let appName = apps.first(where: { $0.hasSuffix(".app") }) else {
            throw NSError(domain: "GuestApp", code: 1, userInfo: [NSLocalizedDescriptionKey: "No Payload/*.app in IPA"])
        }
        let appDir = (payload as NSString).appendingPathComponent(appName)
        guard let infoPlist = NSDictionary(contentsOfFile: (appDir as NSString).appendingPathComponent("Info.plist")),
              let exeName = infoPlist["CFBundleExecutable"] as? String else {
            throw NSError(domain: "GuestApp", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing CFBundleExecutable"])
        }
        return (appDir as NSString).appendingPathComponent(exeName)
    }

    /// Patch + dlopen + jump to guest entry. [patchedPath] is the output of MachOGuestPatcher.
    @objc public func launchPatched(_ patchedPath: String) throws -> Bool {
        guard let handle = dlopen(patchedPath, RTLD_NOW | RTLD_LOCAL) else {
            let err = String(cString: dlerror())
            throw NSError(domain: "GuestApp", code: 3, userInfo: [NSLocalizedDescriptionKey: "dlopen failed: \(err)"])
        }
        guestHandle = handle
        // LiveContainer finds the entry via the Mach-O header and jumps. For the spike
        // we resolve a conventional `_main` / `UIApplicationMain` trampoline if present.
        if let entry = dlsym(handle, "guest_entry_point") {
            typealias Entry = @convention(c) () -> Void
            let fn = unsafeBitCast(entry, to: Entry.self)
            fn()
            return true
        }
        // Fallback: treat as loaded; host still owns UI until entry hook exists.
        return true
    }
}
