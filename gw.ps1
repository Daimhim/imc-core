# gradlew 包装器：下载 Gradle 分发时优先使用腾讯镜像
#
# 用法：
#   .\gw.ps1 :imc-core:test
#   .\gw.ps1 build --info
#
# 工作原理：
#   1. 解析 gradle/wrapper/gradle-wrapper.properties 里的 distributionUrl
#   2. 按 Gradle wrapper 的算法计算本地缓存目录的 MD5/base36 哈希
#   3. 若缓存里没有 `<zipname>.ok` 标记，就从腾讯镜像下载并就地放置 + 解压 + 写标记
#   4. 转发参数给原 gradlew.bat，wrapper 看到缓存命中直接跳过下载
#
#   腾讯镜像不通时回退到默认 wrapper 行为，不会比直接跑 gradlew 更糟
#requires -Version 5.1

$ErrorActionPreference = 'Continue'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$wrapperProps = Join-Path $scriptDir 'gradle\wrapper\gradle-wrapper.properties'

function Get-WrapperHash([string]$Url) {
    # 复刻 org.gradle.wrapper.PathAssembler#getHash：
    #   new BigInteger(1, MD5(url)).toString(36)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $bytes = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Url))
    # Java BigInteger(1, bytes) = 无符号大端；.NET BigInteger 是有符号小端，
    # 所以反转字节序、末位补 0x00 保证正数
    $le = [byte[]]::new($bytes.Length + 1)
    for ($i = 0; $i -lt $bytes.Length; $i++) { $le[$i] = $bytes[$bytes.Length - 1 - $i] }
    $big = [System.Numerics.BigInteger]::new($le)
    $alpha = '0123456789abcdefghijklmnopqrstuvwxyz'.ToCharArray()
    $sb = [System.Text.StringBuilder]::new()
    while ($big -gt 0) {
        $rem = [int]([System.Numerics.BigInteger]::Remainder($big, 36))
        [void]$sb.Insert(0, $alpha[$rem])
        $big = [System.Numerics.BigInteger]::Divide($big, 36)
    }
    return $sb.ToString()
}

function Ensure-GradleDist {
    if (-not (Test-Path $wrapperProps)) { return }

    $line = Get-Content $wrapperProps | Where-Object { $_ -match '^\s*distributionUrl\s*=' } | Select-Object -First 1
    if (-not $line) { return }

    $origUrl = ($line -replace '^\s*distributionUrl\s*=\s*', '' -replace '\\:', ':').Trim()
    $zipName = Split-Path $origUrl -Leaf                    # gradle-7.6.6-bin.zip
    $distName = $zipName -replace '\.zip$', ''               # gradle-7.6.6-bin
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "$env:USERPROFILE\.gradle" }
    $hash = Get-WrapperHash $origUrl
    $cacheDir = Join-Path $gradleHome "wrapper\dists\$distName\$hash"
    $zipPath = Join-Path $cacheDir $zipName
    $okFile = "$zipPath.ok"

    if (Test-Path $okFile) {
        # 缓存已就绪，无需操作
        return
    }

    Write-Host "[gw] Gradle 未缓存，从腾讯镜像预下载 $zipName" -ForegroundColor Cyan
    $tencentUrl = "https://mirrors.cloud.tencent.com/gradle/$zipName"

    try {
        $null = New-Item -ItemType Directory -Force -Path $cacheDir
        Invoke-WebRequest -Uri $tencentUrl -OutFile $zipPath -UseBasicParsing -TimeoutSec 60
        Expand-Archive -Path $zipPath -DestinationPath $cacheDir -Force
        Set-Content -Path $okFile -Value '' -NoNewline
        Write-Host "[gw] 缓存就绪 -> $cacheDir" -ForegroundColor Green
    } catch {
        Write-Warning "[gw] 腾讯镜像下载失败 ($($_.Exception.Message))，回退默认 wrapper"
        Remove-Item $zipPath -ErrorAction SilentlyContinue
        Remove-Item $okFile -ErrorAction SilentlyContinue
    }
}

Ensure-GradleDist

# 转发给原 wrapper
& (Join-Path $scriptDir 'gradlew.bat') @args
exit $LASTEXITCODE
