# Reaching a home gateway from anywhere: the tunnel setup

The app talks to `hermes dashboard` (`hermes serve`) running on your own PC. That PC has no public address, and
this app needs no relay of its own: one reverse SSH tunnel and one reverse proxy are enough. This is the setup the
fork is built and tested against.

```
phone ──LTE──▶ https://<domain>/hermes ──▶ Caddy on the VPS ──▶ 127.0.0.1:19119 (ssh -R)
                                                                    │
                                                                    ▼
                                                    127.0.0.1:9119 on the PC (hermes serve)
```

Nothing listens on a public port of the PC, and no port of the VPS is opened in its firewall: the proxy is the only
way in, and it speaks HTTPS.

## 1. The PC side

The tunnel and the dashboard are one unit: if either dies, the other is useless. One loop keeps both up
(a Windows scheduled task running this `.cmd` at logon):

```bat
@echo off
setlocal
set "H=%LOCALAPPDATA%\hermes\hermes-agent\venv\Scripts"
:loop
curl -s -o nul -m 3 http://127.0.0.1:9119/api/status
if errorlevel 1 (
  start "" /min "%H%\hermes.exe" serve --skip-build
  timeout /t 20 /nobreak >nul
)
ssh -N -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new -R 127.0.0.1:19119:127.0.0.1:9119 -p 10222 medovarov@45.67.202.247
timeout /t 10 /nobreak >nul
goto loop
```

`ssh -R` binds `127.0.0.1:19119` on the *VPS*, not on its public interface, so the tunnel itself is unreachable from
the internet; only the proxy can use it. `ServerAliveInterval` makes the tunnel die and re-form instead of hanging
half-open when the link drops (a laptop sleeping, a router rebooting, an ISP changing the address).

## 2. The gateway side

The dashboard refuses a `Host` header it was not told about, and a reverse proxy on a path prefix needs to say so.
Both come from one setting:

```bash
hermes config set dashboard.public_url https://<domain>/hermes
```

Restart `hermes serve` afterwards; the value is read at start. Consequences:

- a request arriving with `Host: <domain>` is accepted, and every other host (the raw IP, a random name) is refused
  with 400 — the address cannot be reached by anyone who has not been told the name;
- cookies and generated links are scoped to `/hermes`, so they stay inside the prefix;
- basic auth (`dashboard.basic_auth`) stays on: the host guard is a name check, not a password.

## 3. The VPS side

Caddy terminates TLS with a certificate for the domain you already own, strips the prefix and forwards to the tunnel:

```
<domain> {
    handle_path /hermes/* {
        reverse_proxy 127.0.0.1:19119 {
            flush_interval -1
            header_up X-Forwarded-Prefix /hermes
        }
    }
}
```

`flush_interval -1` turns off response buffering, which a WebSocket stream needs; Caddy handles the `Upgrade` itself.
`X-Forwarded-Prefix` is what makes the dashboard build prefixed URLs (asset paths, `Location`, cookie `Path`).

No DNS change and no firewall change are needed if the domain already points at the VPS.

`docs/tunnel-setup.sh` in this repository does this edit with a backup and a validate-then-rollback, so a typo cannot
leave Caddy broken.

## 4. What the app does with such an address

Enter `https://<domain>/hermes` on the connect screen. The fork adds three rules that matter for this setup:

- **A public address without `https://` is refused, not just warned about.** With a tunnel that is the difference
  between an encrypted channel and your password and every chat in the clear over LTE: the proxy answers on a public
  address, so a plain `http://` URL is exactly the case that leaks. Sign-in stays disabled until the address is
  `https://` (or a private one, e.g. over Tailscale or the LAN).
- **`502` / `503` / `504` from the proxy are named as what they are.** The proxy is up, nothing answered behind it:
  the tunnel or the dashboard is down. Before, this read "this is not a Hermes dashboard, check port 9119", which
  pointed at the wrong machine — the one thing that actually goes wrong with this setup.
- **The chat says `Gateway down` instead of `Waiting for network…`** when the answer was a proxy error, so a dead
  tunnel is not mistaken for a dead mobile connection.

## 5. What still breaks, and how you will see it

| What happened | What the app shows |
| --- | --- |
| PC asleep / `hermes serve` stopped | `Gateway down · retrying…`, then reconnects on its own within ~30 s of the loop restarting it |
| SSH tunnel dropped | same, once Caddy answers 502 |
| VPS down or DNS wrong | `Waiting for network…` only if the host does not resolve; otherwise `Can't reach <host>: …` |
| Certificate expired | connection error naming the TLS handshake; the app trusts the system store only |
| Wrong password / rotated cookie | `Session expired`, sign-in screen |

A turn that was streaming when the link died is not lost: the app resumes the session and replays from the last event
it saw, and an undelivered prompt is kept, not silently resent.
