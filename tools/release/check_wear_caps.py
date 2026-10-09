#!/usr/bin/env python3
"""Wear capability check (spec §4; final review Important 1). Only Play services reads the `android_wear_capabilities`
array, by name, so the release resource shrinker deletes it unless res/raw/keep.xml keeps it. Without it a release app
advertises no capability, and the sender gate and best-node routing silently fall back to "any connected node".

Usage: check_wear_caps.py <bundle.aab>=<capability> [...]
Reads the aapt2 proto resource table (base/resources.pb in an AAB, or resources.pb in a proto .ap_) and requires
array/android_wear_capabilities to exist and to contain <capability>.
Exit 0 = all present, 1 = missing, 2 = usage or I/O error."""
import sys
import zipfile

ARRAY = "android_wear_capabilities"


def _fields(buf):
    """Yields (field number, wire type, value) for one protobuf message; length-delimited values are bytes."""
    i, n = 0, len(buf)
    while i < n:
        key, i = _varint(buf, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            val, i = _varint(buf, i)
        elif wire == 1:
            val, i = buf[i:i + 8], i + 8
        elif wire == 2:
            size, i = _varint(buf, i)
            val, i = buf[i:i + size], i + size
        elif wire == 5:
            val, i = buf[i:i + 4], i + 4
        else:
            raise ValueError("unsupported protobuf wire type %d" % wire)
        yield num, wire, val


def _varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def _array_entries(table):
    """{entry name: entry bytes} for type "array" in every package of a ResourceTable (Resources.proto field numbers:
    ResourceTable.package=2, Package.type=3, Type.name=2, Type.entry=3, Entry.name=2)."""
    found = {}
    for num, wire, pkg in _fields(table):
        if num != 2 or wire != 2:
            continue
        for pnum, pwire, typ in _fields(pkg):
            if pnum != 3 or pwire != 2:
                continue
            fields = list(_fields(typ))
            name = next((v for f, w, v in fields if f == 2 and w == 2), b"")
            if name != b"array":
                continue
            for f, w, entry in fields:
                if f == 3 and w == 2:
                    ename = next((v for ef, ew, v in _fields(entry) if ef == 2 and ew == 2), b"")
                    found[ename.decode("utf-8", "replace")] = entry
    return found


def read_table(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        for candidate in ("base/resources.pb", "resources.pb"):
            if candidate in names:
                return z.read(candidate)
    raise ValueError("no proto resource table (base/resources.pb) in " + path)


def problems(path, capability):
    entries = _array_entries(read_table(path))
    if ARRAY not in entries:
        return ["%s: array/%s is missing (resource shrinker removed it; see res/raw/keep.xml)" % (path, ARRAY)]
    if capability.encode() not in entries[ARRAY]:
        return ["%s: array/%s does not contain %s" % (path, ARRAY, capability)]
    return []


def main(argv):
    if not argv or any("=" not in a for a in argv):
        print(__doc__, file=sys.stderr)
        return 2
    failures = []
    for arg in argv:
        path, capability = arg.rsplit("=", 1)
        try:
            found = problems(path, capability)
        except (OSError, ValueError, zipfile.BadZipFile, IndexError) as e:
            print("ERROR %s: %s" % (path, e), file=sys.stderr)
            return 2
        failures += found
        print(("FAIL " + found[0]) if found else "PASS %s: array/%s contains %s" % (path, ARRAY, capability))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
