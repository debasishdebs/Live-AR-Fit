import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(name):
    with open(os.path.join(ROOT, ".github", "workflows", name)) as f:
        return f.read()


def steps(workflow):
    """The text of each step, split on the step marker (no YAML dependency)."""
    body = workflow.split("    steps:\n", 1)[1]
    return ("\n" + body).split("\n      - ")[1:]


class WorkflowPolicyTest(unittest.TestCase):
    """Spec §8: what the two workflows must do (string checks; no YAML dependency)."""

    def test_ci_runs_all_unit_tests_and_three_debug_builds_on_jdk17(self):
        ci = read("ci.yml")
        for needed in ("push", "pull_request", "java-version: '17'", "gradle/actions/setup-gradle",
                       ":phone:assembleDebug", ":watch:assembleDebug", ":glasses:assembleDebug", " test ",
                       "-p buildSrc test", "python3 -m unittest discover -s tools/release"):
            self.assertIn(needed, ci)

    def test_ci_token_is_read_only(self):
        self.assertRegex(read("ci.yml"), r"(?m)^permissions:\n  contents: read$")

    def test_release_fails_without_secrets_and_ships_only_owner_signed(self):
        rel = read("release.yml")
        for needed in ("tags:", "'v*'", "LIVEAR_KEYSTORE_BASE64", "LIVEAR_KEY_ALIAS", "LIVEAR_STORE_PASSWORD",
                       "LIVEAR_KEY_PASSWORD", "LIVEAR_TILES_KEY", "LIVEAR_UPLOAD_CERT_SHA256", "exit 1",
                       "-Plivefit.requireSigning=true", "check_16kb.py", "zipalign", "-c -P 16 -v 4", "body_path",
                       "apksigner", "--print-certs", "check_wear_caps.py", "=livefit_phone", "=livefit_watch",
                       "sha256sum", "draft: true"):
            self.assertIn(needed, rel)

    def test_release_gates_run_before_the_uploads(self):
        rel = read("release.yml")
        for gate in ("check_wear_caps.py", "check_16kb.py", "-c -P 16 -v 4", "--print-certs", 'rm -f "$RUNNER_TEMP'):
            for upload in ("actions/upload-artifact", "action-gh-release"):
                self.assertLess(rel.index(gate), rel.index(upload), gate + " before " + upload)

    def test_release_secrets_reach_only_the_secret_check_and_build_steps(self):
        """Final review Important 2: no job-level secret env; third-party actions never see the signing secrets."""
        rel = read("release.yml")
        self.assertNotIn("secrets.", rel.split("    steps:\n", 1)[0], "job-level secret env")
        self.assertNotIn("GITHUB_ENV", rel, "secrets or the keystore path leaked into later steps")
        with_secrets = [s.splitlines()[0] for s in steps(rel) if "secrets." in s]
        self.assertEqual(2, len(with_secrets), with_secrets)
        self.assertTrue(any("Require the release secrets" in s for s in with_secrets), with_secrets)
        self.assertTrue(any("Build owner-signed" in s for s in with_secrets), with_secrets)
        for s in steps(rel):
            if "secrets." in s:
                self.assertNotIn("uses:", s, "a secret passed to an action")

    def test_release_deletes_the_decoded_keystore_even_on_failure(self):
        rel = read("release.yml")
        cleanup = [s for s in steps(rel) if 'rm -f "$RUNNER_TEMP/upload.jks"' in s]
        self.assertEqual(1, len(cleanup))
        self.assertIn("if: always()", cleanup[0])
        self.assertLess(rel.index("Build owner-signed"), rel.index('rm -f "$RUNNER_TEMP/upload.jks"'))

    def test_every_action_is_pinned_to_a_commit_sha(self):
        for name in ("ci.yml", "release.yml"):
            uses = re.findall(r"uses: (\S+)", read(name))
            self.assertTrue(uses, name)
            for ref in uses:
                self.assertRegex(ref, r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$", name + ": " + ref)


if __name__ == "__main__":
    unittest.main()
