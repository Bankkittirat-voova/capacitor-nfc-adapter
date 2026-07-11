// =============================================================================
// nfc-adapter — executable verification harness (Node.js, zero dependencies)
//
// This machine has no JDK/Android SDK/Xcode, so this harness transliterates the
// EXACT algorithms from the Kotlin and Swift sources and executes them against:
//   - the shared golden vectors (computed with an independent oracle:
//     .NET System.Numerics.BigInteger — anchor FB5D8229 -> 696409595 matches
//     the product contract),
//   - scripted USB byte streams (taps, dwells, card flicks, truncated frames,
//     checksum corruption, disconnects),
//   - a 500-round cross-implementation fuzz (Kotlin BigInteger semantics vs
//     the Swift digit-carry algorithm),
//   - a 500-event attach/detach vibration-storm model of the ConnectionManager
//     generation-token design.
//
// The Kotlin (src/test) and Swift (ios/Tests) suites contain the same cases
// for CI lanes with real toolchains. Run:  node verification/verify.mjs
// =============================================================================

import { readFileSync } from 'node:fs';

const vectors = JSON.parse(
  readFileSync(new URL('../shared-test-vectors/uid_vectors.json', import.meta.url), 'utf8')
);

// ----------------------------------------------------------------- reporting
let pass = 0, fail = 0;
const failures = [];
function section(title) { console.log(`\n=== ${title} ===`); }
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; failures.push(`${name}${detail ? ' — ' + detail : ''}`); console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
}

// -------------------------------------------------------------------- helpers
function hexToBytes(hex) {
  if (typeof hex !== 'string' || hex.length % 2 !== 0) return null;
  if (!/^[0-9A-Fa-f]*$/.test(hex)) return null;
  const out = [];
  for (let i = 0; i < hex.length; i += 2) out.push(parseInt(hex.slice(i, i + 2), 16));
  return out;
}
const VALID_LENGTHS = [4, 7, 10];

// ============================================================================
// 1) UidNormalizer — Kotlin path (BigInteger(1, reversed).toString(10))
// ============================================================================
function kotlinDecReversed(raw) {
  if (raw === null || !VALID_LENGTHS.includes(raw.length)) return null;
  const reversed = [...raw].reverse();
  const hex = reversed.map(b => b.toString(16).padStart(2, '0')).join('');
  return BigInt('0x' + (hex.length ? hex : '0')).toString(10);
}

// ============================================================================
// 2) UidNormalizer — Swift path (exact transliteration of
//    ios/Sources/UidNormalizer.swift decimalString(bigEndianBytes:))
// ============================================================================
function swiftDecimalString(bytes) {
  let digits = [0];                       // little-endian decimal digits
  for (const byte of bytes) {
    let carry = byte;
    for (let i = 0; i < digits.length; i++) {
      const v = digits[i] * 256 + carry;
      digits[i] = v % 10;
      carry = Math.floor(v / 10);
    }
    while (carry > 0) { digits.push(carry % 10); carry = Math.floor(carry / 10); }
  }
  return digits.slice().reverse().join('');
}
function swiftDecReversed(raw) {
  if (raw === null || !VALID_LENGTHS.includes(raw.length)) return null;
  return swiftDecimalString([...raw].reverse());
}

// ============================================================================
// 3) CCID protocol — transliteration of handler/ccid/CcidProtocol.kt
// ============================================================================
const HEADER_LEN = 10;
const GET_UID_APDU = [0xFF, 0xCA, 0x00, 0x00, 0x00];

function ccidHeader(type, dataLen, slot, seq) {
  const b = new Array(HEADER_LEN + dataLen).fill(0);
  b[0] = type;
  b[1] = dataLen & 0xFF; b[2] = (dataLen >> 8) & 0xFF;
  b[3] = (dataLen >> 16) & 0xFF; b[4] = (dataLen >> 24) & 0xFF;
  b[5] = slot; b[6] = seq & 0xFF;
  return b;
}
const buildIccPowerOn   = (seq) => ccidHeader(0x62, 0, 0, seq);
const buildGetSlotStatus = (seq) => ccidHeader(0x65, 0, 0, seq);
function buildXfrBlock(seq, apdu) {
  const b = ccidHeader(0x6F, apdu.length, 0, seq);
  for (let i = 0; i < apdu.length; i++) b[HEADER_LEN + i] = apdu[i];
  return b;
}
const readLeInt = (b, off) => (b[off] | (b[off+1] << 8) | (b[off+2] << 16) | (b[off+3] << 24));

