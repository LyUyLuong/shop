[CmdletBinding()]
param(
    [ValidateSet(10000, 100000, 1000000)]
    [int]$Rows = 10000,

    [switch]$Reset,

    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$ProjectName = "shop-product-search-benchmark"

$RepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..\..")
)

$ComposeFile = Join-Path `
    $RepoRoot `
    "docker-compose.search-benchmark.yml"

$SeedFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-products.sql"

$VerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-dataset.sql"

$EvidenceDirectory = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\a\raw\dataset"

$script:EvidenceFile = $null

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $previousErrorActionPreference =
            $ErrorActionPreference

    try {
        # Windows PowerShell 5.1 represents native stderr as
        # ErrorRecord objects. Docker writes normal progress there.
        $ErrorActionPreference = "Continue"

        [object[]]$rawOutput = @(
            & $FilePath @CommandArguments 2>&1
        )
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference =
                $previousErrorActionPreference
    }

    [string[]]$output = @(
        foreach ($line in $rawOutput) {
            $line.ToString()
        }
    )

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output   = $output
    }
}

function Invoke-GitCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $result = Invoke-NativeCapture `
        -FilePath "git" `
        -CommandArguments $CommandArguments

    if ($result.ExitCode -ne 0) {
        throw (
            "Git command failed with exit code " +
            "$($result.ExitCode): " +
            ($result.Output -join [Environment]::NewLine)
        )
    }

    return $result.Output
}

function Write-Evidence {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    Add-Content `
        -LiteralPath $script:EvidenceFile `
        -Value $Value `
        -Encoding UTF8
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $displayCommand =
            "$FilePath $($CommandArguments -join ' ')"

    Write-Host ">> $displayCommand"
    Write-Evidence ">> $displayCommand"

    $result = Invoke-NativeCapture `
        -FilePath $FilePath `
        -CommandArguments $CommandArguments

    foreach ($line in @($result.Output)) {
        Write-Host $line
        Write-Evidence $line
    }

    if ($result.ExitCode -ne 0) {
        throw (
            "Command failed with exit code " +
            "$($result.ExitCode): $displayCommand"
        )
    }
}

foreach ($requiredCommand in @("git", "docker")) {
    if ($null -eq (
        Get-Command `
            $requiredCommand `
            -CommandType Application `
            -ErrorAction SilentlyContinue
    )) {
        throw "Required command was not found: $requiredCommand"
    }
}

if (-not $Reset) {
    throw (
        "Dataset preparation is destructive. " +
        "Pass -Reset to recreate only the isolated benchmark database."
    )
}

foreach ($requiredFile in @(
    $ComposeFile,
    $SeedFile,
    $VerifyFile
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file does not exist: $requiredFile"
    }
}

Push-Location $RepoRoot

try {
    $branch = (
        @(
            Invoke-GitCommand -CommandArguments @(
                "branch",
                "--show-current"
            )
        ) -join ""
    ).Trim()

    $head = (
        @(
            Invoke-GitCommand -CommandArguments @(
                "rev-parse",
                "HEAD"
            )
        ) -join ""
    ).Trim()

    [string[]]$workingTree = @(
        Invoke-GitCommand -CommandArguments @(
            "status",
            "--porcelain=v1"
        )
    )

    if ($workingTree.Count -gt 0 -and -not $AllowDirty) {
        throw (
            "Working tree is not clean. " +
            "Commit the harness first, or use -AllowDirty " +
            "only for the pre-commit smoke run."
        )
    }

    New-Item `
        -ItemType Directory `
        -Path $EvidenceDirectory `
        -Force |
        Out-Null

    $timestamp = [DateTime]::UtcNow.ToString(
        "yyyyMMddTHHmmssZ"
    )

    $script:EvidenceFile = Join-Path `
        $EvidenceDirectory `
        "dataset-$Rows-$timestamp.txt"

    $workingTreeState = if (
        $workingTree.Count -eq 0
    ) {
        "clean"
    }
    else {
        "dirty-allowed"
    }

    @(
        "work_package=V2-PS-A2"
        "generated_at_utc=$timestamp"
        "branch=$branch"
        "head=$head"
        "rows=$Rows"
        "seed=$Seed"
        "working_tree=$workingTreeState"
    ) | Set-Content `
        -LiteralPath $script:EvidenceFile `
        -Encoding UTF8

    if ($workingTree.Count -gt 0) {
        Write-Evidence "working_tree_entries:"

        foreach ($entry in $workingTree) {
            Write-Evidence $entry
        }
    }

    $composePrefix = @(
        "compose",
        "--project-name",
        $ProjectName,
        "-f",
        $ComposeFile
    )

    $totalWatch =
            [System.Diagnostics.Stopwatch]::StartNew()

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $composePrefix + @(
                "down",
                "--volumes",
                "--remove-orphans"
            )
        )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $composePrefix + @(
                "up",
                "-d",
                "--wait",
                "postgres"
            )
        )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $composePrefix + @(
                "run",
                "--rm",
                "flyway"
            )
        )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $composePrefix + @("images")
        )

    $psqlPrefix = $composePrefix + @(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-X",
        "-P",
        "pager=off",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "shop_benchmark",
        "-d",
        "shop_search_benchmark"
    )

    $environmentSql = @"
SELECT
    current_database() AS database_name,
    current_user AS database_user,
    version() AS postgres_version,
    current_setting('server_encoding') AS server_encoding,
    database_catalog.datcollate AS lc_collate,
    database_catalog.datctype AS lc_ctype
FROM pg_database AS database_catalog
WHERE database_catalog.datname = current_database();
"@

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $environmentSql
            )
        )

    $sessionSettings = (
        "SET shop_benchmark.row_count = '$Rows'; " +
        "SET shop_benchmark.seed = '$Seed';"
    )

    $seedWatch =
            [System.Diagnostics.Stopwatch]::StartNew()

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $sessionSettings,
                "-f",
                "/benchmark/seed-products.sql"
            )
        )

    $seedWatch.Stop()

    Write-Evidence (
        "client_seed_command_elapsed_ms=" +
        $seedWatch.ElapsedMilliseconds
    )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                "ANALYZE products;"
            )
        )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $sessionSettings,
                "-f",
                "/benchmark/verify-dataset.sql"
            )
        )

    Invoke-CheckedCommand `
        -FilePath "docker" `
        -CommandArguments (
            $composePrefix + @("ps")
        )

    $totalWatch.Stop()

    Write-Evidence (
        "total_preparation_elapsed_ms=" +
        $totalWatch.ElapsedMilliseconds
    )
    Write-Evidence "result=success"

    Write-Host ""
    Write-Host "Dataset preparation succeeded."
    Write-Host "Rows: $Rows"
    Write-Host "Seed: $Seed"
    Write-Host "Evidence: $script:EvidenceFile"
    Write-Host (
        "The benchmark database remains running " +
        "for the next benchmark step."
    )
}
catch {
    if ($null -ne $script:EvidenceFile) {
        Write-Evidence "result=failed"
        Write-Evidence "failure=$($_.Exception.Message)"
    }

    throw
}
finally {
    Pop-Location
}
