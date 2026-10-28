"""Check publication ordering and refusal paths without contacting GitHub."""
from pathlib import Path
import base64
import copy
import hashlib
import importlib.util
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).resolve().parents[1] / 'publish_release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
HEAD = 'a' * 40


class Fake:
    def __init__(self):
        self.private = True
        self.tag = None
        self.release = None
        self.assets = []
        self.writes = []
        self.content = {}
        self.manifest = {'repository': p.REPOSITORY, 'tag': 'v1.0.0', 'body': 'Approved release notes', 'assets': []}
        for name in sorted(p.NAMES):
            content = ('tested bytes for ' + name).encode()
            sha = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
            self.content[sha] = content
            self.manifest['assets'].append({'name': name, 'blob': sha, 'parts': [sha], 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})

    def api(self, path, method='GET', data=None, media=None):
        if method != 'GET':
            self.writes.append((path, method))
        if path == p.PREFIX:
            return {'private': self.private}
        if '/git/blobs/' in path:
            sha = path.rsplit('/', 1)[1]
            return {'sha': sha, 'encoding': 'base64', 'content': base64.b64encode(self.content[sha]).decode()}
        if '/git/ref/tags/' in path:
            if self.tag is None:
                raise p.NotFound()
            return {'object': {'sha': self.tag}}
        if path.endswith('/git/refs'):
            self.tag = data['sha']
            return {}
        if path.endswith('/releases/tags/v1.0.0'):
            if self.release is None:
                raise p.NotFound()
            return self.release.copy()
        if path == p.PREFIX + '/releases':
            self.release = {**data, 'id': 12, 'upload_url': 'https://uploads.github.com' + p.PREFIX + '/releases/12/assets{?name}',
                            'html_url': 'https://github.com/' + p.REPOSITORY + '/releases/tag/v1.0.0'}
            return self.release.copy()
        if 'uploads.github.com' in path:
            name = path.split('?name=')[1]
            result = {'name': name, 'state': 'uploaded', 'size': len(data), 'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
            self.assets.append(result)
            return result
        if '/assets?' in path:
            return copy.deepcopy(self.assets)
        if path == p.PREFIX + '/releases/12':
            if method == 'PATCH':
                self.release.update(data)
            return self.release.copy()
        raise AssertionError(path)


class ReleaseTests(unittest.TestCase):
    def test_success_is_draft_then_four_uploads_then_publish(self):
        fake = Fake()
        result = p.publish(fake.manifest, HEAD, fake.api)
        self.assertTrue(result['private'])
        self.assertEqual(len(result['assets']), 4)
        self.assertEqual([method for _, method in fake.writes], ['POST'] * 6 + ['PATCH'])
        self.assertFalse(fake.release['draft'])
        fake.writes.clear()
        self.assertEqual(p.publish(fake.manifest, HEAD, fake.api), result)
        self.assertFalse(fake.writes)

    def test_bad_artifact_is_rejected_before_mutations(self):
        fake = Fake()
        fake.content[next(iter(fake.content))] += b'corrupted'
        with self.assertRaisesRegex(ValueError, 'checksum'):
            p.publish(fake.manifest, HEAD, fake.api)
        self.assertFalse(fake.writes)

    def test_ordered_parts_are_merged_and_whole_file_is_checked(self):
        fake = Fake()
        asset = fake.manifest['assets'][0]
        content = fake.content[asset['blob']]
        parts = []
        for piece in [content[:9], content[9:]]:
            sha = hashlib.sha1(b'blob ' + str(len(piece)).encode() + b'\0' + piece).hexdigest()
            fake.content[sha] = piece
            parts.append(sha)
        asset['parts'] = parts
        result = p.publish(fake.manifest, HEAD, fake.api)
        self.assertEqual(result['assets'][0]['sha256'], hashlib.sha256(content).hexdigest())
        asset['parts'] = list(reversed(parts))
        fake.writes.clear()
        with self.assertRaisesRegex(ValueError, 'checksum'):
            p.publish(fake.manifest, HEAD, fake.api)
        self.assertFalse(fake.writes)

    def test_public_repository_is_rejected_before_mutations(self):
        fake = Fake()
        fake.private = False
        with self.assertRaisesRegex(ValueError, 'private'):
            p.publish(fake.manifest, HEAD, fake.api)
        self.assertFalse(fake.writes)

    def test_foreign_tag_is_never_moved(self):
        fake = Fake()
        fake.tag = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'another commit'):
            p.publish(fake.manifest, HEAD, fake.api)
        self.assertFalse(fake.writes)

    def test_conflicting_asset_is_never_overwritten_or_deleted(self):
        fake = Fake()
        p.publish(fake.manifest, HEAD, fake.api)
        fake.assets[0]['digest'] = 'sha256:' + '0' * 64
        fake.writes.clear()
        with self.assertRaisesRegex(ValueError, 'Server asset'):
            p.publish(fake.manifest, HEAD, fake.api)
        self.assertFalse(fake.writes)


if __name__ == '__main__':
    unittest.main()