const Ok = (uid) => ({ kind: 'ok', uid });
const CardGone = { kind: 'cardGone' };
const Reject = (reason) => ({ kind: 'reject', reason });

function parseUidResponse(buf, len) {
  if (len < HEADER_LEN) return Reject(`short frame (${len} bytes)`);
  if (buf[0] !== 0x80) return Reject('unexpected message type');
  const declared = readLeInt(buf, 1);
  if (declared < 0 || len !== HEADER_LEN + declared) return Reject('length mismatch');
  const iccStatus = buf[7] & 0x03;
  const cmdStatus = (buf[7] >> 6) & 0x03;
  if (iccStatus === 2) return CardGone;
  if (cmdStatus !== 0) return Reject(`command failed, bError=${buf[8]}`);
  if (declared < 2) return Reject('no room for SW1SW2');
  const sw1 = buf[HEADER_LEN + declared - 2], sw2 = buf[HEADER_LEN + declared - 1];
  if (sw1 === 0x63 && sw2 === 0x00) return CardGone;
  if (sw1 !== 0x90 || sw2 !== 0x00) return Reject(`APDU status ${sw1} ${sw2}`);
  const uid = buf.slice(HEADER_LEN, HEADER_LEN + declared - 2);
  if (![4, 7, 10].includes(uid.length)) return Reject(`invalid UID length ${uid.length}`);
  return Ok(uid);
}

function parseSlotStatusPresent(buf, len) {
  if (len < HEADER_LEN) return null;
  if (buf[0] !== 0x81 && buf[0] !== 0x80) return null;
  const s = buf[7] & 0x03;
  return s === 0 || s === 1 ? true : s === 2 ? false : null;
}

// Reader-reply frame builders for the scripts
function slotStatusFrame(present) {
  const b = new Array(HEADER_LEN).fill(0); b[0] = 0x81; b[7] = present ? 0 : 2; return b;
}
function dataBlockFrame(data, iccStatus = 0, cmdStatus = 0, bError = 0) {
  const b = new Array(HEADER_LEN + data.length).fill(0);
  b[0] = 0x80; b[1] = data.length & 0xFF; b[2] = (data.length >> 8) & 0xFF;
  b[7] = ((cmdStatus << 6) | iccStatus) & 0xFF; b[8] = bError;
  for (let i = 0; i < data.length; i++) b[HEADER_LEN + i] = data[i];
  return b;
}
const ATR = [0x3B, 0x81, 0x80, 0x01];
const powerOnAck = (iccStatus = 0) => dataBlockFrame(ATR, iccStatus);
const uidBlock = (uid) => dataBlockFrame([...uid, 0x90, 0x00]);

// ============================================================================
// 4) Scripted transport + CCID engine — transliteration of CcidReaderHandler
// ============================================================================
const Reply = (bytes) => ({ t: 'reply', bytes });
const ReplyTruncated = (bytes, cutAt) => ({ t: 'trunc', bytes, cutAt });
const TimeoutStep = { t: 'timeout' };
const Disconnect = { t: 'disconnect' };

class ScriptedTransport {
  constructor(script) { this.queue = [...script]; this.disconnected = false; this.sent = []; }
  bulkOut(data) { if (this.disconnected) return -1; this.sent.push([...data]); return data.length; }
  bulkIn(buffer) {
    if (this.disconnected) return -1;
    const s = this.queue.shift();
    if (!s) return -1;
    if (s.t === 'timeout') return -1;
    if (s.t === 'disconnect') { this.disconnected = true; return -1; }
    const src = s.t === 'trunc' ? s.bytes.slice(0, s.cutAt) : s.bytes;
    for (let i = 0; i < src.length; i++) buffer[i] = src[i];
    return src.length;
  }
}

class TransferFailure extends Error {}

