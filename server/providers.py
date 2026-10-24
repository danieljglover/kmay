import asyncio
import json
import re
from urllib.parse import urlsplit, quote
from playwright.async_api import async_playwright
from network import valid_url

HOSTED = {
    'rive': {'name': 'Rive', 'domain': 'www.rivestream.app'},
    'vidlink': {'name': 'VidLink', 'domain': 'vidlink.pro'},
    'vidfast': {'name': 'VidFast', 'domain': 'vidfast.pro'},
}
OPEN_FILM = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'


def embed_url(provider, title):
    tmdb = str(title.get('tmdb_id') or '')
    if not re.fullmatch(r'\d+', tmdb):
        raise ValueError('This hosted provider needs a TMDB identifier')
    kind = 'tv' if title['type'] == 'series' else 'movie'
    season, episode = title.get('season'), title.get('episode')
    if kind == 'tv' and (not season or not episode):
        raise ValueError('Episode information missing')
    if provider == 'rive':
        return f'https://www.rivestream.app/embed?type={kind}&id={tmdb}' + (f'&season={season}&episode={episode}' if kind == 'tv' else '')
    suffix = f'/{season}/{episode}' if kind == 'tv' else ''
    return f'https://{HOSTED[provider]["domain"]}/{kind}/{tmdb}{suffix}?autoplay=true'


class Providers:
    def __init__(self, network, addons):
        self.network, self.addons = network, addons
        self.browser_lock = asyncio.Lock()
        self.playwright = self.browser = None

    async def close(self):
        if self.browser:
            await self.browser.close()
        if self.playwright:
            await self.playwright.stop()

    async def hosted(self, provider, title):
        target = embed_url(provider, title)
        async with self.browser_lock:
            if not self.browser or not self.browser.is_connected():
                self.playwright = await async_playwright().start()
                self.browser = await self.playwright.chromium.launch(args=['--disable-dev-shm-usage'])
            context = await self.browser.new_context(service_workers='block', accept_downloads=False)
            found = asyncio.get_running_loop().create_future()
            async def route(request_route):
                try:
                    valid_url(request_route.request.url)
                except ValueError:
                    await request_route.abort()
                    return
                await request_route.continue_()
            await context.route('**/*', route)
            page = await context.new_page()
            async def capture(response):
                path = urlsplit(response.url).path.lower()
                content_type = response.headers.get('content-type', '').lower()
                if response.status in (200, 206) and (path.endswith(('.m3u8', '.mpd', '.mp4')) or 'mpegurl' in content_type or 'dash+xml' in content_type):
                    if found.done():
                        return
                    headers = await response.request.all_headers()
                    keep = {k:v for k,v in headers.items() if k.lower() in ('referer','origin','user-agent','cookie','authorization')}
                    if not found.done():
                        found.set_result({'url': response.url, 'headers': keep, 'mime': content_type.split(';')[0]})
            page.on('response', capture)
            try:
                async with asyncio.timeout(45):
                    try:
                        await page.goto(target, wait_until='domcontentloaded', timeout=25000)
                    except Exception:
                        if not found.done():
                            await page.wait_for_timeout(1000)
                    for _ in range(8):
                        if found.done():
                            break
                        body=(await page.locator('body').inner_text()).lower()
                        if 'sorry, you have been blocked' in body or 'verify you are human' in body:
                            raise ValueError('Provider requires a browser challenge or blocks this server')
                        if "couldn't find this content" in body:
                            raise ValueError('No stream for this title')
                        for frame in page.frames:
                            try:
                                await frame.evaluate("document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})})")
                                button = frame.locator('[aria-label="Play"], .vjs-big-play-button, [data-plyr="play"]').first
                                if await button.count():
                                    await button.click(timeout=1000)
                            except Exception:
                                pass
                        await page.wait_for_timeout(1500)
                    return await found
            finally:
                await context.close()

    async def resolve(self, provider, title):
        if provider == 'open-film':
            if title['id'] not in ('bbb','open:bbb'):
                return []
            return [{'url': OPEN_FILM, 'headers': {}, 'mime': 'application/vnd.apple.mpegurl'}]
        if provider in HOSTED:
            return [await self.hosted(provider, title)]
        addon = next(a for a in self.addons if a['id'] == provider)
        capabilities=addon.get('capabilities')
        if capabilities is not None:
            prefixes=capabilities.get(title['type'])
            if prefixes is None or (prefixes and not any(title['id'].startswith(p) for p in prefixes)):return []
        from urllib.parse import urlunsplit
        manifest_url=urlsplit(addon['manifest'])
        base=urlunsplit((manifest_url.scheme,manifest_url.netloc,manifest_url.path.removesuffix('/manifest.json'),'',''))
        raw, _ = await self.network.read(f'{base}/stream/{title["type"]}/{quote(title["id"], safe="")}.json'+('?' + manifest_url.query if manifest_url.query else ''))
        result = []
        for stream in json.loads(raw).get('streams', [])[:12]:
            url = stream.get('url', '')
            if not url.startswith(('http://','https://')):
                continue
            hints = stream.get('behaviorHints', {}).get('proxyHeaders', {}).get('request', {})
            result.append({'url':valid_url(url), 'headers': {k:v for k,v in hints.items() if k.lower() in ('referer','origin','user-agent','cookie','authorization')},
                           'subtitles': stream.get('subtitles', []), 'name': stream.get('name', addon['name'])})
        return result
