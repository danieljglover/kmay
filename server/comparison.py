"""Short, bounded comparisons. Results describe samples, not sustained bandwidth."""
import asyncio
import secrets
import time

from verification import probe, scoped_headers
from discovery import safe_error


async def measure(network, stream):
    targets = await probe(network, stream)
    rates, latencies = [], []
    # Two small samples, including a later HLS segment when available.
    for url in (targets if len(targets) > 1 else targets * 2):
        async with asyncio.timeout(4):
            start = time.monotonic()
            response = await network.open(url, {**scoped_headers(stream.get('headers', {}), stream['url'], url),
                                                'Range': 'bytes=0-524287'})
            try:
                if response.status not in (200, 206):
                    raise ValueError('Sample unavailable')
                size, first = 0, None
                while size < 524288:
                    data = await response.content.read(min(65536, 524288-size))
                    if not data:
                        break
                    if first is None:
                        first = time.monotonic()-start
                        if any(t in response.headers.get('Content-Type', '').lower() for t in ('text/', 'json', 'mpegurl', 'dash+xml')) or data.lstrip().lower().startswith((b'<html', b'<!doctype')):
                            raise ValueError('Sample was not media')
                    size += len(data)
                if size < 1024:
                    raise ValueError('Sample too small')
                rates.append(size*8/max(time.monotonic()-start, .001)/1_000_000)
                latencies.append(first*1000)
            finally:
                response.close()
    # The slower sample rewards consistency; response delay breaks close ties.
    return {'sample_mbps': round(min(rates), 2), 'response_ms': round(max(latencies)),
            'score': min(rates)/(1+max(latencies)/1000)}


class Comparisons:
    def __init__(self, network, providers, relay, budget=20, measure_fn=measure):
        self.network, self.providers, self.relay = network, providers, relay
        self.budget, self.measure = budget, measure_fn
        self.jobs = {}

    def start(self, title, candidates, base):
        cutoff = time.monotonic()-120
        self.jobs = {k:v for k,v in self.jobs.items() if v['started'] > cutoff or not v['task'].done()}
        if sum(not j['task'].done() for j in self.jobs.values()) >= 2 or len(self.jobs) >= 32:
            raise ValueError('Comparison busy. Please retry shortly.')
        key = secrets.token_urlsafe(18)
        job = {'started': time.monotonic(), 'state': 'running', 'results': [], 'streams': None,
               'rows': [{**p, 'state': 'queued', 'tested': 0, 'working': 0} for p in candidates],
               'base': base}
        self.jobs[key] = job
        job['task'] = asyncio.create_task(self.run(job, title))
        return key

    async def run(self, job, title):
        resolve_slots, sample_slot = asyncio.Semaphore(3), asyncio.Semaphore(1)

        async def check(row):
            try:
                async with resolve_slots:
                    row['state'] = 'resolving'
                    streams = await self.providers.resolve(row['id'], title)
                if not streams:
                    row['state'] = 'unavailable'
                    return
                row['state'] = 'waiting'
                # Round-robin scheduling at the sample lock lets other providers run.
                for stream in streams[:6]:
                    try:
                        async with sample_slot:
                            row['state'] = 'testing'
                            async with asyncio.timeout(6):
                                result = await self.measure(self.network, stream)
                        row['working'] += 1
                        job['results'].append({'provider': row['id'], 'name': row['name'],
                                               'stream': stream, **result})
                    except Exception as exc:
                        row['reason'] = 'Sample check timed out' if isinstance(exc,TimeoutError) else safe_error(exc)
                    row['tested'] += 1
                    row['state'] = 'waiting'
                row['state'] = 'ready' if row['working'] else 'unavailable'
            except Exception as exc:
                row['state'] = 'unavailable'
                row['reason'] = safe_error(exc)

        tasks = [asyncio.create_task(check(row)) for row in job['rows']]
        try:
            async with asyncio.timeout(self.budget):
                await asyncio.gather(*tasks)
        except TimeoutError:
            pass
        except asyncio.CancelledError:
            pass
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            for row in job['rows']:
                if row['state'] not in ('ready', 'unavailable'):
                    row['state'] = 'ready' if row['working'] else ('cancelled' if job['state'] == 'cancelled' else 'timed_out')
            if job['state'] != 'cancelled':
                job['state'] = 'complete'
            job['elapsed'] = min(self.budget, time.monotonic()-job['started'])

    def snapshot(self, key):
        job = self.jobs[key]
        results = sorted(job['results'], key=lambda r: (-r['score'], r['response_ms']))
        streams = []
        if job['state'] == 'complete':
            if job['streams'] is None:
                # Retain distinct working links, including alternates from the same provider.
                seen = set()
                job['streams'] = []
                for result in results[:12]:
                    if result['stream']['url'] in seen:
                        continue
                    seen.add(result['stream']['url'])
                    try:
                        stream = self.relay.create(result['provider'], result['stream'], job['base'])
                        job['streams'].append({**stream, 'provider_name': result['name'],
                                               'sample_mbps': result['sample_mbps'], 'response_ms': result['response_ms']})
                    except Exception:
                        continue
            streams = job['streams']
        return {'id': key, 'state': job['state'], 'budget_seconds': self.budget,
                'elapsed_seconds': round(job.get('elapsed', min(self.budget, time.monotonic()-job['started'])), 1),
                'providers': job['rows'], 'working': len(results), 'streams': streams}

    async def stop(self, key, cancel=False):
        job = self.jobs[key]
        if not job['task'].done():
            if cancel:
                job['state'] = 'cancelled'
            job['task'].cancel()
            try:
                await job['task']
            except asyncio.CancelledError:
                job['state'] = 'cancelled' if cancel else 'complete'
        return self.snapshot(key)

    async def close(self):
        for key in list(self.jobs):
            await self.stop(key, cancel=True)
