#!/bin/sh
set -eu
umask 077
if [ ! -f /data/sentinel.conf ]; then
  {
    printf 'port 26379\ndir /data\n'
    printf 'sentinel monitor streaming-master 172.30.0.10 6379 2\n'
    printf 'sentinel auth-pass streaming-master %s\n' "$REDIS_PASSWORD"
    printf 'sentinel down-after-milliseconds streaming-master 3000\n'
    printf 'sentinel failover-timeout streaming-master 10000\n'
    printf 'sentinel parallel-syncs streaming-master 1\n'
    printf 'requirepass %s\n' "$REDIS_SENTINEL_PASSWORD"
  } > /data/sentinel.conf
fi
exec redis-server /data/sentinel.conf --sentinel
