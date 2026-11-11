"""Publish a version once; verify retries without replacing tags or assets."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def api(endpoint, method="GET", payload=None, *, paginate=False):
    command = ["gh", "api", "--method", method, endpoint]
    if paginate:
        command.extend(["--paginate", "--slurp"])
    if payload is not None:
        command.extend(["--input", "-"])
    result = subprocess.run(
        command, check=True, text=True, stdout=subprocess.PIPE,
        input=json.dumps(payload) if payload is not None else None,
    )
    response = json.loads(result.stdout)
    return [item for page in response for item in page] if paginate else response


def conflict(message):
    raise RuntimeError(f"{message} Bump the plugin version before publishing different content.")


def tag_commit(base, tag):
    refs = api(f"{base}/git/matching-refs/tags/{tag}", paginate=True)
    matching = [ref for ref in refs if ref["ref"] == f"refs/tags/{tag}"]
    if not matching:
        return None
    target = matching[0]["object"]
    seen = set()
    while target["type"] == "tag":
        if target["sha"] in seen:
            conflict("The version tag cannot be resolved to a commit.")
        seen.add(target["sha"])
        target = api(f"{base}/git/tags/{target['sha']}")["object"]
    if target["type"] != "commit":
        conflict("The version tag does not refer to a commit.")
    return target["sha"]


def verified_asset(base, release_id, archive):
    assets = api(f"{base}/releases/{release_id}/assets?per_page=100", paginate=True)
    if not assets:
        return False
    if len(assets) != 1 or assets[0]["name"] != archive.name:
        conflict("This version already has different release assets.")
    asset = assets[0]
    if asset["state"] != "uploaded" or asset["size"] != archive.stat().st_size:
        conflict("The existing ZIP is incomplete or has a different size.")
    # Download the actual bytes, including for older releases without an API digest.
    with tempfile.TemporaryFile() as downloaded, archive.open("rb") as local:
        subprocess.run(
            ["gh", "api", "--method", "GET", "--header", "Accept: application/octet-stream",
             f"{base}/releases/assets/{asset['id']}"],
            check=True, stdout=downloaded,
        )
        downloaded.seek(0)
        if hashlib.file_digest(downloaded, "sha256").digest() != hashlib.file_digest(local, "sha256").digest():
            conflict("This version already contains a ZIP with a different SHA-256.")
    return True


def summary(message, url):
    print(message)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
        output.write(f"{message} [Release]({url}).\n")


def main():
    repository = os.environ["GITHUB_REPOSITORY"]
    base = f"repos/{repository}"
    commit = os.environ["GITHUB_SHA"]
    tag = os.environ["RELEASE_TAG"]
    archive = Path(os.environ["ZIP_PATH"])
    server = os.environ["GITHUB_SERVER_URL"]
    url = f"{server}/{repository}/releases/tag/{tag}"
    if api(f"{base}/git/ref/heads/main")["object"]["sha"] != commit:
        print("::notice::Skipping release: this commit is no longer the head of main.")
        return

    tagged_commit = tag_commit(base, tag)
    if tagged_commit is not None and tagged_commit != commit:
        conflict("This version tag already points to a different commit.")
    releases = api(f"{base}/releases?per_page=100", paginate=True)
    matches = [release for release in releases if release["tag_name"] == tag]
    if len(matches) > 1:
        conflict("More than one release already uses this version tag.")
    release = matches[0] if matches else None
    has_asset = False
    if release is not None:
        if tagged_commit is None:
            conflict("The existing release is missing its version tag.")
        has_asset = verified_asset(base, release["id"], archive)
        if not release["draft"]:
            if not has_asset:
                conflict("The published release is missing its ZIP.")
            summary("Verified existing release; nothing changed.", url)
            return

    if tagged_commit is None:
        # POST is create-only: a conflicting tag created meanwhile fails safely.
        api(f"{base}/git/refs", "POST", {"ref": f"refs/tags/{tag}", "sha": commit})
    if release is None:
        release = api(f"{base}/releases", "POST", {
            "tag_name": tag, "target_commitish": commit,
            "name": f"Koin Dependency Navigation {os.environ['PLUGIN_VERSION']}", "draft": True,
            "body": f"Built from commit {commit} on main.\n\n"
                    f"Build: {server}/{repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}\n",
        })
    if not has_asset:
        # Never --clobber: a failed/competing upload cannot replace an existing asset.
        subprocess.run(
            ["gh", "release", "upload", tag, str(archive), "--repo", repository], check=True,
        )
        if not verified_asset(base, release["id"], archive):
            raise RuntimeError("The uploaded ZIP could not be verified; the release remains a draft.")
    # Only an unpublished draft reaches this mutation; tags and assets are never updated.
    api(f"{base}/releases/{release['id']}", "PATCH", {
        "draft": False, "prerelease": False, "make_latest": "true",
    })
    summary("Published verified release.", url)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
