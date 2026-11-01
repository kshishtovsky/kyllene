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
CADDY=/usr/local/bin/caddy

[ "${EUID:-$(id -u)}" -eq 0 ] || { echo "запускать через sudo: sudo ~/hermes-tls.sh"; exit 1; }
grep -q "$MARK" "$CF" && { echo "уже настроено, ничего не меняю"; exit 0; }

# caddy validate из моей сессии не наследует окружение systemd, а в Caddyfile есть {$ACME_EMAIL}
# и {$TPROXY_HOSTNAME}: без них он падает на 'email' и выглядит как ошибка правки.
SVC_ENV=$(systemctl show caddy -p Environment | sed 's/^Environment=//')
for name in $(grep -oE '\{\$[A-Za-z_][A-Za-z0-9_]*\}' "$CF" | tr -d '{} $' | sort -u); do
  val=$(for kv in $SVC_ENV; do case "$kv" in "$name="*) echo "${kv#*=}" ;; esac; done | head -1)
  if [ -z "${val:-}" ]; then echo "ОШИБКА: в окружении caddy нет $name, а Caddyfile его требует"; exit 4; fi
  export "$name=$val"
done

# Pre-flight: файл должен валидироваться ещё до моих правок, иначе чинить надо не меня.
if ! "$CADDY" validate --config "$CF" --adapter caddyfile >/tmp/caddy-validate.log 2>&1; then
  echo "ОШИБКА: исходный $CF не валидируется и без правок, ничего не трогаю:" >&2
  tail -3 /tmp/caddy-validate.log >&2
  exit 4
fi
echo "исходный Caddyfile валиден"

cp -a "$CF" "$CF.bak.$(date +%Y%m%d-%H%M%S)"

python3 - "$CF" "$DOMAIN" <<'PY' || { echo "правка не применена, файл не тронут"; exit 2; }
import os, re, sys

path, domain = sys.argv[1], sys.argv[2]
lines = open(path, encoding="utf-8").read().splitlines()

# Site-блок: строка с нулевого отступа, кончается "{". Голая "{" — глобальные опции, её пропускаем.
heads = [i for i, ln in enumerate(lines)
         if re.match(r'^[^\s#]', ln) and ln.rstrip().endswith("{") and ln.strip() != "{"]

def is_ours(line):
    # Срезаем хвостовую "{": адрес может сам начинаться с неё ({$TPROXY_HOSTNAME}).
    for host in line.rstrip().rstrip("{").replace(",", " ").split():
        if host == domain:
            return True
        # {$ИМЯ} — сравниваем по значению, уже подставленному в окружение.
        if host.startswith("{$") and host.endswith("}") and os.environ.get(host[2:-1]) == domain:
            return True
    return False

picked = [i for i in heads if is_ours(lines[i])]
if len(picked) != 1:
    sys.stderr.write(f"site-блок для {domain} не найден однозначно (кандидатов {len(picked)})\n")
    sys.exit(2)

head = picked[0]
end = next(i for i in range(head + 1, len(lines)) if lines[i].rstrip() == "}")
body = [ln for ln in lines[head + 1:end] if ln.strip()]
indent = re.match(r'^\s*', body[0]).group(0) if body else "    "

lines[head + 1:head + 1] = [
    f"{indent}# hermes-dashboard (added by hermes-tls.sh)",
    f"{indent}handle_path /hermes/* {{",
    f"{indent}    reverse_proxy 127.0.0.1:19119 {{",
    f"{indent}        flush_interval -1",
    f"{indent}        header_up X-Forwarded-Prefix /hermes",
    f"{indent}    }}",
    f"{indent}}}",
]
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(f"вставил в site-блок после строки {head + 1}")
PY

if ! "$CADDY" validate --config "$CF" --adapter caddyfile >/tmp/caddy-validate.log 2>&1; then
  echo "ОШИБКА валидации после правки, откатываю:" >&2
  tail -3 /tmp/caddy-validate.log >&2
  cp -a "$(ls -1t "$CF".bak.* | head -1)" "$CF"
  exit 3
fi
echo "после правки валидно"

# admin off -> reload невозможен, только рестарт (сайт моргает на секунду).
systemctl restart caddy
sleep 2

echo "--- проверки с самого VPS:"
curl -s -o /dev/null -w "  туннель     127.0.0.1:19119/api/status -> %{http_code}\n" -m 5 http://127.0.0.1:19119/api/status || true
curl -s -o /dev/null -w "  новый путь  https://$DOMAIN/hermes/api/status -> %{http_code}\n" -m 10 "https://$DOMAIN/hermes/api/status" || true
curl -s -o /dev/null -w "  старый сайт https://$DOMAIN/ -> %{http_code}\n" -m 10 "https://$DOMAIN/" || true
echo
echo "200 на /hermes/api/status = готово. 502 = прокси жив, а туннель или дашборд лежит."
echo "откат: cp -a $(ls -1t "$CF".bak.* | head -1) $CF && systemctl restart caddy"
