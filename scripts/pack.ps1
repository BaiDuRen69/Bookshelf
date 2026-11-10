<#
    pack.ps1 —— 交付包产出 + 机器校验

    流程：assembleRelease → aapt dump badging 读包名/版本/ABI → 校验「包名必须是正式版、
    native-code 只能是 ARM 两套、包内版本等于 build.gradle 声明」→ 与 docs/发版记录.md 的
    上一条可交付行比 SHA-256 与体积（±5%）→ 扫一遍交付目录里所有 XiaoShuFang-*.apk →
    全部判定通过才复制成 XiaoShuFang-v{versionName}.apk，并打印 SHA-256 与体积。

    工具链默认值一律从仓库/环境推导（卡 A2：本机绝对路径不再当默认值），参数只做 override：
        -GradleExe  默认 <工程根>/gradlew.bat（Windows）或 ./gradlew
        -JavaHome   默认取环境变量 JAVA_HOME；没有就用 PATH 上的 java（gradlew 自己能找）
        -SdkDir     默认取 local.properties 的 sdk.dir，再退到 ANDROID_HOME / ANDROID_SDK_ROOT
        -OutDir     默认工程根的上一级（工作空间根）

    用法（在任意目录均可）：
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pack.ps1
        powershell ... -File scripts\pack.ps1 -OutDir D:\some\dir      # 换交付目录
        powershell ... -File scripts\pack.ps1 -SkipBuild               # 只校验已有 release 包
        powershell ... -File scripts\pack.ps1 -JavaHome D:\tools\jdk   # 只在需要时覆盖

    退出码：0 = 全部判定通过且已产出；1 = 硬失败（环境 / 构建 / 这个产物本身不可交付，不产出文件）；
    N(>1) = 产物本身可用但判定不通过，N = Fail 条数（此时同样不复制到交付目录）。判定类失败逐条列完
    再退，不在第一条就 exit，免得「修一个跑一次」。

    注意：aapt 打不开含中文的路径（静默无输出），所以校验一律先拷到 %TEMP% 的纯英文路径再做。
    体积基准是「上一条**可交付**行」而不是字面上一行：交付目录里混过 debug 包时，字面上一行是
    74 MB 的 .devtest 产物，拿它当基线会给正常版本报出 -49% 的假告警。
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [switch]$SkipBuild,
    [string]$GradleExe,
    [string]$JavaHome,
    [string]$SdkDir,
    [string]$Package = 'com.bai.bookshelf'
)

$ErrorActionPreference = 'Stop'

# 输出中文：统一按 UTF-8 写控制台（老 cp936 窗口请先 chcp 65001）
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 允许进入交付目录的 ABI；debug/devtest 包多出来的 x86 / x86_64 一律拒绝
$AllowedAbis = @('arm64-v8a', 'armeabi-v7a')
$SizeTolerancePct = 5.0

# scripts/ 的上一级是工程目录 Bookshelf/，再上一级是工作空间根（交付目录默认放这里）
$ProjectDir = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
    $OutDir = Split-Path -Parent $ProjectDir
}
$DocRelease = Join-Path $ProjectDir 'docs\发版记录.md'

# ---------- 判定计数：硬失败当场退，判定失败逐条累计 ----------
$script:FailCount = 0
function Fail([string]$Message) {
    Write-Host "[FAIL] $Message"
    exit 1
}
function Add-Fail([string]$Message) {
    Write-Host "[FAIL] $Message"
    $script:FailCount++
}

# 外部命令的 stderr（javac 过时 API 提示、aapt 警告）不该变成终止性错误
function RunNative([scriptblock]$Block) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $Block 2>&1 | ForEach-Object { "$_" })
        $code = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }
    }
    finally {
        $ErrorActionPreference = $prev
    }
    New-Object psobject -Property @{ Lines = $lines; Code = $code }
}

# java properties 的转义：sdk.dir=C\:\\Users\\bai\\AppData\\Local\\Android\\Sdk
# 必须显式按 UTF-8 读：该文件常带中文注释，Windows PowerShell 5.1 默认按 cp936 解码会把行尾 LF
# 当成双字节字符的尾字节吃掉，两行并成一行，sdk.dir 就再也匹配不上。
function Get-SdkDirFromProperties([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    foreach ($line in @(Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.*)$') {
            $value = $Matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
            if ($value) { return $value }
        }
    }
    return $null
}

