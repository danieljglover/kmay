"""Public Internet requests with DNS validation at connection time."""
import ipaddress
import socket
from urllib.parse import urlsplit, urljoin
import aiohttp


def public_ip(value):
    ip = ipaddress.ip_address(value)
    if not ip.is_global or (ip.version == 6 and ip.ipv4_mapped and not ip.ipv4_mapped.is_global):
        raise ValueError('Private or reserved upstream address blocked')
    return value


def valid_url(url):
    p = urlsplit(url)
    if p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password:
        raise ValueError('Invalid upstream URL')
    if p.port not in (None, 80, 443):
        raise ValueError('Unsupported upstream port')
    try:
        ipaddress.ip_address(p.hostname)
    except ValueError:
        if '.' not in p.hostname or p.hostname.endswith(('.local', '.lan', '.internal')):
            raise ValueError('Local upstream hostname blocked')
    else:
        public_ip(p.hostname)
    return url


class PublicResolver(aiohttp.abc.AbstractResolver):
    def __init__(self):
        self.resolver = aiohttp.resolver.ThreadedResolver()

    async def resolve(self, host, port=0, family=socket.AF_INET):
        results = await self.resolver.resolve(host, port, family)
        for result in results:
            public_ip(result['host'])
        return results

    async def close(self):
        await self.resolver.close()


class Network:
    async def start(self):
        self.client = aiohttp.ClientSession(
            connector=aiohttp.TCPConnector(resolver=PublicResolver(), limit=32, ttl_dns_cache=30),
            timeout=aiohttp.ClientTimeout(total=None, connect=12, sock_read=25),
            cookie_jar=aiohttp.DummyCookieJar(), auto_decompress=True,
            headers={'User-Agent': 'Mozilla/5.0 KMayProxy/1.1', 'Accept-Encoding': 'identity'})

    async def close(self):
        await self.client.close()

    async def open(self, url, headers=None, method='GET'):
        headers = dict(headers or {})
        for _ in range(6):
            valid_url(url)
            response = await self.client.request(method, url, headers=headers, allow_redirects=False)
            if response.status not in (301, 302, 303, 307, 308):
                return response
            target = urljoin(str(response.url), response.headers.get('Location', ''))
            response.release()
            if urlsplit(target).netloc != urlsplit(url).netloc:
                headers = {k:v for k,v in headers.items() if k.lower() not in ('cookie', 'authorization')}
            url = target
        raise ValueError('Too many upstream redirects')

    async def read(self, url, headers=None, limit=4_000_000):
        response = await self.open(url, headers)
        try:
            if response.status != 200:
                raise ValueError(f'Upstream HTTP {response.status}')
            chunks, size = [], 0
            async for chunk in response.content.iter_chunked(65536):
                size += len(chunk)
                if size > limit:
                    raise ValueError('Upstream document too large')
                chunks.append(chunk)
            return b''.join(chunks), str(response.url)
        finally:
            response.close()
