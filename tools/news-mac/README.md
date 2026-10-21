# Soma News on the Mac Pro: FreshRSS

The phone's news reader reads from FreshRSS on the Mac Pro, through FreshRSS's Google Reader API, over Tailscale only.

- Docker Desktop container `freshrss` (image `freshrss/freshrss:latest`), published on the Mac Pro's Tailscale
  address only: `http://100.120.219.46:8482` (never 0.0.0.0; 127.0.0.1:8482 belongs to Soma, not FreshRSS).
- Data in Docker volumes `freshrss_data` (config, SQLite, user `boss`) and `freshrss_extensions`.
- `--restart unless-stopped`: it comes back with Docker Desktop, which starts at login (as Home Assistant does).
- Feeds refresh every 30 minutes by the image's own cron (`CRON_MIN='*/30'`; runs at :07 and :37).
- User `boss`, API access on. The passwords live only in `~/.config/freshrss/credentials` (chmod 600), never in Git.
- Phone: `POST /api/greader.php/accounts/ClientLogin` (`Email=boss&Passwd=<API password>`) gives `Auth=`; then
  `GET /api/greader.php/reader/api/0/stream/contents/user/-/state/com.google/reading-list?n=20` with
  `Authorization: GoogleLogin auth=<token>`.

## Feeds
Tailscale blog, The Verge, Hacker News front page (250+ points), Import AI, Android Police, 9to5Google, Ars Technica,
SamMobile, Simon Willison, and FreshRSS's own releases (added by the installer). Tailscale, Android Police and
9to5Google send summaries only; full text would need a per-site CSS selector (feed settings > Advanced > "Article
CSS selector on original website").

## Restart and repair
    docker restart freshrss                     # restart
    docker logs --tail 50 freshrss              # what it said
    docker exec -u www-data freshrss php ./cli/actualize-user.php --user boss         # refresh now
    docker exec -u www-data freshrss php ./cli/update-user.php --user boss --api-password '...'   # new API password
Recreate it (data stays in the volumes):
    docker rm -f freshrss
    docker run -d --name freshrss --restart unless-stopped -p 100.120.219.46:8482:80 -e TZ=Pacific/Honolulu \
      -e CRON_MIN='*/30' -v freshrss_data:/var/www/FreshRSS/data -v freshrss_extensions:/var/www/FreshRSS/extensions \
      freshrss/freshrss:latest

If Tailscale isn't up when Docker starts, the published address can't be bound: `docker restart freshrss`.
If `docker pull` hangs, Docker Desktop's credential helper is waiting on the Keychain; a public image pulls with
`DOCKER_CONFIG=<dir with {"currentContext":"desktop-linux"}> docker pull ...`.
