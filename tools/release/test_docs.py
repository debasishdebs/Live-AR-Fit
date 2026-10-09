import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = ["README.md", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md", "docs/install-glasses.md", "docs/privacy-policy.md",
        "docs/play/data-safety.md", "docs/play/health-apps-declaration.md", "docs/play/foreground-services.md",
        "docs/play/permissions.md", "docs/play/store-listing.md", "docs/play/content-rating.md"]


def read(path):
    with open(os.path.join(ROOT, path)) as f:
        return f.read()


class DocsTest(unittest.TestCase):
    def test_every_document_exists(self):
        for d in DOCS + ["LICENSE"]:
            self.assertTrue(os.path.isfile(os.path.join(ROOT, d)), d)

    def test_licence_is_apache_2(self):
        self.assertIn("Apache License", read("LICENSE"))
        self.assertIn("Version 2.0, January 2004", read("LICENSE"))

    def test_privacy_policy_covers_the_spec_list(self):
        p = read("docs/privacy-policy.md")
        for needed in ("heart rate", "location", "audio", "on-device", "title, artist, playback state and up-next queue",
                       "sent to your paired watch and glasses", "Google's cloud, encrypted", "no server of its own",
                       "MapTiler", "IP address", "Clear history", "uninstall", "no account", "no analytics",
                       "d.kanhar@gmail.com"):
            self.assertIn(needed, p, needed)

    def test_data_safety_matches_the_relay_and_encryption(self):
        d = read("docs/play/data-safety.md")
        for needed in ("encrypted in transit", "Google's cloud", "Data safety", "shared"):
            self.assertIn(needed, d, needed)

    def test_readme_has_the_keystore_and_secret_setup(self):
        r = read("README.md")
        for needed in ("keytool -genkeypair", "keystore.properties", "LIVEAR_KEYSTORE", "LIVEAR_KEY_ALIAS",
                       "LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD", "LIVEAR_TILES_KEY", "LIVEAR_KEYSTORE_BASE64",
                       "LIVEAR_UPLOAD_CERT_SHA256", "unsigned-diagnostic", "docs/install-glasses.md"):
            self.assertIn(needed, r, needed)

    def test_no_rokid_in_the_app_name(self):
        listing = read("docs/play/store-listing.md")
        self.assertRegex(listing, r"(?m)^App name: Live AR Fit$")

    def test_public_repo_hygiene_no_serials_adb_names_or_macs(self):
        """Spec §11.5: the new public documents carry placeholders (<phone-serial>), never real identifiers."""
        mac = re.compile(r"\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")
        mdns = re.compile(r"\badb-[A-Za-z0-9]{6,}-[A-Za-z0-9]{4,}\b")  # wireless-adb service names: adb-<serial>-<suffix>
        for path in DOCS:
            text = read(path)
            self.assertIsNone(mac.search(text), "MAC address in " + path)
            self.assertIsNone(mdns.search(text), "adb mDNS name in " + path)


if __name__ == "__main__":
    unittest.main()