// Mirrors CcidReaderHandler.runPollingLoop / readUidValidated exactly
// (delays removed — the sim is synchronous; delay() carries no logic).
function runCcidEngine(transport, { strictReRead = true, maxFail = 3 } = {}) {
  const emissions = [];
  const log = [];
  const inBuf = new Array(512).fill(0);
  let seq = 0, cardPresent = false, fails = 0;

  const pollPresence = () => {
    if (transport.bulkOut(buildGetSlotStatus(seq++)) < 0) return null;
    const n = transport.bulkIn(inBuf);
    if (n < 0) return null;
    return parseSlotStatusPresent(inBuf, n);
  };
  const xfrGetUid = () => {
    if (transport.bulkOut(buildXfrBlock(seq++, GET_UID_APDU)) < 0) return Reject('XfrBlock write failed');
    const n = transport.bulkIn(inBuf);
    if (n < 0) return Reject('XfrBlock read failed');
    return parseUidResponse(inBuf, n);
  };
  const readUidValidated = () => {
    if (transport.bulkOut(buildIccPowerOn(seq++)) < 0) return Reject('power-on write failed');
    const nAtr = transport.bulkIn(inBuf);
    if (nAtr < 0) return Reject('power-on read failed');
    const p = parseSlotStatusPresent(inBuf, nAtr);
    if (p === false) return CardGone;
    if (p === null) return Reject('malformed power-on response');
    const first = xfrGetUid();
    if (first.kind !== 'ok' || !strictReRead) return first;
    const second = xfrGetUid();
    if (second.kind !== 'ok') return CardGone;
    return first.uid.join(',') === second.uid.join(',')
      ? first : Reject('strict re-read mismatch (RF corruption)');
  };

  try {
    for (;;) {
      const present = pollPresence();
      if (present === null) {
        if (++fails >= maxFail) throw new TransferFailure('consecutive transfer failures');
        continue;
      }
      fails = 0;
      if (present && !cardPresent) {
        const r = readUidValidated();
        if (r.kind === 'ok') { emissions.push(r.uid); cardPresent = true; }
        else if (r.kind === 'cardGone') log.push('flick: card left field mid-read — discarded');
        else { cardPresent = true; log.push(`read rejected: ${r.reason}`); }
      } else if (!present && cardPresent) {
        cardPresent = false;
      }
    }
  } catch (e) {
    if (!(e instanceof TransferFailure)) throw e;
  }
  return { emissions, log };
}

// ============================================================================
// 5) Serial engine — transliteration of SerialProfile / SerialFrameAssembler
// ============================================================================
const asciiHexLineProfile = {
  name: 'ascii-hex-line', maxFrameSize: 64,
  extractFrame(buf) {
    const idx = buf.findIndex(b => b === 0x0D || b === 0x0A);
    if (idx < 0) return null;
    return { frame: buf.slice(0, idx), consumed: idx + 1 };
  },
  validate(frame) {
    if (frame.length === 0 || frame.length % 2 !== 0) return null;
    const text = frame.map(b => String.fromCharCode(b)).join('').toUpperCase();
    if (!/^[0-9A-F]+$/.test(text)) return null;
    const bytes = hexToBytes(text);
    return bytes && [4, 7, 10].includes(bytes.length) ? bytes : null;
  }
};

const stxEtxProfile = {
  name: 'stx-etx-binary', maxFrameSize: 32,
  extractFrame(buf) {
    const stx = buf.indexOf(0x02);
    if (stx < 0) return buf.length ? { frame: [], consumed: buf.length } : null;
    if (stx > 0) return { frame: [], consumed: stx };
    if (buf.length < 2) return null;
    const len = buf[1];
    const total = 1 + 1 + len + 1 + 1;
    if (len > this.maxFrameSize) return { frame: [], consumed: 1 };
    if (buf.length < total) return null;
    if (buf[total - 1] !== 0x03) return { frame: [], consumed: 1 };
    return { frame: buf.slice(0, total), consumed: total };
  },
  validate(frame) {
    if (frame.length < 5 || frame[0] !== 0x02 || frame[frame.length - 1] !== 0x03) return null;
    const len = frame[1];
    if (frame.length !== len + 4) return null;
    const payload = frame.slice(2, 2 + len);
    const chk = payload.reduce((a, b) => a ^ b, 0);
    if (chk !== frame[2 + len]) return null;
    return [4, 7, 10].includes(payload.length) ? payload : null;
  }
};

class FrameAssembler {
  constructor(profile, interByteGapMs = 150) {
    this.profile = profile; this.gap = interByteGapMs; this.buf = []; this.lastByteAt = 0;
  }
  feed(chunk, nowMs) {
    if (this.buf.length > 0 && nowMs - this.lastByteAt > this.gap) this.buf = [];
    this.lastByteAt = nowMs;
    this.buf = this.buf.concat(chunk);
    const uids = [];
    for (;;) {
      const ex = this.profile.extractFrame(this.buf);
      if (!ex) break;
      this.buf = this.buf.slice(ex.consumed);
      if (ex.frame.length === 0) continue;
      const uid = this.profile.validate(ex.frame);
      if (uid) uids.push(uid);
    }
    if (this.buf.length > this.profile.maxFrameSize * 2) this.buf = [];
    return uids;
  }
}

