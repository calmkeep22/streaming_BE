# OCI deployment

Backend VM: Spring Boot, Redis primary/replica, three Sentinels, Nginx.
MySQL: managed MySQL HeatWave in the same VCN, private subnet.
Media VM: MediaMTX. Application/media ports initially bind to localhost.

## Initial operator setup

1. Run `deploy/scripts/bootstrap-oracle-linux.sh` as `opc` on each VM.
2. Run `python deploy/setup-secure-cicd.py` on the operator's PC. It uploads
   reviewed configuration, installs the root-owned deployment entry point,
   creates independent CI keys, tests their restrictions, and replaces GitHub
   Secrets. Administrator keys remain on the PC and are not registered in GitHub.
3. Run `deploy/configure-backend.ps1` on Windows. Override `-DbHost`, `-DbPort`,
   `-DbAdminUser` if needed. Enter the DB administrator password at the hidden
   prompt. The server creates the initial schema and an application account with
   SELECT/INSERT/UPDATE/DELETE, verifies TLS, and saves root-owned `backend.env`
   with mode 600. The administrator password is never saved. Existing tables and
   environment files are preserved; partial schemas require manual inspection.
4. On the media VM, run `initialize-media-env.sh` with the media public IP.
   Random media passwords are saved only on that VM, without printing them.

## CI credentials and permissions

| GitHub repository secret | Meaning |
| --- | --- |
| BACKEND_HOST | Backend public IP |
| BACKEND_SSH_KEY | Restricted CI key for backend |
| BACKEND_KNOWN_HOSTS | Pinned backend SSH host key |
| MEDIA_HOST | Media public IP |
| MEDIA_SSH_KEY | Restricted CI key for media |
| MEDIA_KNOWN_HOSTS | Pinned media SSH host key |

`GITHUB_TOKEN` is supplied by Actions automatically. DB/Redis/JWT/media passwords
stay on the VMs. Secrets must never be committed, printed, or included in images.

The `streaming-deploy` account has no docker/wheel group membership. Its key has
an SSH forced command, no shell/SFTP/forwarding, and sudo access to one root-owned
entry point with one fixed component argument. CI sends a bounded JSON request,
not a shell command, Compose file, or executable script. The server's Compose
files and deployment code are root-owned. Backend images must belong to this
repository and be pinned by digest; media images must use the official repository
and a versioned tag. Media configuration is mounted inside a read-only container
with all capabilities dropped. Configuration requiring host privileges is applied
separately by the operator after review.

Deploying an application necessarily permits changing the application's behavior
and accessing credentials/data available to that application. These restrictions
reduce host privileges; they do not make a compromised deployment harmless.
Protect the GitHub account and workflow changes accordingly.

GitHub-hosted runners need SSH connectivity. Before limiting ingress to a personal
IP, arrange a fixed runner IP, private network, or another deployment transport.

## Deployment and rollback

PRs run `Test`. Pushes to `main` run `Deploy`: test, build amd64 image, publish
to GHCR, deploy backend, then deploy media after the backend succeeds. Manual
dispatch is limited to `main`. The order keeps HTTP media authentication available.
Successful health checks update the last-good image/configuration. Failed releases
restore that state; failed first releases stop the application without deleting
volumes. Application restarts cause a brief interruption. Redis processes are not
force-recreated on routine backend deployment.

Application rollback does not revert database schema. Apply backward-compatible
schema migrations explicitly. Never run `docker compose down -v` against persistent
data. Three Sentinels on one VM handle Redis process failures, not VM failures.

## Seat pricing schema upgrade

Before deploying the seat-pricing application, apply
`backend/migrations/002-seat-grade-pricing.sql` as the DB administrator.
It preserves existing seats as STANDARD/50000 KRW and existing payment amounts.
Existing orders receive STANDARD as their historical grade. Each column is
checked independently so partial execution and retries preserve data.

Run on the operator's Windows PC after reviewing the migration:

```powershell
.\deploy\configure-backend.ps1 -Operation migrate-seat-pricing
```

The command uploads only this migration and its runner, then prompts for the
administrator password (hidden). It is sent over SSH stdin
and is not stored in GitHub or the VM environment. The application account keeps
DML privileges only. After this command succeeds, merge the application PR to
trigger normal CI/CD. Rollbacks may keep the added columns.

