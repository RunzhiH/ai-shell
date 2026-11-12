#!/usr/bin/env bash
# AIShell macOS 打包脚本
# 用法: 在项目根目录执行 bash packaging/build-package.sh
# 产物: target/dist/AIShell.app             -> 可双击运行的应用
#       target/dist/AIShell-<version>.dmg   -> macOS 安装镜像 (拖入 Applications 安装)
#
# 环境要求: JDK 21 及以上 (设置 JAVA_HOME，或让 java 出现在 PATH 里)。
#          dmg 用 macOS 自带的 hdiutil 生成，不需要额外工具；pkg 需要 Xcode
#          command line tools，可选。
#
# 说明: jpackage 不能交叉打包，本脚本只能在 macOS 上运行。Windows 包请用
#       packaging/build-package.ps1。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

# 版本号与 pom.xml 保持一致，发版时只改一处
APP_VER="$(sed -n 's/^ *<version>\(.*\)<\/version> *$/\1/p' pom.xml | head -n 1)"
IMAGE_NAME="AIShell"
JAR_NAME="${IMAGE_NAME}-${APP_VER}.jar"

echo "AIShell 版本: ${APP_VER}"

# 1. 定位 JDK（jpackage / jlink 都是 JDK 自带的）
JDK_HOME="${JAVA_HOME:-}"
if [ -z "${JDK_HOME}" ] || [ ! -x "${JDK_HOME}/bin/jlink" ]; then
    # macOS 上先问 java_home（会按版本选最高的 JDK）
    if [ -x /usr/libexec/java_home ]; then
        JDK_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    fi
fi
if [ -z "${JDK_HOME}" ] || [ ! -x "${JDK_HOME}/bin/jlink" ]; then
    JAVA_EXE="$(command -v java || true)"
    if [ -n "${JAVA_EXE}" ]; then
        # 解析软链接 (macOS 上 java 通常在 JDK 的 Contents/Home/bin 下)
        JDK_HOME="$(cd "$(dirname "${JAVA_EXE}")/.." && pwd -P)"
    fi
fi
if [ -z "${JDK_HOME}" ] || [ ! -x "${JDK_HOME}/bin/jlink" ]; then
    echo '找不到 JDK：请设置 JAVA_HOME 指向 JDK 21，或让 java 出现在 PATH 里' >&2
    exit 1
fi
echo "使用 JDK: ${JDK_HOME}"

# 2. 构建胖 jar (maven-shade-plugin 已内置于 pom.xml)
[ -x ./mvnw ] || chmod +x ./mvnw
./mvnw clean package -DskipTests

# 3. 定制 JRE (与 Windows 脚本一致的模块集合)
if [ -d target/runtime ]; then rm -rf target/runtime; fi
jlink_modules='java.base,java.datatransfer,java.desktop,java.logging,java.management,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.unsupported,jdk.zipfs'
"${JDK_HOME}/bin/jlink" --add-modules "${jlink_modules}" --include-locales en,zh \
    --no-header-files --no-man-pages --output target/runtime

# 4. jpackage 输入目录只放胖 jar
mkdir -p target/jpackage-input
cp "target/${JAR_NAME}" target/jpackage-input/

# 可选签名: 正式分发建议传 Developer ID 证书并另行公证 (xcrun notarytool)
#   AISHELL_MAC_SIGN_IDENTITY="Developer ID Application: Your Name (XXXXXXXXXX)" bash packaging/build-package.sh
JP_ARGS=(
    --name "${IMAGE_NAME}"
    --input target/jpackage-input
    --main-jar "${JAR_NAME}"
    --main-class com.runzhi.aishell.Launcher
    --app-version "${APP_VER}"
    --vendor runzhi
    --description 'AIShell - AI native SSH workbench'
    --icon packaging/app-icon.icns
    --runtime-image target/runtime
    --dest target/dist
    --java-options '--enable-preview'
    --java-options '--add-opens=java.base/java.lang=ALL-UNNAMED'
    --java-options '--add-opens=java.base/java.util=ALL-UNNAMED'
    --java-options '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED'
    --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED'
    --java-options '--add-opens=java.desktop/sun.java2d=ALL-UNNAMED'
    --mac-package-identifier com.runzhi.aishell
    --mac-package-name "${IMAGE_NAME}"
)
# 空数组展开写法兼容 bash 3.2 (macOS 和 GitHub Actions 默认 shell) 下 set -u 对空数组的报错
SIGN_ARGS=()
if [ -n "${AISHELL_MAC_SIGN_IDENTITY:-}" ]; then
    SIGN_ARGS=(--mac-sign --mac-signing-key-user-name "${AISHELL_MAC_SIGN_IDENTITY}")
    if [ -n "${AISHELL_MAC_SIGN_KEYCHAIN:-}" ]; then
        SIGN_ARGS+=(--mac-signing-keychain "${AISHELL_MAC_SIGN_KEYCHAIN}")
    fi
    echo "将使用签名身份: ${AISHELL_MAC_SIGN_IDENTITY}"
fi

# 5. 生成可双击运行的 .app
if [ -d "target/dist/${IMAGE_NAME}.app" ]; then rm -rf "target/dist/${IMAGE_NAME}.app"; fi
"${JDK_HOME}/bin/jpackage" --type app-image ${JP_ARGS[@]+"${JP_ARGS[@]}"} ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}

# 6. 生成 dmg (需要 macOS 自带的 hdiutil)
"${JDK_HOME}/bin/jpackage" --type dmg \
    --app-image "target/dist/${IMAGE_NAME}.app" \
    --name "${IMAGE_NAME}" --app-version "${APP_VER}" --vendor runzhi \
    --description 'AIShell - AI native SSH workbench' \
    --icon packaging/app-icon.icns \
    --dest target/dist ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}

echo "Done."
echo "  app: target/dist/${IMAGE_NAME}.app"
echo "  dmg: target/dist/${IMAGE_NAME}-${APP_VER}.dmg"
