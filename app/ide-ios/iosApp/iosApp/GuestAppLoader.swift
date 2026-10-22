import Foundation
import UIKit
import MachO
import Darwin

/// Load a patched guest Mach-O (MH_DYLIB after MachOGuestPatcher) in-process
/// and jump to its entry — LiveContainer-style, pure in-process (no external Process).
@objc public final class GuestAppLoader: NSObject {
    @objc public static let shared = GuestAppLoader()

    private var guestHandle: UnsafeMutableRawPointer?

    /// Locate the unpacked guest executable inside [containerRoot]/Payload/*.app (extracted in-process
    /// by Kotlin's `IosZipExtractor.extractArchive`) ready for patch + dlopen.
    @objc public func unpackIpa(_ ipaPath: String, into containerRoot: String) throws -> String {
        let fm = FileManager.default
        try fm.createDirectory(atPath: containerRoot, withIntermediateDirectories: true)
        let payload = (containerRoot as NSString).appendingPathComponent("Payload")
        let apps = (try? fm.contentsOfDirectory(atPath: payload)) ?? []
        guard let appName = apps.first(where: { $0.hasSuffix(".app") }) else {
            throw NSError(domain: "GuestApp", code: 1, userInfo: [NSLocalizedDescriptionKey: "No Payload/*.app in \(containerRoot)"])
        }
        let appDir = (payload as NSString).appendingPathComponent(appName)
        guard let infoPlist = NSDictionary(contentsOfFile: (appDir as NSString).appendingPathComponent("Info.plist")),
              let exeName = infoPlist["CFBundleExecutable"] as? String else {
            throw NSError(domain: "GuestApp", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing CFBundleExecutable"])
        }
        return (appDir as NSString).appendingPathComponent(exeName)
    }

    /// Patch + dlopen + jump to guest entry. [patchedPath] is the output of MachOGuestPatcher.
    @objc public func launchPatched(_ patchedPath: String) throws {
        guard let handle = dlopen(patchedPath, RTLD_NOW | RTLD_LOCAL) else {
            let err = String(cString: dlerror())
            throw NSError(domain: "GuestApp", code: 3, userInfo: [NSLocalizedDescriptionKey: "dlopen failed: \(err)"])
        }
        guestHandle = handle
        if let entry = dlsym(handle, "guest_entry_point") {
            typealias Entry = @convention(c) () -> Void
            let fn = unsafeBitCast(entry, to: Entry.self)
            fn()
        }
    }
}
