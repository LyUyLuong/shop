[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(10000, 100000)]
    [int]$Rows,

    [switch]$Reset,

    [switch]$AllowDirty,

    [switch]$Smoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectName = "shop-product-search-benchmark"
$PageSize = 100
$ClientCounts = @(1, 8)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$InvariantCulture =
        [System.Globalization.CultureInfo]::InvariantCulture

if ($Smoke) {
    $WarmupExecutions = 8
    $MeasuredExecutions = 16
    $RoundCount = 1
    $Mode = "smoke"
}
else {
    $WarmupExecutions = 24
    $MeasuredExecutions = 104
    $RoundCount = 3
    $Mode = "full"
}

if (-not $Reset) {
    throw (
        "Index comparison recreates the isolated benchmark dataset. " +
        "Pass -Reset to confirm this destructive benchmark-only action."
    )
}

$RepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..\..")
)
$ComposeFile = Join-Path `
    $RepoRoot `
    "docker-compose.search-benchmark.yml"
$PrepareDatasetScript = Join-Path `
    $PSScriptRoot `
    "prepare-dataset.ps1"
$WorkloadFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\benchmark-ranked-search.sql"
$PlanFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-ranked-search-candidates.sql"
$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\b\b3\raw\index-selection"

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        [object[]]$rawOutput = @(
            & $FilePath @CommandArguments 2>&1
        )
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    [string[]]$output = @(
        foreach ($line in $rawOutput) {
            $line.ToString()
        }
    )

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $result = Invoke-NativeCapture `
        -FilePath $FilePath `
        -CommandArguments $CommandArguments

    if ($result.ExitCode -ne 0) {
        throw (
            "$FilePath command failed: " +
            ($result.Output -join [Environment]::NewLine)
        )
    }

    return $result
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    [System.IO.File]::WriteAllText(
        $Path,
        $Value,
        $Utf8NoBom
    )
}

function Add-Utf8Line {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    [System.IO.File]::AppendAllText(
        $Path,
        $Value + [Environment]::NewLine,
        $Utf8NoBom
    )
}

function Format-Decimal {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Value
    )

    return $Value.ToString("F3", $InvariantCulture)
}

function Get-Median {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$Values
    )

    [double[]]$sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) {
        throw "Median input must not be empty."
    }

    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) {
        return $sorted[$middle]
    }

    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