# aapt 只认英文路径，所以先拷到 %TEMP% 再 dump
function Get-Badging([string]$Aapt, [string]$Apk) {
    $staged = Join-Path $env:TEMP ('xsfpack_' + [Guid]::NewGuid().ToString('N') + '.apk')
    Copy-Item -LiteralPath $Apk -Destination $staged -Force
    try {
        $run = RunNative { & $Aapt dump badging $staged 2>&1 }
        if ($run.Code -ne 0 -or -not $run.Lines) { return $null }
        return $run.Lines
    }
    finally {
        Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
    }
}

# badging 行 → 包名 / versionCode / versionName / ABI；解析不到 package 行返回 $null
function Read-PackageInfo([string[]]$Badging) {
    $pkg = $Badging | Select-String -Pattern "^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'" | Select-Object -First 1
    if (-not $pkg) { return $null }
    $abi = $Badging | Select-String -Pattern '^native-code:' | Select-Object -First 1
    $abis = @()
    if ($abi) {
        $abis = @([regex]::Matches($abi.Line, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
    }
    New-Object psobject -Property @{
        Name        = $pkg.Matches[0].Groups[1].Value
        VersionCode = $pkg.Matches[0].Groups[2].Value
        VersionName = $pkg.Matches[0].Groups[3].Value
        Abis        = $abis
    }
}

# ---------- 0. 工具链：默认值从仓库/环境推导，参数只做 override ----------
if (-not $GradleExe) {
    $wrapper = if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' }
    $GradleExe = Join-Path $ProjectDir $wrapper
}
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $SdkDir) {
    $SdkDir = Get-SdkDirFromProperties (Join-Path $ProjectDir 'local.properties')
    if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
    if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
}

Write-Host ("> 工具链 gradle    = {0}" -f $GradleExe)
Write-Host ("> 工具链 JAVA_HOME = {0}" -f $(if ($JavaHome) { $JavaHome } else { '（未指定，交给 gradlew 用 PATH 上的 java）' }))
Write-Host ("> 工具链 sdk       = {0}" -f $SdkDir)

if (-not $SdkDir) {
    Fail "推导不出 Android SDK 路径：local.properties 里没有 sdk.dir，ANDROID_HOME / ANDROID_SDK_ROOT 也为空；用 -SdkDir 指定"
}
$aaptCandidates = Get-ChildItem -Path (Join-Path $SdkDir 'build-tools') -Filter 'aapt.exe' -Recurse -ErrorAction SilentlyContinue
if (-not $aaptCandidates) {
    Fail "在 $SdkDir\build-tools 下找不到 aapt.exe（local.properties 的 sdk.dir 对不对，或用 -SdkDir 指定）"
}
$aapt = ($aaptCandidates | Sort-Object DirectoryName -Descending | Select-Object -First 1).FullName

# ---------- 1. assembleRelease ----------
$ApkPath = Join-Path $ProjectDir 'app\build\outputs\apk\release\app-release.apk'

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $GradleExe)) {
        Fail "找不到 gradle wrapper：$GradleExe（仓库里应有 gradlew / gradlew.bat；或用 -GradleExe 指定）"
    }
    if (-not $JavaHome -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        Fail "既没有 JAVA_HOME 环境变量，PATH 上也没有 java：用 -JavaHome 指定一个 JDK 21"
    }
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    Push-Location $ProjectDir
    try {
        Write-Host '> gradle assembleRelease --offline --console=plain'
        $Build = RunNative { & $GradleExe assembleRelease --offline --console=plain 2>&1 }
        $Build.Lines | Select-Object -Last 4
        if ($Build.Code -ne 0) {
            Fail "assembleRelease 退出码 $($Build.Code)"
        }
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    Fail "没有产出 release 包：$ApkPath"
}

