# AIShell 打包脚本
# 用法: 在项目根目录执行 powershell -ExecutionPolicy Bypass -File packaging/build-package.ps1
# 产物: target/dist/AIShell -> 可双击运行的程序目录 (AIShell.exe)
#       target/dist/AIShell-<version>.msi -> Windows 安装包 (开始菜单 + 可选桌面快捷方式)
#       target/dist/AIShell-<version>-portable.zip -> 便携压缩包
#
# 环境要求: JDK 21（设置 JAVA_HOME，或让 java 出现在 PATH 里）。Maven 用项目自带的
# mvnw 包装器，不需要本机另装。
#
# 版本号 / MSI 覆盖升级（重要）:
#   版本号 = pom.xml 基础版本(major.minor) + 自动构建号(第 3 段)。
#   构建号取 git 提交数(×2, 工作区有未提交改动时再 +1), 每次打包都唯一递增,
#   通过 -Drevision 注入 Maven, jar 文件名、应用内“关于”对话框、MSI 三处版本号自动一致。
#   MSI 只比较版本号前 3 段(第 4 段忽略), 版本递增后双击新包即自动卸载旧版本。
#   GitHub tag 发版使用 vX.Y.Z 中的版本，提交数只用于普通构建。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 版本号: 基础版本取 pom.xml 的 revision 属性(major.minor), 第 3 段是自动构建号
$PomVer = ([xml](Get-Content (Join-Path $root 'pom.xml'))).project.properties.revision
if (-not $PomVer) { $PomVer = '1.0.0' }
$BaseVer = ($PomVer.Split('.')[0..1] -join '.')
$BuildNo = 0
if ((Get-Command git -ErrorAction SilentlyContinue) -and (Test-Path (Join-Path $root '.git'))) {
    # 干净构建 = 提交数×2; 有未提交改动 +1, 保证下一条提交的构建号必然更大
    $Commits = [int](git -C $root rev-list --count HEAD)
    $BuildNo = $Commits * 2
    if (git -C $root status --porcelain) { $BuildNo += 1 }
} else {
    Write-Warning '未找到 git 仓库, 无法计算自动构建号; 版本号不会递增, MSI 覆盖升级需手动处理'
}
$AppVer = if ($BuildNo -gt 0) { "$BaseVer.$BuildNo" } else { $PomVer }
if ($env:GITHUB_REF_TYPE -eq 'tag') {
    if ($env:GITHUB_REF_NAME -cnotmatch '^v((0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*))$') {
        throw 'Release tag must use vX.Y.Z'
    }
    $AppVer = $Matches[1]
}
$ImageName = 'AIShell'
$JarName = "$ImageName-$AppVer.jar"
Write-Host "版本号: $AppVer"

# 固定为 v1.1.0 使用的 jpackage 默认 UpgradeCode（UpgradeCode/runzhi/AIShell）。
# 保持公开发行包的升级关系，避免后续安装被识别为另一个产品。
$UpgradeUuid = 'c13599d5-be42-355d-8b4b-265e83b708b4'

# 1. 定位 JDK（jpackage / jlink 都是 JDK 自带的）
$JdkHome = $env:JAVA_HOME
# JAVA_HOME 可能指向没有 jlink 的旧 JDK（如 1.8），此时回退到 PATH 里的 JDK
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($javaExe) { $JdkHome = Split-Path -Parent (Split-Path -Parent $javaExe) }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
    throw '找不到 JDK：请设置 JAVA_HOME 指向 JDK 21，或让 java 出现在 PATH 里'
}
$env:JAVA_HOME = $JdkHome
Write-Host "使用 JDK: $JdkHome"

# 2. 构建胖 jar (maven-shade-plugin 已内置于 pom.xml)
& .\mvnw.cmd clean package '-DskipTests' "-Drevision=$AppVer"
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }

# 3. 定制 JRE (仅含所需模块, 约 100MB)
if (Test-Path 'target\runtime') { Remove-Item -Recurse -Force 'target\runtime' }
$jlinkModules = 'java.base,java.datatransfer,java.desktop,java.logging,java.management,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.unsupported,jdk.zipfs'
& (Join-Path $JdkHome 'bin\jlink.exe') --add-modules $jlinkModules --include-locales en,zh --no-header-files --no-man-pages --output 'target\runtime'
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

# 4. jpackage 输入目录只放胖 jar
New-Item -ItemType Directory -Force -Path 'target\jpackage-input' | Out-Null
Copy-Item "target\$JarName" 'target\jpackage-input\' -Force

$jpArgs = @(
    '--name', $ImageName,
    '--input', 'target\jpackage-input',
    '--main-jar', $JarName,
    '--main-class', 'com.runzhi.aishell.Launcher',
    '--app-version', $AppVer,
    '--vendor', 'runzhi',
    '--description', 'AIShell - AI native SSH workbench',
    '--icon', 'packaging\app-icon.ico',
    '--runtime-image', 'target\runtime',
    '--dest', 'target\dist',
    '--java-options', '--enable-preview',
    '--java-options', '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--java-options', '--add-opens=java.base/java.util=ALL-UNNAMED',
    '--java-options', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    '--java-options', '--add-opens=java.desktop/sun.awt=ALL-UNNAMED',
    '--java-options', '--add-opens=java.desktop/sun.java2d=ALL-UNNAMED'
)

# 5. 生成可双击运行的 app-image
if (Test-Path ('target\dist\' + $ImageName)) { Remove-Item -Recurse -Force ('target\dist\' + $ImageName) }
& (Join-Path $JdkHome 'bin\jpackage.exe') --type app-image @jpArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed' }

# 6. 便携 zip
Compress-Archive -Path ('target\dist\' + $ImageName) -DestinationPath ('target\dist\' + $ImageName + '-' + $AppVer + '-portable.zip') -Force

# 7. MSI 安装包 (需要 WiX: https://github.com/wixtoolset/wix3/releases 下载 wix311-binaries.zip 解压到 packaging/wix311)
if (Test-Path 'packaging\wix311\candle.exe') {
    $env:Path = 'packaging\wix311;' + $env:Path
    & (Join-Path $JdkHome 'bin\jpackage.exe') --type msi --app-image ('target\dist\' + $ImageName) --name $ImageName --app-version $AppVer --vendor 'runzhi' --description 'AIShell - AI native SSH workbench' --icon 'packaging\app-icon.ico' --dest 'target\dist' --win-per-user-install --win-dir-chooser --win-menu --win-menu-group $ImageName --win-shortcut --win-shortcut-prompt --win-upgrade-uuid $UpgradeUuid
    if ($LASTEXITCODE -ne 0) { throw 'jpackage msi failed' }
    Write-Host ('MSI: target\dist\' + $ImageName + '-' + $AppVer + '.msi')
} else {
    Write-Host '未找到 packaging\wix311\candle.exe, 跳过 MSI 生成 (仅 app-image + zip)'
}

Write-Host 'Done. See target\dist'
