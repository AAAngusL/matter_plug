#!/usr/bin/env bash
# first-build.sh: from a clean Linux host to a debug APK of the Matter Plug app.
#
# Steps (all Android tooling runs in the project's published build container):
#   1. chiptool  Clone connectedhomeip at the pinned tag (outside this repository), then in
#                the container: check out the Android submodules, bootstrap, and build
#                CHIPTool plus the Matter libraries (android-arm64-chip-tool).
#   2. copy      Run tools/copy-matter-libs.sh to copy jars, .so files and the two lifted
#                CHIPTool sources into the app.
#   3. app       In the same container image: create the Gradle wrapper if missing, then
#                ./gradlew assembleDebug.
#
# Environment variables (all optional):
#   MATTER_CHECKOUT_DIR    Where connectedhomeip lives.            Default: $HOME/src/connectedhomeip
#   MATTER_TAG             Tag to clone and expect.                Default: v1.6.1.0
#   MATTER_REPO_URL        Clone URL.                              Default: https://github.com/project-chip/connectedhomeip.git
#   MATTER_IMAGE           Container image name.                   Default: ghcr.io/project-chip/chip-build-android
#   MATTER_IMAGE_TAG       Image tag. Default: read from the checkout's .github/workflows/
#                          (the chip-build-android:<version> line). Set it to override, or when
#                          there is no checkout yet (--only-app) or the files name several tags.
#   CONTAINER_RUNTIME      docker or podman.                       Default: docker, else podman
#   MATTER_BUILD_CACHE     Host directory for the persistent Gradle cache and the container's
#                          HOME.                                   Default: $HOME/.cache/matter_plug_build
#   MATTER_MIN_FREE_GB     Free disk space wanted for the checkout. Default: 30 (warning only)
#   MATTER_MOUNT_OPTS      Suffix for every bind mount, for example ":z" on SELinux hosts.
#                          Default: empty
#   MATTER_SDK_MODE        How the container gets a writable Android SDK (see below).
#                          copy (default) or licenses.
#   MATTER_IMAGE_SDK_DIR   Only for MATTER_SDK_MODE=licenses: the SDK path inside the image.
#                          Default: ANDROID_HOME as the image sets it, else /opt/android/sdk
#
# Writable SDK. The Matter builder (scripts/build/builders/android.py, validate_build_environment)
# refuses to start unless it may write to the Android SDK: to ANDROID_HOME if there is no
# licenses/ folder in it, else to ANDROID_HOME/licenses (it then runs "yes | sdkmanager
# --licenses"). The image's SDK is root-owned and we run as you, so the SDK must be made
# writable without running as root. Two ways, chosen with MATTER_SDK_MODE:
#   copy      (default) The image's SDK is copied once, from inside the container and as you, to
#             $MATTER_BUILD_CACHE/android-sdk/sdk (a few GB), and ANDROID_HOME/ANDROID_SDK_ROOT
#             point at that copy in steps 1 and 3. Everything that wants to write to the SDK
#             (licence acceptance, a build-tools version the Android Gradle plugin installs on
#             demand) then works. The copy is skipped on later runs; delete that directory to
#             make a new one. A copy interrupted half way is detected (no marker file) and redone.
#   licenses  No copy: only an empty host directory ($MATTER_BUILD_CACHE/android-licenses) is
#             mounted over <SDK>/licenses. Enough for the check and for licence acceptance, but
#             nothing else in the SDK becomes writable.
# The NDK is never written to by the builder and stays where the image has it.
#
# Ownership choice. The container runs with the invoking user's uid and gid
# (--user "$(id -u):$(id -g)"), so everything it writes into the mounted directories
# (.environment/ and out/ in the checkout, .gradle/ and build/ in the app, wrapper files) is
# owned by you, and no chown is needed afterwards. Running as root and chowning at the end
# would leave root-owned files behind if a step were interrupted. The image has no passwd entry
# for that uid, so HOME is set explicitly to a writable host directory ($MATTER_BUILD_CACHE/home)
# mounted at /home/builder. Bootstrap needs a writable HOME (cipd and pip caches, git config).
# Gradle's home is $MATTER_BUILD_CACHE/gradle, mounted at /gradle-home (GRADLE_USER_HOME), and
# the Android tooling's home (ANDROID_USER_HOME) is /home/builder/.android. Both directories are
# created on the host first, as you, so the runtime never creates them as root.
# With rootless podman, container uid 0 is already mapped to you, so a plain --user would map
# you to a subuid; the script passes --userns=keep-id instead, which keeps your uid inside the
# container, and the same ownership result follows.
#
# Step 2 runs on the host, not in the container: the files it reads and writes are owned by you
# either way, it needs only git, sed and cp, and the container would see the same two
# directories only under different paths.
#
# The Android SDK is taken from ANDROID_HOME (the image's, or the copy, see above); nothing is written to
# local.properties (it is in .gitignore anyway). Nothing is installed on the host, no sudo is
# used, and nothing is committed, tagged or pushed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CHECKOUT_DIR="${MATTER_CHECKOUT_DIR:-$HOME/src/connectedhomeip}"
MATTER_TAG="${MATTER_TAG:-v1.6.1.0}"
MATTER_REPO_URL="${MATTER_REPO_URL:-https://github.com/project-chip/connectedhomeip.git}"
IMAGE_NAME="${MATTER_IMAGE:-ghcr.io/project-chip/chip-build-android}"
IMAGE_TAG="${MATTER_IMAGE_TAG:-}"
CACHE_DIR="${MATTER_BUILD_CACHE:-$HOME/.cache/matter_plug_build}"
MIN_FREE_GB="${MATTER_MIN_FREE_GB:-30}"
MOUNT_OPTS="${MATTER_MOUNT_OPTS:-}"
SDK_MODE="${MATTER_SDK_MODE:-copy}"
IMAGE_SDK_DIR="${MATTER_IMAGE_SDK_DIR:-}"
RUNTIME="${CONTAINER_RUNTIME:-}"

