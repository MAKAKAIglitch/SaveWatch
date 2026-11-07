[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ToolsRoot = Join-Path $ProjectRoot ".tools"
$JdkInstallRoot = Join-Path $ToolsRoot "jdk-21-temurin"
$DownloadRoot = Join-Path $ToolsRoot "downloads"

function Write-Info([string] $Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Ok([string] $Message) {
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

function Write-Warn([string] $Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Get-JdkInfo([string] $Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        return $null
    }

    $Candidate = [Environment]::ExpandEnvironmentVariables($Candidate.Trim().Trim('"'))
    if (Test-Path -LiteralPath $Candidate -PathType Leaf) {
        if ([IO.Path]::GetFileName($Candidate) -ieq "java.exe") {
            $Candidate = Split-Path (Split-Path $Candidate -Parent) -Parent
        } else {
            return $null
        }
    }

    $JavaExe = Join-Path $Candidate "bin\java.exe"
    $JavacExe = Join-Path $Candidate "bin\javac.exe"
    if (!(Test-Path -LiteralPath $JavaExe -PathType Leaf) -or
        !(Test-Path -LiteralPath $JavacExe -PathType Leaf)) {
        return $null
    }

    try {
        $VersionOutput = (& $JavaExe -version 2>&1 | Out-String)
        $CompilerOutput = (& $JavacExe -version 2>&1 | Out-String)
        $VersionMatch = [regex]::Match($VersionOutput, 'version\s+"(?<major>\d+)')
        $CompilerMatch = [regex]::Match($CompilerOutput, 'javac\s+(?<major>\d+)')
        if (!$VersionMatch.Success -or !$CompilerMatch.Success -or
            [int]$VersionMatch.Groups["major"].Value -ne 21 -or
            [int]$CompilerMatch.Groups["major"].Value -ne 21) {
            return $null
        }

        return [PSCustomObject]@{
            Home = (Resolve-Path -LiteralPath $Candidate).Path
            Java = $JavaExe
            Javac = $JavacExe
            Version = $VersionOutput.Trim()
        }
    } catch {
        return $null
    }
}

function Find-Jdk21 {
    $Candidates = New-Object 'System.Collections.Generic.List[string]'
    $Seen = @{}

    function Add-Candidate([string] $Value) {
        if (![string]::IsNullOrWhiteSpace($Value)) {
            $Normalized = [Environment]::ExpandEnvironmentVariables($Value.Trim().Trim('"'))
            if (!$Seen.ContainsKey($Normalized)) {
                $Seen[$Normalized] = $true
                $null = $Candidates.Add($Normalized)
            }
        }
    }

    Add-Candidate $JdkInstallRoot
    Add-Candidate $env:JAVA_HOME

    $PathJava = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($PathJava) {
        Add-Candidate $PathJava.Source
    }

    $SearchRoots = @(
        (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:ProgramFiles "Microsoft"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"),
        (Join-Path $env:LOCALAPPDATA "Programs\Microsoft"),
        (Join-Path $env:APPDATA "PrismLauncher\runtime"),
        (Join-Path $env:APPDATA "PrismLauncher\java"),
        (Join-Path $env:LOCALAPPDATA "Programs\PrismLauncher\runtime")
    )

    foreach ($Root in $SearchRoots) {
        if (Test-Path -LiteralPath $Root -PathType Container) {
            Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Add-Candidate $_.FullName }
            Add-Candidate $Root
        }
    }

    foreach ($Candidate in $Candidates) {
        $Jdk = Get-JdkInfo $Candidate
        if ($Jdk) {
            return $Jdk
        }
    }

    return $null
}

function Install-Temurin21 {
    New-Item -ItemType Directory -Force -Path $ToolsRoot, $DownloadRoot | Out-Null

    $Architecture = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "aarch64" } else { "x64" }
    $ApiUrl = "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$Architecture&image_type=jdk&os=windows&vendor=eclipse"

    Write-Info "No suitable JDK 21 was found."
    Write-Info "Fetching official Eclipse Temurin 21 package information ($Architecture)..."

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $Assets = Invoke-RestMethod -Uri $ApiUrl -Headers @{ "User-Agent" = "SaveWatch-Builder" }
    $Asset = $Assets | Select-Object -First 1
    if (!$Asset -or !$Asset.binary -or !$Asset.binary.package -or
        !$Asset.binary.package.link -or !$Asset.binary.package.checksum) {
        throw "Adoptium did not return a JDK archive and SHA-256 checksum."
    }

    $Package = $Asset.binary.package
    $ArchivePath = Join-Path $DownloadRoot "temurin-21.zip"
    $ExtractPath = Join-Path $DownloadRoot "temurin-21-extracted"

    if (Test-Path -LiteralPath $ExtractPath) {
        Remove-Item -LiteralPath $ExtractPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $ExtractPath | Out-Null

    Write-Info "Downloading Eclipse Temurin JDK 21..."
    $null = Invoke-WebRequest -Uri $Package.link -OutFile $ArchivePath -UseBasicParsing -Headers @{ "User-Agent" = "SaveWatch-Builder" }

    $ActualHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $ExpectedHash = ([string]$Package.checksum).ToLowerInvariant()
    if ($ActualHash -ne $ExpectedHash) {
        Remove-Item -LiteralPath $ArchivePath -Force -ErrorAction SilentlyContinue
        throw "JDK archive SHA-256 mismatch. The archive was rejected."
    }
    Write-Ok "JDK archive SHA-256 verified."

    Write-Info "Extracting JDK into the project-local .tools folder..."
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $ExtractPath -Force

    $JavaFile = Get-ChildItem -LiteralPath $ExtractPath -Filter "java.exe" -File -Recurse |
        Where-Object { $_.Directory.Name -ieq "bin" } |
        Select-Object -First 1

    if (!$JavaFile) {
        throw "The downloaded archive does not contain bin\java.exe."
    }

    $ExtractedJdkHome = Split-Path (Split-Path $JavaFile.FullName -Parent) -Parent
    $ExistingJdk = Get-JdkInfo $ExtractedJdkHome
    if (!$ExistingJdk) {
        throw "The downloaded package is not a valid JDK 21 installation (javac.exe or version check failed)."
    }

    if (Test-Path -LiteralPath $JdkInstallRoot) {
        $BackupPath = "$JdkInstallRoot.previous-$(Get-Date -Format 'yyyyMMddHHmmss')"
        Move-Item -LiteralPath $JdkInstallRoot -Destination $BackupPath | Out-Null
    }

    Move-Item -LiteralPath $ExtractedJdkHome -Destination $JdkInstallRoot | Out-Null
    Remove-Item -LiteralPath $ExtractPath -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $ArchivePath -Force -ErrorAction SilentlyContinue

    $Installed = Get-JdkInfo $JdkInstallRoot
    if (!$Installed) {
        throw "JDK 21 was extracted, but the final installation check failed."
    }

    return $Installed
}

try {
    Push-Location $ProjectRoot

    Write-Host ""
    Write-Host " SaveWatch automatic builder" -ForegroundColor White
    Write-Host " Minecraft 1.21.1 | NeoForge | Java 21" -ForegroundColor DarkGray
    Write-Host ""

    if (!(Test-Path -LiteralPath (Join-Path $ProjectRoot "gradlew.bat"))) {
        throw "gradlew.bat is missing. Download or clone the complete SaveWatch repository."
    }

    $Jdk = Find-Jdk21
    if (!$Jdk) {
        $Jdk = Install-Temurin21
    }

    $env:JAVA_HOME = $Jdk.Home
    $env:Path = (Join-Path $Jdk.Home "bin") + ";" + $env:Path

    Write-Ok "Using JDK 21: $($Jdk.Home)"
    Write-Host $Jdk.Version

    if (!$GradleArgs -or $GradleArgs.Count -eq 0) {
        $GradleArgs = @("build")
    }

    $Wrapper = Join-Path $ProjectRoot "gradlew.bat"
    Write-Info "Starting Gradle Wrapper. Gradle and Minecraft development dependencies will be downloaded when needed."
    Write-Host ""

    & $Wrapper @GradleArgs --no-daemon
    $BuildExitCode = $LASTEXITCODE

    if ($BuildExitCode -ne 0) {
        throw "Gradle returned exit code $BuildExitCode."
    }

    $JarDirectory = Join-Path $ProjectRoot "build\libs"
    $Jar = Get-ChildItem -LiteralPath $JarDirectory -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*-sources.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (!$Jar) {
        throw "Gradle finished, but no mod JAR was found in build\libs."
    }

    Write-Host ""
    Write-Host " ==========================================" -ForegroundColor Green
    Write-Host " BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host " ==========================================" -ForegroundColor Green
    Write-Ok "Mod: $($Jar.FullName)"
    Write-Ok ("Size: {0:N2} MiB" -f ($Jar.Length / 1MB))
    Write-Host ""
    Write-Host "Copy this JAR into your Minecraft instance's mods folder." -ForegroundColor White

    exit 0
}
catch {
    Write-Host ""
    Write-Host "[BUILD ERROR] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "No system-wide Java installation or environment variables were changed." -ForegroundColor DarkGray
    exit 1
}
finally {
    Pop-Location -ErrorAction SilentlyContinue
}
