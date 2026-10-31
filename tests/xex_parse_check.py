#!/usr/bin/env python3
"""Reference check for GameStorage.parseXex (Kotlin): same offsets, same logic.
Builds a synthetic XEX2 header and verifies the title id / disc fields."""
import struct

def parse(b):
    if len(b) < 0x20 or b[:4] != b"XEX2": return None
    count = struct.unpack(">I", b[0x14:0x18])[0]
    if count > 256 or 0x18 + count * 8 > len(b): return None
    for i in range(count):
        key, val = struct.unpack(">II", b[0x18 + i * 8: 0x20 + i * 8])
        if key != 0x00040006: continue
        if val + 24 > len(b): return None
        title = struct.unpack(">I", b[val + 12: val + 16])[0]
        return title, b[val + 18], b[val + 19]
    return None

h = bytearray(0x200); h[:4] = b"XEX2"
struct.pack_into(">I", h, 0x14, 2)
struct.pack_into(">II", h, 0x18, 0x000103FF, 0x1234)
struct.pack_into(">II", h, 0x20, 0x00040006, 0x100)
struct.pack_into(">IIII", h, 0x100, 0, 0, 0, 0x544307D5)
h[0x112], h[0x113] = 1, 1
assert parse(bytes(h)) == (0x544307D5, 1, 1)
assert parse(b"MZ" + bytes(64)) is None
assert parse(bytes(h[:0x1C])) is None
bad = bytearray(h); struct.pack_into(">I", bad, 0x14, 9999); assert parse(bytes(bad)) is None
print("xex_parse_check: OK")
