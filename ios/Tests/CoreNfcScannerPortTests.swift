import XCTest
@testable import NfcAdapter

/// Exercises the iOS engine's pure seam `handleDiscoveredIdentifier` with
/// mocked tag identifier payloads (valid and corrupted) — no NFC hardware,
/// no NFCTagReaderSession needed.
final class CoreNfcScannerPortTests: XCTestCase {

    final class RecordingListener: NfcScanListener {
        var scans: [String] = []
        var errors: [ReaderError] = []
        var onEvent: (() -> Void)?

        func onCardScanned(_ uidDecReversed: String) {
            scans.append(uidDecReversed); onEvent?()
        }
        func onReaderStateChanged(_ state: ReaderState) {}
        func onReaderError(_ error: ReaderError) {
            errors.append(error); onEvent?()
        }
    }

    private func makePort() -> (CoreNfcScannerPort, RecordingListener) {
        let port = CoreNfcScannerPort()
        let listener = RecordingListener()
        port.setListener(listener)
        return (port, listener)
    }

    func testValidMifareIdentifierEmitsContractString() {
        let (port, listener) = makePort()
        let exp = expectation(description: "scan delivered")
        listener.onEvent = { exp.fulfill() }

        // Simulated NFCMiFareTag.identifier for raw UID FB5D8229.
        port.handleDiscoveredIdentifier(Data([0xFB, 0x5D, 0x82, 0x29]))

        wait(for: [exp], timeout: 2)
        XCTAssertEqual(listener.scans, ["696409595"])   // contract anchor vector
        XCTAssertTrue(listener.errors.isEmpty)
    }

    func testSevenByteIdentifier() {
        let (port, listener) = makePort()
        let exp = expectation(description: "scan delivered")
        listener.onEvent = { exp.fulfill() }

        port.handleDiscoveredIdentifier(Data([0x04, 0xE1, 0x51, 0x2A, 0x3B, 0x6C, 0x80]))

        wait(for: [exp], timeout: 2)
        XCTAssertEqual(listener.scans, ["36147798387843332"])   // oracle-computed vector
    }

    func testCorruptedIdentifierReportsPartialReadNotScan() {
        let (port, listener) = makePort()
        let exp = expectation(description: "error delivered")
        listener.onEvent = { exp.fulfill() }

        // Card flicked away: truncated 3-byte identifier.
        port.handleDiscoveredIdentifier(Data([0xFB, 0x5D, 0x82]))

        wait(for: [exp], timeout: 2)
        XCTAssertTrue(listener.scans.isEmpty, "corrupted payload must never emit a UID")
        XCTAssertEqual(listener.errors.first?.code, .partialRead)
    }

    func testNilIdentifierReportsPartialRead() {
        let (port, listener) = makePort()
        let exp = expectation(description: "error delivered")
        listener.onEvent = { exp.fulfill() }

        // connect() failed / non-ISO14443 tag.
        port.handleDiscoveredIdentifier(nil)

        wait(for: [exp], timeout: 2)
        XCTAssertTrue(listener.scans.isEmpty)
        XCTAssertEqual(listener.errors.first?.code, .partialRead)
    }
}