# Fixed paths inside the container.
C_CHIP=/work/connectedhomeip
C_APP=/work/app
C_GRADLE_HOME=/gradle-home
C_HOME=/home/builder
C_SDK_CACHE=/sdk-cache        # mount of $CACHE_DIR/android-sdk (MATTER_SDK_MODE=copy)
C_SDK_COPY=/sdk-cache/sdk     # the writable SDK copy inside it
USE_SDK_COPY=0                # set to 1 once the copy is complete

DO_CHIPTOOL=1
DO_COPY=1
DO_APP=1
FORCE=0

CURRENT_STEP=""
STEP_HINT=""

usage() {
  cat <<EOF
Usage: tools/first-build.sh [options]

Builds CHIPTool and the Matter libraries from connectedhomeip $MATTER_TAG in a container,
copies the libraries into this app, and builds the app's debug APK.

Options:
  -h, --help        Show this text and exit.
  --skip-chiptool   Skip step 1 (the CHIPTool and library build).
  --skip-app        Skip step 3 (the app build).
  --only-chiptool   Run step 1 only.
  --only-copy       Run step 2 only (copy the libraries into the app).
  --only-app        Run step 3 only.
  --force           Run the selected steps even if their output already exists.

Without --force a step is skipped when its output exists: CHIPTool's APK and the library files
for step 1, an up-to-date copy of the same tag for step 2, an APK newer than all app sources
for step 3. Environment variables are listed at the top of this script.
Checkout: $CHECKOUT_DIR
Project:  $APP_ROOT
EOF
}

say()  { printf '%s\n' "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
die()  { printf 'Error: %s\n' "$*" >&2; exit 1; }

banner() {
  printf '\n==== %s ====\n' "$*"
}

begin_step() { # name hint
  CURRENT_STEP="$1"
  STEP_HINT="$2"
  banner "$1"
}

on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ -n "$CURRENT_STEP" ]; then
    printf '\nThe step "%s" failed (exit status %s).\n' "$CURRENT_STEP" "$rc" >&2
    [ -n "$STEP_HINT" ] && printf 'What to check: %s\n' "$STEP_HINT" >&2
    printf 'Fix the cause and run this script again; finished steps are skipped.\n' >&2
  fi
}
trap on_exit EXIT

# ---------------------------------------------------------------- arguments
only_set=0
select_only() { # chiptool|copy|app
  if [ "$only_set" -eq 0 ]; then DO_CHIPTOOL=0; DO_COPY=0; DO_APP=0; only_set=1; fi
  case "$1" in
    chiptool) DO_CHIPTOOL=1 ;;
    copy)     DO_COPY=1 ;;
    app)      DO_APP=1 ;;
  esac
}
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)       usage; exit 0 ;;
    --skip-chiptool) DO_CHIPTOOL=0 ;;
    --skip-app)      DO_APP=0 ;;
    --only-chiptool) select_only chiptool ;;
    --only-copy)     select_only copy ;;
    --only-app)      select_only app ;;
    --force)         FORCE=1 ;;
    *) printf 'Error: unknown option "%s".\n\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
if [ "$DO_CHIPTOOL" -eq 0 ] && [ "$DO_COPY" -eq 0 ] && [ "$DO_APP" -eq 0 ]; then
  die "no step is left to run; check the combination of --skip-* and --only-* options."
fi

# ---------------------------------------------------------------- preflight
begin_step "Preflight checks" "the messages above; nothing slow has started yet."

[ "$(id -u)" -ne 0 ] || die "run this script as your normal user, not as root (the files must belong to you)."
[ -n "${HOME:-}" ] && [ -d "$HOME" ] || die "HOME is not set to an existing directory."

[ -f "$APP_ROOT/settings.gradle.kts" ] \
  || die "the app project root was not found at $APP_ROOT (no settings.gradle.kts). The script must stay in <app root>/tools/."
[ -f "$APP_ROOT/tools/copy-matter-libs.sh" ] \
  || die "$APP_ROOT/tools/copy-matter-libs.sh is missing."
[ -f "$APP_ROOT/gradle/wrapper/gradle-wrapper.properties" ] \
  || die "$APP_ROOT/gradle/wrapper/gradle-wrapper.properties is missing."

command -v git >/dev/null 2>&1 || die "git is not installed. Install git with your package manager and run again."