# ---------- 2. 本次产物的 badging ----------
$Badging = Get-Badging $aapt $ApkPath
if (-not $Badging) {
    Fail "aapt dump badging 失败：$ApkPath"
}
$Info = Read-PackageInfo $Badging
if (-not $Info) {
    Fail "badging 里解析不到 package 行"
}
$PkgName = $Info.Name
$VersionCode = $Info.VersionCode
$VersionName = $Info.VersionName
$Abis = $Info.Abis

# ---------- 3. 本次产物的硬判据（这个包不可交付就直接退，不产出文件） ----------
if ($PkgName -ne $Package) {
    Fail "包名 $PkgName 不是 $Package（.devtest 禁止进入交付目录）"
}
if ($Abis.Count -eq 0) {
    Fail 'native-code 解析为空'
}
$Bad = @($Abis | Where-Object { $AllowedAbis -notcontains $_ })
if ($Bad.Count -gt 0) {
    Fail ("含非交付 ABI：" + ($Bad -join ', ') + '（只允许 ' + ($AllowedAbis -join ', ') + '）')
}
if (-not $VersionName) {
    Fail 'versionName 为空，无法命名'
}

# 防「拿旧 release 包交差」：包里的版本必须等于 app/build.gradle 声明的版本
$GradleFile = Join-Path $ProjectDir 'app\build.gradle'
$Declared = Select-String -LiteralPath $GradleFile -Pattern 'versionName\s+"([^"]+)"' | Select-Object -First 1
if (-not $Declared) {
    Fail "在 $GradleFile 里找不到 versionName 声明"
}
$DeclaredVersion = $Declared.Matches[0].Groups[1].Value
if ($DeclaredVersion -ne $VersionName) {
    Fail ("包内 versionName $VersionName ≠ app/build.gradle 声明的 $DeclaredVersion（产物是旧的，重新构建）")
}

$Hash = (Get-FileHash -Algorithm SHA256 -Path $ApkPath).Hash.ToLower()
$Size = (Get-Item -LiteralPath $ApkPath).Length

