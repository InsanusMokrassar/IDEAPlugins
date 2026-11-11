"""Check the aggregate ZIP without Gradle, network access or credentials."""

import io
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_plugins import package_plugins


PLUGINS = {
    "koin-dependency-navigation-1.0.4.zip": "dev.inmo.idea.koin.dependency.navigation",
    "navigation-flow-diagram-0.1.2.zip": "dev.inmo.idea.navigation.flow.diagram",
    "function-call-graph-0.1.2.zip": "dev.inmo.idea.function.call.graph",
}


def plugin_zip(plugin_id):
    jar_bytes = io.BytesIO()
    with zipfile.ZipFile(jar_bytes, "w") as jar:
        jar.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{plugin_id}</id></idea-plugin>")
    plugin_bytes = io.BytesIO()
    with zipfile.ZipFile(plugin_bytes, "w") as plugin:
        plugin.writestr("plugin/lib/plugin.jar", jar_bytes.getvalue())
    return plugin_bytes.getvalue()


class PackagePluginsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "gradle.properties").write_text("bundleVersion=1.0.0\n", encoding="utf-8")
        self.inputs = self.root / "local.dists"
        self.inputs.mkdir()
        self.originals = {name: plugin_zip(plugin_id) for name, plugin_id in PLUGINS.items()}
        for name, content in self.originals.items():
            (self.inputs / name).write_bytes(content)

    def test_repo_named_archive_contains_exactly_three_unchanged_plugin_zips(self):
        (self.inputs / "local.notes.txt").write_text("Do not publish me", encoding="utf-8")
        metadata = package_plugins(self.root, "InsanusMokrassar/IDEAPlugins")
        self.assertEqual({
            "version": "1.0.0", "tag": "IDEAPlugins-v1.0.0",
            "zip": "build/distributions/IDEAPlugins-1.0.0.zip",
            "name": "IDEAPlugins-1.0.0.zip",
        }, metadata)
        with zipfile.ZipFile(self.root / metadata["zip"]) as bundle:
            self.assertEqual(sorted(PLUGINS), bundle.namelist())
            self.assertEqual(self.originals, {name: bundle.read(name) for name in bundle.namelist()})

    def test_retry_is_identical_after_input_order_mtime_and_permissions_change(self):
        metadata = package_plugins(self.root, "owner/IDEAPlugins")
        output = self.root / metadata["zip"]
        original = output.read_bytes()
        for name in self.originals:
            (self.inputs / name).unlink()
        for name, content in reversed(list(self.originals.items())):
            path = self.inputs / name
            path.write_bytes(content)
            os.utime(path, (1_700_000_000, 1_700_000_000))
            path.chmod(0o600)
        package_plugins(self.root, "owner/IDEAPlugins")
        self.assertEqual(original, output.read_bytes())

    def test_repository_rename_changes_archive_and_tag(self):
        metadata = package_plugins(self.root, "another-owner/RenamedPlugins")
        self.assertEqual("RenamedPlugins-v1.0.0", metadata["tag"])
        self.assertEqual("build/distributions/RenamedPlugins-1.0.0.zip", metadata["zip"])
        self.assertEqual(Path(metadata["zip"]).name, metadata["name"])

    def test_missing_plugin_is_rejected_before_creating_bundle(self):
        (self.inputs / next(iter(PLUGINS))).unlink()
        with self.assertRaisesRegex(ValueError, "Expected exactly three plugin ZIPs"):
            package_plugins(self.root, "owner/IDEAPlugins")
        self.assertFalse((self.root / "build").exists())

    def test_extra_cli_or_stale_zip_is_rejected(self):
        (self.inputs / "cli.zip").write_bytes(b"Not an installable plugin")
        with self.assertRaisesRegex(ValueError, "Expected exactly three plugin ZIPs"):
            package_plugins(self.root, "owner/IDEAPlugins")

    def test_duplicate_or_unknown_plugin_id_is_rejected(self):
        name = next(iter(PLUGINS))
        for plugin_id in ["dev.inmo.idea.function.call.graph", "unexpected.plugin"]:
            with self.subTest(plugin_id=plugin_id):
                (self.inputs / name).write_bytes(plugin_zip(plugin_id))
                with self.assertRaisesRegex(ValueError, "Expected one ZIP for each plugin"):
                    package_plugins(self.root, "owner/IDEAPlugins")

    def test_missing_descriptor_is_rejected(self):
        name = next(iter(PLUGINS))
        with zipfile.ZipFile(self.inputs / name, "w") as archive:
            archive.writestr("README.txt", "not a plugin")
        with self.assertRaisesRegex(ValueError, "Expected one plugin descriptor"):
            package_plugins(self.root, "owner/IDEAPlugins")

    def test_symlink_input_is_rejected(self):
        name = next(iter(PLUGINS))
        external = self.root / "external.zip"
        (self.inputs / name).rename(external)
        (self.inputs / name).symlink_to(external)
        with self.assertRaisesRegex(ValueError, "regular file"):
            package_plugins(self.root, "owner/IDEAPlugins")

    def test_unsafe_repository_or_version_cannot_escape_output_directory(self):
        for repository in ["owner/../escape", "owner/..", "owner/repo\nzip=evil"]:
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                package_plugins(self.root, repository)
        for properties in ["", "bundleVersion=../escape\n", "bundleVersion=1.0.0\nbundleVersion=2.0.0\n"]:
            (self.root / "gradle.properties").write_text(properties, encoding="utf-8")
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                package_plugins(self.root, "owner/IDEAPlugins")
        self.assertFalse((self.root / "build").exists())


if __name__ == "__main__":
    unittest.main()
