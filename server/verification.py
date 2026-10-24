"""Bounded playback checks shared by daily verification and interactive resolution."""
import asyncio
import re
from urllib.parse import urljoin,urlsplit
from defusedxml.ElementTree import fromstring


def scoped_headers(headers,origin,target):
    return {k:v for k,v in headers.items() if urlsplit(origin).netloc==urlsplit(target).netloc or k.lower() not in ('cookie','authorization')}


def hls_attributes(line):
    return {m[0]:m[1].strip('"') for m in re.findall(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)',line)}


async def verify_hls(network, stream, raw, url, overrides, depth=0, label='Video'):
    """Verify the selected video AND audio; relay only that tested variant."""
    if depth > 4:
        raise ValueError('Media manifest nesting exceeded')
    lines = [line.strip() for line in raw.decode('utf-8-sig').splitlines() if line.strip()]
    variants = [(line, lines[i+1]) for i,line in enumerate(lines[:-1]) if line.startswith('#EXT-X-STREAM-INF:')]
    headers = stream.get('headers', {})
    origin = stream['url']
    if variants:
        variants.sort(key=lambda v:int(hls_attributes(v[0]).get('BANDWIDTH','0')), reverse=True)
        last_error = ValueError('No working video variant')
        for line, child in variants[:6]:
            try:
                attrs = hls_attributes(line)
                audio_lines = [l for l in lines if l.startswith('#EXT-X-MEDIA:') and
                               hls_attributes(l).get('TYPE') == 'AUDIO' and
                               hls_attributes(l).get('GROUP-ID') == attrs.get('AUDIO')]
                audio_lines.sort(key=lambda l:(hls_attributes(l).get('LANGUAGE','').lower() not in ('en','eng','english'),
                                              hls_attributes(l).get('DEFAULT') != 'YES'))
                selected_audio = audio_lines[:1]
                for audio in selected_audio:
                    uri = hls_attributes(audio).get('URI')
                    if uri:
                        audio_url = urljoin(url,uri)
                        audio_raw,final = await network.read(audio_url,scoped_headers(headers,origin,audio_url),limit=4_000_000)
                        await verify_hls(network,stream,audio_raw,final,overrides,depth+1,'Audio')
                child_url = urljoin(url,child)
                child_raw,final = await network.read(child_url,scoped_headers(headers,origin,child_url),limit=4_000_000)
                targets = await verify_hls(network,stream,child_raw,final,overrides,depth+1,label)
                kept = []
                skip = False
                for entry in lines:
                    if skip:
                        skip = False
                        continue
                    if entry.startswith('#EXT-X-STREAM-INF:'):
                        skip = True
                        continue
                    if entry.startswith('#EXT-X-I-FRAME-STREAM-INF:'):
                        continue
                    if entry.startswith('#EXT-X-MEDIA:') and hls_attributes(entry).get('TYPE') == 'AUDIO' and entry not in selected_audio:
                        continue
                    kept.append(entry)
                # Keep redirects from changing the base used by relative child URLs.
                pinned = [re.sub(r'URI="([^"]+)"',lambda m:'URI="'+urljoin(url,m[1])+'"',entry)
                          if entry.startswith('#') else urljoin(url,entry) for entry in kept+[line,child]]
                overrides[url] = '\n'.join(pinned)+'\n'
                return targets
            except (ValueError, OSError) as exc:
                last_error = exc
        raise last_error
    segments = [urljoin(url,line) for line in lines if not line.startswith('#')]
    if not segments:
        raise ValueError('Empty HLS playlist')
    # Check the start and several later segments, rather than only two video bytes.
    selected = list(dict.fromkeys(segments[min(i,len(segments)-1)] for i in (0,1,2,5,8,12)))
    resources = [(target, 'Audio segment' if label == 'Audio' else 'Later media segment') for target in selected]
    for line in lines:
        if line.startswith(('#EXT-X-KEY:', '#EXT-X-MAP:')):
            attrs = hls_attributes(line)
            if attrs.get('METHOD') not in (None,'NONE','AES-128'):
                raise ValueError('Unsupported encrypted HLS stream')
            if attrs.get('URI'):
                resources.append((urljoin(url,attrs['URI']), 'Media initialization or key'))
    slots = asyncio.Semaphore(4)
    async def check(target, kind):
        async with slots:
            response = await network.open(target,scoped_headers(headers,origin,target))
            try:
                if response.status not in (200,206):
                    raise ValueError(f'{kind} unavailable (HTTP {response.status})')
                data = await response.content.read(1024)
                if not data:
                    raise ValueError(f'{kind} unavailable')
                if any(t in response.headers.get('Content-Type','').lower() for t in ('text/','json','mpegurl','dash+xml')) or data.lstrip().lower().startswith((b'<html',b'<!doctype')):
                    raise ValueError(f'{kind} was not media')
            finally:
                response.close()
    tasks = [asyncio.create_task(check(*item)) for item in dict.fromkeys(resources)]
    try:
        await asyncio.gather(*tasks)
    finally:
        for task in tasks:task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
    return [selected[0],selected[-1]]


def dash_segment(raw,url):
    root=fromstring(raw)
    def tag(n):return n.tag.split('}')[-1]
    def walk(node,base,template=None,segment_list=None):
        bases=[c for c in node if tag(c)=='BaseURL']
        if bases:base=urljoin(base,bases[0].text or '')
        templates=[c for c in node if tag(c)=='SegmentTemplate']
        if templates:
            attrs=dict(template[0]) if template else {}
            attrs.update(templates[0].attrib)
            timeline=next((c for c in templates[0] if tag(c)=='SegmentTimeline'),template[1] if template else None)
            template=(attrs,timeline)
        lists=[c for c in node if tag(c)=='SegmentList']
        if lists:segment_list=lists[0]
        if tag(node)=='Representation':
            if template:
                attrs,timeline=template
                media=attrs.get('media')
                if not media:raise ValueError('DASH template has no media segment')
                first=next(iter(timeline),None) if timeline is not None else None
                values={'RepresentationID':node.get('id',''),'Bandwidth':node.get('bandwidth','0'),
                        'Number':attrs.get('startNumber','1'),'Time':first.get('t','0') if first is not None else '0'}
                def replace(m):
                    value=values[m[1]]
                    return value.zfill(int(m[2])) if m[2] else value
                media=re.sub(r'\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\d+)d)?\$',replace,media)
                if '$' in media:raise ValueError('Unsupported DASH template')
                return urljoin(base,media)
            if segment_list is not None:
                segment=next((c for c in segment_list if tag(c)=='SegmentURL'),None)
                if segment is not None:return urljoin(base,segment.get('media',''))
            if base!=url and not base.endswith('/'):return base
        for child in node:
            if tag(child) in ('Period','AdaptationSet','Representation'):
                found=walk(child,base,template,segment_list)
                if found:return found
    segment=walk(root,url)
    if not segment:raise ValueError('DASH has no supported media segment')
    return segment


async def probe(network,stream):
    origin=stream['url'];headers=stream.get('headers',{});url=origin
    async with asyncio.timeout(35):
        for depth in range(5):
            response=await network.open(url,{**scoped_headers(headers,origin,url),'Range':'bytes=0-1023'})
            try:
                if response.status not in (200,206):raise ValueError('Media bytes unavailable')
                content_type=response.headers.get('Content-Type','').lower()
                final=str(response.url)
                data=await response.content.read(1024)
                if not data:raise ValueError('Media bytes unavailable')
                hls=b'#EXTM3U' in data[:20] or 'mpegurl' in content_type
                dash='dash+xml' in content_type or b'<MPD' in data
                if not hls and not dash:
                    if 'text/' in content_type or 'json' in content_type or data.lstrip().lower().startswith((b'<!doctype',b'<html')):raise ValueError('Host returned a document instead of media')
                    if urlsplit(final).path.endswith(('.mpd','.m3u8')):raise ValueError('Response was not a media manifest')
                    return [final]
            finally:response.close()
            raw,final=await network.read(final,scoped_headers(headers,origin,final),limit=4_000_000)
            if dash:url=dash_segment(raw,final)
            else:
                overrides={}
                targets=await verify_hls(network,stream,raw,final,overrides)
                if final in overrides:overrides[origin]=overrides[final]
                stream['_hls_manifests']=overrides
                stream['mime']='application/vnd.apple.mpegurl'
                return targets
        raise ValueError('Media manifest nesting exceeded')