foreach ($commandName in @("git", "docker")) {
    if ($null -eq (
        Get-Command `
            $commandName `
            -CommandType Application `
            -ErrorAction SilentlyContinue
    )) {
        throw "Required command was not found: $commandName"
    }
}

foreach ($requiredFile in @(
    $ComposeFile,
    $PrepareDatasetScript,
    $WorkloadFile,
    $PlanFile
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file was not found: $requiredFile"
    }
}

$branch = (
    (Invoke-CheckedNative `
        -FilePath "git" `
        -CommandArguments @(
            "-C", $RepoRoot,
            "branch", "--show-current"
        )).Output -join ""
).Trim()

if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "Benchmark must run from a branch, not detached HEAD."
}

$head = (
    (Invoke-CheckedNative `
        -FilePath "git" `
        -CommandArguments @(
            "-C", $RepoRoot,
            "rev-parse", "HEAD"
        )).Output -join ""
).Trim()

[string[]]$workingTree = @(
    (Invoke-CheckedNative `
        -FilePath "git" `
        -CommandArguments @(
            "-C", $RepoRoot,
            "status", "--porcelain=v1"
        )).Output
)

$isDirty = $workingTree.Count -gt 0
if ($isDirty -and (-not $AllowDirty)) {
    throw (
        "Working tree is not clean. Commit the benchmark harness first, " +
        "or use -AllowDirty only for its pre-commit smoke run."
    )
}

$workingTreeState = if ($isDirty) {
    "dirty-allowed"
}
else {
    "clean"
}

foreach ($clients in $ClientCounts) {
    if (
        $WarmupExecutions % $clients -ne 0 -or
        $MeasuredExecutions % $clients -ne 0
    ) {
        throw "Execution counts must be divisible by client count $clients."
    }
}

$ComposePrefix = @(
    "compose",
    "--project-name", $ProjectName,
    "--file", $ComposeFile
)
$PsqlPrefix = $ComposePrefix + @(
    "exec", "-T",
    "postgres",
    "psql",
    "-X", "-q", "-A", "-t",
    "-P", "pager=off",
    "-v", "ON_ERROR_STOP=1",
    "-U", "shop_benchmark",
    "-d", "shop_search_benchmark"
)

$candidates = @(
    [pscustomobject]@{
        Name = "baseline"
        IndexName = $null
        CreateSql = $null
    },
    [pscustomobject]@{
        Name = "name-pattern-global"
        IndexName = "idx_products_name_lower_pattern_b3"
        CreateSql = @"
CREATE INDEX idx_products_name_lower_pattern_b3
ON products (lower(name) text_pattern_ops)
"@
    },
    [pscustomobject]@{
        Name = "name-pattern-active-partial"
        IndexName = "idx_products_active_name_lower_pattern_b3"
        CreateSql = @"
CREATE INDEX idx_products_active_name_lower_pattern_b3
ON products (lower(name) text_pattern_ops)
WHERE status = 'ACTIVE'
"@
    },
    [pscustomobject]@{
        Name = "status-name-pattern"
        IndexName = "idx_products_status_name_lower_pattern_b3"
        CreateSql = @"
CREATE INDEX idx_products_status_name_lower_pattern_b3
ON products (status, lower(name) text_pattern_ops)
"@
    }
)

$workloads = @(
    [pscustomobject]@{ Name = "exact-data"; Kind = "data"; Term = "delta-20260806-00000007-green" },
    [pscustomobject]@{ Name = "exact-count"; Kind = "count"; Term = "delta-20260806-00000007-green" },
    [pscustomobject]@{ Name = "rare-data"; Kind = "data"; Term = "rare orchid" },
    [pscustomobject]@{ Name = "rare-count"; Kind = "count"; Term = "rare orchid" },
    [pscustomobject]@{ Name = "medium-data"; Kind = "data"; Term = "medium cedar" },
    [pscustomobject]@{ Name = "medium-count"; Kind = "count"; Term = "medium cedar" },
    [pscustomobject]@{ Name = "common-data"; Kind = "data"; Term = "common market" },
    [pscustomobject]@{ Name = "common-count"; Kind = "count"; Term = "common market" },
    [pscustomobject]@{ Name = "insert-active"; Kind = "insert"; Term = "unused" },
    [pscustomobject]@{ Name = "update-active-name"; Kind = "update"; Term = "unused" }
)

function Invoke-Psql {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    return Invoke-CheckedNative `
        -FilePath "docker" `
        -CommandArguments ($PsqlPrefix + $Arguments)
}

function Remove-CandidateIndexes {
    $dropSql = @"
DROP INDEX IF EXISTS idx_products_name_lower_pattern_b3;
DROP INDEX IF EXISTS idx_products_active_name_lower_pattern_b3;
DROP INDEX IF EXISTS idx_products_status_name_lower_pattern_b3;
"@

    return Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments ($PsqlPrefix + @("-c", $dropSql))
}

function Invoke-DatasetReset {
    $parameters = @{
        Rows = $Rows
        Reset = $true
        AllowDirty = $AllowDirty.IsPresent
    }

    & $PrepareDatasetScript @parameters
}

function New-PgbenchArguments {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Workload,

        [Parameter(Mandatory = $true)]
        [int]$Clients,

        [Parameter(Mandatory = $true)]
        [int]$TransactionsPerClient
    )

    $isCount = [int]($Workload.Kind -eq "count")
    $isInsert = [int]($Workload.Kind -eq "insert")
    $isUpdate = [int]($Workload.Kind -eq "update")
    $keywordPattern = "%$($Workload.Term)%"
    $namePrefixPattern = "$($Workload.Term)%"

    return $ComposePrefix + @(
        "exec", "-T",
        "-e", "PGOPTIONS=-c statement_timeout=30000",
        "-e", "PGAPPNAME=shop-v2-ps-b3",
        "postgres",
        "pgbench",
        "-n",
        "-M", "prepared",
        "-c", "$Clients",
        "-j", "$Clients",
        "-t", "$TransactionsPerClient",
        "-r",
        "--failures-detailed",
        "--verbose-errors",
        "-D", "is_count=$isCount",
        "-D", "is_insert=$isInsert",
        "-D", "is_update=$isUpdate",
        "-D", "keyword_pattern=$keywordPattern",
        "-D", "exact_keyword=$($Workload.Term)",
        "-D", "name_prefix_pattern=$namePrefixPattern",
        "-D", "status=ACTIVE",
        "-D", "page_size=$PageSize",
        "-U", "shop_benchmark",
        "-f", "/benchmark/benchmark-ranked-search.sql",
        "shop_search_benchmark"
    )
}

function Read-PgbenchMetrics {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Output,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedTransactions
    )

    $text = $Output -join [Environment]::NewLine
    $processedMatch = [regex]::Match(
        $text,
        "number of transactions actually processed:\s*(\d+)\/(\d+)"
    )
    $latencyMatch = [regex]::Match(
        $text,
        "latency average =\s*([0-9.]+)\s*ms"
    )
    $tpsMatch = [regex]::Match(
        $text,
        "tps =\s*([0-9.]+)\s*\(without initial connection time\)"
    )

    if (
        -not $processedMatch.Success -or
        -not $latencyMatch.Success -or
        -not $tpsMatch.Success
    ) {
        throw "Could not parse pgbench metrics."
    }

    $processed = [int]$processedMatch.Groups[1].Value
    $requested = [int]$processedMatch.Groups[2].Value
    if (
        $processed -ne $ExpectedTransactions -or
        $requested -ne $ExpectedTransactions
    ) {
        throw (
            "Expected $ExpectedTransactions successful transactions, " +
            "found $processed/$requested."
        )
    }

    return [pscustomobject]@{
        LatencyMs = [double]::Parse(
            $latencyMatch.Groups[1].Value,
            $InvariantCulture
        )
        Tps = [double]::Parse(
            $tpsMatch.Groups[1].Value,
            $InvariantCulture
        )
    }
}

New-Item `
    -ItemType Directory `
    -Path $EvidenceRoot `
    -Force |
    Out-Null

$timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$evidenceDirectory = Join-Path `
    $EvidenceRoot `
    "ranked-index-$Rows-$Mode-$timestamp"
New-Item `
    -ItemType Directory `
    -Path $evidenceDirectory `
    -Force |
    Out-Null

$manifestFile = Join-Path $evidenceDirectory "manifest.txt"
$candidateFile = Join-Path $evidenceDirectory "candidates.csv"
$roundsFile = Join-Path $evidenceDirectory "rounds.csv"
$summaryFile = Join-Path $evidenceDirectory "summary.csv"

Write-Utf8File `
    -Path $manifestFile `
    -Value ((@(
        "work_package=V2-PS-B3"
        "generated_at_utc=$timestamp"
        "branch=$branch"
        "head=$head"
        "rows=$Rows"
        "mode=$Mode"
        "working_tree=$workingTreeState"
        "warmup_executions_per_case=$WarmupExecutions"
        "measured_executions_per_round=$MeasuredExecutions"
        "rounds=$RoundCount"
        "clients=$($ClientCounts -join ',')"
        "result=running"
    ) -join [Environment]::NewLine) + [Environment]::NewLine)

Write-Utf8File `
    -Path $candidateFile `
    -Value (
        "candidate|index_name|build_ms|candidate_bytes|total_index_bytes" +
        [Environment]::NewLine
    )
Write-Utf8File `
    -Path $roundsFile `
    -Value (
        "candidate|workload|clients|round|latency_ms|tps|raw_file" +
        [Environment]::NewLine
    )

$roundResults =
        New-Object "System.Collections.Generic.List[object]"

try {
    foreach ($candidate in $candidates) {
        Write-Host "Reset dataset: $($candidate.Name), rows=$Rows"
        Invoke-DatasetReset

        $buildMilliseconds = 0.0
        $candidateBytes = 0L

        if ($null -ne $candidate.CreateSql) {
            Write-Host "Build candidate: $($candidate.Name)"
            $buildSql = @"
CREATE TEMP TABLE psb3_index_build_clock (
    started_at timestamptz NOT NULL
);
INSERT INTO psb3_index_build_clock
VALUES (clock_timestamp());
$($candidate.CreateSql);
SELECT round(
    EXTRACT(
        EPOCH FROM (
            clock_timestamp() -
            (SELECT started_at FROM psb3_index_build_clock)
        )
    ) * 1000,
    3
);
"@

            $buildMilliseconds = [double]::Parse(
                ((
                    Invoke-Psql -Arguments @("-c", $buildSql)
                ).Output -join "").Trim(),
                $InvariantCulture
            )

            $candidateBytes = [long]((
                Invoke-Psql -Arguments @(
                    "-c",
                    "SELECT pg_relation_size('public.$($candidate.IndexName)');"
                )
            ).Output -join "").Trim()
        }

        [void](Invoke-Psql -Arguments @(
            "-c", "ANALYZE products;"
        ))

        $totalIndexBytes = [long]((
            Invoke-Psql -Arguments @(
                "-c", "SELECT pg_indexes_size('public.products');"
            )
        ).Output -join "").Trim()

        Add-Utf8Line `
            -Path $candidateFile `
            -Value (
                "$($candidate.Name)|$($candidate.IndexName)|" +
                "$(Format-Decimal $buildMilliseconds)|" +
                "$candidateBytes|$totalIndexBytes"
            )

        Write-Host "Capture plans: $($candidate.Name)"
        $planResult = Invoke-Psql -Arguments @(
            "-v", "expected_rows=$Rows",
            "-f", "/benchmark/explain-ranked-search-candidates.sql"
        )
        Write-Utf8File `
            -Path (Join-Path $evidenceDirectory "plans-$($candidate.Name).txt") `
            -Value ($planResult.Output -join [Environment]::NewLine)

        foreach ($workload in $workloads) {
            foreach ($clients in $ClientCounts) {
                $warmupPerClient = $WarmupExecutions / $clients
                $measuredPerClient = $MeasuredExecutions / $clients

                for (
                    $round = 1;
                    $round -le $RoundCount;
                    $round++
                ) {
                    Write-Host (
                        "$($candidate.Name): $($workload.Name), " +
                        "clients=$clients, round=$round"
                    )

                    $warmupResult = Invoke-NativeCapture `
                        -FilePath "docker" `
                        -CommandArguments (
                            New-PgbenchArguments `
                                -Workload $workload `
                                -Clients $clients `
                                -TransactionsPerClient $warmupPerClient
                        )

                    if ($warmupResult.ExitCode -ne 0) {
                        throw (
                            "Warm-up failed for $($candidate.Name)/" +
                            "$($workload.Name): " +
                            ($warmupResult.Output -join
                                [Environment]::NewLine)
                        )
                    }

                    $measuredResult = Invoke-NativeCapture `
                        -FilePath "docker" `
                        -CommandArguments (
                            New-PgbenchArguments `
                                -Workload $workload `
                                -Clients $clients `
                                -TransactionsPerClient $measuredPerClient
                        )

                    $rawName = (
                        "$($candidate.Name)-$($workload.Name)-" +
                        "c$clients-r$round.txt"
                    )
                    Write-Utf8File `
                        -Path (Join-Path $evidenceDirectory $rawName) `
                        -Value (
                            $measuredResult.Output -join
                            [Environment]::NewLine
                        )

                    if ($measuredResult.ExitCode -ne 0) {
                        throw (
                            "Measured run failed for $rawName`: " +
                            ($measuredResult.Output -join
                                [Environment]::NewLine)
                        )
                    }

                    $metrics = Read-PgbenchMetrics `
                        -Output $measuredResult.Output `
                        -ExpectedTransactions $MeasuredExecutions

                    $record = [pscustomobject]@{
                        Key = (
                            "$($candidate.Name)|$($workload.Name)|$clients"
                        )
                        Candidate = $candidate.Name
                        Workload = $workload.Name
                        Clients = $clients
                        Round = $round
                        LatencyMs = $metrics.LatencyMs
                        Tps = $metrics.Tps
                    }
                    $roundResults.Add($record)

                    Add-Utf8Line `
                        -Path $roundsFile `
                        -Value (
                            "$($record.Candidate)|$($record.Workload)|" +
                            "$($record.Clients)|$($record.Round)|" +
                            "$(Format-Decimal $record.LatencyMs)|" +
                            "$(Format-Decimal $record.Tps)|$rawName"
                        )
                }
            }
        }

        $cleanupResult = Remove-CandidateIndexes
        if ($cleanupResult.ExitCode -ne 0) {
            throw (
                "Candidate cleanup failed: " +
                ($cleanupResult.Output -join [Environment]::NewLine)
            )
        }
    }

    Write-Utf8File `
        -Path $summaryFile `
        -Value (
            "candidate|workload|clients|rounds|" +
            "median_latency_ms|median_tps" +
            [Environment]::NewLine
        )

    foreach ($group in ($roundResults | Group-Object Key)) {
        $first = $group.Group[0]
        $medianLatency = Get-Median `
            -Values ([double[]]$group.Group.LatencyMs)
        $medianTps = Get-Median `
            -Values ([double[]]$group.Group.Tps)

        Add-Utf8Line `
            -Path $summaryFile `
            -Value (
                "$($first.Candidate)|$($first.Workload)|" +
                "$($first.Clients)|$($group.Count)|" +
                "$(Format-Decimal $medianLatency)|" +
                "$(Format-Decimal $medianTps)"
            )
    }

    $cleanupGuard = [int]((
        Invoke-Psql -Arguments @(
            "-c",
            @"
SELECT count(*)
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
      'idx_products_name_lower_pattern_b3',
      'idx_products_active_name_lower_pattern_b3',
      'idx_products_status_name_lower_pattern_b3'
  );
"@
        )
    ).Output -join "").Trim()

    if ($cleanupGuard -ne 0) {
        throw "A benchmark-only candidate index remains in PostgreSQL."
    }

    Add-Utf8Line -Path $manifestFile -Value "result=success"
    Write-Host ""
    Write-Host "Ranked-search index comparison succeeded."
    Write-Host "Rows: $Rows"
    Write-Host "Candidates: $($candidates.Count)"
    Write-Host "Evidence: $evidenceDirectory"
    Write-Host "No benchmark-only candidate index remains."
}
catch {
    Add-Utf8Line `
        -Path $manifestFile `
        -Value "result=failed"
    Add-Utf8Line `
        -Path $manifestFile `
        -Value (
            "failure=" +
            $_.Exception.Message.Replace("`r", " ").Replace("`n", " ")
        )
    throw
}
finally {
    [void](Remove-CandidateIndexes)
}
