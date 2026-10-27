# AIShell 打包脚本
# 用法: 在项目根目录执行 powershell -ExecutionPolicy Bypass -File packaging/build-package.ps1
# 产物: target/dist/AIShell -> 可双击运行的程序目录 (AIShell.exe)
#       target/dist/AIShell-1.0.0.msi -> Windows 安装包 (开始菜单 + 可选桌面快捷方式)
#       target/dist/AIShell-1.0.0-portable.zip -> 便携压缩包
#
# 环境要求: JDK 21（设置 JAVA_HOME，或让 java 出现在 PATH 里）。Maven 用项目自带的
# mvnw 包装器，不需要本机另装。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$AppVer = '1.0.0'
$ImageName = 'AIShell'
$JarName = "$ImageName-$AppVer.jar"

# 1. 定位 JDK（jpackage / jlink 都是 JDK 自带的）
$JdkHome = $env:JAVA_HOME
if (-not $JdkHome) {
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($javaExe) { $JdkHome = Split-Path -Parent (Split-Path -Parent $javaExe) }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
    throw '找不到 JDK：请设置 JAVA_HOME 指向 JDK 21，或让 java 出现在 PATH 里'
}
$env:JAVA_HOME = $JdkHome
Write-Host "使用 JDK: $JdkHome"

# 2. 构建胖 jar (maven-shade-plugin 已内置于 pom.xml)
& .\mvnw.cmd clean package '-DskipTests'
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
    & (Join-Path $JdkHome 'bin\jpackage.exe') --type msi --app-image ('target\dist\' + $ImageName) --name $ImageName --app-version $AppVer --vendor 'runzhi' --description 'AIShell - AI native SSH workbench' --icon 'packaging\app-icon.ico' --dest 'target\dist' --win-per-user-install --win-dir-chooser --win-menu --win-menu-group $ImageName --win-shortcut --win-shortcut-prompt
    if ($LASTEXITCODE -ne 0) { throw 'jpackage msi failed' }
    Write-Host ('MSI: target\dist\' + $ImageName + '-' + $AppVer + '.msi')
} else {
    Write-Host '未找到 packaging\wix311\candle.exe, 跳过 MSI 生成 (仅 app-image + zip)'
}

Write-Host 'Done. See target\dist'
