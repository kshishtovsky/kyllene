#!/usr/bin/env bash
# Прокидывает Hermes-дашборд (через обратный SSH-туннель 127.0.0.1:19119) наружу по HTTPS
# на уже существующий домен: https://mylifemyrulesonline.ru/hermes
# Домен/сертификат/443 уже есть -> ни DNS, ни ufw не трогаем.
set -euo pipefail
export PATH="$PATH:/usr/local/bin:/usr/bin:/bin"

CF=/etc/caddy/Caddyfile
DOMAIN=mylifemyrulesonline.ru
UP=127.0.0.1:19119
MARK="# hermes-dashboard (added by hermes-tls.sh)"

[ "${EUID:-$(id -u)}" -eq 0 ] || { echo "запускать через sudo: sudo ~/hermes-tls.sh"; exit 1; }

grep -q "$MARK" "$CF" && { echo "уже настроено, ничего не меняю"; exit 0; }

# Адрес сайта в Caddyfile может быть литералом или {$ENV}: берём значение переменной у самого сервиса.
ENV_HOST=$(systemctl show caddy -p Environment 2>/dev/null | tr ' ' '\n' | sed -n 's/^TPROXY_HOSTNAME=//p' | head -1)
echo "домен из окружения caddy: ${ENV_HOST:-неизвестен}"

cp -a "$CF" "$CF.bak.$(date +%Y%m%d-%H%M%S)"

python3 - "$CF" "$DOMAIN" "$ENV_HOST" "$UP" "$MARK" <<'PY' || { echo "правка не применена"; exit 2; }
import re, sys

path, domain, env_host, upstream, mark = sys.argv[1:6]
lines = open(path, encoding="utf-8").read().splitlines()

# Site-блок: строка с нулевого отступа, кончается "{". Голая "{" — глобальные опции, её пропускаем.
heads = [i for i, ln in enumerate(lines)
         if re.match(r'^[^\s#]', ln) and ln.rstrip().endswith("{") and ln.strip() != "{"]

def is_ours(line):
    # срезаем фигурную скобку: адрес может сам начинаться с "{" (env-переменная)
    hosts = line.rstrip().rstrip("{").replace(",", " ").split()
    return any(h == domain or (h.startswith("{$") and env_host and h.strip("{$}") == "TPROXY_HOSTNAME")
               for h in hosts)

picked = [i for i in heads if is_ours(lines[i])]
if len(picked) != 1:
    sys.stderr.write(f"site-блок для {domain} не найден однозначно (кандидатов {len(picked)})\n")
    sys.exit(2)

head = picked[0]
end = next(i for i in range(head + 1, len(lines)) if lines[i].rstrip() == "}")
body = [ln for ln in lines[head + 1:end] if ln.strip()]
indent = re.match(r'^\s*', body[0]).group(0) if body else "    "

lines[head + 1:head + 1] = [
    f"{indent}{mark}",
    f"{indent}handle_path /hermes/* {{",
    f"{indent}    reverse_proxy {upstream} {{",
    f"{indent}        flush_interval -1",
    f"{indent}        header_up X-Forwarded-Prefix /hermes",
    f"{indent}    }}",
    f"{indent}}}",
]
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(f"вставил после строки {head + 1}")
PY

if ! caddy validate --config "$CF" --adapter caddyfile >/tmp/caddy-validate.log 2>&1; then
  echo "ОШИБКА валидации, откатываю:" >&2
  tail -5 /tmp/caddy-validate.log >&2
  cp -a "$(ls -1t "$CF".bak.* | head -1)" "$CF"
  exit 3
fi
echo "caddy validate: ок"

systemctl reload caddy 2>/dev/null || systemctl restart caddy
sleep 2

echo "--- проверки с самого VPS:"
curl -s -o /dev/null -w "  туннель     127.0.0.1:19119/api/status -> %{http_code}\n" -m 5 http://127.0.0.1:19119/api/status || true
curl -s -o /dev/null -w "  новый путь  https://$DOMAIN/hermes/api/status -> %{http_code}\n" -m 10 "https://$DOMAIN/hermes/api/status" || true
curl -s -o /dev/null -w "  старый сайт https://$DOMAIN/ -> %{http_code}\n" -m 10 "https://$DOMAIN/" || true
echo
echo "200 на /hermes/api/status = готово. 502 = прокси жив, а туннель или дашборд лежит."
echo "откат: cp -a $(ls -1t "$CF".bak.* | head -1) $CF && systemctl reload caddy"
