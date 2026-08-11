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
$CommonTerm = "common market"
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
        "Pagination comparison recreates the isolated benchmark dataset. " +
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
    "src\test\resources\benchmark\product-search\benchmark-pagination-strategies.sql"
$PlanFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-pagination-strategies.sql"
$VerificationFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-pagination-strategies.sql"
$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\b\b5\raw\pagination-comparison"

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

function Get-DeepOffset {
    param(
        [Parameter(Mandatory = $true)]
        [long]$QualifyingRows
    )

    if ($QualifyingRows -lt ($PageSize * 2)) {
        throw (
            "At least $($PageSize * 2) qualifying rows are required, " +
            "found $QualifyingRows."
        )
    }

    [long]$candidate = [long][Math]::Floor(
        ($QualifyingRows * 0.90) / $PageSize
    ) * $PageSize
    [long]$maximum = [long][Math]::Floor(
        ($QualifyingRows - $PageSize) / $PageSize
    ) * $PageSize

    return [Math]::Min($candidate, $maximum)
}

function Get-MatchingOutputLine {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Output,

        [Parameter(Mandatory = $true)]
        [string]$Pattern,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    [string[]]$matches = @(
        $Output |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match $Pattern }
    )

    if ($matches.Count -ne 1) {
        throw (
            "Expected one $Description line, found $($matches.Count): " +
            ($Output -join [Environment]::NewLine)
        )
    }

    return $matches[0]
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
    $PlanFile,
    $VerificationFile
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
    "-F", "|",
    "-P", "pager=off",
    "-v", "ON_ERROR_STOP=1",
    "-U", "shop_benchmark",
    "-d", "shop_search_benchmark"
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

function Invoke-DatasetReset {
    $parameters = @{
        Rows = $Rows
        Reset = $true
        AllowDirty = $AllowDirty.IsPresent
    }

    & $PrepareDatasetScript @parameters
}

Write-Host "Reset dataset: rows=$Rows"
Invoke-DatasetReset

[void](Invoke-Psql -Arguments @("-c", "ANALYZE products;"))

$browseCountLine = Get-MatchingOutputLine `
    -Output (Invoke-Psql -Arguments @(
        "-c",
        "SELECT count(*) FROM products WHERE status = 'ACTIVE';"
    )).Output `
    -Pattern "^\d+$" `
    -Description "browse count"
$browseCount = [long]$browseCountLine

$commonCountSql = @"
SELECT count(*)
FROM products AS p
WHERE (
    lower(p.sku) LIKE '%common market%' ESCAPE ''
    OR lower(p.name) LIKE '%common market%' ESCAPE ''
)
AND p.status = 'ACTIVE';
"@
$commonCountLine = Get-MatchingOutputLine `
    -Output (Invoke-Psql -Arguments @(
        "-c", $commonCountSql
    )).Output `
    -Pattern "^\d+$" `
    -Description "common-keyword count"
$commonCount = [long]$commonCountLine

$expectedBrowseCount = [long]($Rows * 4 / 5)
$expectedCommonCount = [long](($Rows * 3 / 4) - 1)
if (
    $browseCount -ne $expectedBrowseCount -or
    $commonCount -ne $expectedCommonCount
) {
    throw (
        "Dataset cardinality mismatch. " +
        "Browse=$browseCount/$expectedBrowseCount, " +
        "common=$commonCount/$expectedCommonCount."
    )
}

$browseDeepOffset = Get-DeepOffset -QualifyingRows $browseCount
$commonDeepOffset = Get-DeepOffset -QualifyingRows $commonCount
$browseAnchorOffset = $browseDeepOffset - 1
$commonAnchorOffset = $commonDeepOffset - 1

$browseAnchorSql = @"
SELECT
    extract(epoch FROM p.created_at)::bigint,
    p.id
