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

    /// Inspects and activates a guest `.app` bundle in-process, resolving its principal class or executable entry.
    @objc public func activateGuestBundle(_ appPath: String) -> String {
        let plistPath = (appPath as NSString).appendingPathComponent("Info.plist")
        let dict = NSDictionary(contentsOfFile: plistPath)
        let bundleId = (dict?["CFBundleIdentifier"] as? String) ?? "guest.bundle"
        let principal = (dict?["UIPrincipalClass"] as? String)
            ?? (dict?["NSPrincipalClass"] as? String)
            ?? (dict?["CFBundleExecutable"] as? String)
            ?? "main"
        if let bundle = Bundle(path: appPath) {
            _ = bundle.principalClass
        }
        return "\(bundleId):\(principal)"
    }
}

@_cdecl("ca_guest_activate_bundle")
public func ca_guest_activate_bundle(
    _ appPathPtr: UnsafePointer<CChar>?,
    _ outBuf: UnsafeMutablePointer<CChar>?,
    _ outBufLen: Int32
) -> Int32 {
    guard let appPathPtr = appPathPtr else { return -1 }
    let appPath = String(cString: appPathPtr)
    let resolved = GuestAppLoader.shared.activateGuestBundle(appPath)
    if let outBuf = outBuf, outBufLen > 0 {
        let utf8 = Array(resolved.utf8CString)
        let copyCount = min(utf8.count, Int(outBufLen) - 1)
        for i in 0..<copyCount {
            outBuf[i] = utf8[i]
        }
        outBuf[max(0, copyCount)] = 0
    }
    return 0
}
