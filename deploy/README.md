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
to GHCR, deploy backend/media. Manual dispatch is limited to `main`.
Successful health checks update the last-good image/configuration. Failed releases
restore that state; failed first releases stop the application without deleting
volumes. Application restarts cause a brief interruption. Redis processes are not
force-recreated on routine backend deployment.

Application rollback does not revert database schema. Apply backward-compatible
schema migrations explicitly. Never run `docker compose down -v` against persistent
data. Three Sentinels on one VM handle Redis process failures, not VM failures.

## Remaining production work

TLS is currently required for DB connections. Install the service CA and configure
VERIFY_IDENTITY for certificate and hostname verification. Add a domain/HTTPS and
ticket-based media authorization before opening application/media traffic publicly.
Static media publisher/viewer accounts and `PAYMENT_GATEWAY=fake` are bootstrap/demo
settings. Export managed MySQL data before the Oracle trial ends.
