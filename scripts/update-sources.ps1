#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Repository = "MAKAKAIglitch/SaveWatch"
$Branch = "main"
$ExpectedRemote = "https://github.com/$Repository.git"

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    & git -C $ProjectRoot @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed: git $($Arguments -join ' ') (exit code $LASTEXITCODE)."
    }
}

try {
    Write-Host ""
    Write-Host " SaveWatch source updater" -ForegroundColor White
    Write-Host " Repository: $Repository | Branch: $Branch" -ForegroundColor DarkGray
    Write-Host ""

    if (!(Get-Command git.exe -ErrorAction SilentlyContinue)) {
        throw "Git for Windows is not installed or git.exe is not available in PATH. Install Git, then run this updater again."
    }

    # A ZIP download has no .git metadata, so Git cannot fetch or fast-forward it.
    # Check this before invoking Git: Windows PowerShell 5.1 can turn native stderr
    # into a terminating error when ErrorActionPreference is Stop.
    $GitMetadata = Join-Path $ProjectRoot ".git"
    if (!(Test-Path -LiteralPath $GitMetadata)) {
        throw "This SaveWatch folder is not a Git clone (the .git directory/file is missing). The source updater uses Git and cannot update a ZIP-extracted copy. Clone the repository with: git clone --branch main https://github.com/$Repository.git SaveWatch. Then run update-sources.bat from the cloned SaveWatch folder. Your current files have not been changed."
    }

    $GitRootOutput = @(& git -C $ProjectRoot rev-parse --show-toplevel 2>&1)
    $GitRootExitCode = $LASTEXITCODE
    if ($GitRootExitCode -ne 0 -or !$GitRootOutput) {
        throw "Git metadata exists, but this folder is not a valid Git working tree. Check the .git entry and repository integrity. Git said: $($GitRootOutput -join ' ')"
    }
    $GitRoot = $GitRootOutput | Select-Object -Last 1

    $GitRoot = [IO.Path]::GetFullPath(($GitRoot | Select-Object -First 1).Trim())
    $ExpectedRoot = [IO.Path]::GetFullPath($ProjectRoot)
    if (!$GitRoot.Equals($ExpectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The updater must be run from the root of the SaveWatch clone. Detected Git root: $GitRoot"
    }

    $Remote = (& git -C $ProjectRoot remote get-url origin 2>$null)
    if ($LASTEXITCODE -ne 0 -or !$Remote) {
        throw "Git remote 'origin' is missing."
    }
    $Remote = ($Remote | Select-Object -First 1).Trim()
    $AllowedRemotes = @(
        $ExpectedRemote,
        "https://github.com/$Repository",
        "git@github.com:$Repository.git",
        "ssh://git@github.com/$Repository.git"
    )
    if ($AllowedRemotes -notcontains $Remote) {
        throw "Remote 'origin' does not point to the official repository $Repository. Current URL: $Remote"
    }

    $CurrentBranch = (& git -C $ProjectRoot branch --show-current)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not determine the current Git branch."
    }
    $CurrentBranch = ($CurrentBranch | Select-Object -First 1).Trim()
    if ($CurrentBranch -ne $Branch) {
        throw "Current branch is '$CurrentBranch', but this updater only updates '$Branch'. Switch to main and run it again."
    }

    $Changes = (& git -C $ProjectRoot status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the working tree."
    }
    if ($Changes) {
        Write-Host "Local changes were found:" -ForegroundColor Yellow
        $Changes | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
        throw "Update cancelled to protect local changes. Commit or back up your changes, then run the updater again."
    }

    Write-Host "[INFO] Checking GitHub for the latest main branch..." -ForegroundColor Cyan
    Invoke-Git -Arguments @("fetch", "--prune", "origin", $Branch)

    $Counts = (& git -C $ProjectRoot rev-list --left-right --count "HEAD...origin/$Branch")
    if ($LASTEXITCODE -ne 0 -or !$Counts) {
        throw "Could not compare the local branch with origin/$Branch."
    }
    $Parts = ($Counts | Select-Object -First 1).Trim() -split '\s+'
    if ($Parts.Count -ne 2) {
        throw "Unexpected response while comparing Git branches."
    }
    $Ahead = [int]$Parts[0]
    $Behind = [int]$Parts[1]

    if ($Ahead -gt 0) {
        throw "The local main branch contains $Ahead commit(s) that are not on GitHub. No files were changed; reconcile these commits before updating."
    }

    if ($Behind -eq 0) {
        Write-Host "[ OK ] Source files are already up to date." -ForegroundColor Green
        exit 0
    }

    Write-Host "[INFO] $Behind new commit(s) found. Updating source files with a fast-forward..." -ForegroundColor Cyan
    Invoke-Git -Arguments @("merge", "--ff-only", "origin/$Branch")

    $NewCommit = (& git -C $ProjectRoot rev-parse --short HEAD)
    if ($LASTEXITCODE -ne 0) {
        throw "Update completed, but the resulting commit could not be read."
    }

    Write-Host ""
    Write-Host " ==========================================" -ForegroundColor Green
    Write-Host " SOURCES UPDATED SUCCESSFULLY" -ForegroundColor Green
    Write-Host " ==========================================" -ForegroundColor Green
    Write-Host "Current commit: $NewCommit" -ForegroundColor Green
    Write-Host "The repository source files now match the latest main branch." -ForegroundColor White
    Write-Host "Run build.bat if you also want to compile the updated mod." -ForegroundColor White
    Write-Host ""
    exit 0
}
catch {
    Write-Host ""
    Write-Host "[UPDATE ERROR] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "No local changes were overwritten." -ForegroundColor DarkGray
    Write-Host ""
    exit 1
}
