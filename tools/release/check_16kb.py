#!/usr/bin/env python3
"""16 KB page-size check (spec §3, revision 4). For every 64-bit .so inside the given AAB/APK/.so files (a directory
stands for the .aab/.apk files in it):
  FAIL (hard gate) - a LOAD segment with p_align < 0x4000, or, inside an APK, an uncompressed .so whose zip data
                     offset is not a multiple of 16384 (the zipalign -P 16 rule);
  WARN (reported)  - a PT_GNU_RELRO end (p_vaddr + p_memsz) that is not a multiple of 0x4000 (Play doesn't enforce it);
  SKIP             - 32-bit libraries (no 16 KB devices).
Zip alignment inside an AAB is not checked: bundletool/Play build the installed APKs (Play Console's 16 KB status covers it).
Prints a table. Exit 0 = no FAIL (warnings allowed), 1 = a FAIL, 2 = usage or I/O error (including no artifact)."""
import os
import struct
import sys
import zipfile

PAGE = 0x4000
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def check_elf(data):
    """Returns (status, detail): PASS, WARN (RELRO end only), FAIL (LOAD alignment / not an ELF) or SKIP (32-bit)."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return "FAIL", "not an ELF file"
    if data[4] != 2:
        return "SKIP", "32-bit"
    endian = "<" if data[5] == 1 else ">"
    phoff, = struct.unpack_from(endian + "Q", data, 0x20)
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    errors, warnings = [], []
    loads = 0
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + 56 > len(data):
            return "FAIL", "truncated program headers"
        p_type, _flags, _offset, p_vaddr, _paddr, _filesz, p_memsz, p_align = struct.unpack_from(endian + "IIQQQQQQ", data, off)
        if p_type == PT_LOAD:
            loads += 1
            if p_align < PAGE:
                errors.append("LOAD p_align 0x%x < 0x4000" % p_align)
        elif p_type == PT_GNU_RELRO and (p_vaddr + p_memsz) % PAGE != 0:
            warnings.append("RELRO end 0x%x not 16 KB aligned" % (p_vaddr + p_memsz))
    if loads == 0:
        errors.append("no LOAD segment")
    if errors:
        return "FAIL", "; ".join(errors + warnings)
    return ("WARN", "; ".join(warnings)) if warnings else ("PASS", "")


def zip_data_offset(f, info):
    """Where an entry's data starts: local header (30 bytes) + file name + local extra field."""
    f.seek(info.header_offset)
    header = f.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def libraries(path):
    """(name, bytes, zip problem or None) for a bare .so, or every .so inside an AAB/APK (zip)."""
    if path.endswith(".so"):
        with open(path, "rb") as f:
            yield path, f.read(), None
        return
    is_apk = path.endswith(".apk")
    with zipfile.ZipFile(path) as z, open(path, "rb") as raw:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            if not info.filename.endswith(".so"):
                continue
            problem = None
            if is_apk and info.compress_type == zipfile.ZIP_STORED:
                offset = zip_data_offset(raw, info)
                if offset % PAGE != 0:
                    problem = "zip data offset 0x%x not 16 KB aligned (zipalign -P 16)" % offset
            yield "%s!%s" % (path, info.filename), z.read(info), problem


def artifacts(path):
    """A directory stands for the .aab/.apk files directly inside it; a missing path or an empty directory is an error."""
    if os.path.isdir(path):
        found = sorted(os.path.join(path, n) for n in os.listdir(path) if n.endswith((".aab", ".apk")))
        if not found:
            raise OSError("no .aab or .apk in %s" % path)
        return found
    if not os.path.isfile(path):
        raise OSError("no such file: %s" % path)
    return [path]


def check(name, data, zip_problem):
    status, detail = check_elf(data)
    if zip_problem and status != "SKIP":
        status, detail = "FAIL", "; ".join(d for d in (zip_problem, detail) if d)
    return status, detail


def main(argv):
    if not argv:
        print("usage: check_16kb.py <aab|apk|so|dir>...", file=sys.stderr)
        return 2
    rows = []
    try:
        for p in [a for arg in argv for a in artifacts(arg)]:
            for name, data, zip_problem in libraries(p):
                status, detail = check(name, data, zip_problem)
                rows.append((status, name, detail))
    except (OSError, zipfile.BadZipFile) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    for status, name, detail in rows:
        print("%-4s  %s%s" % (status, name, "  (" + detail + ")" if detail else ""))
    failing = sum(r[0] == "FAIL" for r in rows)
    warned = sum(r[0] == "WARN" for r in rows)
    print("%d libraries, %d failing, %d warnings" % (len(rows), failing, warned))
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