# Container runtime: explicit choice, else docker, else podman.
usable() { "$1" info >/dev/null 2>&1; }
if [ -n "$RUNTIME" ]; then
  case "$RUNTIME" in docker|podman) ;; *) die "CONTAINER_RUNTIME must be docker or podman, not '$RUNTIME'." ;; esac
  command -v "$RUNTIME" >/dev/null 2>&1 || die "CONTAINER_RUNTIME is '$RUNTIME' but it is not on the PATH."
  usable "$RUNTIME" || die "'$RUNTIME info' failed for your user. Check that the daemon is running and that your user may use it (for docker: membership of the docker group; log in again after changing groups)."
else
  if command -v docker >/dev/null 2>&1; then
    if usable docker; then
      RUNTIME=docker
    elif command -v podman >/dev/null 2>&1 && usable podman; then
      warn "docker is installed but not usable by this user; using podman."
      RUNTIME=podman
    else
      die "'docker info' failed for your user. Check that the daemon is running and that your user is in the docker group (log in again after changing groups), or install podman."
    fi
  elif command -v podman >/dev/null 2>&1; then
    usable podman || die "'podman info' failed for your user. Check your rootless podman setup (subuid and subgid ranges)."
    RUNTIME=podman
  else
    die "no container runtime found. Install docker or podman (this script installs nothing), then run again."
  fi
fi
say "Container runtime: $RUNTIME"

case "$(uname -m)" in
  x86_64|amd64) ;;
  *) warn "this host is $(uname -m); the build image is published for x86-64 only, so it may fail or run under emulation." ;;
esac

