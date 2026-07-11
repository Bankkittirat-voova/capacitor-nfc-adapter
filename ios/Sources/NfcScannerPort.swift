import Foundation

/// Swift twin of the Kotlin `NfcScannerPort` — the unified cross-platform
/// contract. Both platforms deliver the identical `uid_dec_reversed` string:
/// raw FB5D8229 -> reversed 29825DFB -> "696409595".
public protocol NfcScannerPort: AnyObject {
    var isSupported: Bool { get }
    func setListener(_ listener: NfcScanListener?)
    func start()
    func stop()
}

public protocol NfcScanListener: AnyObject {
    /// Always invoked on the main queue with the `uid_dec_reversed` contract string.
    func onCardScanned(_ uidDecReversed: String)
    func onReaderStateChanged(_ state: ReaderState)
    func onReaderError(_ error: ReaderError)
}

public enum ReaderState {
    case disconnected, initializing, standby, reading, error
}

public struct ReaderError {
    public enum Code {
        case sessionUnavailable   // NFC not available / session could not start
        case partialRead          // tag left the field / malformed identifier
        case canceled             // driver dismissed the system NFC sheet
        case internalError
    }
    public let code: Code
    public let message: String
    public let recoverable: Bool

    public init(code: Code, message: String, recoverable: Bool) {
        self.code = code
        self.message = message
        self.recoverable = recoverable
    }
}

/// Default binding for targets without NFC (e.g. iPad models, simulator).
public final class NoopScannerPort: NfcScannerPort {
    public init() {}
    public var isSupported: Bool { false }
    public func setListener(_ listener: NfcScanListener?) {}
    public func start() {}
    public func stop() {}
}
