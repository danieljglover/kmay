"""Source-specific directory parsers. Published manifests remain unmodified internally."""
import hashlib
import json
import re
from urllib.parse import urljoin, urlsplit
from bs4 import BeautifulSoup
from network import valid_url

COMMUNITY = 'https://stremio-addons.net/api/manifest.json'
ELFHOSTED = 'https://stremio-addons-guide.elfhosted.com/'
FMHY_BACKUP = 'https://raw.githubusercontent.com/wiki/fmhy/FMHY/Streaming.md'
ADAPTERS = {'www.rivestream.app':'rive','rivestream.app':'rive','vidlink.pro':'vidlink','vidfast.pro':'vidfast'}
IGNORE = {'reddit.com','www.reddit.com','github.com','discord.com','discord.gg','youtube.com','www.youtube.com'}


def addon_id(url):
    return 'addon-' + hashlib.sha256(url.encode()).hexdigest()[:16]


def candidate(url, name, source, manifest=None):
    url = valid_url(url)
    p = urlsplit(url)
    return {'id':addon_id(manifest) if manifest else p.hostname,'name':str(name)[:160],
            'url':f'{p.scheme}://{p.netloc}','manifest':manifest,'adapter':ADAPTERS.get(p.hostname),'origin':source}


def parse_links(html, source):
    soup = BeautifulSoup(html, 'html.parser')
    root = soup.select_one('.wiki-page-content, .md, main, article') or soup
    links = {}
    for a in root.select('a[href]'):
        url = urljoin(source, a['href'].replace('stremio://','https://',1))
        try:
            valid_url(url)
            p = urlsplit(url)
            if p.hostname == urlsplit(source).hostname or p.hostname in IGNORE:continue
            name = a.get_text(' ',strip=True)
            if not name:continue
            item = candidate(url,name,source,url if p.path.endswith('/manifest.json') else None)
            links[item['id']] = item
        except (ValueError,TypeError):continue
    return list(links.values())


def parse_markdown(raw, source):
    links = {}
    for name,url in re.findall(r'\[([^\]]+)\]\((https?://[^\s)]+)\)',raw.decode('utf-8-sig')):
        try:
            if urlsplit(url).hostname in IGNORE:continue
            item=candidate(url,name,source,url if urlsplit(url).path.endswith('/manifest.json') else None)
            links[item['id']]=item
        except ValueError:continue
    return list(links.values())


def parse_catalog(raw, source):
    data=json.loads(raw)
    if not isinstance(data,dict) or not isinstance(data.get('addons'),list):raise ValueError('Invalid add-on catalog')
    items={}
    for entry in data['addons'][:5000]:
        if not isinstance(entry,dict):continue
        manifest=entry.get('manifest',{})
        if not isinstance(manifest,dict):continue
        url=entry.get('transportUrl','')
        try:
            if not urlsplit(url).path.endswith('/manifest.json'):continue
            item=candidate(url,manifest.get('name','Unnamed add-on'),source,url)
            item['description']=str(manifest.get('description',''))[:4000]
            item['eligibility_hint']=eligibility(manifest)[0]
            item['language_priority']=0 if eligibility(manifest)[1]=='Playback verification pending' else 1
            items[item['id']]=item
        except (ValueError,TypeError):continue
    return list(items.values())


def stream_types(manifest):
    result={}
    for resource in manifest.get('resources',[]):
        if resource=='stream':resource={'name':'stream','types':manifest.get('types',[]),'idPrefixes':manifest.get('idPrefixes',[])}
        if not isinstance(resource,dict) or resource.get('name')!='stream':continue
        prefixes=resource.get('idPrefixes',manifest.get('idPrefixes',[]))
        if prefixes and not any('tt0137523'.startswith(p) or p.startswith('tt') for p in prefixes if isinstance(p,str)):continue
        for kind in resource.get('types',manifest.get('types',[])):
            if kind in ('movie','series'):result[kind]=prefixes
    return result


def eligibility(manifest):
    if not isinstance(manifest,dict) or not manifest.get('id') or not manifest.get('name'):return 'invalid_manifest','Missing add-on identity'
    if manifest.get('behaviorHints',{}).get('configurationRequired'):return 'configuration_required','Configure this add-on manually'
    text=' '.join(str(manifest.get(k,'')) for k in ('name','description','languages','language')).lower()
    if re.search(r'porn|hentai|\b(nsfw|xxx|adult)\b',text):return 'out_of_scope','Adult catalog'
    if not stream_types(manifest):return 'incompatible','No movie/series stream resource for IMDb identifiers'
    if not re.search(r'\b(english|multilingual|multi-language|multi language)\b|[\'\"]en[\'\"]',text):
        languages=str(manifest.get('languages',manifest.get('language',''))).lower()
        if re.search(r'\b(french|fran[cç]ais|spanish|espa[nñ]ol|castellano|latino|portuguese|portugu[eê]s|russian|german|deutsch|italian|italiano|telugu|hindi|korean|arabic|persian|vietnamese|turkish)\b',text) or re.search(r'\b(fr|es|pt|ru|de|it|ar|fa|vi|tr|ko|hi|te)\b',languages):
            return 'language_pending','Declared language does not include English'
        return 'queued','Language unspecified; HTTP playback verification pending'
    return 'queued','Playback verification pending'


def public_candidate(item):
    # Configured manifest paths may contain credentials; expose only safe metadata.
    return {k:v for k,v in item.items() if k not in ('manifest','description')}
