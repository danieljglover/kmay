import asyncio
from dataclasses import dataclass, field
import re
import secrets
import time
from urllib.parse import urljoin, urlsplit
import xml.etree.ElementTree as ET
from defusedxml.ElementTree import fromstring
from fastapi import HTTPException, Request
from fastapi.responses import Response, StreamingResponse
from network import valid_url


@dataclass
class Session:
    provider: str
    origin: str
    headers: dict
    expires: float
    resources: dict = field(default_factory=dict)
    reverse: dict = field(default_factory=dict)
    hls_manifests: dict = field(default_factory=dict)


class Relay:
    def __init__(self, network):
        self.network = network
        self.sessions = {}
        self.requests = self.bytes = 0
        self.active = 0

    def create(self, provider, stream, base):
        self.prune()
        if len(self.sessions) >= 64:
            raise HTTPException(429, 'Too many playback sessions. Try again later.')
        sid = secrets.token_urlsafe(24)
        self.sessions[sid] = Session(provider, urlsplit(stream['url']).netloc, stream.get('headers', {}), time.time() + 21600)
        self.sessions[sid].hls_manifests = dict(stream.get('_hls_manifests', {}))
        url = self.register(sid, stream['url'], base)
        subtitles = []
        for subtitle in stream.get('subtitles', [])[:20]:
            try:
                subtitles.append({**subtitle, 'url':self.register(sid, valid_url(subtitle['url']), base)})
            except (ValueError, KeyError):
                continue
        return {'name': stream.get('name', provider), 'provider_id': provider, 'url': url,
                'mime': stream.get('mime', ''), 'subtitles': subtitles}

    def prune(self):
        self.sessions = {k:v for k,v in self.sessions.items() if v.expires > time.time()}

    def register(self, sid, url, base, directory=False):
        valid_url(url)
        session = self.sessions[sid]
        key = (url, directory)
        if key not in session.reverse:
            if len(session.resources) >= 50000:
                raise ValueError('Manifest resource limit exceeded')
            rid = secrets.token_urlsafe(12)
            session.resources[rid] = key
            session.reverse[key] = rid
        rid = session.reverse[key]
        if directory:
            name = ''
        else:
            name = urlsplit(url).path.rsplit('/', 1)[-1] or 'media'
            tokens = re.findall(r'\$(?:Number|Time|RepresentationID|Bandwidth)(?:%0\d+d)?\$', url)
            if tokens:
                name = 'segment-' + '-'.join(tokens) + '.m4s'
            name = re.sub(r'[^a-zA-Z0-9._$%-]', '_', name)
        return f'{base}/relay/{sid}/{rid}/{name}'

    def hls(self, raw, url, sid, base):
        def mapped(value):
            return self.register(sid, urljoin(url, value), base)
        lines = []
        for line in raw.decode('utf-8-sig').splitlines():
            if line.startswith('#'):
                line = re.sub(r'URI="([^"]+)"', lambda m:'URI="' + mapped(m[1]) + '"', line)
            elif line.strip():
                line = mapped(line.strip())
            lines.append(line)
        return '\n'.join(lines).encode()

    def dash(self, raw, url, sid, base):
        root = fromstring(raw)
        namespace = root.tag.partition('}')[0].lstrip('{')
        if namespace:
            ET.register_namespace('', namespace)
        def walk(node, upstream):
            bases = [c for c in node if c.tag.split('}')[-1] == 'BaseURL']
            current = urljoin(upstream, bases[0].text or '') if bases else upstream
            for b in bases:
                resolved = urljoin(upstream, b.text or '')
                b.text = self.register(sid, resolved, base, directory=resolved.endswith('/'))
            for attr in ('media', 'initialization', 'sourceURL', 'index'):
                if attr in node.attrib:
                    node.attrib[attr] = self.register(sid, urljoin(current, node.attrib[attr]), base)
            # External manifest inclusions could bypass the relay. Reject them.
            for key in node.attrib:
                if key.endswith('}href'):
                    raise ValueError('External DASH inclusions unsupported')
            for child in node:
                if child.tag.split('}')[-1] == 'Location':
                    child.text = self.register(sid, urljoin(current, child.text or ''), base)
                elif child.tag.split('}')[-1] != 'BaseURL':
                    walk(child, current)
        walk(root, url)
        return ET.tostring(root, encoding='utf-8', xml_declaration=True)

    def target(self, session, rid, tail):
        if rid not in session.resources:
            raise HTTPException(404, 'Unknown playback resource')
        url, directory = session.resources[rid]
        if directory:
            if not tail or tail.startswith('/') or any(p in ('.', '..') for p in tail.split('/')) or ':' in tail or '\\' in tail:
                raise HTTPException(400, 'Invalid media path')
            return urljoin(url, tail)
        tokens = re.findall(r'\$(?:Number|Time|RepresentationID|Bandwidth)(?:%0\d+d)?\$', url)
        if tokens:
            filename = 'segment-' + '-'.join(tokens) + '.m4s'
            pattern = re.escape(filename)
            for token in tokens:
                pattern = pattern.replace(re.escape(token), r'([A-Za-z0-9_-]+)' if 'RepresentationID' in token else r'(\d+)')
            match = re.fullmatch(pattern, tail)
            if not match:
                raise HTTPException(400, 'Invalid DASH segment')
            for token, value in zip(tokens, match.groups()):
                url = url.replace(token, value)
        return url

    async def serve(self, sid, rid, tail, request: Request):
        session = self.sessions.get(sid)
        if not session or session.expires < time.time():
            raise HTTPException(410, 'Playback session expired. Refresh sources.')
        if self.active >= 24:
            raise HTTPException(429, 'Relay busy')
        self.active += 1
        response = None
        handed_off = False
        try:
            url = self.target(session, rid, tail)
            if url in session.hls_manifests:
                base = str(request.base_url).rstrip('/')
                content = self.hls(session.hls_manifests[url].encode(),url,sid,base)
                return Response(b'' if request.method == 'HEAD' else content,media_type='application/vnd.apple.mpegurl',headers={'Cache-Control':'no-store'})
            headers = dict(session.headers)
            if urlsplit(url).netloc != session.origin:
                headers = {k:v for k,v in headers.items() if k.lower() not in ('cookie','authorization')}
            if request.headers.get('range'):
                headers['Range'] = request.headers['range']
            response = await self.network.open(url, headers, request.method)
            self.requests += 1
            if response.status not in (200, 206):
                raise HTTPException(response.status if response.status in (404, 410, 416) else 502, 'Upstream media unavailable')
            content_type = response.headers.get('Content-Type', 'application/octet-stream').split(';')[0]
            path = urlsplit(str(response.url)).path.lower()
            is_hls = 'mpegurl' in content_type or path.endswith('.m3u8')
            is_dash = 'dash+xml' in content_type or path.endswith('.mpd')
            if request.method != 'HEAD' and (is_hls or is_dash):
                raw = bytearray()
                async for chunk in response.content.iter_chunked(65536):
                    raw.extend(chunk)
                    if len(raw) > 4_000_000:
                        raise ValueError('Manifest too large')
                base = str(request.base_url).rstrip('/')
                content = self.hls(bytes(raw), str(response.url), sid, base) if is_hls else self.dash(bytes(raw), str(response.url), sid, base)
                return Response(content, media_type='application/vnd.apple.mpegurl' if is_hls else 'application/dash+xml', headers={'Cache-Control':'no-store'})
            outgoing = {k:v for k,v in response.headers.items() if k.lower() in ('content-type','content-length','content-range','accept-ranges','etag','last-modified')}
            outgoing['Cache-Control'] = 'no-store'
            if request.method == 'HEAD':
                return Response(status_code=response.status, headers=outgoing)
            async def chunks():
                try:
                    async for chunk in response.content.iter_chunked(65536):
                        self.bytes += len(chunk)
                        yield chunk
                finally:
                    response.close()
                    self.active -= 1
            result = StreamingResponse(chunks(), status_code=response.status, headers=outgoing)
            handed_off = True
            return result
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(502, 'Upstream media request failed') from None
        finally:
            if not handed_off:
                if response is not None:
                    response.close()
                self.active -= 1
