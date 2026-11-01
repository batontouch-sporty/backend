#!/bin/sh
set -eu

# The image runs as nobody. Private runtime files live in its writable /tmp.
umask 077
runtime_dir=$(mktemp -d /tmp/sporty-alertmanager.XXXXXX)
if [ -n "${DISCORD_WEBHOOK_URL:-}" ]; then
    printf '%s' "$DISCORD_WEBHOOK_URL" > "$runtime_dir/discord_webhook_url"
    sed "s|/run/secrets/discord_webhook_url|$runtime_dir/discord_webhook_url|g" \
        /etc/alertmanager/alertmanager.yml > "$runtime_dir/alertmanager.yml"
else
    cp /etc/alertmanager/alertmanager.disabled.yml "$runtime_dir/alertmanager.yml"
    echo 'Discord webhook is not configured; Discord notifications are disabled.'
fi
# Do not pass the webhook environment variable to the Alertmanager process.
unset DISCORD_WEBHOOK_URL
exec /bin/alertmanager --config.file="$runtime_dir/alertmanager.yml" "$@"