# ---------- 4. 与 docs/发版记录.md 的上一条可交付行比 SHA 与体积 ----------
$Baseline = $null
if (-not (Test-Path -LiteralPath $DocRelease)) {
    Add-Fail "发版记录不存在：$DocRelease（SHA 与体积比对没有基准）"
}
else {
    foreach ($line in @(Get-Content -LiteralPath $DocRelease -Encoding UTF8)) {
        if ($line.TrimStart() -notmatch '^\|') { continue }
        $cells = $line.Split('|')
        if ($cells.Count -lt 10) { continue }
        $verdict = $cells[9]
        if ($verdict -match '不可交付') { continue }
        if ($verdict -notmatch '可交付') { continue }
        $shaMatch = [regex]::Match($cells[8], '([0-9a-fA-F]{64})')
        $sizeMatch = [regex]::Match($cells[7], '(\d[\d ]{4,})\s*B')
        if (-not $shaMatch.Success -or -not $sizeMatch.Success) { continue }
        $Baseline = New-Object psobject -Property @{
            Name  = $cells[2].Trim().Trim('`')
            Sha   = $shaMatch.Value.ToLower()
            Bytes = [long]($sizeMatch.Value -replace '\D', '')
        }
    }
    if (-not $Baseline) {
        Add-Fail 'docs/发版记录.md 里没有可解析的可交付行（判定列缺「可交付」，或体积/SHA 列格式不对）：本轮没有基准'
    }
}
if ($Baseline) {
    Write-Host ("> 基准 上一条可交付 = {0} / {1} 字节 / SHA {2}…" -f $Baseline.Name, $Baseline.Bytes, $Baseline.Sha.Substring(0, 12))
    if ($Hash -eq $Baseline.Sha) {
        Add-Fail ("SHA-256 与上一条可交付记录完全相同（{0}…）：内容没变，不要当新版本交付" -f $Hash.Substring(0, 12))
    }
    $pct = ($Size - $Baseline.Bytes) / [double]$Baseline.Bytes * 100.0
    if ([Math]::Abs($pct) -gt $SizeTolerancePct) {
        Add-Fail ("体积 {0} 字节 相对上一条可交付行 {1}（{2} 字节）变化 {3:N2}%，超出 ±{4:N0}%：要么产物异常，要么确认变化合理后把上一行补进发版记录" `
                  -f $Size, $Baseline.Name, $Baseline.Bytes, $pct, $SizeTolerancePct)
    }
    else {
        Write-Host ("> 体积 判定通过（{0:+0.00;-0.00}%，阈值 ±{1:N0}%）" -f $pct, $SizeTolerancePct)
    }
}

# ---------- 5. 扫交付目录里全部 XiaoShuFang-*.apk ----------
# 判据只取与版本无关的三条：包名、ABI、文件名版本 == 包内 versionName。
# 「包内版本 == build.gradle 声明」只对当前产物成立，历史记录不参与。
$Target = Join-Path $OutDir ("XiaoShuFang-v{0}.apk" -f $VersionName)
$swept = @(Get-ChildItem -LiteralPath $OutDir -Filter 'XiaoShuFang-*.apk' -ErrorAction SilentlyContinue)
if ($swept.Count -gt 0) {
    Write-Host ("> 扫描交付目录 {0}：{1} 个 XiaoShuFang-*.apk" -f $OutDir, $swept.Count)
}
foreach ($file in $swept) {
    $fileBadging = Get-Badging $aapt $file.FullName
    $fileInfo = if ($fileBadging) { Read-PackageInfo $fileBadging } else { $null }
    if (-not $fileInfo) {
        Add-Fail ("旧包 {0}：aapt 读不出 badging 或解析不到 package 行（损坏 / 路径异常）" -f $file.Name)
        continue
    }
    $problems = @()
    if ($fileInfo.Name -ne $Package) {
        $problems += ("包名 {0} 不是 {1}" -f $fileInfo.Name, $Package)
    }
    $stray = @($fileInfo.Abis | Where-Object { $AllowedAbis -notcontains $_ })
    if ($fileInfo.Abis.Count -eq 0) {
        $problems += '没有 native-code 行'
    }
    elseif ($stray.Count -gt 0) {
        $problems += ('含非交付 ABI：' + ($stray -join ', '))
    }
    $fileVersion = [regex]::Match($file.BaseName, 'v([0-9][0-9.]*)$').Groups[1].Value
    if ($fileVersion -and ($fileVersion -ne $fileInfo.VersionName)) {
        $problems += ("文件名版本 {0} ≠ 包内 versionName {1}" -f $fileVersion, $fileInfo.VersionName)
    }
    if ($problems.Count -gt 0) {
        Add-Fail ("旧包 {0}（versionName {1} / {2:N0} 字节）不可交付：{3}" -f `
                  $file.Name, $fileInfo.VersionName, $file.Length, ($problems -join '；'))
    }
    else {
        Write-Host ("> 旧包通过     {0}  versionName {1}" -f $file.Name, $fileInfo.VersionName)
    }
}

# ---------- 6. 出口：按 Fail 计数决定退出码，有 Fail 就不复制到交付目录 ----------
Write-Host ''
Write-Host ("本次产物: {0}" -f $ApkPath)
Write-Host ("包名    : {0} (versionCode {1} / versionName {2})" -f $PkgName, $VersionCode, $VersionName)
Write-Host ("ABI     : {0}" -f ($Abis -join ', '))
Write-Host ("SHA-256 : {0}" -f $Hash)
Write-Host ("体积    : {0} 字节（{1:N2} MB）" -f $Size, ($Size / 1MB))

if ($script:FailCount -gt 0) {
    Write-Host ''
    Write-Host ("[未交付] 判定失败 {0} 条，没有复制到 {1}" -f $script:FailCount, $Target)
    Write-Host '           旧包请按点名删除或移出交付目录（脚本不代删）；产物本身没问题时，清理完再跑一次即可。'
    exit ([Math]::Min($script:FailCount, 125))
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
Copy-Item -LiteralPath $ApkPath -Destination $Target -Force
Write-Host ("交付路径: {0}" -f $Target)
Write-Host '[OK] 全部判定通过，已交付。记得把这一行补进 docs/发版记录.md（新行加在表末，判定列写「可交付」）。'
exit 0
