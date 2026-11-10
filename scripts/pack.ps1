<#
    pack.ps1 —— 交付包产出 + 机器校验

    流程：assembleRelease → aapt dump badging 读包名/版本/ABI → 校验「包名必须是正式版、
    native-code 只能是 ARM 两套、包内版本等于 build.gradle 声明」→ 与 docs/发版记录.md 的
    上一条可交付行比 SHA-256 与体积（±5%）→ 扫一遍交付目录里所有 XiaoShuFang-*.apk →
    全部判定通过才复制成 XiaoShuFang-v{versionName}.apk，并打印 SHA-256 与体积 →
    工作区干净时把这包的 HEAD / SHA / 体积自动追加成台账新行（不干净只打印原因，不写行）。

    工具链默认值一律从仓库/环境推导（卡 A2：本机绝对路径不再当默认值），参数只做 override：
        -GradleExe  默认 <工程根>/gradlew.bat（Windows）或 ./gradlew
        -JavaHome   默认取环境变量 JAVA_HOME；没有就用 PATH 上的 java（gradlew 自己能找）
        -SdkDir     默认取 local.properties 的 sdk.dir，再退到 ANDROID_HOME / ANDROID_SDK_ROOT
        -OutDir     默认工作空间根的 dist/（卡 G2：交付产物不再散在工作空间根）

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
    台账（docs/发版记录.md）的取列下标写在文件开头的 $Col* 常量里，改表头必须同步改那里；
    体积列的千分位只用空格（基准解析的正则认空格不认逗号）。
    只想验证判定、不想覆盖交付目录里的正式包时：-SkipBuild -OutDir %TEMP%\某个空目录。
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

# scripts/ 的上一级是工程目录 Bookshelf/，再上一级是工作空间根；交付目录是它下面的 dist/
$ProjectDir = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
    $OutDir = Join-Path (Split-Path -Parent $ProjectDir) 'dist'
}
$DocRelease = Join-Path $ProjectDir 'docs\发版记录.md'

# 台账表的列下标（Split('|') 后 cells[0] 是行首竖线前的空串）：
# | 日期 | 文件名 | versionCode | versionName | SHA-256 | 体积 | 产出时 HEAD | 工作区 | 正式槽位安装 | androidTest 复跑 | 判定 |
# 改这张表的列顺序时必须同步改这里，否则基准解析会静默拿到错的列（比对口径见 docs/发版记录.md）。
$LedgerCells = 12   # 至少这么多个分段才算一行台账（判定下标 11 存在）
$ColDate = 1
$ColName = 2
$ColCode = 3
$ColVer = 4
$ColSha = 5
$ColSize = 6
$ColHead = 7
$ColWork = 8
$ColSlot = 9
$ColTest = 10
$ColVerdict = 11

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

# ---------- 台账写行：git 状态 + 追加一行 ----------
# 「工作区不干净」= 跟踪文件有改动，或 app/ 下有新文件没提交——这两种情况下产出的 APK 归不到
# 任何一个提交上（v5.9.5 就是这么交付的：包比仓库里的源码早一天）。其余目录的未跟踪文件
# （辅助脚本、截图）不参与构建，只计数不拦。
function Get-GitState([string]$Repo) {
    $unknown = New-Object psobject -Property @{
        Available = $false; Head = $null; Reason = ''; Dirty = @(); Other = 0
    }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        $unknown.Reason = 'PATH 上没有 git，读不出 HEAD 与工作区状态'
        return $unknown
    }
    $rev = RunNative { & git -C $Repo rev-parse --short HEAD 2>&1 }
    if ($rev.Code -ne 0 -or -not $rev.Lines) {
        $unknown.Reason = "git rev-parse 失败（$Repo 不是 git 仓库？）"
        return $unknown
    }
    $head = ("$($rev.Lines[0])").Trim()
    $st = RunNative { & git -C $Repo status --porcelain 2>&1 }
    if ($st.Code -ne 0) {
        $unknown.Reason = 'git status --porcelain 失败'
        return $unknown
    }
    $entries = @($st.Lines | ForEach-Object { "$_".TrimEnd() } | Where-Object { $_.Trim() })
    # M/A/D/R/C/U 开头都是跟踪文件的改动；?? 里只有 app/ 下的会影响产物
    $dirty = @($entries | Where-Object {
        $_ -notmatch '^\?\?' -or $_ -match '^\?\?\s+"?app/'
    })
    New-Object psobject -Property @{
        Available = $true; Head = $head; Reason = ''
        # 注意别写 @(a - b)：@() 会把标量包成 Object[]，下游 [int] 参数绑定会直接报错
        Dirty = $dirty; Other = ($entries.Count - $dirty.Count)
    }
}

