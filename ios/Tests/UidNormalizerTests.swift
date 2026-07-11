import XCTest
@testable import NfcAdapter

/// Pins the Swift normalizer to the shared cross-platform golden vectors
/// (shared-test-vectors/uid_vectors.json — add it to the test bundle's
/// Copy Bundle Resources). The Kotlin suite loads the same file; green on
/// both CI lanes proves byte-identical output across platforms.
final class UidNormalizerTests: XCTestCase {

    struct Vectors: Decodable {
        struct Valid: Decodable {
            let raw_hex: String
            let uid_dec_reversed: String
        }
        let valid: [Valid]
        let invalid_hex: [String]
    }

    private func loadVectors() throws -> Vectors {
        let url = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "uid_vectors", withExtension: "json"),
            "uid_vectors.json missing from test bundle resources"
        )
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }

    private func bytes(fromHex hex: String) -> [UInt8]? {
        guard hex.count % 2 == 0 else { return nil }
        var out: [UInt8] = []
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let b = UInt8(hex[idx..<next], radix: 16) else { return nil }
            out.append(b)
            idx = next
        }
        return out
    }

    func testGoldenVectors() throws {
        let vectors = try loadVectors()
        XCTAssertFalse(vectors.valid.isEmpty)
        for v in vectors.valid {
            let raw = try XCTUnwrap(bytes(fromHex: v.raw_hex), "bad vector hex \(v.raw_hex)")
            XCTAssertEqual(
                UidNormalizer.decReversed(raw), v.uid_dec_reversed,
                "uid_dec_reversed mismatch for raw \(v.raw_hex)"
            )
        }
    }

    func testContractAnchorVector() {
        // The vector fixed in the product contract: FB5D8229 -> "696409595".
        XCTAssertEqual(UidNormalizer.decReversed([0xFB, 0x5D, 0x82, 0x29]), "696409595")
    }

    func testInvalidLengthsRejected() throws {
        let vectors = try loadVectors()
        for hex in vectors.invalid_hex {
            if let raw = bytes(fromHex: hex) {
                XCTAssertNil(UidNormalizer.decReversed(raw), "should reject \(hex)")
            } // unparseable hex strings are inherently rejected upstream
        }
        XCTAssertNil(UidNormalizer.decReversed([]))
        XCTAssertNil(UidNormalizer.decReversed([0x01, 0x02, 0x03]))      // 3 bytes
        XCTAssertNil(UidNormalizer.decReversed([UInt8](repeating: 0, count: 5)))
    }
}
