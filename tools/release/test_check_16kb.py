import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import check_16kb  # noqa: E402

PT_LOAD, PT_GNU_RELRO = 1, 0x6474E552
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_16kb.py")


def elf64(segments):
    """A minimal little-endian ELF64 shared object with the given (p_type, p_vaddr, p_memsz, p_align) headers."""
    phoff, phentsize = 64, 56
    header = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
    header += struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, phoff, 0, 0, 64, phentsize, len(segments), 0, 0, 0)
    body = b"".join(struct.pack("<IIQQQQQQ", t, 4, 0, v, v, m, m, a) for t, v, m, a in segments)
    return header + body


# Known-good and known-bad fixtures (spec §9): synthesized so the repo carries no vendor binaries.
GOOD = elf64([(PT_LOAD, 0, 0x5000, 0x4000), (PT_LOAD, 0x8000, 0x2000, 0x4000), (PT_GNU_RELRO, 0x8000, 0x4000, 1)])
BAD_LOAD = elf64([(PT_LOAD, 0, 0x5000, 0x1000)])
RELRO_ONLY = elf64([(PT_LOAD, 0, 0x5000, 0x4000), (PT_GNU_RELRO, 0x8000, 0x1a00, 1)])


def write_apk(path, entries):
    """entries: (name, data, aligned). Stored entries; aligned ones get a zipalign-style extra field (0xD935) padding."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for name, data, aligned in entries:
            info = zipfile.ZipInfo(name)
            if aligned:
                start = z.fp.tell() + 30 + len(name.encode())
                pad = (-start) % 0x4000
                if pad < 4:
                    pad += 0x4000
                info.extra = struct.pack("<HH", 0xD935, pad - 4) + bytes(pad - 4)
            z.writestr(info, data)


def run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


class Check16kbTest(unittest.TestCase):
    def test_good_library_passes(self):
        self.assertEqual(("PASS", ""), check_16kb.check_elf(GOOD))

    def test_4k_load_alignment_fails(self):
        status, detail = check_16kb.check_elf(BAD_LOAD)
        self.assertEqual("FAIL", status)
        self.assertIn("p_align", detail)

    def test_unaligned_relro_end_is_only_a_warning(self):
        """Spec rev 4: Play doesn't enforce the RELRO end (graphics-path and the Rokid prebuilts fail it)."""
        status, detail = check_16kb.check_elf(RELRO_ONLY)
        self.assertEqual("WARN", status)
        self.assertIn("RELRO", detail)

    def test_32bit_is_skipped_and_garbage_fails(self):
        self.assertEqual("SKIP", check_16kb.check_elf(b"\x7fELF" + bytes([1]) + bytes(59))[0])
        self.assertEqual("FAIL", check_16kb.check_elf(b"not an elf")[0])
        self.assertEqual("FAIL", check_16kb.check_elf(GOOD[:80])[0], "truncated headers")

    def test_exit_codes_load_fails_relro_warns(self):
        with tempfile.TemporaryDirectory() as d:
            warn, bad = os.path.join(d, "warn.aab"), os.path.join(d, "bad.aab")
            with zipfile.ZipFile(warn, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr("base/lib/arm64-v8a/libgood.so", GOOD)
                z.writestr("base/lib/arm64-v8a/librelro.so", RELRO_ONLY)
            with zipfile.ZipFile(bad, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr("base/lib/arm64-v8a/libbad.so", BAD_LOAD)
            ok = run(warn)
            self.assertEqual(0, ok.returncode, ok.stdout)
            self.assertIn("WARN  " + warn + "!base/lib/arm64-v8a/librelro.so", ok.stdout)
            self.assertIn("2 libraries, 0 failing, 1 warnings", ok.stdout)
            ko = run(warn, bad)
            self.assertEqual(1, ko.returncode)
            self.assertIn("FAIL  " + bad + "!base/lib/arm64-v8a/libbad.so", ko.stdout)
            self.assertEqual(2, run().returncode)

    def test_uncompressed_apk_libraries_must_be_16k_zip_aligned(self):
        with tempfile.TemporaryDirectory() as d:
            aligned, unaligned = os.path.join(d, "aligned.apk"), os.path.join(d, "unaligned.apk")
            write_apk(aligned, [("classes.dex", b"dex", False), ("lib/arm64-v8a/libgood.so", GOOD, True)])
            write_apk(unaligned, [("classes.dex", b"dex", False), ("lib/arm64-v8a/libgood.so", GOOD, False)])
            self.assertEqual(0, run(aligned).returncode, run(aligned).stdout)
            r = run(unaligned)
            self.assertEqual(1, r.returncode, r.stdout)
            self.assertIn("zip data offset", r.stdout)

    def test_compressed_apk_libraries_need_no_zip_alignment(self):
        with tempfile.TemporaryDirectory() as d:
            legacy = os.path.join(d, "legacy.apk")
            with zipfile.ZipFile(legacy, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr("lib/arm64-v8a/libgood.so", GOOD)  # extracted at install: only the ELF rule applies
            self.assertEqual(0, run(legacy).returncode)

    def test_directory_argument_scans_its_archives(self):
        with tempfile.TemporaryDirectory() as d:
            with zipfile.ZipFile(os.path.join(d, "app-release.aab"), "w") as z:
                z.writestr("base/lib/arm64-v8a/libbad.so", BAD_LOAD)
            with open(os.path.join(d, "output-metadata.json"), "w") as f:
                f.write("{}")
            r = run(d)
            self.assertEqual(1, r.returncode, r.stdout)
            self.assertIn("libbad.so", r.stdout)

    def test_missing_or_empty_artifact_path_is_an_error_not_a_pass(self):
        """Review focus 5: a wrong output path must never pass the hard gate with '0 libraries'."""
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(2, run(d).returncode, "empty directory")
            self.assertEqual(2, run(os.path.join(d, "nope.aab")).returncode, "missing file")


if __name__ == "__main__":
    unittest.main()
