import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import check_wear_caps  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_wear_caps.py")


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(num, payload):
    """A length-delimited protobuf field."""
    if isinstance(payload, str):
        payload = payload.encode()
    return varint(num << 3 | 2) + varint(len(payload)) + payload


def table(types):
    """A minimal aapt2 ResourceTable: one package holding {type name: {entry name: string payload}}."""
    pkg = field(2, "com.livear.fit")
    for type_name, entries in types.items():
        body = field(2, type_name)
        for entry_name, value in entries.items():
            body += field(3, field(2, entry_name) + field(6, field(2, field(7, field(3, field(1, field(1, value)))))))
        pkg += field(3, body)
    return field(2, pkg)


def write_aab(path, resources):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("base/resources.pb", resources)
        z.writestr("base/manifest/AndroidManifest.xml", b"\0")


class CheckWearCapsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def aab(self, types):
        path = os.path.join(self.dir, "app.aab")
        write_aab(path, table(types))
        return path

    def test_kept_array_with_the_capability_passes(self):
        path = self.aab({"string": {"app_name": "Live AR Fit"}, "array": {"android_wear_capabilities": "livefit_phone"}})
        self.assertEqual([], check_wear_caps.problems(path, "livefit_phone"))

    def test_shrunk_bundle_without_the_array_fails(self):
        """Final review Important 1: the resource shrinker removed the array; only app_name survived."""
        path = self.aab({"string": {"app_name": "Live AR Fit", "x": "android_wear_capabilities"}})
        self.assertTrue(check_wear_caps.problems(path, "livefit_phone"))

    def test_array_with_the_wrong_capability_fails(self):
        path = self.aab({"array": {"android_wear_capabilities": "livefit_watch"}})
        self.assertTrue(check_wear_caps.problems(path, "livefit_phone"))

    def test_cli_exit_codes(self):
        good = self.aab({"array": {"android_wear_capabilities": "livefit_watch"}})
        run = lambda *a: subprocess.run([sys.executable, SCRIPT, *a], capture_output=True).returncode
        self.assertEqual(0, run(good + "=livefit_watch"))
        self.assertEqual(1, run(good + "=livefit_phone"))
        self.assertEqual(2, run(os.path.join(self.dir, "missing.aab") + "=livefit_watch"))
        self.assertEqual(2, run())

    def test_both_apps_keep_the_array_from_the_shrinker(self):
        for app in ("phone", "watch"):
            with open(os.path.join(ROOT, app, "src", "main", "res", "raw", "keep.xml")) as f:
                self.assertIn('tools:keep="@array/android_wear_capabilities"', f.read(), app)


if __name__ == "__main__":
    unittest.main()
