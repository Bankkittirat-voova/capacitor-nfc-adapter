import Foundation
import Capacitor

/// Capacitor 3 bridge over CoreNfcScannerPort. Registered with the runtime by
/// the CAP_PLUGIN macro in NfcAdapterPlugin.m under the JS name "NfcAdapter".
///
/// Events (notifyListeners):
///   "onCardScanned"        -> { uid }  — the strict uid_dec_reversed contract value
///   "onReaderStateChanged" -> { state }
///   "onReaderError"        -> { code, message, recoverable }
@objc(NfcAdapterPlugin)
public class NfcAdapterPlugin: CAPPlugin, NfcScanListener {

    private var port: NfcScannerPort?

    private func ensurePort() -> NfcScannerPort {
        if let existing = port { return existing }
        // CoreNfcScannerPort self-gates via NFCTagReaderSession.readingAvailable:
        // isSupported returns false and start() reports sessionUnavailable on
        // hardware without NFC, so no separate Noop branch is needed here.
        let created = CoreNfcScannerPort()
        created.setListener(self)
        port = created
        return created
    }

    @objc func startScanning(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.ensurePort().start()
            call.resolve()
        }
    }

    @objc func stopScanning(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.port?.stop()
            call.resolve()
        }
    }

    @objc func isSupported(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(["supported": self.ensurePort().isSupported])
        }
    }

    // ------------------------------------------------ NfcScanListener -> JS
    // CoreNfcScannerPort already delivers these on the main queue.

    public func onCardScanned(_ uidDecReversed: String) {
        notifyListeners("onCardScanned", data: ["uid": uidDecReversed])
    }

    public func onReaderStateChanged(_ state: ReaderState) {
        notifyListeners(
            "onReaderStateChanged",
            data: ["state": String(describing: state).uppercased()]
        )
    }

    public func onReaderError(_ error: ReaderError) {
        notifyListeners("onReaderError", data: [
            "code": String(describing: error.code),
            "message": error.message,
            "recoverable": error.recoverable,
        ])
    }
}
