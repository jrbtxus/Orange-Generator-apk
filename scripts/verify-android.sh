#!/usr/bin/env bash
# 本机安卓校验（可选，但很省事）：资源静态检查 + Java 编译校验。
#
# 目的：在没有 Android SDK、没有 Gradle、甚至没有 javac 的机器上，先确认
# `SaveToGalleryPlugin.java` / `MainActivity.java` 能编译过，再去推 CI，省一个来回。
#
# 做法：Eclipse 编译器（ECJ）+ 一份 platform 的 android.jar + Capacitor core 的 AAR。
#   * ECJ 从 Maven Central 下（org.eclipse.jdt:ecj）
#   * Capacitor core 从 Maven Central 下（com.capacitorjs:core:<版本>）
#   * androidx 依赖从 Google Maven 下（dl.google.com/dl/android/maven2）
#   * android.jar 需要自备（见下），没有就跳过
#
# 用法：
#   bash scripts/verify-android.sh
#
# 环境变量：
#   ANDROID_JAR   指定 platform 的 android.jar；不给就依次找
#                 $ANDROID_HOME/platforms/android-35/android.jar 等常见位置
#   CACHE_DIR     依赖缓存目录，默认 .cache/android-java-check
#
# 找不到 android.jar 时会**跳过**并返回 0（这是本机的可选检查，不是构建的一部分）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${CACHE_DIR:-$ROOT/.cache/android-java-check}"
DEPS="$CACHE_DIR/deps"
OUT="$CACHE_DIR/out"
CAP_VERSION="$(node -e "console.log(require('$ROOT/node_modules/@capacitor/core/package.json').version)" 2>/dev/null || echo "")"
M_CENTRAL="https://repo1.maven.org/maven2"
M_GOOGLE="https://dl.google.com/dl/android/maven2"

log() { printf '* %s\n' "$*"; }
die() { printf 'x %s\n' "$*" >&2; exit 1; }

# 1) 资源静态检查：不需要 SDK，先跑。历史上这里漏过 XML 注释里的连续短横线
#    （aapt 报 "The string -- is not permitted within comments"），害 CI 白跑一趟。
log "检查安卓资源（XML 合法性 + 资源引用）"
python3 "$ROOT/scripts/check-android-res.py" || die "资源检查未通过"

