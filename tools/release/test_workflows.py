import os
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(name):
    with open(os.path.join(ROOT, ".github", "workflows", name)) as f:
        return f.read()


class WorkflowPolicyTest(unittest.TestCase):
    """Spec §8: what the two workflows must do (string checks; no YAML dependency)."""

    def test_ci_runs_all_unit_tests_and_three_debug_builds_on_jdk17(self):
        ci = read("ci.yml")
        for needed in ("push", "pull_request", "java-version: '17'", "gradle/actions/setup-gradle", 
                       ":phone:assembleDebug", ":watch:assembleDebug", ":glasses:assembleDebug", " test ",
                       "-p buildSrc test", "python3 -m unittest discover -s tools/release"):
            self.assertIn(needed, ci)

    def test_release_fails_without_secrets_and_ships_only_owner_signed(self):
        rel = read("release.yml")
        for needed in ("tags:", "'v*'", "LIVEAR_KEYSTORE_BASE64", "LIVEAR_KEY_ALIAS", "LIVEAR_STORE_PASSWORD",
                       "LIVEAR_KEY_PASSWORD", "LIVEAR_TILES_KEY", "LIVEAR_UPLOAD_CERT_SHA256", "exit 1",
                       "-Plivefit.requireSigning=true", "check_16kb.py", "zipalign", "-c -P 16 -v 4", "body_path",
                       "apksigner", "--print-certs",
                       "sha256sum", "draft: true"):
            self.assertIn(needed, rel)

    def test_release_gates_run_before_the_upload(self):
        rel = read("release.yml")
        self.assertLess(rel.index("check_16kb.py"), rel.index("action-gh-release"))
        self.assertLess(rel.index("--print-certs"), rel.index("action-gh-release"))


if __name__ == "__main__":
    unittest.main()
