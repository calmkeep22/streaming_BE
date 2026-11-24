#!/bin/sh
set -eu
umask 077
# Persist the configuration so Sentinel's CONFIG REWRITE survives a restart.
if [ ! -f /data/redis.conf ]; then
  {
    printf 'bind 0.0.0.0\nport 6379\ndir /data\nappendonly yes\n'
    printf 'requirepass %s\nmasterauth %s\n' "$REDIS_PASSWORD" "$REDIS_PASSWORD"
    printf 'maxmemory 256mb\nmaxmemory-policy noeviction\n'
    if [ "${INITIAL_REPLICA:-false}" = true ]; then
      printf 'replicaof 172.30.0.10 6379\n'
    fi
  } > /data/redis.conf
fi
exec redis-server /data/redis.conf