New event requests specify either the legacy `seatCount` (STANDARD/50000 KRW)
or `seatSections` with grade/count/price. Grades are FREE, STANDARD, PREMIUM, VIP.
Prices are configured per event: FREE must be zero; paid grades must be positive.
Payment orders retain their prepared amount and grade. Free seats cannot create
paid orders.

## Free reservations and admission

Before deploying admission code, run the additional migration as the operator:

```powershell
.\deploy\configure-backend.ps1 -Operation migrate-admission
```

This adds `reservation_entity.admission_type`. Existing rows default to PAID,
so old reservations without successful payment do not become free tickets.
Migration retries preserve FREE reservations created after the first run.
The administrator password is entered at the hidden prompt and is not saved.

- `POST /events/{eventId}/seats/{seatId}/holds`: acquire a seat hold.
- FREE: `POST /events/{eventId}/seats/{seatId}/free-reservations` confirms the
  caller's held free seat without creating a payment. Repeated/concurrent
  successful requests return the same active reservation ID (201).
- Paid grades: use the existing payment prepare/confirm APIs.
- `GET /events/{eventId}/seats/{seatId}/admission`: returns 200 with reservation,
  event, seat and grade only when the authenticated caller has an active FREE
  reservation or an active PAID reservation with a matching SUCCESS payment.
  Missing, foreign, canceled and unpaid reservations all return the same 403.
  Admission responses use `Cache-Control: no-store`.

FREE/PAID is fixed at reservation creation; the paid grade comes from the order
snapshot. Changing the seat later does not upgrade existing tickets. Canceling
a reservation revokes admission even while its refund is still pending.
This endpoint checks current database state. Playback tickets use the same checks.

## Media playback authentication

Before the first playback-auth release, the operator runs
`python deploy/configure-media-auth.py --apply`. It prepares the reviewed
root-owned Compose/Nginx files on both VMs, preserves backups, generates an
independent media signing key and a private TLS certificate on the backend, and transfers only SHA-256 hashes
of the existing publisher/monitor credentials. It neither rotates CI keys nor
prints passwords/signing keys. It does not restart the running services.

Allow TCP 8082 to the backend private address 10.0.0.217 from the media private
address **10.0.0.150/32** in the VCN. The callback uses HTTPS with the backend
certificate fingerprint pinned in MediaMTX. Preserve the generated certificate
across retries; renew it and update the fingerprint before its one-year expiry.
The callback listener serves only
`POST /internal/media/auth`; application routes are not served there. Normal
application Nginx rejects `/internal/`. Playback/publishing/API ports remain
bound to loopback until public HTTPS and client networking are configured.

Authenticated users request `POST /events/{eventId}/seats/{seatId}/playback-tickets`.
The response contains `token`, `path` (`events/{eventId}`), `whepPath`, `expiresAt`
and the booked `grade`. Pass the token as `Authorization: Bearer <token>` to
MediaMTX WHEP; keep it out of URLs, logs and shared viewer passwords.

MediaMTX uses HTTP authentication with no excluded actions. Reads require a
separate media JWT, a matching path/reservation and current valid DB admission.
Login JWTs and media JWTs cannot be interchanged. Publisher credentials grant
publish only for existing events; monitor credentials grant API only.
The previous internal credential entries exist only for configuration rollback
and are ignored while HTTP authentication is active.

Tickets last two minutes by default (maximum five), capped by login expiration.
The callback returns MediaMTX `expireAt`, which closes the session when its lease
ends. Cancellation immediately denies new connections, including reuse of an old
ticket; an already connected session may continue until its lease expires.
Clients must obtain a new ticket and reconnect for continued viewing. This release
does not implement seamless player reconnection or a frontend.

Authentication protocol: [MediaMTX HTTP authentication](https://mediamtx.org/docs/features/authentication).
Pinned server configuration: [MediaMTX v1.21.4](https://github.com/bluenviron/mediamtx/blob/v1.21.4/mediamtx.yml).

## Remaining production work

TLS is currently required for DB connections. Install the service CA and configure
VERIFY_IDENTITY for certificate and hostname verification. Add a domain/HTTPS and
the player before opening application/media traffic publicly. Publisher/monitor
credentials remain operator-only and `PAYMENT_GATEWAY=fake` remains a demo setting.
Export managed MySQL data before the Oracle trial ends.
