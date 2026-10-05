"""Small, bounded NBT editor for level.dat; untouched tag payloads stay byte-for-byte intact.

Minecraft uses modified UTF-8. Keeping names and strings as bytes avoids corrupting
player/mod strings (including NULs and surrogate pairs) during a settings migration.
"""
import gzip
import struct
from pathlib import Path

MAX_BYTES = 64 * 1024 * 1024


def _take(data, offset, size):
    end = offset + size
    if size < 0 or end > len(data):
        raise ValueError('Truncated or invalid NBT payload')
    return end


def _number(data, offset, fmt):
    end = _take(data, offset, struct.calcsize(fmt))
    return struct.unpack(fmt, data[offset:end])[0], end


def _string_end(data, offset):
    size, offset = _number(data, offset, '>H')
    return _take(data, offset, size)


def _end(data, offset, kind, depth=0):
    if depth > 64:
        raise ValueError('NBT nesting is too deep')
    if kind in (1, 2, 3, 4, 5, 6):
        return _take(data, offset, {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}[kind])
    if kind == 8:
        return _string_end(data, offset)
    if kind in (7, 11, 12):
        count, offset = _number(data, offset, '>i')
        return _take(data, offset, count * {7: 1, 11: 4, 12: 8}[kind])
    if kind == 9:
        child, offset = _number(data, offset, '>B')
        count, offset = _number(data, offset, '>i')
        if count < 0 or count > len(data) or (count and not 1 <= child <= 12):
            raise ValueError('Invalid NBT list')
        for _ in range(count):
            offset = _end(data, offset, child, depth + 1)
        return offset
    if kind == 10:
        while True:
            child, offset = _number(data, offset, '>B')
            if child == 0:
                return offset
            offset = _end(data, _string_end(data, offset), child, depth + 1)
    raise ValueError('Unknown NBT tag: ' + str(kind))


class Compound:
    def __init__(self, payload):
        if _end(payload, 0, 10) != len(payload):
            raise ValueError('Trailing NBT data')
        self.fields = {}
        offset = 0
        while payload[offset]:
            kind = payload[offset]
            start = offset
            name_end = _string_end(payload, offset + 1)
            name = payload[offset + 3:name_end]
            offset = _end(payload, name_end, kind)
            if name in self.fields:
                raise ValueError('Duplicate NBT field')
            self.fields[name] = (kind, payload[name_end:offset], payload[start:offset])

    def get(self, name, kind):
        value = self.fields.get(name.encode('ascii'))
        if value is None or value[0] != kind:
            raise ValueError('Missing or invalid NBT field: ' + name)
        return value[1]

    def compound(self, name):
        return Compound(self.get(name, 10))

    def set(self, name, kind, payload):
        if _end(payload, 0, kind) != len(payload):
            raise ValueError('Invalid replacement NBT')
        key = name.encode('ascii')
        raw = bytes([kind]) + struct.pack('>H', len(key)) + key + payload
        self.fields[key] = (kind, payload, raw)

    def encode(self):
        return b''.join(row[2] for row in self.fields.values()) + b'\0'


def read(path):
    with gzip.open(Path(path), 'rb') as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES or not raw or raw[0] != 10:
        raise ValueError('Invalid or oversized level.dat')
    header = _string_end(raw, 1)
    return raw[:header], Compound(raw[header:])


def seed(path):
    _, root = read(path)
    data = root.compound('Data')
    value = (data.compound('WorldGenSettings').get('seed', 4)
             if b'WorldGenSettings' in data.fields else data.get('RandomSeed', 4))
    return struct.unpack('>q', value)[0]