FROM products AS p
WHERE p.status = 'ACTIVE'
ORDER BY p.created_at DESC, p.id DESC
OFFSET $browseAnchorOffset ROWS
FETCH FIRST 1 ROW ONLY;
"@
$browseAnchorLine = Get-MatchingOutputLine `
    -Output (Invoke-Psql -Arguments @(
        "-c", $browseAnchorSql
    )).Output `
    -Pattern "^\d+\|[0-9a-fA-F-]{36}$" `
    -Description "browse anchor"
$browseAnchorParts = $browseAnchorLine.Split("|")
$browseAnchorEpoch = [long]$browseAnchorParts[0]
$browseAnchorId = [Guid]$browseAnchorParts[1]

$commonAnchorSql = @"
SELECT
    ranked.match_priority,
    extract(epoch FROM ranked.created_at)::bigint,
    ranked.id
FROM (
    SELECT
        p.id,
        p.created_at,
        CASE
            WHEN lower(p.sku) = 'common market' THEN 0
            WHEN lower(p.name) LIKE 'common market%' ESCAPE E'\\' THEN 1
            ELSE 2
        END AS match_priority
    FROM products AS p
    WHERE (
        lower(p.sku) LIKE '%common market%' ESCAPE ''
        OR lower(p.name) LIKE '%common market%' ESCAPE ''
    )
    AND p.status = 'ACTIVE'
) AS ranked
ORDER BY
    ranked.match_priority ASC,
    ranked.created_at DESC,
    ranked.id DESC
OFFSET $commonAnchorOffset ROWS
FETCH FIRST 1 ROW ONLY;
"@
$commonAnchorLine = Get-MatchingOutputLine `
    -Output (Invoke-Psql -Arguments @(
        "-c", $commonAnchorSql
    )).Output `
    -Pattern "^[0-2]\|\d+\|[0-9a-fA-F-]{36}$" `
    -Description "common-keyword anchor"
$commonAnchorParts = $commonAnchorLine.Split("|")
$commonAnchorPriority = [int]$commonAnchorParts[0]
$commonAnchorEpoch = [long]$commonAnchorParts[1]
$commonAnchorId = [Guid]$commonAnchorParts[2]

New-Item `
    -ItemType Directory `
    -Path $EvidenceRoot `
    -Force |
    Out-Null

$timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$evidenceDirectory = Join-Path `
    $EvidenceRoot `
    "pagination-$Rows-$Mode-$timestamp"
New-Item `
    -ItemType Directory `
    -Path $evidenceDirectory `
    -Force |
    Out-Null

$manifestFile = Join-Path $evidenceDirectory "manifest.txt"
$anchorFile = Join-Path $evidenceDirectory "anchors.csv"
$verificationOutputFile = Join-Path `
    $evidenceDirectory `
    "verification.txt"
$planOutputFile = Join-Path $evidenceDirectory "plans.txt"
$roundsFile = Join-Path $evidenceDirectory "rounds.csv"
$summaryFile = Join-Path $evidenceDirectory "summary.csv"
$comparisonFile = Join-Path $evidenceDirectory "comparison.csv"

$workloadHash = (
    Get-FileHash -LiteralPath $WorkloadFile -Algorithm SHA256
).Hash.ToLowerInvariant()
$planHash = (
    Get-FileHash -LiteralPath $PlanFile -Algorithm SHA256
).Hash.ToLowerInvariant()
$verificationHash = (
    Get-FileHash -LiteralPath $VerificationFile -Algorithm SHA256
).Hash.ToLowerInvariant()

Write-Utf8File `
    -Path $manifestFile `
    -Value ((@(
        "work_package=V2-PS-B5"
        "evidence_task=pagination-strategy-comparison"
        "generated_at_utc=$timestamp"
        "branch=$branch"
        "head=$head"
        "rows=$Rows"
        "mode=$Mode"
        "working_tree=$workingTreeState"
        "page_size=$PageSize"
        "browse_qualifying_rows=$browseCount"
        "browse_deep_offset=$browseDeepOffset"
        "common_qualifying_rows=$commonCount"
        "common_deep_offset=$commonDeepOffset"
        "warmup_executions_per_case=$WarmupExecutions"
        "measured_executions_per_round=$MeasuredExecutions"
        "rounds=$RoundCount"
        "clients=$($ClientCounts -join ',')"
        "workload_sha256=$workloadHash"
        "plan_sha256=$planHash"
        "verification_sha256=$verificationHash"
        "result=running"
    ) -join [Environment]::NewLine) + [Environment]::NewLine)

