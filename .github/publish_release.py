"""Publish pre-signed, hash-checked artifacts using the job's temporary token."""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = 'JIUSIS/MiStep'
PREFIX = '/repos/' + REPOSITORY
NAMES = {'MiStep-1.0.0.apk', 'MiStep-1.0.0-source.zip', 'build-info.json', 'SHA256SUMS.txt'}


class NotFound(Exception):
    pass


def request(path, method='GET', data=None, media='application/json'):
    if path.startswith('/'):
        if not path.startswith(PREFIX + '/') and path != PREFIX:
            raise ValueError('Unexpected repository endpoint')
        url = 'https://api.github.com' + path
    else:
        parsed = urllib.parse.urlsplit(path)
        if (parsed.scheme != 'https' or parsed.hostname != 'uploads.github.com'
                or not parsed.path.startswith(PREFIX + '/releases/')):
            raise ValueError('Unexpected upload endpoint')
        url = path
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
               'Accept': 'application/vnd.github+json', 'Content-Type': media,
               'X-GitHub-Api-Version': '2026-03-10', 'User-Agent': 'MiStep-release'}
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise NotFound() from None
        raise RuntimeError('GitHub request failed with HTTP ' + str(error.code)) from None


def validate(manifest, head):
    if manifest.get('repository') != REPOSITORY or manifest.get('tag') != 'v1.0.0':
        raise ValueError('Unexpected release target')
    if not re.fullmatch('[0-9a-f]{40}', head):
        raise ValueError('Missing exact source commit')
    assets = manifest['assets']
    if len(assets) != 4 or {a['name'] for a in assets} != NAMES:
        raise ValueError('Unexpected release asset list')
    for asset in assets:
        if (not re.fullmatch('[0-9a-f]{40}', asset['blob'])
                or not re.fullmatch('[0-9a-f]{64}', asset['sha256'])
                or not 1 <= len(asset.get('parts', [])) <= 300
                or not all(re.fullmatch('[0-9a-f]{40}', part) for part in asset['parts'])
                or not 0 < asset['bytes'] <= 10_000_000):
            raise ValueError('Invalid asset identity')


def verify_asset(actual, expected):
    if (actual.get('state') != 'uploaded' or actual.get('size') != expected['bytes']
            or actual.get('digest') != 'sha256:' + expected['sha256']):
        raise ValueError('Server asset identity mismatch: ' + expected['name'])


def publish(manifest, head, api=request):
    validate(manifest, head)
    if api(PREFIX).get('private') is not True:
        raise ValueError('Release repository must remain private')
    # Validate every byte before creating tags, drafts or uploaded assets.
    prepared = []
    for asset in manifest['assets']:
        pieces = []
        for part in asset['parts']:
            blob = api(PREFIX + '/git/blobs/' + part)
            if blob.get('encoding') != 'base64' or blob.get('sha') != part:
                raise ValueError('Unexpected artifact blob')
            pieces.append(base64.b64decode(blob['content']))
        content = b''.join(pieces)
        if len(content) != asset['bytes'] or hashlib.sha256(content).hexdigest() != asset['sha256']:
            raise ValueError('Artifact checksum mismatch: ' + asset['name'])
        prepared.append((asset, content))
    tag_path = PREFIX + '/git/ref/tags/v1.0.0'
    try:
        if api(tag_path)['object']['sha'] != head:
            raise ValueError('Existing release tag points to another commit')
    except NotFound:
        api(PREFIX + '/git/refs', 'POST', {'ref': 'refs/tags/v1.0.0', 'sha': head})
    try:
        release = api(PREFIX + '/releases/tags/v1.0.0')
    except NotFound:
        release = api(PREFIX + '/releases', 'POST', {
            'tag_name': 'v1.0.0', 'target_commitish': head, 'name': 'Mi Step 1.0.0',
            'body': manifest['body'], 'draft': True, 'prerelease': False})
    release_path = PREFIX + '/releases/' + str(release['id'])
    existing = {a['name']: a for a in api(release_path + '/assets?per_page=100')}
    if set(existing) - NAMES:
        raise ValueError('Unexpected existing release assets')
    upload = release['upload_url'].split('{', 1)[0]
    for asset, content in prepared:
        if asset['name'] in existing:
            verify_asset(existing[asset['name']], asset)
            continue
        if not release['draft']:
            raise ValueError('Published release is missing expected assets')
        media = 'application/zip' if asset['name'].endswith('.zip') else 'application/octet-stream'
        result = api(upload + '?name=' + urllib.parse.quote(asset['name']), 'POST', content, media)
        verify_asset(result, asset)
    if release['draft']:
        api(release_path, 'PATCH', {'draft': False, 'prerelease': False, 'make_latest': 'true'})
    final = api(release_path)
    if final['draft'] or final['prerelease'] or final['tag_name'] != 'v1.0.0':
        raise ValueError('Release publication was not confirmed')
    remote_assets = {a['name']: a for a in api(release_path + '/assets?per_page=100')}
    if set(remote_assets) != NAMES:
        raise ValueError('Final asset list mismatch')
    for asset in manifest['assets']:
        verify_asset(remote_assets[asset['name']], asset)
    return {'url': final['html_url'], 'releaseId': final['id'], 'sourceCommit': head,
            'private': True, 'tag': 'v1.0.0', 'assets': manifest['assets']}


if __name__ == '__main__':
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise ValueError('Unexpected workflow repository')
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    result = publish(manifest, os.environ['GITHUB_SHA'])
    print('RELEASE_VERIFIED ' + json.dumps(result, ensure_ascii=False))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('Mi Step 1.0.0 published and all four asset digests verified.\n\n'
                          + result['url'] + '\n')
