import CoreNFC
import Foundation

/// iOS engine: reads card UIDs with the built-in NFC antenna via CoreNFC
/// (`NFCTagReaderSession`, ISO 14443 polling). Students tap their card against
/// the back/top of the iPhone.
///
/// PLATFORM REALITY (differs from Android by Apple design — do not fight it):
///  - Scanning always presents the system NFC sheet; there is NO silent
///    background standby on iOS.
///  - A session is limited to ~60 seconds. With `autoRestart` (default on),
///    this port re-begins a session whenever the system times one out while
///    `start()` is in effect, re-presenting the sheet.
///  - Within one live session, `restartPolling()` keeps the reader armed, so a
///    whole line of students can tap consecutively without touching the phone.
public final class CoreNfcScannerPort: NSObject, NfcScannerPort {

    public struct Config {
        public var alertMessage = "Hold the student card near the top of the phone."
        public var autoRestart = true
        public var restartDelaySeconds: TimeInterval = 0.5
        public init() {}
    }

    private let config: Config
    private weak var listener: NfcScanListener?
    private var session: NFCTagReaderSession?
    private var wantScanning = false

    public init(config: Config = Config()) {
        self.config = config
    }

    public var isSupported: Bool { NFCTagReaderSession.readingAvailable }

    public func setListener(_ listener: NfcScanListener?) {
        self.listener = listener
    }

    public func start() {
        guard isSupported else {
            deliverError(ReaderError(
                code: .sessionUnavailable,
                message: "This device cannot scan NFC cards.",
                recoverable: false
            ))
            return
        }
        wantScanning = true
        beginSession()
    }

    public func stop() {
        wantScanning = false
        session?.invalidate()
        session = nil
        deliverState(.disconnected)
    }

    private func beginSession() {
        guard wantScanning, session == nil else { return }
        guard let s = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil) else {
            deliverError(ReaderError(
                code: .sessionUnavailable,
                message: "Could not start the NFC scanner.",
                recoverable: true
            ))
            return
        }
        s.alertMessage = config.alertMessage
        session = s
        deliverState(.initializing)
        s.begin()
    }

    // MARK: - Listener delivery (always main queue)

    private func deliverState(_ state: ReaderState) {
        DispatchQueue.main.async { [weak self] in self?.listener?.onReaderStateChanged(state) }
    }

    private func deliverError(_ error: ReaderError) {
        DispatchQueue.main.async { [weak self] in self?.listener?.onReaderError(error) }
    }

    /// PURE SEAM for unit tests: identifier bytes in, contract string out to the
    /// listener. All validation/normalization for the iOS engine happens here.
    func handleDiscoveredIdentifier(_ identifier: Data?) {
        guard let id = identifier, let uid = UidNormalizer.decReversed(id) else {
            deliverError(ReaderError(
                code: .partialRead,
                message: "Card read was incomplete — tap again.",
                recoverable: true
            ))
            return
        }
        DispatchQueue.main.async { [weak self] in self?.listener?.onCardScanned(uid) }
    }
}

// MARK: - NFCTagReaderSessionDelegate

extension CoreNfcScannerPort: NFCTagReaderSessionDelegate {

    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        deliverState(.standby)
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        self.session = nil
        if let nfcError = error as? NFCReaderError,
           nfcError.code == .readerSessionInvalidationErrorUserCanceled {
            wantScanning = false
            deliverError(ReaderError(code: .canceled, message: "Scanning stopped.", recoverable: true))
            deliverState(.disconnected)
            return
        }
        // System 60 s timeout or transient failure: re-arm while start() is in effect.
        if wantScanning && config.autoRestart {
            DispatchQueue.main.asyncAfter(deadline: .now() + config.restartDelaySeconds) { [weak self] in
                self?.beginSession()
            }
        } else {
            deliverError(ReaderError(
                code: .sessionUnavailable,
                message: "NFC scanning stopped: \(error.localizedDescription)",
                recoverable: true
            ))
            deliverState(.disconnected)
        }
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else {
            session.restartPolling()
            return
        }
        deliverState(.reading)
        session.connect(to: tag) { [weak self] connectError in
            guard let self = self else { return }
            defer {
                // Stay armed for the next student in line within this session.
                session.restartPolling()
                self.deliverState(.standby)
            }
            if connectError != nil {
                // Tag left the field during connect — the iOS card-flick case.
                self.handleDiscoveredIdentifier(nil)
                return
            }
            let identifier: Data?
            switch tag {
            case .miFare(let t):   identifier = t.identifier
            case .iso7816(let t):  identifier = t.identifier
            case .iso15693(let t): identifier = t.identifier
            case .feliCa:          identifier = nil   // not an ISO 14443 school card
            @unknown default:      identifier = nil
            }
            self.handleDiscoveredIdentifier(identifier)
        }
    }
}