# Resolve the checkout path to an absolute path.
case "$CHECKOUT_DIR" in /*) ;; *) CHECKOUT_DIR="$PWD/$CHECKOUT_DIR" ;; esac

# Free disk space where the checkout goes (warning only).
if [ "$DO_CHIPTOOL" -eq 1 ]; then
  probe="$CHECKOUT_DIR"
  while [ ! -e "$probe" ] && [ "$probe" != "/" ]; do probe="$(dirname "$probe")"; done
  free_kb="$(df -Pk "$probe" 2>/dev/null | awk 'NR==2 {print $4}')" || free_kb=""
  case "$free_kb" in
    ''|*[!0-9]*) warn "could not measure the free disk space at $probe; make sure about ${MIN_FREE_GB} GB are free." ;;
    *)
      free_gb=$(( free_kb / 1024 / 1024 ))
      if [ "$free_gb" -lt "$MIN_FREE_GB" ] && [ ! -d "$CHECKOUT_DIR/out/android-arm64-chip-tool" ]; then
        warn "only about ${free_gb} GB are free at $probe; the checkout, tools and build need about ${MIN_FREE_GB} GB."
      else
        say "Free disk space at $probe: about ${free_gb} GB."
      fi ;;
  esac
fi

# Host directories that get mounted. Create them now, as this user, so the runtime
# never creates them as root.
case "$SDK_MODE" in copy|licenses) ;; *) die "MATTER_SDK_MODE must be copy or licenses, not '$SDK_MODE'." ;; esac
mkdir -p "$CACHE_DIR/gradle" "$CACHE_DIR/home" "$CACHE_DIR/logs"
case "$SDK_MODE" in
  copy)     mkdir -p "$CACHE_DIR/android-sdk" ;;
  licenses) mkdir -p "$CACHE_DIR/android-licenses" ;;
esac
CACHE_DIR="$(cd "$CACHE_DIR" && pwd)"

# ---------------------------------------------------------------- container helper
USER_ARGS=(--user "$(id -u):$(id -g)")
if [ "$RUNTIME" = "podman" ]; then
  USER_ARGS=(--userns=keep-id --user "$(id -u):$(id -g)")
fi

# run_in_container <script-text> <workdir> [extra runtime args...]
# The script text is passed to "bash -c". Mounts: app root, checkout, Gradle cache, HOME.
run_in_container() {
  local script="$1" workdir="$2"; shift 2
  local tty_args=(-i)
  if [ -t 0 ] && [ -t 1 ]; then tty_args=(-it); fi
  local mounts=(-v "$APP_ROOT:$C_APP$MOUNT_OPTS"
                -v "$CACHE_DIR/gradle:$C_GRADLE_HOME$MOUNT_OPTS"
                -v "$CACHE_DIR/home:$C_HOME$MOUNT_OPTS")
  local sdk_env=()
  if [ -d "$CHECKOUT_DIR" ]; then
    mounts+=(-v "$CHECKOUT_DIR:$C_CHIP$MOUNT_OPTS")
  fi
  case "$SDK_MODE" in
    copy)
      mounts+=(-v "$CACHE_DIR/android-sdk:$C_SDK_CACHE$MOUNT_OPTS")
      if [ "$USE_SDK_COPY" -eq 1 ]; then
        sdk_env=(-e ANDROID_HOME="$C_SDK_COPY" -e ANDROID_SDK_ROOT="$C_SDK_COPY")
      fi ;;
    licenses)
      [ -n "$IMAGE_SDK_DIR" ] && mounts+=(-v "$CACHE_DIR/android-licenses:$IMAGE_SDK_DIR/licenses$MOUNT_OPTS") ;;
  esac
  "$RUNTIME" run --rm "${tty_args[@]}" "${USER_ARGS[@]}" \
    -e HOME="$C_HOME" \
    -e GRADLE_USER_HOME="$C_GRADLE_HOME" \
    -e ANDROID_USER_HOME="$C_HOME/.android" \
    -e GIT_CONFIG_GLOBAL="$C_HOME/.gitconfig" \
    ${sdk_env[@]+"${sdk_env[@]}"} \
    "${mounts[@]}" \
    -w "$workdir" \
    ${@+"$@"} \
    "$IMAGE_NAME:$IMAGE_TAG" bash -c "$script"
}

# ---------------------------------------------------------------- Java deps helpers
# connectedhomeip's build (third_party/java_deps/set_up_java_deps.sh at v1.6.1.0) downloads 11 jars
# with a bare "curl --fail --location" into third_party/java_deps/artifacts/ and skips any jar that
# is already there. When one of those downloads is refused (HTTP 403 seen from Maven Central or
# dist.wso2.org on some networks), the whole build stops. So step 1 first fetches the same jars
# itself (several mirrors, browser-like User-Agent, SHA-1 check where the server offers one) into the
# same directory; the upstream script then finds them and downloads nothing. Nothing tracked in the
# checkout is edited. The same list drives the diagnostics printed when step 1 fails.
# These functions are text that is prepended to the scripts run inside the container.
read -r -d '' JD_FUNCS <<'JDEOF' || true
JD_DIR=third_party/java_deps/artifacts
JD_UA="Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
JD_MAVEN_BASES="https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2"
# Same 11 jars and versions as set_up_java_deps.sh at v1.6.1.0. The last one is hosted by
# dist.wso2.org upstream; Maven Central is tried as a fallback for it.
JD_JARS="
com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar
com/google/code/gson/gson/2.9.1/gson-2.9.1.jar
com/google/protobuf/protobuf-java/3.22.0/protobuf-java-3.22.0.jar
com/google/truth/truth/1.1.3/truth-1.1.3.jar
junit/junit/4.13.2/junit-4.13.2.jar
org/jetbrains/kotlin/kotlin-stdlib/1.8.20/kotlin-stdlib-1.8.20.jar
org/jetbrains/kotlin/kotlin-test/1.8.20/kotlin-test-1.8.20.jar
org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.7.3/kotlinx-coroutines-core-jvm-1.7.3.jar
org/json/json/20220924/json-20220924.jar
org/hamcrest/hamcrest-all/1.3/hamcrest-all-1.3.jar
com/google/common/google-collect/1.0-rc1/google-collect-1.0-rc1.jar
"
jd_urls() { # <maven path of a jar>: print the URLs to try, in order
  local rel="$1" b
  case "$rel" in
    com/google/common/google-collect/*)
      echo "https://dist.wso2.org/maven2/$rel"
      for b in $JD_MAVEN_BASES; do echo "$b/com/google/collections/google-collect/1.0-rc1/google-collect-1.0-rc1.jar"; done ;;
    *) for b in $JD_MAVEN_BASES; do echo "$b/$rel"; done ;;
  esac
}
jd_is_jar() { [ -s "$1" ] && [ "$(head -c 2 "$1" 2>/dev/null)" = "PK" ]; }
jd_get() { # <maven path of a jar>
  local rel="$1" jar dest tmp url code want got
  jar="${rel##*/}"; dest="$JD_DIR/$jar"; tmp="$dest.part"
  if jd_is_jar "$dest"; then echo "  present   $jar"; return 0; fi
  rm -f "$dest" "$tmp"; mkdir -p "$JD_DIR" || return 1
  for url in $(jd_urls "$rel"); do
    code="$(curl -sSL --retry 2 --connect-timeout 20 --max-time 300 -A "$JD_UA" -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null || true)"
    if [ "$code" != "200" ]; then
      echo "  HTTP $code  $url" >&2; rm -f "$tmp"; continue
    fi
    if ! jd_is_jar "$tmp"; then
      echo "  HTTP 200 but not a jar (proxy or captive-portal page?)  $url" >&2; rm -f "$tmp"; continue
    fi
    want="$(curl -sSL --max-time 60 -A "$JD_UA" "$url.sha1" 2>/dev/null | tr -d ' \r\n' | cut -c1-40 || true)"
    if printf '%s' "$want" | grep -Eq '^[0-9a-f]{40}$'; then
      got="$(sha1sum "$tmp" | cut -c1-40)"
      if [ "$got" != "$want" ]; then
        echo "  SHA-1 mismatch (got $got, server says $want)  $url" >&2; rm -f "$tmp"; continue
      fi
      mv "$tmp" "$dest" && echo "  fetched   $jar  (sha1 ok)  $url" && return 0
    else
      mv "$tmp" "$dest" && echo "  fetched   $jar  (no checksum offered)  $url" && return 0
    fi
  done
  echo "  FAILED    $jar: no URL worked" >&2
  return 1
}
jd_prefetch() {
  local rel rc=0
  for rel in $JD_JARS; do jd_get "$rel" || rc=1; done
  return $rc
}
jd_diagnose() { # print the HTTP status of every download URL, as seen from inside the container
  local rel jar url code ua_code body proxies
  echo "curl:   $(curl --version 2>/dev/null | head -n 1)"
  echo "date:   $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  proxies="$(env | grep -i '^[a-z_]*proxy=' | sed 's#//[^@/]*@#//***@#' | tr '\n' ' ' || true)"
  echo "proxy variables in the container: ${proxies:-none}"
  echo "DNS:    $(getent hosts repo1.maven.org 2>&1 | head -n 1 || true)"
  body="$(mktemp)"
  for rel in $JD_JARS; do
    jar="${rel##*/}"
    if jd_is_jar "$JD_DIR/$jar"; then echo "[present in $JD_DIR] $jar"; else echo "[MISSING in $JD_DIR] $jar"; fi
    for url in $(jd_urls "$rel"); do
      # Status as the upstream script sees it (curl's default User-Agent), and with a browser User-Agent.
      code="$(curl -sSL --connect-timeout 20 --max-time 60 -r 0-1023 -o "$body" -w '%{http_code}' "$url" 2>&1 || true)"
      ua_code="$(curl -sSL --connect-timeout 20 --max-time 60 -r 0-1023 -A "$JD_UA" -o /dev/null -w '%{http_code}' "$url" 2>&1 || true)"
      printf '   default-UA %-4s  browser-UA %-4s  %s\n' "$code" "$ua_code" "$url"
      case "$code" in 200|206) ;; *) [ -s "$body" ] && printf '      body: %s\n' "$(head -c 160 "$body" | tr '\r\n\t' '   ')" ;; esac
    done
  done
  rm -f "$body"
  echo "Statuses 200 or 206 are fine. 403 on every URL from this container but not from the host usually means"
  echo "a proxy, firewall or VPN between the container and the internet; 403 for default-UA only means the"
  echo "server blocks curl's User-Agent (the pre-fetch above already works around that)."
}
JDEOF

