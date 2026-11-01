#!/usr/bin/env bash
# Прокидывает Hermes-дашборд (через обратный SSH-туннель на 127.0.0.1:19119) наружу по HTTPS
# на уже существующий домен: https://mylifemyrulesonline.ru/hermes
# Cert и порт 443 уже работают -> ни DNS, ни ufw трогать не надо.
set -euo pipefail

CF=/etc/caddy/Caddyfile
DOMAIN=mylifemyrulesonline.ru
UP=127.0.0.1:19119
MARK="# hermes-dashboard (added by hermes-tls.sh)"

[ "${EUID:-$(id -u)}" -eq 0 ] || { echo "запускать через sudo"; exit 1; }

if grep -q "$MARK" "$CF"; then
  echo "уже настроено, ничего не меняю"
  exit 0
fi

LINE=$(grep -nE "^[[:space:]]*([a-z]+://)?([a-z0-9*.-]+,)?$DOMAIN[^ ]*[[:space:]]*\{" "$CF" | head -1 | cut -d: -f1 || true)
if [ -z "${LINE:-}" ]; then
  echo "ОШИБКА: в $CF не нашёл site-блок для $DOMAIN. Вот файл, покажи его целиком:" >&2
  sed -n '1,200p' "$CF" >&2
  exit 2
fi

cp -a "$CF" "$CF.bak.$(date +%Y%m%d-%H%M%S)"

python3 - "$CF" "$LINE" "$UP" "$MARK" <<'PY'
import sys
path, line, upstream, mark = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
lines = open(path, encoding="utf-8").read().splitlines()
block = [
    f"    {mark}",
    "    handle_path /hermes/* {",
    f"        reverse_proxy {upstream} {{",
    "            flush_interval -1",
    "            header_up X-Forwarded-Prefix /hermes",
    "        }",
    "    }",
    "",
]
lines[line:line] = block
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY

if ! caddy validate --config "$CF" --adapter caddyfile >/tmp/caddy-validate.log 2>&1; then
  echo "ОШИБКА валидации, откатываю:" >&2
  cat /tmp/caddy-validate.log >&2
  cp -a "$(ls -1t $CF.bak.* | head -1)" "$CF"
  exit 3
fi

systemctl reload caddy 2>/dev/null || systemctl restart caddy 2>/dev/null || caddy reload --config "$CF" --adapter caddyfile
sleep 2
echo "--- проверка с самого сервера:"
curl -s -o /dev/null -w "локально  http://127.0.0.1:19119/api/status -> %{http_code}\n" -m 5 http://127.0.0.1:19119/api/status || true
curl -s -o /dev/null -w "снаружи   https://$DOMAIN/hermes/api/status   -> %{http_code}\n" -m 10 "https://$DOMAIN/hermes/api/status" || true
echo "готово"