Write-Utf8File `
    -Path $anchorFile `
    -Value ((@(
        "query|qualifying_rows|deep_offset|anchor_priority|anchor_epoch|anchor_id"
        "browse|$browseCount|$browseDeepOffset||$browseAnchorEpoch|$browseAnchorId"
        "common|$commonCount|$commonDeepOffset|$commonAnchorPriority|$commonAnchorEpoch|$commonAnchorId"
    ) -join [Environment]::NewLine) + [Environment]::NewLine)

$commonArguments = @(
    "-v", "expected_rows=$Rows",
    "-v", "page_size=$PageSize",
    "-v", "browse_deep_offset=$browseDeepOffset",
    "-v", "common_deep_offset=$commonDeepOffset"
)

try {
    Write-Host "Verify candidate keyset semantics"
    $verificationResult = Invoke-Psql -Arguments (
        $commonArguments + @(
            "-f", "/benchmark/verify-pagination-strategies.sql"
        )
    )
    $verificationText =
        $verificationResult.Output -join [Environment]::NewLine
    Write-Utf8File `
        -Path $verificationOutputFile `
        -Value $verificationText

    if ($verificationText -notmatch "verification_result=success") {
        throw "Pagination semantic verification did not report success."
    }

    Write-Host "Capture final B4 offset and keyset plans"
    $planArguments = $commonArguments + @(
        "-v", "browse_anchor_epoch=$browseAnchorEpoch",
        "-v", "browse_anchor_id=$browseAnchorId",
        "-v", "common_anchor_priority=$commonAnchorPriority",
        "-v", "common_anchor_epoch=$commonAnchorEpoch",
        "-v", "common_anchor_id=$commonAnchorId",
        "-f", "/benchmark/explain-pagination-strategies.sql"
    )
    $planResult = Invoke-Psql -Arguments $planArguments
    $planText = $planResult.Output -join [Environment]::NewLine
    Write-Utf8File `
        -Path $planOutputFile `
        -Value $planText

    $planCount = [regex]::Matches(
        $planText,
        '"Execution Time"\s*:'
    ).Count
    $planLabelCount = [regex]::Matches(
        $planText,
        '=== .+ ==='
    ).Count
    if ($planCount -ne 8 -or $planLabelCount -ne 8) {
        throw (
            "Expected 8 labelled execution plans, found " +
            "$planLabelCount labels and $planCount plans."
        )
    }

    $workloads = @(
        [pscustomobject]@{
            Name = "browse-first-shared"
            Query = "browse"
            Kind = "data"
            Position = "first"
            Strategy = "shared"
        },
        [pscustomobject]@{
            Name = "browse-deep-offset"
            Query = "browse"
            Kind = "data"
            Position = "deep"
            Strategy = "offset"
        },
        [pscustomobject]@{
            Name = "browse-deep-keyset"
            Query = "browse"
            Kind = "data"
            Position = "deep"
            Strategy = "keyset"
        },
        [pscustomobject]@{
            Name = "browse-count"
            Query = "browse"
            Kind = "count"
            Position = "count"
            Strategy = "count"
        },
        [pscustomobject]@{
            Name = "common-first-shared"
            Query = "common"
            Kind = "data"
            Position = "first"
            Strategy = "shared"
        },
        [pscustomobject]@{
            Name = "common-deep-offset"
            Query = "common"
            Kind = "data"
            Position = "deep"
            Strategy = "offset"
        },
        [pscustomobject]@{
            Name = "common-deep-keyset"
            Query = "common"
            Kind = "data"
            Position = "deep"
            Strategy = "keyset"
        },
        [pscustomobject]@{
            Name = "common-count"
            Query = "common"
            Kind = "count"
            Position = "count"
            Strategy = "count"
        }
    )

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
        $hasKeyword = [int]($Workload.Query -eq "common")
        $isKeyset = [int]($Workload.Strategy -eq "keyset")
        $isDeep = [int]($Workload.Position -eq "deep")

        if ($Workload.Query -eq "common") {
            $term = $CommonTerm
            $offsetRows = $commonDeepOffset
            $anchorPriority = $commonAnchorPriority
            $anchorEpoch = $commonAnchorEpoch
            $anchorId = $commonAnchorId
        }
        else {
            $term = "unused-browse-term"
            $offsetRows = $browseDeepOffset
            $anchorPriority = 0
            $anchorEpoch = $browseAnchorEpoch
            $anchorId = $browseAnchorId
        }

        if ($Workload.Position -ne "deep") {
            $offsetRows = 0
        }

        $keywordPattern = "%$term%"
        $namePrefixPattern = "$term%"

        return $ComposePrefix + @(
            "exec", "-T",
            "-e", "PGOPTIONS=-c statement_timeout=30000",
            "-e", "PGAPPNAME=shop-v2-ps-b5",
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
            "-D", "has_keyword=$hasKeyword",
            "-D", "is_keyset=$isKeyset",
            "-D", "is_deep=$isDeep",
            "-D", "keyword_pattern=$keywordPattern",
            "-D", "exact_keyword=$term",
            "-D", "name_prefix_pattern=$namePrefixPattern",
            "-D", "status=ACTIVE",
            "-D", "offset_rows=$offsetRows",
            "-D", "page_size=$PageSize",
            "-D", "anchor_priority=$anchorPriority",
            "-D", "anchor_epoch=$anchorEpoch",
            "-D", "anchor_id=$anchorId",
            "-U", "shop_benchmark",
            "-f", "/benchmark/benchmark-pagination-strategies.sql",
            "shop_search_benchmark"
        )
    }

    Write-Utf8File `
        -Path $roundsFile `
        -Value (
            "query|position|strategy|workload|clients|round|" +
            "latency_ms|tps|raw_file" +
            [Environment]::NewLine
        )

    $roundResults =
        New-Object "System.Collections.Generic.List[object]"

    foreach ($workload in $workloads) {
        foreach ($clients in $ClientCounts) {
            $warmupPerClient = $WarmupExecutions / $clients
            $measuredPerClient = $MeasuredExecutions / $clients

            for ($round = 1; $round -le $RoundCount; $round++) {
                Write-Host (
                    "$($workload.Name), clients=$clients, round=$round"
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
                        "Warm-up failed for $($workload.Name): " +
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
                    "$($workload.Name)-c$clients-r$round.txt"
                )
                Write-Utf8File `
                    -Path (Join-Path $evidenceDirectory $rawName) `
                    -Value (
                        $measuredResult.Output -join
                        [Environment]::NewLine
                    )

                if ($measuredResult.ExitCode -ne 0) {
                    throw (
                        "Measured run failed for " + $rawName + ": " +
                        ($measuredResult.Output -join
                            [Environment]::NewLine)
                    )
                }

                $metrics = Read-PgbenchMetrics `
                    -Output $measuredResult.Output `
                    -ExpectedTransactions $MeasuredExecutions

                $record = [pscustomobject]@{
                    Key = "$($workload.Name)|$clients"
                    Query = $workload.Query
                    Position = $workload.Position
                    Strategy = $workload.Strategy
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
                        "$($record.Query)|$($record.Position)|" +
                        "$($record.Strategy)|$($record.Workload)|" +
                        "$($record.Clients)|$($record.Round)|" +
                        "$(Format-Decimal $record.LatencyMs)|" +
                        "$(Format-Decimal $record.Tps)|$rawName"
                    )
            }
        }
    }

    $expectedRoundRecords =
        $workloads.Count * $ClientCounts.Count * $RoundCount
    if ($roundResults.Count -ne $expectedRoundRecords) {
        throw (
            "Expected $expectedRoundRecords measured records, found " +
            "$($roundResults.Count)."
        )
    }

    Write-Utf8File `
        -Path $summaryFile `
        -Value (
            "query|position|strategy|workload|clients|rounds|" +
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
                "$($first.Query)|$($first.Position)|" +
                "$($first.Strategy)|$($first.Workload)|" +
                "$($first.Clients)|$($group.Count)|" +
                "$(Format-Decimal $medianLatency)|" +
                "$(Format-Decimal $medianTps)"
            )
    }

    Write-Utf8File `
        -Path $comparisonFile `
        -Value (
            "query|clients|offset_median_latency_ms|" +
            "keyset_median_latency_ms|offset_over_keyset_ratio" +
            [Environment]::NewLine
        )

    foreach ($queryName in @("browse", "common")) {
        foreach ($clients in $ClientCounts) {
            [object[]]$offsetRecords = @(
                $roundResults |
                    Where-Object {
                        $_.Workload -eq "$queryName-deep-offset" -and
                        $_.Clients -eq $clients
                    }
            )
            [object[]]$keysetRecords = @(
                $roundResults |
                    Where-Object {
                        $_.Workload -eq "$queryName-deep-keyset" -and
                        $_.Clients -eq $clients
                    }
            )

            if (
                $offsetRecords.Count -ne $RoundCount -or
                $keysetRecords.Count -ne $RoundCount
            ) {
                throw (
                    "Incomplete deep-page comparison for " +
                    "$queryName with $clients clients."
                )
            }

            $offsetMedian = Get-Median `
                -Values ([double[]]$offsetRecords.LatencyMs)
            $keysetMedian = Get-Median `
                -Values ([double[]]$keysetRecords.LatencyMs)
            if ($keysetMedian -le 0) {
                throw "Keyset median latency must be greater than zero."
            }

            $ratio = $offsetMedian / $keysetMedian
            Add-Utf8Line `
                -Path $comparisonFile `
                -Value (
                    "$queryName|$clients|" +
                    "$(Format-Decimal $offsetMedian)|" +
                    "$(Format-Decimal $keysetMedian)|" +
                    "$(Format-Decimal $ratio)"
                )
        }
    }

    $schemaGuard = [int]((
        Invoke-Psql -Arguments @(
            "-c",
            @"
SELECT count(*)
FROM pg_class AS relation
JOIN pg_namespace AS namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relname LIKE 'psb5_%';
"@
        )
    ).Output -join "").Trim()

    if ($schemaGuard -ne 0) {
        throw "A persistent B5 benchmark object remains in PostgreSQL."
    }

    Add-Utf8Line -Path $manifestFile -Value "plans=8"
    Add-Utf8Line `
        -Path $manifestFile `
        -Value "measured_round_records=$($roundResults.Count)"
    Add-Utf8Line `
        -Path $manifestFile `
        -Value "persistent_benchmark_objects=0"
    Add-Utf8Line -Path $manifestFile -Value "result=success"

    Write-Host ""
    Write-Host "Pagination strategy comparison succeeded."
    Write-Host "Rows: $Rows"
    Write-Host "Mode: $Mode"
    Write-Host "Workloads: $($workloads.Count)"
    Write-Host "Plans: $planCount"
    Write-Host "Evidence: $evidenceDirectory"
    if ($Rows -eq 10000) {
        Write-Host "Stable traversal proof: complete for browse and common."
    }
    else {
        Write-Host (
            "Stable traversal proof: reused from 10k; " +
            "100k deep-page parity passed."
        )
    }
    Write-Host "No persistent benchmark object remains."
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
