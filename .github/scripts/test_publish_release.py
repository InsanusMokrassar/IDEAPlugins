"""Exercise publication against a fake GitHub CLI; no network or credentials needed."""

import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish_release


COMMIT = "a" * 40
REPOSITORY = "example/IDEAPlugins"
TAG = "IDEAPlugins-v1.0.0"
BASE = f"repos/{REPOSITORY}"


class FakeGitHub:
    def __init__(self, archive, *, repository=REPOSITORY, tag=TAG):
        self.archive = archive
        self.repository = repository
        self.base = f"repos/{repository}"
        self.tag = tag
        self.main = COMMIT
        self.refs = []
        self.tag_objects = {}
        self.release = None
        self.other_releases = []
        self.assets = []
        self.content = archive.read_bytes()
        self.writes = []
        self.fail_upload = False
        self.fail_endpoint = None

    def existing_release(self, *, draft=False, asset=True):
        self.refs = [{"ref": f"refs/tags/{self.tag}", "object": {"type": "commit", "sha": COMMIT}}]
        self.release = {"id": 7, "tag_name": self.tag, "draft": draft}
        if asset:
            self.add_asset()

    def add_asset(self):
        self.assets.append({"id": 8, "name": self.archive.name, "state": "uploaded", "size": len(self.content)})

    def run(self, command, **kwargs):
        if command[:3] == ["gh", "release", "upload"]:
            assert command == ["gh", "release", "upload", self.tag, str(self.archive), "--repo", self.repository]
            assert self.release["draft"] and not self.assets
            self.writes.append(("upload", self.tag))
            if self.fail_upload:
                raise subprocess.CalledProcessError(1, command)
            self.add_asset()
            return subprocess.CompletedProcess(command, 0)
        assert command[:2] == ["gh", "api"], command
        method = command[command.index("--method") + 1]
        if "--header" in command:
            assert method == "GET" and command[-1] == f"{self.base}/releases/assets/8"
            assert command[command.index("--header") + 1] == "Accept: application/octet-stream"
            kwargs["stdout"].write(self.content)
            return subprocess.CompletedProcess(command, 0)
        endpoint = command[4]
        payload = json.loads(kwargs["input"]) if kwargs.get("input") else None
        if endpoint == self.fail_endpoint:
            raise subprocess.CalledProcessError(1, command)
        if method != "GET":
            self.writes.append((method, endpoint))
        if method == "GET":
            if endpoint == f"{self.base}/git/ref/heads/main":
                response = {"object": {"sha": self.main}}
            elif endpoint == f"{self.base}/git/matching-refs/tags/{self.tag}":
                response = self.refs
            elif endpoint.startswith(f"{self.base}/git/tags/"):
                response = self.tag_objects[endpoint.rsplit("/", 1)[1]]
            elif endpoint == f"{self.base}/releases?per_page=100":
                response = self.other_releases + ([self.release] if self.release else [])
            elif endpoint == f"{self.base}/releases/7/assets?per_page=100":
                response = self.assets
            else:
                raise AssertionError(command)
        elif method == "POST" and endpoint == f"{self.base}/git/refs":
            assert payload == {"ref": f"refs/tags/{self.tag}", "sha": COMMIT}
            assert not any(ref["ref"] == payload["ref"] for ref in self.refs)
            response = {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            self.refs.append(response)
        elif method == "POST" and endpoint == f"{self.base}/releases":
            assert self.release is None and payload["draft"] is True
            assert payload["tag_name"] == self.tag and payload["target_commitish"] == COMMIT
            self.release = response = {"id": 7, **payload}
        elif method == "PATCH" and endpoint == f"{self.base}/releases/7":
            assert self.release["draft"] is True and len(self.assets) == 1
            assert payload == {"draft": False, "prerelease": False, "make_latest": "true"}
            self.release.update(payload)
            response = self.release
        else:
            raise AssertionError(f"Unexpected mutation: {command}")
        if "--slurp" in command:
            response = [response]
        return subprocess.CompletedProcess(command, 0, json.dumps(response))


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        self.archive = directory / "IDEAPlugins-1.0.0.zip"
        self.archive.write_bytes(b"built plugin bundle zip")
        self.github = FakeGitHub(self.archive)
        environment = {
            "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_SHA": COMMIT,
            "RELEASE_TAG": TAG, "ZIP_PATH": str(self.archive),
            "GITHUB_SERVER_URL": "https://github.com", "GITHUB_RUN_ID": "42",
            "BUNDLE_VERSION": "1.0.0", "GITHUB_STEP_SUMMARY": str(directory / "summary"),
        }
        self.enterContext(patch.dict(os.environ, environment))
        self.enterContext(patch.object(publish_release.subprocess, "run", side_effect=self.github.run))
        self.enterContext(patch("sys.stdout", new=io.StringIO()))

    def assert_conflict(self):
        with self.assertRaisesRegex(RuntimeError, r"Bump bundleVersion in gradle\.properties"):
            publish_release.main()
        self.assertEqual([], self.github.writes)

    def test_new_release_creates_tag_uploads_and_publishes_in_order(self):
        publish_release.main()
        self.assertEqual([
            ("POST", f"{BASE}/git/refs"), ("POST", f"{BASE}/releases"),
            ("upload", TAG), ("PATCH", f"{BASE}/releases/7"),
        ], self.github.writes)
        self.assertFalse(self.github.release["draft"])

    def test_release_name_uses_repository_and_bundle_version(self):
        with patch.dict(os.environ, {"PLUGIN_VERSION": "99.0.0"}):
            publish_release.main()
        self.assertEqual("IDEAPlugins 1.0.0", self.github.release["name"])
        self.assertIn("Extract the three plugin ZIPs", self.github.release["body"])

    def test_release_name_follows_repository_rename(self):
        archive = self.archive.with_name("RenamedPlugins-1.0.0.zip")
        archive.write_bytes(self.archive.read_bytes())
        github = FakeGitHub(archive, repository="example/RenamedPlugins", tag="RenamedPlugins-v1.0.0")
        with patch.dict(os.environ, {
            "GITHUB_REPOSITORY": github.repository, "RELEASE_TAG": github.tag,
            "ZIP_PATH": str(archive), "PLUGIN_VERSION": "99.0.0",
        }), patch.object(publish_release.subprocess, "run", side_effect=github.run):
            publish_release.main()
        self.assertEqual("RenamedPlugins 1.0.0", github.release["name"])
        self.assertEqual("RenamedPlugins-v1.0.0", github.release["tag_name"])

    def test_legacy_koin_tag_and_release_are_untouched(self):
        legacy_ref = {"ref": "refs/tags/v1.0.0", "object": {"type": "commit", "sha": "b" * 40}}
        legacy_release = {"id": 3, "tag_name": "v1.0.0", "name": "Koin Dependency Navigation 1.0.0", "draft": False}
        self.github.refs = [legacy_ref]
        self.github.other_releases = [legacy_release.copy()]
        publish_release.main()
        self.assertEqual(legacy_ref, self.github.refs[0])
        self.assertEqual([legacy_release], self.github.other_releases)
        self.assertEqual(TAG, self.github.release["tag_name"])
        self.assertEqual([
            ("POST", f"{BASE}/git/refs"), ("POST", f"{BASE}/releases"),
            ("upload", TAG), ("PATCH", f"{BASE}/releases/7"),
        ], self.github.writes)

    def test_verified_published_release_retry_does_not_write(self):
        self.github.existing_release()
        self.github.release["immutable"] = True
        publish_release.main()
        self.assertEqual([], self.github.writes)

    def test_different_commit_requires_version_bump(self):
        self.github.existing_release()
        self.github.refs[0]["object"]["sha"] = "b" * 40
        self.assert_conflict()

    def test_same_size_different_content_requires_version_bump(self):
        self.github.existing_release()
        self.github.content = b"x" * len(self.github.content)
        self.assert_conflict()

    def test_stale_main_run_skips_without_writes(self):
        self.github.main = "b" * 40
        publish_release.main()
        self.assertEqual([], self.github.writes)
        self.assertIsNone(self.github.release)

    def test_empty_draft_resumes_upload(self):
        self.github.existing_release(draft=True, asset=False)
        publish_release.main()
        self.assertEqual([("upload", TAG), ("PATCH", f"{BASE}/releases/7")], self.github.writes)

    def test_verified_draft_resumes_publication(self):
        self.github.existing_release(draft=True)
        publish_release.main()
        self.assertEqual([("PATCH", f"{BASE}/releases/7")], self.github.writes)

    def test_tag_only_retry_resumes_release_creation(self):
        self.github.existing_release(asset=False)
        self.github.release = None
        publish_release.main()
        self.assertEqual([
            ("POST", f"{BASE}/releases"), ("upload", TAG), ("PATCH", f"{BASE}/releases/7"),
        ], self.github.writes)

    def test_published_release_missing_zip_requires_version_bump(self):
        self.github.existing_release(asset=False)
        self.assert_conflict()

    def test_release_missing_tag_requires_version_bump(self):
        self.github.existing_release()
        self.github.refs = []
        self.assert_conflict()

    def test_incomplete_upload_requires_version_bump(self):
        self.github.existing_release(draft=True)
        self.github.assets[0]["state"] = "starter"
        self.assert_conflict()

    def test_wrong_asset_size_requires_version_bump(self):
        self.github.existing_release()
        self.github.assets[0]["size"] += 1
        self.assert_conflict()

    def test_wrong_asset_name_requires_version_bump(self):
        self.github.existing_release()
        self.github.assets[0]["name"] = "other.zip"
        self.assert_conflict()

    def test_extra_asset_requires_version_bump(self):
        self.github.existing_release()
        self.github.add_asset()
        self.assert_conflict()

    def test_annotated_tag_resolves_to_same_commit(self):
        self.github.existing_release()
        self.github.refs[0]["object"] = {"type": "tag", "sha": "c" * 40}
        self.github.tag_objects["c" * 40] = {"object": {"type": "commit", "sha": COMMIT}}
        publish_release.main()
        self.assertEqual([], self.github.writes)

    def test_ref_with_same_prefix_is_not_the_version_tag(self):
        self.github.refs = [{"ref": f"refs/tags/{TAG}0", "object": {"type": "commit", "sha": "b" * 40}}]
        publish_release.main()
        self.assertEqual(("POST", f"{BASE}/git/refs"), self.github.writes[0])

    def test_failed_upload_leaves_draft_and_never_publishes(self):
        self.github.fail_upload = True
        with self.assertRaises(subprocess.CalledProcessError):
            publish_release.main()
        self.assertTrue(self.github.release["draft"])
        self.assertNotIn(("PATCH", f"{BASE}/releases/7"), self.github.writes)

    def test_corrupt_upload_leaves_draft_and_never_publishes(self):
        self.github.content = b"x" * len(self.github.content)
        with self.assertRaisesRegex(RuntimeError, "different SHA-256"):
            publish_release.main()
        self.assertTrue(self.github.release["draft"])
        self.assertNotIn(("PATCH", f"{BASE}/releases/7"), self.github.writes)

    def test_api_failure_does_not_masquerade_as_absent_release(self):
        self.github.fail_endpoint = f"{BASE}/releases?per_page=100"
        with self.assertRaises(subprocess.CalledProcessError):
            publish_release.main()
        self.assertEqual([], self.github.writes)

    def test_competing_tag_creation_fails_without_updating_tag(self):
        self.github.fail_endpoint = f"{BASE}/git/refs"
        with self.assertRaises(subprocess.CalledProcessError):
            publish_release.main()
        self.assertIsNone(self.github.release)
        self.assertEqual([], self.github.writes)


if __name__ == "__main__":
    unittest.main()
