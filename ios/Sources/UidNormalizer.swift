import Foundation

/// THE OUTPUT CONTRACT — `uid_dec_reversed`: the decimal representation of the
/// reversed raw UID byte array. Identical to the Kotlin implementation
/// (src/main/kotlin/.../core/UidNormalizer.kt); both are pinned by
/// shared-test-vectors/uid_vectors.json.
///
///   raw bytes (MSB-first, CoreNFC `tag.identifier`):  FB 5D 82 29
///   reversed:                                          29 82 5D FB
///   delivered to onCardScanned():                      "696409595"
///
/// No zero padding. Valid ISO 14443 UID lengths only (4 / 7 / 10 bytes).
public enum UidNormalizer {

    private static let validLengths: Set<Int> = [4, 7, 10]

    public static func decReversed(_ data: Data) -> String? {
        decReversed([UInt8](data))
    }

    public static func decReversed(_ raw: [UInt8]) -> String? {
        guard validLengths.contains(raw.count) else { return nil }
        return decimalString(bigEndianBytes: Array(raw.reversed()))
    }

    /// Arbitrary-precision base-256 -> base-10 conversion. Needed because
    /// 10-byte (triple-size) UIDs are 80 bits and overflow UInt64.
    /// Classic digit-wise multiply-accumulate over little-endian decimal digits.
    static func decimalString(bigEndianBytes bytes: [UInt8]) -> String {
        var digits: [Int] = [0]                    // little-endian decimal digits
        for byte in bytes {
            var carry = Int(byte)
            for i in 0..<digits.count {
                let v = digits[i] * 256 + carry
                digits[i] = v % 10
                carry = v / 10
            }
            while carry > 0 {
                digits.append(carry % 10)
                carry /= 10
            }
        }
        return String(digits.reversed().map { Character(String($0)) })
    }
}