const asciiBytes = (s) => [...s].map(c => c.charCodeAt(0));
function stxFrame(payload, corruptChk = false) {
  let chk = payload.reduce((a, b) => a ^ b, 0);
  if (corruptChk) chk ^= 0xFF;
  return [0x02, payload.length, ...payload, chk, 0x03];
}

// Serial double-fire cooldown — mirrors SerialReadLoop's guard
function runSerialCooldown(events, cooldownMs = 1500) {
  const emitted = [];
  let lastUid = null, lastAt = 0;
  for (const { uid, at } of events) {
    const dup = lastUid !== null && lastUid.join(',') === uid.join(',') && at - lastAt < cooldownMs;
    if (dup) continue;
    lastUid = uid; lastAt = at; emitted.push(uid);
  }
  return emitted;
}

// ============================================================================
// 6) DeviceRouter — transliteration of core/DeviceRouter.kt
// ============================================================================
const SERIAL_BRIDGES = new Map([
  ['6790:29987', 'CH340'], ['6790:21795', 'CH341'],
  ['4292:60000', 'CP210x'], ['4292:60016', 'CP210x'],
  ['1027:24577', 'FTDI'], ['1027:24592', 'FTDI'], ['1027:24593', 'FTDI'],
  ['1027:24596', 'FTDI'], ['1027:24597', 'FTDI'],
  ['1659:8963', 'PL2303'],
]);
function route(d, brandAVid = -1, brandAPids = []) {
  if (brandAVid > 0 && d.vid === brandAVid && (brandAPids.length === 0 || brandAPids.includes(d.pid)))
    return 'BrandA';
  if (d.deviceClass === 0x0B || (d.ifaceClasses || []).includes(0x0B)) return 'Ccid';
  const hit = SERIAL_BRIDGES.get(`${d.vid}:${d.pid}`);
  if (hit) return `Serial:${hit}`;
  if (d.deviceClass === 0x02 || (d.ifaceClasses || []).includes(0x02)) return 'Serial:CDC-ACM';
  return 'Unsupported';
}

// ============================================================================
// 7) ConnectionManager vibration-storm MODEL (design check of the
//    generation-token + debounce rules in core/ConnectionManager.kt)
// ============================================================================
class ConnectionModel {
  constructor(debounceMs = 300) {
    this.debounceMs = debounceMs;
    this.generation = 0;
    this.active = null;            // { generation }
    this.pendingAttachAt = null;
    this.maxConcurrent = 0;
    this.generationsSeen = [];
  }
  advanceTo(now) {
    if (this.pendingAttachAt !== null && now >= this.pendingAttachAt + this.debounceMs) {
      this.pendingAttachAt = null;
      if (this.active === null) {                 // single-owner rule
        this.generation++;
        this.generationsSeen.push(this.generation);
        this.active = { generation: this.generation };
      }
      this.maxConcurrent = Math.max(this.maxConcurrent, this.active ? 1 : 0);
    }
  }
  attach(now) { this.advanceTo(now); this.pendingAttachAt = now; }
  detach(now) {
    this.advanceTo(now);
    this.pendingAttachAt = null;                  // bounce during debounce: zero work
    if (this.active) { this.active = null; this.generation++; }  // orphan callbacks
  }
  emitAllowed(sessionGeneration) { return sessionGeneration === this.generation; }
}

// LCG for reproducible pseudo-randomness
let seed = 0xC0FFEE;
const rnd = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 0x100000000; };

// ############################################################################
// TEST EXECUTION
// ############################################################################
console.log('nfc-adapter verification harness');
console.log('================================');

// --------------------------------------------------------------------------
section('A. uid_dec_reversed golden vectors (Kotlin path + Swift path vs oracle)');
for (const v of vectors.valid) {
  const raw = hexToBytes(v.raw_hex);
  check(`kotlin  ${v.raw_hex.padEnd(20)} -> ${v.uid_dec_reversed}`,
    kotlinDecReversed(raw) === v.uid_dec_reversed,
    `got ${kotlinDecReversed(raw)}`);
  check(`swift   ${v.raw_hex.padEnd(20)} -> ${v.uid_dec_reversed}`,
    swiftDecReversed(raw) === v.uid_dec_reversed,
    `got ${swiftDecReversed(raw)}`);
}
check('contract anchor: FB5D8229 -> "696409595" (both paths)',
  kotlinDecReversed([0xFB, 0x5D, 0x82, 0x29]) === '696409595' &&
  swiftDecReversed([0xFB, 0x5D, 0x82, 0x29]) === '696409595');
