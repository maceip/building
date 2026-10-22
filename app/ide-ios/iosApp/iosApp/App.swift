import SwiftUI
import UIKit
import Foundation
import Darwin
import CodeAssistUi

final class PhysicalDeviceUiAutomationServer {
    static let shared = PhysicalDeviceUiAutomationServer()
    private var started = false
    private let queue = DispatchQueue(label: "dev.ide.ios.ui-automation", qos: .userInitiated, attributes: .concurrent)

    func startIfNeeded(port: UInt16 = 8100) {
        guard !started else { return }
        started = true
        startIpv4Listener(port: port)
        startIpv6Listener(port: port)
    }

    private func startIpv4Listener(port: UInt16) {
        queue.async {
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            guard fd >= 0 else { return }
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = port.bigEndian
            addr.sin_addr = in_addr(s_addr: INADDR_ANY)

            let bindResult = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            guard bindResult == 0, listen(fd, 16) == 0 else {
                close(fd)
                return
            }
            while true {
                var clientAddr = sockaddr_in()
                var clientLen = socklen_t(MemoryLayout<sockaddr_in>.size)
                let clientFd = withUnsafeMutablePointer(to: &clientAddr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        accept(fd, $0, &clientLen)
                    }
                }
                if clientFd >= 0 {
                    self.handleClient(clientFd)
                }
            }
        }
    }

    private func startIpv6Listener(port: UInt16) {
        queue.async {
            let fd = socket(AF_INET6, SOCK_STREAM, 0)
            guard fd >= 0 else { return }
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

            var addr = sockaddr_in6()
            addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            addr.sin6_family = sa_family_t(AF_INET6)
            addr.sin6_port = port.bigEndian
            addr.sin6_addr = in6addr_any

            let bindResult = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
            guard bindResult == 0, listen(fd, 16) == 0 else {
                close(fd)
                return
            }
            while true {
                var clientAddr = sockaddr_in6()
                var clientLen = socklen_t(MemoryLayout<sockaddr_in6>.size)
                let clientFd = withUnsafeMutablePointer(to: &clientAddr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        accept(fd, $0, &clientLen)
                    }
                }
                if clientFd >= 0 {
                    self.handleClient(clientFd)
                }
            }
        }
    }

    private func handleClient(_ clientFd: Int32) {
        defer { close(clientFd) }
        var buffer = [UInt8](repeating: 0, count: 16384)
        let bytesRead = recv(clientFd, &buffer, buffer.count - 1, 0)
        guard bytesRead > 0 else { return }
        let requestStr = String(decoding: buffer[0..<bytesRead], as: UTF8.self)
        let firstLine = requestStr.components(separatedBy: "\r\n").first ?? ""
        let parts = firstLine.components(separatedBy: " ")
        guard parts.count >= 2 else { return }
        let target = parts[1]
        guard let urlComponents = URLComponents(string: "http://localhost" + target) else { return }
        let path = urlComponents.path
        let queryItems = urlComponents.queryItems ?? []
        func param(_ name: String) -> String? {
            queryItems.first(where: { $0.name == name })?.value
        }

        var contentType = "application/json; charset=utf-8"
        var responseBody = "{\"error\":\"not_found\"}"

        if path == "/status" {
            responseBody = "{\"status\":\"ready\",\"device\":\"00008020-001325320A08002E\"}"
        } else if path == "/source" {
            contentType = "application/xml; charset=utf-8"
            responseBody = DispatchQueue.main.sync {
                MainViewControllerKt.dumpLiveUiHierarchyXml()
            }
        } else if path == "/tap" {
            let x = Double(param("x") ?? "0") ?? 0.0
            let y = Double(param("y") ?? "0") ?? 0.0
            let ok = DispatchQueue.main.sync {
                MainViewControllerKt.dispatchScreenTap(xPt: x, yPt: y)
            }
            responseBody = "{\"ok\":\(ok),\"x\":\(x),\"y\":\(y)}"
        } else if path == "/swipe" {
            let x1 = Double(param("x1") ?? "0") ?? 0.0
            let y1 = Double(param("y1") ?? "0") ?? 0.0
            let x2 = Double(param("x2") ?? "0") ?? 0.0
            let y2 = Double(param("y2") ?? "0") ?? 0.0
            let steps = Int32(param("steps") ?? "15") ?? 15
            let ok = DispatchQueue.main.sync {
                MainViewControllerKt.dispatchScreenSwipe(x1Pt: x1, y1Pt: y1, x2Pt: x2, y2Pt: y2, steps: steps)
            }
            responseBody = "{\"ok\":\(ok),\"x1\":\(x1),\"y1\":\(y1),\"x2\":\(x2),\"y2\":\(y2)}"
        } else if path == "/keys" {
            let text = param("text") ?? ""
            let ok = DispatchQueue.main.sync {
                MainViewControllerKt.dispatchFocusedTextInput(text: text)
            }
            responseBody = "{\"ok\":\(ok)}"
        } else if path == "/run" {
            let ok = DispatchQueue.main.sync {
                MainViewControllerKt.triggerRunBuild()
                return true
            }
            responseBody = "{\"ok\":\(ok),\"status\":\"running\"}"
        } else if path == "/run_pointer" {
            let x = Float(param("x") ?? "0.5") ?? 0.5
            let y = Float(param("y") ?? "0.5") ?? 0.5
            let ok = DispatchQueue.main.sync {
                MainViewControllerKt.triggerRunPointerTap(x: x, y: y)
                return true
            }
            responseBody = "{\"ok\":\(ok),\"x\":\(x),\"y\":\(y)}"
        }

        let bodyData = Data(responseBody.utf8)
        let header = "HTTP/1.1 200 OK\r\nContent-Type: \(contentType)\r\nContent-Length: \(bodyData.count)\r\nConnection: close\r\n\r\n"
        var payload = Data(header.utf8)
        payload.append(bodyData)
        payload.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return }
            var sent = 0
            while sent < payload.count {
                let n = send(clientFd, base.advanced(by: sent), payload.count - sent, 0)
                if n <= 0 { break }
                sent += n
            }
        }
    }
}

// The Kotlin side owns everything above this line: `MainViewController()` builds the Compose
// `UIViewController` that renders `CodeAssistApp`. SwiftUI's only job is to put it on screen.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct CodeAssistApp: App {
    init() {
        PhysicalDeviceUiAutomationServer.shared.startIfNeeded(port: 8100)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // The IDE draws its own chrome and reads the safe-area insets itself, as it does on Android.
                .ignoresSafeArea(.all)
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true
                    PhysicalDeviceUiAutomationServer.shared.startIfNeeded(port: 8100)
                }
                // `codeassist://store/<id>` opens that project's page; `codeassist://auth-callback` is a
                // sign-in coming back. Kotlin tells the two apart -- the same code the Android activity
                // runs -- so this only has to hand the URL over.
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}