function Get-LedgerRow([string]$ApkPath, [string]$FileName, [string]$Code, [string]$Ver,
                       [string]$Sha, [long]$Bytes, [string]$Head, [int]$Other) {
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    $date = (Get-Item -LiteralPath $ApkPath).LastWriteTime.ToString('yyyy-MM-dd', $inv)
    $mb = [Math]::Round($Bytes / 1MB, 2).ToString('0.00', $inv)
    # 体积分组只用空格：与表里历史行写法一致，且基准解析的正则是 (\d[\d ]{4,})\s*B——
    # 认空格、认不出逗号，N0 格式默认带逗号会把这一列读成 0。
    $nfi = New-Object System.Globalization.NumberFormatInfo
    $nfi.NumberGroupSeparator = ' '
    $size = $Bytes.ToString('#,0', $nfi)
    '| {0} | `{1}` | {2} | {3} | `{4}` | {5} B（{6} MB） | `{7}` | 干净（另有 {8} 个与构建无关的未跟踪文件） | 待填 | 待填 | 可交付（pack.ps1 自动写入） |' `
        -f $date, $FileName, $Code, $Ver, $Sha, $size, $mb, $Head, $Other
}

# 把新行插到台账最后一行数据之后；返回 $null 表示成功，否则返回失败原因
function Set-LedgerRow([string]$Path, [string]$Row, [string]$Sha) {
    if (-not (Test-Path -LiteralPath $Path)) { return "台账不存在：$Path" }
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $lines = @($text -split "`n")
    $dataIdx = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\|\s*\d{4}-\d{2}-\d{2}\s*\|') { $dataIdx += $i }
    }
    if ($dataIdx.Count -eq 0) { return "$Path 里找不到以日期开头的台账数据行（表头被改了？）" }
    # 查重只看数据行：正文里引用过的 SHA（「重建得到 eb4e1ce…」那段）不是登记，
    # 按全文匹配会把该写的新行误判成「已存在」。
    $needle = $Sha.ToLowerInvariant()
    foreach ($i in $dataIdx) {
        if ($lines[$i].ToLowerInvariant().Contains($needle)) { return 'SKIP' }
    }
    $last = $dataIdx[$dataIdx.Count - 1]
    # 台账是 CRLF 检出（core.autocrlf=true）时，新行跟着用 CRLF，别把这一节写成混合换行
    $newRow = if ($lines[$last].EndsWith("`r")) { $Row + "`r" } else { $Row }
    $out = @()
    $out += $lines[0..$last]
    $out += $newRow
    if ($last + 1 -lt $lines.Count) { $out += $lines[($last + 1)..($lines.Count - 1)] }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, ($out -join "`n"), $utf8NoBom)
    return $null
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

# 台账的「产出时 HEAD / 工作区」在构建之前取：构建只动 app/build/（gitignore 之内），
# 但判定与复制之后不该再让别的操作污染这个读数。
$GitState = Get-GitState $ProjectDir
if (-not $GitState.Available) {
    Write-Host ("> 台账 git       = 不可用（{0}）：本轮只交付，不写台账行" -f $GitState.Reason)
}
elseif ($GitState.Dirty.Count -gt 0) {
    Write-Host ("> 台账 HEAD      = {0} / 工作区 = 不干净（{1} 处与构建有关）" -f $GitState.Head, $GitState.Dirty.Count)
    foreach ($d in $GitState.Dirty) { Write-Host (">   {0}" -f $d) }
}
else {
    Write-Host ("> 台账 HEAD      = {0} / 工作区 = 干净（另有 {1} 个与构建无关的未跟踪文件）" -f $GitState.Head, $GitState.Other)
}

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
        if ($cells.Count -lt $LedgerCells) { continue }
        $verdict = $cells[$ColVerdict]
        if ($verdict -match '不可交付') { continue }
        if ($verdict -notmatch '可交付') { continue }
        $shaMatch = [regex]::Match($cells[$ColSha], '([0-9a-fA-F]{64})')
        $sizeMatch = [regex]::Match($cells[$ColSize], '(\d[\d ]{4,})\s*B')
        if (-not $shaMatch.Success -or -not $sizeMatch.Success) { continue }
        $Baseline = New-Object psobject -Property @{
            Name  = $cells[$ColName].Trim().Trim('`')
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

# 台账写行：一行 = 一个包 = 一个提交。工作区不干净时这个包归不到任何提交上（v5.9.5 就是），
# 只打印原因不写行——宁可让人手工补，也不要表里留一条 SHA 和 HEAD 对不上的记录。
if (-not $GitState.Available) {
    Write-Host ("[台账未写入] {0}（交付已完成，这一行请人工补）" -f $GitState.Reason)
}
elseif ($GitState.Dirty.Count -gt 0) {
    Write-Host ("[台账未写入] 工作区不干净（{0} 处与构建有关的改动）：HEAD {1} 复现不出这个包，" -f $GitState.Dirty.Count, $GitState.Head)
    Write-Host (">            先提交源码、在干净的树上重打一次，同号不要用覆盖发布（把 versionName/Code 往前抬一位）")
}
else {
    $Row = Get-LedgerRow $ApkPath ([System.IO.Path]::GetFileName($Target)) $VersionCode $VersionName $Hash $Size $GitState.Head $GitState.Other
    $written = Set-LedgerRow $DocRelease $Row $Hash
    if ($null -eq $written) {
        Write-Host ("[台账已写入] {0}：HEAD {1} / {2} B（正式槽位安装、androidTest 复跑两列请人工补，补完跑 tools/check_doc_drift.py）" -f `
                  (Split-Path -Leaf $DocRelease), $GitState.Head, $Size)
    }
    elseif ($written -eq 'SKIP') {
        Write-Host ("[台账已存在] SHA {0}… 这一行已在 {1} 里，不重复追加" -f $Hash.Substring(0, 12), (Split-Path -Leaf $DocRelease))
    }
    else {
        Write-Host ("[台账未写入] {0}（交付已完成，这一行请人工补）" -f $written)
    }
}
exit 0