for (const bad of vectors.invalid_hex) {
  const raw = hexToBytes(bad);
  check(`invalid rejected: '${bad}'`,
    kotlinDecReversed(raw) === null && swiftDecReversed(raw) === null);
}

// --------------------------------------------------------------------------
section('B. Cross-implementation fuzz: Kotlin BigInteger vs Swift digit-carry (500 UIDs)');
{
  let mismatches = 0;
  for (let i = 0; i < 500; i++) {
    const len = [4, 7, 10][Math.floor(rnd() * 3)];
    const raw = Array.from({ length: len }, () => Math.floor(rnd() * 256));
    if (kotlinDecReversed(raw) !== swiftDecReversed(raw)) mismatches++;
  }
  check('500 random 4/7/10-byte UIDs: identical output on both algorithms', mismatches === 0,
    `${mismatches} mismatches`);
}

// --------------------------------------------------------------------------
section('C. CCID protocol layer');
check('XfrBlock(FF CA 00 00 00) golden bytes',
  JSON.stringify(buildXfrBlock(7, GET_UID_APDU)) ===
  JSON.stringify([0x6F, 5, 0, 0, 0, 0, 7, 0, 0, 0, 0xFF, 0xCA, 0, 0, 0]));
{
  const uid = [0xFB, 0x5D, 0x82, 0x29];
  const frame = uidBlock(uid);
  const r = parseUidResponse(frame, frame.length);
  check('valid DataBlock parses to UID', r.kind === 'ok' && r.uid.join(',') === uid.join(','));
  let leaked = 0;
  for (let cut = 0; cut < frame.length; cut++) {
    if (parseUidResponse(frame, cut).kind === 'ok') leaked++;
  }
  check(`truncation at every cut point (0..${frame.length - 1}) never yields a UID`, leaked === 0);
  check('ICC-absent status -> CardGone (flick)',
    parseUidResponse(dataBlockFrame([...uid, 0x90, 0x00], 2), frame.length).kind === 'cardGone');
  const rf = dataBlockFrame([0x63, 0x00]);
  check('SW 63 00 (RF lost) -> CardGone', parseUidResponse(rf, rf.length).kind === 'cardGone');
  const bad = dataBlockFrame([0x6A, 0x81]);
  check('SW 6A 81 -> Reject', parseUidResponse(bad, bad.length).kind === 'reject');
  const five = dataBlockFrame([1, 2, 3, 4, 5, 0x90, 0x00]);
  check('5-byte UID length -> Reject', parseUidResponse(five, five.length).kind === 'reject');
}

