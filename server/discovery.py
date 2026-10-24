import asyncio
from datetime import datetime,timedelta
import json
from urllib.parse import urljoin,quote,urlsplit
from zoneinfo import ZoneInfo
from bs4 import BeautifulSoup
from feeds import parse_links,parse_markdown,parse_catalog,candidate,COMMUNITY,ELFHOSTED,FMHY_BACKUP,public_candidate
from network import valid_url
from store import now

DIRECTORIES={'reddit':'https://www.reddit.com/r/Piracy/wiki/megathread/movies_and_tv/',
 'champagne':'https://champagne.pages.dev/movies-shows-sports/general-entertainment','fmhy':'https://fmhy.net/video',
 'stremio-community':COMMUNITY,'elfhosted':ELFHOSTED}


def scheduled_day(current=None):
    current=current or datetime.now(ZoneInfo('Europe/London'))
    if current.hour<4:current-=timedelta(days=1)
    return current.date().isoformat()


class Discovery:
    def __init__(self,network,store,health):
        self.network,self.store,self.health=network,store,health
        self.lock=asyncio.Lock()

    async def fetch(self,name,url):
        raw,final=await self.network.read(url)
        if name=='stremio-community':
            manifest=json.loads(raw)
            catalogs=manifest.get('addonCatalogs',[])
            if not catalogs:raise ValueError('Catalog manifest contains no add-on catalogs')
            items=[]
            for c in catalogs[:5]:
                endpoint=final.rsplit('/',1)[0]+'/addon_catalog/'+quote(c['type'],safe='')+'/'+quote(c['id'],safe='')+'.json'
                data,_=await self.network.read(endpoint)
                items.extend(parse_catalog(data,url))
            return items
        items=parse_links(raw,final)
        if name=='elfhosted':
            # Only listed provider landing pages, never an unrestricted crawl.
            items=[i for i in items if urlsplit(i['url']).hostname.endswith(('.elfhosted.com','.hayd.uk'))]
            sem=asyncio.Semaphore(4)
            async def landing(item):
                async with sem:
                    manifests=[]
                    try:
                        async with asyncio.timeout(20):
                            page,target=await self.network.read(item['url'])
                            for a in BeautifulSoup(page,'html.parser').select('a[href]'):
                                link=urljoin(target,a['href'].replace('stremio://','https://',1))
                                if urlsplit(link).path.endswith('/manifest.json'):
                                    manifests.append(candidate(link,item['name'],url,valid_url(link)))
                    except Exception:pass
                    link=item['url']+'/manifest.json'
                    manifests.append(candidate(link,item['name'],url,link))
                    return manifests
            groups=await asyncio.gather(*(landing(i) for i in items[:50]))
            return list({i['id']:i for group in groups for i in group}.values())
        return items

    async def refresh(self):
        if self.lock.locked():return
        async with self.lock:
            reports={}
            for name,url in DIRECTORIES.items():
                previous=self.store.get('directory:'+name,[])
                try:
                    used=url
                    try:
                        async with asyncio.timeout(90):items=await self.fetch(name,url)
                        if len(items)<5:raise ValueError('Directory result unexpectedly small')
                    except Exception:
                        if name!='fmhy':raise
                        async with asyncio.timeout(30):raw,_=await self.network.read(FMHY_BACKUP)
                        items=parse_markdown(raw,FMHY_BACKUP);used=FMHY_BACKUP
                    if len(items)<5 or (previous and len(items)<len(previous)//2):raise ValueError('Directory result unexpectedly small; kept previous snapshot')
                    old={i['id']:i for i in previous}
                    for item in items:
                        item['first_seen']=old.get(item['id'],{}).get('first_seen',now())
                        item['last_seen']=now()
                    self.store.put('directory:'+name,items)
                    reports[name]={'ok':True,'count':len(items),'checked_at':now(),'source':used}
                except Exception as exc:
                    reports[name]={'ok':False,'count':len(previous),'error':safe_error(exc),'checked_at':now()}
            self.store.put('discovery',{'checked_at':now(),'directories':reports})
            if any(r['ok'] for r in reports.values()):self.store.put('last_successful_update',now())
            try:
                async with asyncio.timeout(900):await self.health()
            except TimeoutError:self.store.put('verification_budget_exhausted',now())
            finally:self.store.put('scheduled_day',scheduled_day())

    def candidates(self,public=False):
        merged={}
        states=self.store.get('candidate_states',{})
        for name in DIRECTORIES:
            for item in self.store.get('directory:'+name,[]):
                entry=merged.setdefault(item['id'],{**item,'origins':[]})
                if item['origin'] not in entry['origins']:entry['origins'].append(item['origin'])
        for item in merged.values():
            item.update(states.get(item['id'],{'status':'queued' if item.get('manifest') else 'unsupported','reason':'Awaiting verification' if item.get('manifest') else 'Website adapter required'}))
        return [public_candidate(i) if public else i for i in merged.values()]

    def counts(self):
        items=self.candidates()
        return {'discovered':len(items),'queued':sum(i['status']=='queued' for i in items),
                'checked':sum(bool(i.get('checked_at')) for i in items),
                'verified':sum(bool(i.get('verified_at')) for i in items),'enabled':sum(i['status']=='enabled' for i in items)}

    async def schedule(self):
        while True:
            try:
                if self.store.get('scheduled_day')!=scheduled_day():await self.refresh()
            except Exception:
                self.store.put('refresh_error',{'checked_at':now(),'error':'Refresh failed; retrying'})
            await asyncio.sleep(30)


def safe_error(exc):
    import re
    if isinstance(exc,ValueError) and ('http' not in str(exc).lower() or re.fullmatch(r'Upstream HTTP \d{3}|(?:Audio segment|Later media segment|Media initialization or key) unavailable \(HTTP \d{3}\)',str(exc))):return str(exc)[:180]
    return type(exc).__name__+': upstream request failed'