find_android_jar() {
  if [ -n "${ANDROID_JAR:-}" ]; then
    [ -f "$ANDROID_JAR" ] || die "ANDROID_JAR 指向的文件不存在：$ANDROID_JAR"
    printf '%s' "$ANDROID_JAR"; return 0
  fi
  local candidates=(
    "${ANDROID_HOME:-}/platforms/android-35/android.jar"
    "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar"
    "$HOME/Android/Sdk/platforms/android-35/android.jar"
    "/usr/lib/android-sdk/platforms/android-35/android.jar"
    "/opt/android-verify/platforms/android-35/android.jar"
  )
  local c
  for c in "${candidates[@]}"; do
    [ -n "$c" ] && [ -f "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

AJ="$(find_android_jar || true)"
if [ -z "$AJ" ]; then
  log "没找到 platform 的 android.jar，跳过本机 Java 校验（CI 里会真编译）。"
  log "想跑的话：ANDROID_JAR=/path/to/platforms/android-35/android.jar bash scripts/verify-android.sh"
  exit 0
fi

[ -n "$CAP_VERSION" ] || die "读不到 @capacitor/core 版本，先在仓库根目录跑 npm ci"
command -v java >/dev/null || die "需要 java（只用来跑 ECJ，不需要 javac）"
command -v python3 >/dev/null || die "需要 python3（用来生成去重后的 android.jar）"
command -v curl >/dev/null || die "需要 curl（用来下依赖）"

mkdir -p "$DEPS" "$OUT"
log "android.jar = $AJ"
log "Capacitor core = $CAP_VERSION"

fetch() { # url dest
  [ -s "$2" ] && return 0
  curl -fsSL --max-time 120 -o "$2" "$1" || die "下载失败：$1"
}

ECJ="$DEPS/ecj.jar"
fetch "$M_CENTRAL/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" "$ECJ"

CAP_AAR="$DEPS/capacitor-core-$CAP_VERSION.aar"
fetch "$M_CENTRAL/com/capacitorjs/core/$CAP_VERSION/core-$CAP_VERSION.aar" "$CAP_AAR"

# androidx：android.jar / AppCompat 的父类型链要用到，缺一个 ECJ 就会报
# "The hierarchy of the type ... is inconsistent"
ANDROIDX=(
  "androidx/collection/collection/1.4.5/collection-1.4.5.jar"
  "androidx/arch/core/core-common/2.2.0/core-common-2.2.0.jar"
  "androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar"
  "androidx/lifecycle/lifecycle-common-jvm/2.8.7/lifecycle-common-jvm-2.8.7.jar"
  "androidx/lifecycle/lifecycle-runtime-android/2.8.7/lifecycle-runtime-android-2.8.7.aar"
  "androidx/lifecycle/lifecycle-viewmodel-android/2.8.7/lifecycle-viewmodel-android-2.8.7.aar"
  "androidx/activity/activity/1.9.2/activity-1.9.2.aar"
  "androidx/core/core/1.15.0/core-1.15.0.aar"
  "androidx/fragment/fragment/1.8.4/fragment-1.8.4.aar"
  "androidx/appcompat/appcompat/1.7.0/appcompat-1.7.0.aar"
  "androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar"
  "androidx/tracing/tracing/1.2.0/tracing-1.2.0.aar"
  "androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar"
  "androidx/coordinatorlayout/coordinatorlayout/1.2.0/coordinatorlayout-1.2.0.aar"
  "androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar"
  "androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar"
  "androidx/emoji2/emoji2/1.3.0/emoji2-1.3.0.aar"
  "androidx/loader/loader/1.0.0/loader-1.0.0.aar"
  "androidx/drawerlayout/drawerlayout/1.1.1/drawerlayout-1.1.1.aar"
  "androidx/cursoradapter/cursoradapter/1.0.0/cursoradapter-1.0.0.aar"
  "androidx/customview/customview/1.1.0/customview-1.1.0.aar"
  "androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.aar"
  "androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.aar"
)

log "收集依赖（首次会下载，之后走缓存）"
idx=0
for path in "${ANDROIDX[@]}"; do
  name="$(basename "$path")"
  fetch "$M_GOOGLE/$path" "$DEPS/$name"
  idx=$((idx + 1))
done

# android.jar 里自带一份 java.* 副本，和 JDK 的 java.base 模块冲突
# （ECJ 会报 "package java.io is accessible from more than one module"），先剥掉。
AJ_STRIPPED="$OUT/android-stripped.jar"
if [ ! -s "$AJ_STRIPPED" ] || [ "$AJ" -nt "$AJ_STRIPPED" ]; then
  log "生成去重后的 android.jar"
  python3 - "$AJ" "$AJ_STRIPPED" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename.startswith(("java/", "javax/", "META-INF/")):
            continue
        zout.writestr(item, zin.read(item.filename))
PY
fi

# AAR → classes.jar
log "解出 AAR 里的 classes.jar"
python3 - "$DEPS" <<'PY'
import glob, os, sys, zipfile
deps = sys.argv[1]
for aar in sorted(glob.glob(os.path.join(deps, "*.aar"))):
    out = aar[:-4] + "-classes.jar"
    if os.path.exists(out):
        continue
    with zipfile.ZipFile(aar) as z:
        if "classes.jar" in z.namelist():
            with open(out, "wb") as fh:
                fh.write(z.read("classes.jar"))
PY
python3 - "$DEPS" "$CAP_AAR" <<'PY'
import sys, zipfile
deps, aar = sys.argv[1], sys.argv[2]
out = deps + "/capcore-classes.jar"
with zipfile.ZipFile(aar) as z, open(out, "wb") as fh:
    fh.write(z.read("classes.jar"))
PY

CP="$AJ_STRIPPED:$DEPS/capcore-classes.jar"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find "$DEPS" -name '*.jar' ! -name 'ecj.jar' ! -name 'capcore-classes.jar' | sort)

SRC="$ROOT/android/app/src/main/java/io/github/jrbtxus/orangegenerator"
rm -rf "$OUT/appclasses"; mkdir -p "$OUT/appclasses"

log "ECJ 编译 $SRC"
set +e
java -jar "$ECJ" -nowarn -proc:none -source 17 -target 17 \
  -cp "$CP" -d "$OUT/appclasses" "$SRC"/*.java
rc=$?
set -e

if [ "$rc" -ne 0 ]; then
  die "Java 校验失败（上面是 ECJ 的报错）"
fi

count="$(find "$OUT/appclasses" -name '*.class' | wc -l)"
log "通过：编译出 $count 个 class（$(find "$OUT/appclasses" -name '*.class' -printf '%f ' )）"
