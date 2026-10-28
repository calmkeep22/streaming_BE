#!/usr/bin/env bash
# 실험 정의 파일(loadtest/experiments/*.txt)의 variant 들을 반복 실행하고 결과를 비교한다.
#
# 좌석 데이터가 한 번 쓰면 소진되므로 매 회차 앱을 재시작한다 (loadtest 프로필 = ddl-auto=create).
#
# 사용법 (Git Bash, 프로젝트 루트에서):
#   DB_PASSWORD=xxx bash loadtest/run-experiment.sh loadtest/experiments/03-error-mapping.txt [반복횟수=5]
#
# 실험 파일 형식 (한 줄에 하나, # 은 주석):
#   common|<모든 variant에 붙일 앱 인자>
#   <variant 이름>|<hot|spread>|<앱 인자>
#   인자 중 @KEY=VALUE 는 앱이 아니라 부하 도구(reserve-load.mjs)의 환경변수로 넘긴다
#     예) c050|hot|@CONCURRENCY=50 @DURATION_SEC=30 @WARMUP_SEC=5
#
# 결과: loadtest/results/<실험>__<variant>-<mode>-<시각>.json  (+ logs/ 에 앱 로그, 메트릭 스냅샷)

set -euo pipefail
cd "$(dirname "$0")/.."

# DB_PASSWORD 등을 .env (프로젝트 루트 또는 loadtest/) 에 적어두면 읽어온다 (예: DB_PASSWORD=xxxx)
for f in .env loadtest/.env; do
  if [ -f "$f" ]; then set -a; . <(tr -d '\r' <"$f"); set +a; fi
done

EXP_FILE=${1:?실험 파일을 지정하세요. 예: loadtest/experiments/03-error-mapping.txt}
REPEAT=${2:-5}
EXP=$(basename "$EXP_FILE" .txt)
PORT=8080
MGMT_PORT=8081  # loadtest 프로필의 management.server.port (지표·헬스 체크용)
LOG_DIR=loadtest/results/logs
mkdir -p "$LOG_DIR"

# 실험 파일에서 쓸 수 있도록 export
export DB_BASE_URL="jdbc:mysql://localhost:3306/streaming?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul"

COMMON=""
VARIANTS=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"; line="$(echo "$line" | sed 's/[[:space:]]*$//')"
  [ -z "$line" ] && continue
  if [[ "$line" == common\|* ]]; then COMMON="$(eval echo "\"${line#common|}\"")"; else VARIANTS+=("$line"); fi
done <"$EXP_FILE"

# SKIP_BUILD=1 이면 이미 만든 jar 를 그대로 쓴다 (메모리가 빠듯할 때 Gradle 데몬을 안 띄우려고)
[ "${SKIP_BUILD:-}" = 1 ] || ./gradlew -q bootJar -x test
JAR=$(ls build/libs/*.jar | grep -v plain | head -1)
set -f  # 앱 인자의 ? 가 glob 으로 해석되지 않도록

# "--app.arg @KEY=VAL" 에서 @ 로 시작하는 것만 골라낸다 ($1=app|load)
pick() { local want=$1; shift; local out=(); for t in "$@"; do
  if [[ "$t" == @* ]]; then [ "$want" = load ] && out+=("${t#@}"); else [ "$want" = app ] && out+=("$t"); fi
done; echo "${out[*]:-}"; }

port_open() { curl -s -o /dev/null --connect-timeout 1 "http://localhost:${1:-$PORT}" 2>/dev/null; }

wait_up() {  # $1=pid $2=log
  for _ in $(seq 1 90); do
    curl -sf --connect-timeout 1 "http://localhost:$MGMT_PORT/actuator/health" >/dev/null 2>&1 && return 0
    kill -0 "$1" 2>/dev/null || { echo "    앱 기동 실패: $2"; grep -m3 -E 'Access denied|Caused by|APPLICATION FAILED' "$2" | cut -c1-200 >&2; return 1; }
    sleep 1
  done
  echo "    앱이 90초 안에 뜨지 않았습니다: $2" >&2; return 1
}

# 실행기가 동시에 두 개 돌면 같은 포트/DB 를 써서 결과가 오염되므로 잠금을 건다
LOCK=loadtest/results/.running.pid
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK")" 2>/dev/null; then
  echo "다른 실험이 실행 중입니다 (pid $(cat "$LOCK")). 끝난 뒤 다시 실행하세요." >&2; exit 1
fi
echo $$ >"$LOCK"
trap 'rm -f "$LOCK"; if [ -n "${pid:-}" ]; then kill "$pid" 2>/dev/null || true; fi' EXIT

for p in $PORT $MGMT_PORT; do
  if port_open $p; then echo "포트 $p 이 이미 사용 중입니다. 실행 중인 앱을 먼저 종료하세요." >&2; exit 1; fi
done

for i in $(seq 1 "$REPEAT"); do
  # 회차마다 variant 순서를 섞어서 실행 순서(캐시/OS 상태)에 따른 편향을 줄인다
  while IFS= read -r v; do
    IFS='|' read -r name mode args <<<"$v"
    args="$(eval echo "\"$args\"")"
    tag="${EXP}__${name}"
    log="$LOG_DIR/$tag-$mode-$i.log"
    echo "=== [$i/$REPEAT] $name ($mode) $args"
    # shellcheck disable=SC2086
    APP_ARGS="$(pick app $COMMON $args)"; LOAD_ENV="$(pick load $COMMON $args)"
    java -jar "$JAR" --spring.profiles.active=loadtest $APP_ARGS >"$log" 2>&1 &
    pid=$!
    if wait_up "$pid" "$log"; then
      env $LOAD_ENV LABEL="$tag" SAMPLE_METRICS=1 node loadtest/reserve-load.mjs "$mode" 2>"$LOG_DIR/$tag-$mode-$i.node.err" | grep -E '처리량|응답|부하 중|서버 쪽' \
        || echo "    부하 스크립트 실패: $LOG_DIR/$tag-$mode-$i.node.err (이번 회차는 건너뜀)"
      curl -s "http://localhost:$MGMT_PORT/actuator/prometheus" \
        | grep -E '^(hikaricp_connections_(acquire|pending|max|usage)|reservation_|tomcat_(threads|global_request)|http_server_requests_seconds_(count|max)\{.*(reservations|holds))' \
        >"$LOG_DIR/$tag-$mode-$i.metrics" || true
      echo "    앱 로그: $(wc -l <"$log") lines, $(wc -c <"$log") bytes"
    fi
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    while port_open || port_open $MGMT_PORT; do sleep 1; done
  done < <(printf '%s\n' "${VARIANTS[@]}" | shuf)
done

node loadtest/compare.mjs "${EXP}__"