# ---------------------------------------------------------------- step 1 checkout
CHIPTOOL_APK="$CHECKOUT_DIR/out/android-arm64-chip-tool/outputs/apk/debug/app-debug.apk"
CHIP_SO="$CHECKOUT_DIR/out/android-arm64-chip-tool/lib/jni/arm64-v8a/libCHIPController.so"
APP_APK="$APP_ROOT/app/build/outputs/apk/debug/app-debug.apk"

ensure_checkout() {
  if [ -e "$CHECKOUT_DIR" ] && [ -n "$(ls -A "$CHECKOUT_DIR" 2>/dev/null)" ]; then
    git -C "$CHECKOUT_DIR" rev-parse --git-dir >/dev/null 2>&1 \
      || die "$CHECKOUT_DIR exists, is not empty and is not a git checkout. Move it away or set MATTER_CHECKOUT_DIR."
    local actual
    actual="$(git -C "$CHECKOUT_DIR" describe --tags --exact-match HEAD 2>/dev/null || true)"
    if [ "$actual" != "$MATTER_TAG" ]; then
      die "the checkout at $CHECKOUT_DIR is at '${actual:-no tag (HEAD $(git -C "$CHECKOUT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown))}', but this build needs $MATTER_TAG. The script does not change an existing checkout's version. Move that checkout away, or point MATTER_CHECKOUT_DIR at another directory."
    fi
    say "Existing checkout at $CHECKOUT_DIR is at $MATTER_TAG."
  else
    say "Cloning $MATTER_REPO_URL at $MATTER_TAG (shallow) into $CHECKOUT_DIR."
    mkdir -p "$(dirname "$CHECKOUT_DIR")"
    git clone --depth 1 --branch "$MATTER_TAG" "$MATTER_REPO_URL" "$CHECKOUT_DIR"
  fi
}

# Work out the image tag from the checkout's CI workflow files, unless it was given.
resolve_image_tag() {
  [ -z "$IMAGE_TAG" ] || { say "Image: $IMAGE_NAME:$IMAGE_TAG (from MATTER_IMAGE_TAG)."; return 0; }
  [ -d "$CHECKOUT_DIR/.github/workflows" ] \
    || die "cannot determine the image tag: there is no checkout at $CHECKOUT_DIR. Set MATTER_IMAGE_TAG (for $MATTER_TAG it is named in .github/workflows/ as chip-build-android:<version>)."
  local found
  found="$(grep -rhoE 'chip-build-android:[A-Za-z0-9._-]+' "$CHECKOUT_DIR/.github/workflows" 2>/dev/null | sed 's/^chip-build-android://' | sort -u || true)"
  if [ -z "$found" ]; then
    die "no 'chip-build-android:<version>' line found in $CHECKOUT_DIR/.github/workflows/. Set MATTER_IMAGE_TAG yourself; the script does not fall back to 'latest'."
  fi
  if [ "$(printf '%s\n' "$found" | wc -l)" -ne 1 ]; then
    die "the workflow files name several chip-build-android tags ($(printf '%s' "$found" | tr '\n' ' ')). Set MATTER_IMAGE_TAG to the one you want."
  fi
  IMAGE_TAG="$found"
  say "Image: $IMAGE_NAME:$IMAGE_TAG (from the checkout's .github/workflows/)."
}