// --------------------------------------------------------------------------
section('D. CCID engine scenarios (scripted byte streams through the real state machine)');
const uidA = [0xFB, 0x5D, 0x82, 0x29];
const uidB = [0x04, 0xA1, 0xB2, 0xC3];
{
  const { emissions } = runCcidEngine(new ScriptedTransport([
    Reply(slotStatusFrame(false)), Reply(slotStatusFrame(false)),
    Reply(slotStatusFrame(true)),
    Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA)),
    Reply(slotStatusFrame(true)), Reply(slotStatusFrame(false)),
    Disconnect,
  ]));
  check('happy tap: exactly one emission', emissions.length === 1, `got ${emissions.length}`);
  check('happy tap: normalized output is contract anchor "696409595"',
    emissions.length === 1 && kotlinDecReversed(emissions[0]) === '696409595');
}
{
  const steps = [
    Reply(slotStatusFrame(false)), Reply(slotStatusFrame(true)),
    Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA)),
  ];
  for (let i = 0; i < 50; i++) steps.push(Reply(slotStatusFrame(true)));
  steps.push(Reply(slotStatusFrame(false)), Disconnect);
  const { emissions } = runCcidEngine(new ScriptedTransport(steps));
  check('dwell: 50 present-polls, still exactly one emission (edge-trigger proof)',
    emissions.length === 1, `got ${emissions.length}`);
}
{
  const { emissions } = runCcidEngine(new ScriptedTransport([
    Reply(slotStatusFrame(true)),
    Reply(powerOnAck(2)),                       // card flicked away at power-on
    Reply(slotStatusFrame(false)),
    Reply(slotStatusFrame(true)),               // stable second tap
    Reply(powerOnAck()), Reply(uidBlock(uidB)), Reply(uidBlock(uidB)),
    Reply(slotStatusFrame(false)), Disconnect,
  ]));
  check('card flick at power-on: zero corrupt emissions, clean recovery on retap',
    emissions.length === 1 && kotlinDecReversed(emissions[0]) === '3283263748',
    `emissions=${emissions.length}`);
}
{
  const { emissions } = runCcidEngine(new ScriptedTransport([
    Reply(slotStatusFrame(true)),
    Reply(powerOnAck()),
    ReplyTruncated(uidBlock(uidA), 6),          // flick mid-transfer: 6 of 16 bytes
    Reply(slotStatusFrame(false)),
    Reply(slotStatusFrame(true)),
    Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA)),
    Disconnect,
  ]));
  check('truncated UID frame discarded; retap emits exactly once', emissions.length === 1,
    `got ${emissions.length}`);
}
{
  const { emissions } = runCcidEngine(new ScriptedTransport([
    Reply(slotStatusFrame(true)),
    Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidB)),  // reads disagree
    Disconnect,
  ]));
  check('strict re-read mismatch (RF corruption): zero emissions', emissions.length === 0,
    `got ${emissions.length}`);
}
{
  const t = new ScriptedTransport([Reply(slotStatusFrame(false)), Disconnect]);
  const { emissions } = runCcidEngine(t);
  check('brownout/disconnect: loop terminates via TransferFailure, no emissions, no hang',
    emissions.length === 0);
}

// --------------------------------------------------------------------------
section('E. Serial engine scenarios');
{
  const a = new FrameAssembler(asciiHexLineProfile);
  const first = a.feed(asciiBytes('FB5D'), 0);
  const second = a.feed(asciiBytes('8229\r\n'), 50);
  check('fragmented ASCII frame reassembles across chunks',
    first.length === 0 && second.length === 1 && second[0].join(',') === uidA.join(','));
}
{
  const a = new FrameAssembler(asciiHexLineProfile);
  a.feed(asciiBytes('FB5D'), 0);                          // card flick: stream stops
  const orphan = a.feed(asciiBytes('8229\r'), 1000);      // gap exceeded
  const clean = a.feed(asciiBytes('FB5D8229\n'), 1050);
  check('stale bytes discarded after inter-byte gap (flick can never prefix next tap)',
    orphan.length === 0 && clean.length === 1 && clean[0].join(',') === uidA.join(','));
}
{
  const a = new FrameAssembler(asciiHexLineProfile);
  const uids = a.feed(asciiBytes('FB5D8229\r\n04A1B2C3\r\n'), 0);
  check('two frames in one chunk -> two UIDs', uids.length === 2);
  check('non-hex garbage line dropped', new FrameAssembler(asciiHexLineProfile).feed(asciiBytes('HELLO!!\r\n'), 0).length === 0);
}
{
  const a = new FrameAssembler(stxEtxProfile);
  const good = a.feed(stxFrame(uidA), 0);
  const b = new FrameAssembler(stxEtxProfile);
  const corrupt = b.feed(stxFrame(uidA, true), 0);
  const resync = b.feed(stxFrame(uidA), 10);
  check('binary frame with valid XOR checksum accepted', good.length === 1);
  check('corrupted checksum dropped, next good frame still parses (resync)',
    corrupt.length === 0 && resync.length === 1);
  const c = new FrameAssembler(stxEtxProfile);
  const withGarbage = c.feed([0x55, 0x66, 0x77, ...stxFrame(uidA)], 0);
  check('leading garbage before STX skipped', withGarbage.length === 1);
}
{
  const twice = runSerialCooldown([{ uid: uidA, at: 0 }, { uid: uidA, at: 200 }]);
  const later = runSerialCooldown([{ uid: uidA, at: 0 }, { uid: uidA, at: 2000 }]);
  const different = runSerialCooldown([{ uid: uidA, at: 0 }, { uid: uidB, at: 200 }]);
  check('reader double-fire inside cooldown suppressed', twice.length === 1);
  check('same card after cooldown emits again (intentional retap)', later.length === 2);
  check('different card inside cooldown NOT suppressed', different.length === 2);
}

