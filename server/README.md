# KMay proxy

Run on a Linux host with Docker Compose. Copy `.env.example` to `.env`, set a private LAN address for `KMAY_BIND` and choose a private access token for `KMAY_TOKEN`.

```sh
mkdir -p data
sudo chown 1000:1000 data
docker compose up -d --build
```

The app connects to `http://YOUR_SERVER_IP:8788`. Keep the service on a trusted private network, permit client access to TCP 8788, and restrict the container from reaching internal networks except any required DNS resolver. The service is designed for private LAN use; no public reverse proxy is configured here.

Discovery and verification run daily at 04:00 Europe/London with catch-up after downtime. The app can request an immediate refresh. Software updates are manual.

Feeds include Reddit, Champagne, FMHY, the Stremio community catalogue and the ElfHosted guide. Listing a website does not add a playback integration. Supported HTTP add-ons are verified for films and series separately; some require personal configuration. English and multilingual sources have priority, and unspecified languages are tested too. Torrent-only results are unsupported.

Each daily batch checks at most 100 new candidates with four concurrent add-on checks and a 15-minute budget. Unfinished work remains queued. Source availability varies by title.

Playback comparison has a 60-second overall deadline and finishes early when all checks complete. Link discovery is limited to 30 seconds per provider; each source then gets up to 20 seconds of active validation and sampling, excluding queue time. Up to six providers resolve links and four validate media concurrently; bandwidth samples run one at a time. It checks video, selected audio, initialisation/key resources and representative later HLS segments before ranking short transfer samples. Only tested HLS quality variants are exposed to the player. Samples cannot guarantee uninterrupted playback for an entire film.

Control endpoints require `Authorization: Bearer YOUR_TOKEN`: `/v1/status`, `/v1/providers`, `/v1/refresh`, `/v1/addons`, `/v1/resolve`, and `/v1/comparisons`. Playback uses temporary unguessable relay URLs. Do not publish configured manifests or relay URLs; they may contain access credentials.

Back up the data directory and private `.env` before upgrades. The SQLite database stores configured source URLs, discovery snapshots and health results.