ensure_image() {
  if "$RUNTIME" image inspect "$IMAGE_NAME:$IMAGE_TAG" >/dev/null 2>&1; then
    say "Image $IMAGE_NAME:$IMAGE_TAG is already present."
  else
    say "Pulling $IMAGE_NAME:$IMAGE_TAG (several GB; this takes a while the first time)."
    "$RUNTIME" pull "$IMAGE_NAME:$IMAGE_TAG" \
      || die "could not pull $IMAGE_NAME:$IMAGE_TAG. Check the network and that the tag exists."
  fi
}

# Make the Android SDK writable for the build (see "Writable SDK" at the top). Needs the image.
prepare_sdk() {
  if [ "$SDK_MODE" = "licenses" ]; then
    if [ -z "$IMAGE_SDK_DIR" ]; then
      IMAGE_SDK_DIR="$("$RUNTIME" image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$IMAGE_NAME:$IMAGE_TAG" 2>/dev/null \
                       | sed -n 's/^ANDROID_HOME=//p' | head -n 1 || true)"
      [ -n "$IMAGE_SDK_DIR" ] || IMAGE_SDK_DIR=/opt/android/sdk
    fi
    say "Android SDK: the image's ($IMAGE_SDK_DIR) with a writable licenses/ folder from $CACHE_DIR/android-licenses."
    return 0
  fi

  local marker="$CACHE_DIR/android-sdk/.complete" want="$IMAGE_NAME:$IMAGE_TAG"
  if [ -d "$CACHE_DIR/android-sdk/sdk" ] && [ -f "$marker" ] && [ "$(cat "$marker" 2>/dev/null)" = "$want" ]; then
    say "Writable Android SDK copy already present: $CACHE_DIR/android-sdk/sdk"
    USE_SDK_COPY=1
    return 0
  fi

  say "Copying the image's Android SDK to $CACHE_DIR/android-sdk/sdk (once; a few GB; may take a few minutes)."
  local inner
  read -r -d '' inner <<'EOF' || true
set -o pipefail
src="${ANDROID_HOME:-}"
[ -n "$src" ] && [ -d "$src" ] || { echo "Error: ANDROID_HOME is not set or not a directory in this image." >&2; exit 1; }
dst=/sdk-cache/sdk
echo "Copying $src to $dst as $(id -u):$(id -g)"
# Remove an earlier, possibly half-finished copy (no marker file means it was not finished).
rm -f /sdk-cache/.complete
[ -e "$dst" ] && { chmod -R u+w "$dst" 2>/dev/null; rm -rf "$dst" || exit 1; }
cp -a --no-preserve=ownership "$src" "$dst" || exit 1
chmod -R u+w "$dst" || exit 1
[ -w "$dst" ] || { echo "Error: the copy at $dst is not writable." >&2; exit 1; }
# Accept the licences now, so that the Android Gradle plugin can install a missing
# build-tools or platform package later. Best effort: the builder repeats this in step 1.
sm=""
for c in "$dst/cmdline-tools/latest/bin/sdkmanager" "$dst/cmdline-tools/10.0/bin/sdkmanager" "$dst/tools/bin/sdkmanager"; do
  [ -x "$c" ] && { sm="$c"; break; }
done
if [ -n "$sm" ]; then
  echo "Accepting SDK licences with $sm"
  set +o pipefail
  yes | "$sm" --sdk_root="$dst" --licenses >/dev/null 2>&1 || echo "Note: sdkmanager --licenses did not finish cleanly; the build will try again."
else
  echo "Note: no sdkmanager found in the copy."
fi
echo "Copy done."
EOF
  if ! run_in_container "$inner" "$C_SDK_CACHE"; then
    rm -f "$marker"
    die "could not copy the Android SDK into $CACHE_DIR/android-sdk (disk full? the image's SDK unreadable?). Free space and run again, or try MATTER_SDK_MODE=licenses."
  fi
  printf '%s\n' "$want" > "$marker" || die "cannot write $marker."
  USE_SDK_COPY=1
}

step_chiptool() {
  begin_step "Step 1 of 3: build CHIPTool and the Matter libraries" \
    "network access to github.com and the package servers, free disk space (about ${MIN_FREE_GB} GB), and the container output above. The output is also saved under $CACHE_DIR/logs/."

  ensure_checkout
  resolve_image_tag

  if [ "$FORCE" -eq 0 ] && [ -f "$CHIPTOOL_APK" ] && [ -f "$CHIP_SO" ]; then
    say "CHIPTool and the libraries are already built; skipping (use --force to rebuild)."
    return 0
  fi

  ensure_image
  prepare_sdk

  local inner
  read -r -d '' inner <<'EOF' || true
set -o pipefail
cd /work/connectedhomeip || exit 1
echo "Container user: $(id -u):$(id -g), HOME=$HOME"
# Sanity checks on the toolchain the image should provide.
echo "ANDROID_HOME=${ANDROID_HOME:-unset}  ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset}  JAVA_HOME=${JAVA_HOME:-unset}"
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || { echo "Error: ANDROID_HOME is not set or not a directory in this image." >&2; exit 1; }
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ] || { echo "Error: ANDROID_NDK_HOME is not set or not a directory in this image." >&2; exit 1; }
git config --global --add safe.directory '*' || exit 1