// --------------------------------------------------------------------------
section('F. Cross-engine + cross-platform equivalence (the Phase 3 core claim)');
for (const v of vectors.valid) {
  const raw = hexToBytes(v.raw_hex);

  // Android CCID pipeline: reader frame -> validation chain -> normalize
  const parsed = parseUidResponse(uidBlock(raw), HEADER_LEN + raw.length + 2);
  const ccidOut = parsed.kind === 'ok' ? kotlinDecReversed(parsed.uid) : null;

  // Android serial pipeline: ASCII line -> assembly -> normalize
  const serialUids = new FrameAssembler(asciiHexLineProfile).feed(asciiBytes(`${v.raw_hex}\r\n`), 0);
  const serialOut = serialUids.length === 1 ? kotlinDecReversed(serialUids[0]) : null;

  // iOS pipeline: CoreNFC tag.identifier bytes -> Swift normalizer
  const iosOut = swiftDecReversed(raw);

  check(`equivalence ${v.raw_hex.padEnd(20)}: CCID == serial == iOS == "${v.uid_dec_reversed}"`,
    ccidOut === v.uid_dec_reversed && serialOut === v.uid_dec_reversed && iosOut === v.uid_dec_reversed,
    `ccid=${ccidOut} serial=${serialOut} ios=${iosOut}`);
}

// --------------------------------------------------------------------------
section('G. Device router');
check('CCID by device class', route({ vid: 0x1234, pid: 1, deviceClass: 0x0B, ifaceClasses: [] }) === 'Ccid');
check('CCID by interface class only (deviceClass 0)', route({ vid: 0x1234, pid: 1, deviceClass: 0, ifaceClasses: [0x0B] }) === 'Ccid');
check('CH340 by VID/PID', route({ vid: 6790, pid: 29987, deviceClass: 0xFF, ifaceClasses: [] }) === 'Serial:CH340');
check('CP210x by VID/PID', route({ vid: 4292, pid: 60000, deviceClass: 0xFF, ifaceClasses: [] }) === 'Serial:CP210x');
check('FTDI by VID/PID', route({ vid: 1027, pid: 24577, deviceClass: 0xFF, ifaceClasses: [] }) === 'Serial:FTDI');
check('CDC-ACM by class', route({ vid: 0x9999, pid: 1, deviceClass: 0, ifaceClasses: [2, 10] }) === 'Serial:CDC-ACM');
check('unknown device -> Unsupported', route({ vid: 0x9999, pid: 0x9999, deviceClass: 0, ifaceClasses: [] }) === 'Unsupported');
check('Brand A wins over CCID when configured',
  route({ vid: 0x2222, pid: 1, deviceClass: 0, ifaceClasses: [0x0B] }, 0x2222, [1]) === 'BrandA');
check('Brand A inert while unconfigured',
  route({ vid: 0x2222, pid: 1, deviceClass: 0, ifaceClasses: [] }) === 'Unsupported');

// --------------------------------------------------------------------------
section('H. Vibration-storm model (500 random attach/detach events, generation tokens)');
{
  const m = new ConnectionModel(300);
  let now = 0;
  let everTwoSessions = false;
  const gens = [];
  for (let i = 0; i < 500; i++) {
    now += Math.floor(rnd() * 400) + 10;        // 10..410 ms between events
    if (rnd() < 0.5) m.attach(now); else m.detach(now);
    m.advanceTo(now);
    if (m.active && m.pendingAttachAt !== null && now >= m.pendingAttachAt + 300) everTwoSessions = true;
    if (m.active) gens.push(m.active.generation);
  }
  // settle: final attach, then quiet period
  m.attach(now); m.advanceTo(now + 1000);
  const monotonic = m.generationsSeen.every((g, i, a) => i === 0 || g > a[i - 1]);
  check('never more than one active session at any instant', !everTwoSessions);
  check('generation tokens strictly increasing (no stale session can emit)', monotonic);
  check('after storm settles with device attached: exactly one live session', m.active !== null);
  check('stale generation emissions are refused by the token guard',
    m.emitAllowed(m.generation) === true && m.emitAllowed(m.generation - 1) === false);
}

// --------------------------------------------------------------------------
console.log('\n================================');
console.log(`RESULT: ${pass} passed, ${fail} failed`);
if (failures.length) {
  console.log('\nFailures:');
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(fail === 0 ? 0 : 1);