echo "--- Android submodules"
./scripts/checkout_submodules.py --shallow --platform android || exit 1

echo "--- Bootstrap or activate"
# bootstrap.sh creates .environment/ (Python environment, downloaded tools) in the checkout;
# later runs only activate it. The mount path is always /work/connectedhomeip, because
# .environment/ records absolute paths.
if [ -d .environment ] && source scripts/activate.sh; then
  echo "Activated the existing environment."
else
  source scripts/bootstrap.sh || exit 1
fi

echo "--- Pre-fetch Java deps"
# See the comment above JD_FUNCS in first-build.sh. Jars already present are not downloaded again.
jd_prefetch || { echo "Error: could not download every Java dependency; see the HTTP statuses above." >&2; exit 1; }

echo "--- Build"
./scripts/build/build_examples.py --target android-arm64-chip-tool build || exit 1
EOF
  inner="$JD_FUNCS"$'\n'"$inner"
  local log="$CACHE_DIR/logs/step1-chiptool-$(date +%Y%m%d-%H%M%S).log"
  say "Output is also written to $log"
  # With pipefail (set above) the status of run_in_container decides, not tee's.
  if ! run_in_container "$inner" "$C_CHIP" 2>&1 | tee "$log"; then
    banner "The build failed: last 40 lines of the output"
    tail -n 40 "$log" || true
    say ""
    say "Full log of this run: $log"
    if grep -Eq 'curl: \([0-9]+\)|error: 22|requested URL returned error|could not download every Java dependency|no URL worked|HTTP [0-9]{3} +https?://|Could not resolve host|Temporary failure in name resolution|Connection (timed out|refused|reset)|Received status code [45][0-9][0-9]' "$log"; then
      banner "Download diagnostics (HTTP status of each Java dependency URL, from inside the container)"
      run_in_container "$JD_FUNCS"$'\n''jd_diagnose' "$C_CHIP" || true
      say ""
      say "The log looks like a failed download (curl error 22 / HTTP 403): copy everything from the line"
      say "'Download diagnostics' down to here into your report. To compare with the host network, run"
      say "  curl -sSI https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar | head -n 1"
      say "on this computer. You can also put the jars into $CHECKOUT_DIR/third_party/java_deps/artifacts/"
      say "yourself (the build skips jars that exist there)."
    else
      say "This does not look like a download problem, so no download diagnostics were run."
      say "Send the last 40 lines above (or the log file) when asking for help."
    fi
    exit 1
  fi

  [ -f "$CHIPTOOL_APK" ] || die "the build finished but $CHIPTOOL_APK does not exist."
  [ -f "$CHIP_SO" ]      || die "the build finished but $CHIP_SO does not exist."
  say "CHIPTool and the Matter libraries are built."
}

# ---------------------------------------------------------------- step 2 copy
step_copy() {
  begin_step "Step 2 of 3: copy the Matter libraries into the app" \
    "that step 1 finished (the build output must exist under $CHECKOUT_DIR/out/android-arm64-chip-tool/) and the message from copy-matter-libs.sh above."

  [ -d "$CHECKOUT_DIR" ] || die "there is no checkout at $CHECKOUT_DIR; run step 1 first."

  local libs="$APP_ROOT/app/libs" jni="$APP_ROOT/app/src/main/jniLibs/arm64-v8a"
  if [ "$FORCE" -eq 0 ] \
     && [ -f "$libs/MATTER_VERSION.txt" ] \
     && [ "$(cat "$libs/MATTER_VERSION.txt")" = "$MATTER_TAG" ] \
     && [ -f "$libs/CHIPController.jar" ] \
     && [ -f "$jni/libCHIPController.so" ] \
     && [ -f "$APP_ROOT/app/src/main/java/app/matterplug/matter/BleTransport.kt" ] \
     && [ -f "$APP_ROOT/app/src/main/java/app/matterplug/matter/GenericChipDeviceListener.kt" ]; then
    say "The app already contains the libraries for $MATTER_TAG; skipping (use --force to copy again)."
    return 0
  fi

  bash "$APP_ROOT/tools/copy-matter-libs.sh" "$CHECKOUT_DIR"
}

# ---------------------------------------------------------------- step 3 app
app_is_current() {
  [ -f "$APP_APK" ] || return 1
  local newer
  newer="$(find "$APP_ROOT/app/src" "$APP_ROOT/app/libs" "$APP_ROOT/app/build.gradle.kts" \
                "$APP_ROOT/build.gradle.kts" "$APP_ROOT/settings.gradle.kts" \
                "$APP_ROOT/gradle.properties" "$APP_ROOT/gradle" \
                -newer "$APP_APK" -type f -print -quit 2>/dev/null || true)"
  [ -z "$newer" ]
}

step_app() {
  begin_step "Step 3 of 3: build the app's debug APK" \
    "that step 2 ran (app/libs/ holds the jars), network access for Gradle downloads on the first run, and the Gradle or Kotlin error printed above (NOTES.md section b lists the expected compile errors)."

  if [ "$FORCE" -eq 0 ] && app_is_current; then
    say "The app APK is newer than all sources; skipping (use --force to build again)."
    return 0
  fi

  ls "$APP_ROOT/app/libs/"*.jar >/dev/null 2>&1 \
    || die "no jars in $APP_ROOT/app/libs/. Run step 2 first (tools/first-build.sh --only-copy)."

  resolve_image_tag
  ensure_image
  prepare_sdk

  local inner
  read -r -d '' inner <<'EOF' || true
set -o pipefail
cd /work/app || exit 1
echo "Container user: $(id -u):$(id -g), HOME=$HOME"
echo "ANDROID_HOME=${ANDROID_HOME:-unset}  JAVA_HOME=${JAVA_HOME:-unset}"

# The SDK location comes from the environment; no local.properties is written.
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || { echo "Error: ANDROID_HOME is not set or not a directory in this image." >&2; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "Error: SDK platform android-34 is not installed in the image at $ANDROID_HOME/platforms." >&2; exit 1; }
ls "$ANDROID_HOME/build-tools" 2>/dev/null | tr '\n' ' ' | sed 's/^/Build tools in the image: /'; echo
export ANDROID_SDK_ROOT="$ANDROID_HOME"
command -v java >/dev/null 2>&1 || { echo "Error: java is not on the PATH in this image." >&2; exit 1; }
java -version 2>&1 | head -n 1
java -version 2>&1 | grep -q '"17\.' || echo "Warning: the JDK in the image is not 17; the app build expects JDK 17." >&2

if [ ! -f ./gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "--- Generating the Gradle wrapper"
  # The wrapper is generated in a scratch directory (a plain "gradle wrapper" inside the
  # project would first configure the whole build) and only gradlew, gradlew.bat and the
  # wrapper jar are copied in. The existing gradle-wrapper.properties (Gradle 8.7) is kept.
  version="$(sed -n 's/^distributionUrl=.*gradle-\([0-9][0-9.]*\)-\(bin\|all\)\.zip.*/\1/p' gradle/wrapper/gradle-wrapper.properties)"
  [ -n "$version" ] || { echo "Error: cannot read the Gradle version from gradle-wrapper.properties." >&2; exit 1; }
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
    echo "Using the image's gradle: $GRADLE_BIN"
  else
    dist="$GRADLE_USER_HOME/bootstrap-dist"
    GRADLE_BIN="$dist/gradle-$version/bin/gradle"
    if [ ! -x "$GRADLE_BIN" ]; then
      echo "gradle is not on the PATH in the image; downloading Gradle $version."
      mkdir -p "$dist" || exit 1
      zip="$dist/gradle-$version-bin.zip"
      url="https://services.gradle.org/distributions/gradle-$version-bin.zip"
      if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$zip" "$url" || exit 1
      elif command -v wget >/dev/null 2>&1; then wget -O "$zip" "$url" || exit 1
      else python3 -c 'import sys,urllib.request; urllib.request.urlretrieve(sys.argv[1], sys.argv[2])' "$url" "$zip" || exit 1
      fi
      if command -v unzip >/dev/null 2>&1; then unzip -q -o "$zip" -d "$dist" || exit 1
      else python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$zip" "$dist" || exit 1
      fi
      chmod +x "$GRADLE_BIN" || exit 1
    fi
  fi
  scratch="$GRADLE_USER_HOME/wrapper-scratch"
  rm -rf "$scratch"; mkdir -p "$scratch" || exit 1
  # Gradle refuses to run a task in a directory with no settings file, so give it an empty one.
  : > "$scratch/settings.gradle" || exit 1
  ( cd "$scratch" && "$GRADLE_BIN" --no-daemon --console=plain wrapper --gradle-version "$version" --distribution-type bin --no-validate-url ) || exit 1
  mkdir -p gradle/wrapper
  cp -p "$scratch/gradlew" "$scratch/gradlew.bat" . || exit 1
  cp -p "$scratch/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/ || exit 1
  rm -rf "$scratch"
  chmod +x ./gradlew
fi

echo "--- ./gradlew assembleDebug"
./gradlew --no-daemon --console=plain assembleDebug || exit 1
EOF
  run_in_container "$inner" "$C_APP"

  [ -f "$APP_APK" ] || die "Gradle finished but $APP_APK does not exist."
}

# ---------------------------------------------------------------- run
[ "$DO_CHIPTOOL" -eq 1 ] && step_chiptool
[ "$DO_COPY" -eq 1 ]     && step_copy
[ "$DO_APP" -eq 1 ]      && step_app

CURRENT_STEP=""
banner "Done"
if [ -f "$APP_APK" ]; then
  say "App debug APK:      $APP_APK"
else
  say "App debug APK:      not built in this run."
fi
if [ -f "$CHIPTOOL_APK" ]; then
  say "CHIPTool debug APK: $CHIPTOOL_APK"
else
  say "CHIPTool debug APK: not built in this run."
fi
say "Install on an arm64 phone with: adb install -r <apk>"
