[CmdletBinding()]
param(
    [ValidateSet(10000, 100000)]
    [int]$Rows = 10000,

    [switch]$Reset,

    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$PageSize = 100
$ProjectName = "shop-product-search-benchmark"
$ExpectedVolumeName =
        "${ProjectName}_product-search-postgres-data"
$ClientCounts = @(1, 8)
$InvariantCulture =
        [System.Globalization.CultureInfo]::InvariantCulture
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if ($Rows -eq 10000) {
    $Mode = "smoke"
    $WarmupExecutions = 8
    $MeasuredExecutions = 16
    $RoundCount = 1
}
else {
    $Mode = "decision"
    $WarmupExecutions = 24
    $MeasuredExecutions = 104
    $RoundCount = 3
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
$SeedOverlayFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-trigram-workloads.sql"
$BaselineVerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-trigram-workloads.sql"
$CreateFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\create-trigram-comparison-candidate.sql"
$VerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-trigram-comparison.sql"
$ExplainFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-trigram-comparison.sql"
$BenchmarkFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\benchmark-trigram-comparison.sql"
$CleanupFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\cleanup-trigram-comparison-candidate.sql"
$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\c\c3\raw\comparison"

$AllowedDirtyPaths = @(
    "scripts/benchmark/product-search/run-trigram-candidate-comparison.ps1",
    "src/test/resources/benchmark/product-search/create-trigram-comparison-candidate.sql",
    "src/test/resources/benchmark/product-search/verify-trigram-comparison.sql",
    "src/test/resources/benchmark/product-search/explain-trigram-comparison.sql",
    "src/test/resources/benchmark/product-search/benchmark-trigram-comparison.sql",
    "src/test/resources/benchmark/product-search/cleanup-trigram-comparison-candidate.sql"
)

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

function Invoke-GitCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $result = Invoke-NativeCapture `
        -FilePath "git" `
        -CommandArguments (
            @("-C", $RepoRoot) +
            $CommandArguments
        )

    if ($result.ExitCode -ne 0) {
        throw (
            "Git command failed: " +
            ($result.Output -join [Environment]::NewLine)
        )
    }

    return $result.Output
}

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CommandArguments
    )

    $result = Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments $CommandArguments

    if ($result.ExitCode -ne 0) {
        throw (
            "Docker command failed: " +
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

function Get-OptionalProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [AllowNull()]
        [object]$DefaultValue = $null
    )

    $property = $InputObject.PSObject.Properties[$Name]

    if ($null -eq $property) {
        return $DefaultValue
    }

    return $property.Value
}

function Format-Decimal {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Value
    )

    return $Value.ToString("F6", $InvariantCulture)
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$SortedValues,

        [Parameter(Mandatory = $true)]
        [ValidateRange(0.0, 100.0)]
        [double]$Percentile
    )

    if ($SortedValues.Count -eq 0) {
        throw "Percentile input must not be empty."
    }

    $rank = [int][Math]::Ceiling(
        ($Percentile / 100.0) * $SortedValues.Count
    )
    $index = [Math]::Max(
        0,
        [Math]::Min($SortedValues.Count - 1, $rank - 1)
    )

    return [double]$SortedValues[$index]
}

function Get-Median {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$Values
    )

    if ($Values.Count -eq 0) {
        throw "Median input must not be empty."
    }

    [double[]]$sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)

    if ($sorted.Count % 2 -eq 1) {
        return $sorted[$middle]
    }

    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-MedianAbsoluteDeviation {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$Values
    )

    $median = Get-Median -Values $Values
    [double[]]$deviations = @(
        $Values | ForEach-Object {
            [Math]::Abs($_ - $median)
        }
    )

    return Get-Median -Values $deviations
}

function Get-RoundMetrics {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$LogLines,

        [Parameter(Mandatory = $true)]
        [int]$Clients,

        [Parameter(Mandatory = $true)]
        [int]$TransactionsPerClient,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedTransactions
    )

    [object[]]$records = @(
        foreach ($line in $LogLines) {
            $trimmed = $line.Trim()

            if (
                [string]::IsNullOrWhiteSpace($trimmed) -or
                $trimmed.StartsWith("WARNING:")
            ) {
                continue
            }

            [string[]]$parts = @($trimmed -split "\s+")

            if ($parts.Count -ne 6) {
                throw "Unexpected pgbench log line: $trimmed"
            }

            foreach ($index in @(0, 1, 3, 4, 5)) {
                if ($parts[$index] -notmatch "^\d+$") {
                    throw "Invalid pgbench log field: $trimmed"
                }
            }

            $timeToken = $parts[2]
            $succeeded = $timeToken -match "^\d+$"
            $latencyUs = if ($succeeded) {
                [long]$timeToken
            }
            else {
                $null
            }
            $completionUs =
                    ([long]$parts[4] * [long]1000000) +
                    [long]$parts[5]

            [pscustomobject]@{
                ClientId = [int]$parts[0]
                TransactionNumber = [int]$parts[1]
                Succeeded = $succeeded
                LatencyUs = $latencyUs
                CompletionUs = $completionUs
            }
        }
    )

    if ($records.Count -ne $ExpectedTransactions) {
        throw (
            "Expected $ExpectedTransactions transaction logs, " +
            "found $($records.Count)."
        )
    }

    for ($clientId = 0; $clientId -lt $Clients; $clientId++) {
        [int[]]$numbers = @(
            $records |
            Where-Object { $_.ClientId -eq $clientId } |
            Sort-Object TransactionNumber |
            Select-Object -ExpandProperty TransactionNumber
        )

        if ($numbers.Count -ne $TransactionsPerClient) {
            throw (
                "Client $clientId produced $($numbers.Count) " +
                "transactions; expected $TransactionsPerClient."
            )
        }

        for (
            $number = 1;
            $number -le $TransactionsPerClient;
            $number++
        ) {
            if ($numbers[$number - 1] -ne $number) {
                throw "Client $clientId transaction sequence is incomplete."
            }
        }
    }

    [object[]]$successful = @(
        $records | Where-Object Succeeded
    )
    $failures = $records.Count - $successful.Count

    if ($successful.Count -eq 0) {
        throw "No successful measured transactions were found."
    }

    [double[]]$latenciesMs = @(
        $successful |
        ForEach-Object { [double]$_.LatencyUs / 1000.0 } |
        Sort-Object
    )
    [double[]]$startTimes = @(
        $successful |
        ForEach-Object {
            [double]$_.CompletionUs - [double]$_.LatencyUs
        }
    )
    [double[]]$completionTimes = @(
        $successful | Select-Object -ExpandProperty CompletionUs
    )
    $firstStart = ($startTimes | Measure-Object -Minimum).Minimum
    $lastCompletion =
            ($completionTimes | Measure-Object -Maximum).Maximum
    $windowUs = $lastCompletion - $firstStart

    if ($windowUs -le 0) {
        throw "Measured transaction window is invalid."
    }

    return [pscustomobject]@{
        Successes = $successful.Count
        Failures = $failures
        ErrorRatePercent =
            ([double]$failures / $records.Count) * 100.0
        MinimumMs = $latenciesMs[0]
        P50Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs -Percentile 50
        P95Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs -Percentile 95
        P99Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs -Percentile 99
        MaximumMs = $latenciesMs[$latenciesMs.Count - 1]
        AverageMs =
            ($latenciesMs | Measure-Object -Average).Average
        ThroughputTps =
            [double]$successful.Count / ($windowUs / 1000000.0)
    }
}

function Get-PlanPayload {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Output
    )

    $combined = ($Output -join [Environment]::NewLine).Trim()
    $start = $combined.IndexOf("{")
    $end = $combined.LastIndexOf("}")

    if ($start -lt 0 -or $end -le $start) {
        throw "Plan output did not contain a JSON object: $combined"
    }

    $jsonText = $combined.Substring($start, $end - $start + 1)

    try {
        $parsed = ConvertFrom-Json -InputObject $jsonText
    }
    catch {
        throw "Plan output was not valid JSON: $($_.Exception.Message)"
    }

    $candidate = Get-OptionalProperty $parsed "candidate"
    $membershipRows =
        Get-OptionalProperty $parsed "membershipRows"
    $planArray = Get-OptionalProperty $parsed "plan"

    if ([string]::IsNullOrWhiteSpace([string]$candidate)) {
        throw "Plan payload did not contain candidate."
    }
    if ($null -eq $membershipRows -or $null -eq $planArray) {
        throw "Plan payload was missing membershipRows or plan."
    }

    $root = if ($planArray -is [System.Array]) {
        $planArray[0]
    }
    else {
        $planArray
    }

    if (
        $null -eq $root.PSObject.Properties["Plan"] -or
        $null -eq $root.PSObject.Properties["Planning Time"] -or
        $null -eq $root.PSObject.Properties["Execution Time"]
    ) {
        throw "EXPLAIN JSON was missing required plan metadata."
    }

    return [pscustomobject]@{
        Candidate = [string]$candidate
        MembershipRows = [long]$membershipRows
        JsonText = $jsonText
        Root = $root
    }
}

function Get-PlanMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Root
    )

    $nodeTypes = New-Object "System.Collections.Generic.List[string]"
    $indexNames = New-Object "System.Collections.Generic.List[string]"
    $rowsRemoved = 0.0
    $stack = New-Object System.Collections.Stack
    $stack.Push($Root.Plan)

    while ($stack.Count -gt 0) {
        $node = $stack.Pop()
        $nodeType = Get-OptionalProperty $node "Node Type"
        $indexName = Get-OptionalProperty $node "Index Name"
        $removed = Get-OptionalProperty `
            $node "Rows Removed by Filter" 0

        if ($null -ne $nodeType) {
            [void]$nodeTypes.Add([string]$nodeType)
        }
        if ($null -ne $indexName) {
            [void]$indexNames.Add([string]$indexName)
        }
        $rowsRemoved += [double]$removed

        $children = Get-OptionalProperty `
            -InputObject $node `
            -Name "Plans"

        foreach ($child in @($children)) {
            if ($null -ne $child) {
                $stack.Push($child)
            }
        }
    }

    $planRoot = $Root.Plan

    return [pscustomobject]@{
        NodeTypes =
            (($nodeTypes | Sort-Object -Unique) -join ",")
        IndexNames =
            (($indexNames | Sort-Object -Unique) -join ",")
        RowsRemovedByFilter = $rowsRemoved
        ActualRows = [double](Get-OptionalProperty `
            $planRoot "Actual Rows" 0)
        SharedHitBlocks = [long](Get-OptionalProperty `
            $planRoot "Shared Hit Blocks" 0)
        SharedReadBlocks = [long](Get-OptionalProperty `
            $planRoot "Shared Read Blocks" 0)
        SharedDirtiedBlocks = [long](Get-OptionalProperty `
            $planRoot "Shared Dirtied Blocks" 0)
        SharedWrittenBlocks = [long](Get-OptionalProperty `
            $planRoot "Shared Written Blocks" 0)
    }
}

function Escape-LikeLiteral {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $escaped = $Value.Replace("\", "\\")
    $escaped = $escaped.Replace("%", "\%")

    return $escaped.Replace("_", "\_")
}

function ConvertTo-SqlLiteral {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return "'" + $Value.Replace("'", "''") + "'"
}

function New-Workload {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Number,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [bool]$HasKeyword,
        [Parameter(Mandatory = $true)]
        [string]$Term,
        [Parameter(Mandatory = $true)]
        [ValidateSet("ACTIVE", "INACTIVE", "ALL")]
        [string]$Status,
        [Parameter(Mandatory = $true)]
        [ValidateSet("count", "data_offset", "data_cursor")]
        [string]$Surface,
        [Parameter(Mandatory = $true)]
        [long]$ExpectedMembership,
        [Parameter(Mandatory = $true)]
        [long]$OffsetRows,
        [Parameter(Mandatory = $true)]
        [int]$ResultPageSize,
        [Parameter(Mandatory = $true)]
        [bool]$Repeated
    )

    return [pscustomobject]@{
        Number = $Number
        Name = $Name
        HasKeyword = $HasKeyword
        Term = $Term
        Status = $Status
        Surface = $Surface
        ExpectedMembership = $ExpectedMembership
        OffsetRows = $OffsetRows
        PageSize = $ResultPageSize
        Repeated = $Repeated
        AnchorPriority = 0
        AnchorEpochMicros = [long]0
        AnchorId = "00000000-0000-0000-0000-000000000000"
    }
}

function Get-C3Workloads {
    [long]$activeRows = $Rows * 4L / 5L
    [long]$mediumRows = ($Rows / 10L) - 4L
    [long]$commonActiveRows =
            ($Rows * 2L / 5L) - 11L
    [long]$deepOffset = $Rows / 4L

    return @(
        New-Workload 1 "exact-sku-active-data" $true `
            "C1-RANK-TERM" "ACTIVE" "data_offset" 5 0 100 $true
        New-Workload 2 "exact-sku-active-count" $true `
            "C1-RANK-TERM" "ACTIVE" "count" 5 0 100 $true
        New-Workload 3 "sku-infix-active-data" $true `
            "SKU-MIDDLE" "ACTIVE" "data_offset" 1 0 100 $true
        New-Workload 4 "sku-infix-active-count" $true `
            "SKU-MIDDLE" "ACTIVE" "count" 1 0 100 $true
        New-Workload 5 "rare-name-infix-active-data" $true `
            "NAME-MIDDLE" "ACTIVE" "data_offset" 1 0 100 $true
        New-Workload 6 "rare-name-infix-active-count" $true `
            "NAME-MIDDLE" "ACTIVE" "count" 1 0 100 $true
        New-Workload 7 "medium-name-infix-active-data" $true `
            "C1-MEDIUM-INFIX" "ACTIVE" "data_offset" `
            $mediumRows 0 100 $true
        New-Workload 8 "medium-name-infix-active-count" $true `
            "C1-MEDIUM-INFIX" "ACTIVE" "count" `
            $mediumRows 0 100 $true
        New-Workload 9 "common-name-infix-active-data" $true `
            "C1-COMMON-INFIX" "ACTIVE" "data_offset" `
            $commonActiveRows 0 100 $true
        New-Workload 10 "common-name-infix-active-count" $true `
            "C1-COMMON-INFIX" "ACTIVE" "count" `
            $commonActiveRows 0 100 $true
        New-Workload 11 "keyword-miss-active-data" $true `
            "C1-NO-RESULT-NEEDLE" "ACTIVE" "data_offset" `
            0 0 100 $true
        New-Workload 12 "keyword-miss-active-count" $true `
            "C1-NO-RESULT-NEEDLE" "ACTIVE" "count" `
            0 0 100 $true
        New-Workload 13 "one-character-active-data" $true `
            "q" "ACTIVE" "data_offset" 3 0 100 $true
        New-Workload 14 "one-character-active-count" $true `
            "q" "ACTIVE" "count" 3 0 100 $true
        New-Workload 15 "two-character-active-data" $true `
            "qz" "ACTIVE" "data_offset" 2 0 100 $true
        New-Workload 16 "two-character-active-count" $true `
            "qz" "ACTIVE" "count" 2 0 100 $true
        New-Workload 17 "three-character-active-data" $true `
            "qzx" "ACTIVE" "data_offset" 1 0 100 $true
        New-Workload 18 "three-character-active-count" $true `
            "qzx" "ACTIVE" "count" 1 0 100 $true
        New-Workload 19 "percent-wildcard-active-data" $true `
            "%" "ACTIVE" "data_offset" $activeRows 0 100 $true
        New-Workload 20 "percent-wildcard-active-count" $true `
            "%" "ACTIVE" "count" $activeRows 0 100 $true
        New-Workload 21 "admin-dual-field-all-data" $true `
            "C1-DUAL-INFIX" "ALL" "data_offset" 2 0 100 $true
        New-Workload 22 "admin-dual-field-all-count" $true `
            "C1-DUAL-INFIX" "ALL" "count" 2 0 100 $true
        New-Workload 23 "admin-inactive-exact-data" $true `
            "C1-INACTIVE-EXACT" "INACTIVE" "data_offset" `
            1 0 100 $true
        New-Workload 24 "admin-inactive-exact-count" $true `
            "C1-INACTIVE-EXACT" "INACTIVE" "count" `
            1 0 100 $true
        New-Workload 25 "common-active-deep-offset" $true `
            "C1-COMMON-INFIX" "ACTIVE" "data_offset" `
            $commonActiveRows $deepOffset 100 $true
        New-Workload 26 "common-active-deep-cursor" $true `
            "C1-COMMON-INFIX" "ACTIVE" "data_cursor" `
            $commonActiveRows $deepOffset 100 $true
        New-Workload 27 "blank-public-browse-data" $false `
            "__blank__" "ACTIVE" "data_offset" `
            $activeRows 0 100 $false
        New-Workload 28 "blank-public-browse-count" $false `
            "__blank__" "ACTIVE" "count" `
            $activeRows 0 100 $false
        New-Workload 29 "sku-prefix-control-data" $true `
            "C1-RANK-TERM-PREFIX-SKU" "ACTIVE" `
            "data_offset" 1 0 100 $false
        New-Workload 30 "name-prefix-control-data" $true `
            "C1-RANK-TERM Name" "ACTIVE" `
            "data_offset" 1 0 100 $false
        New-Workload 31 "mixed-case-control-data" $true `
            "c1 mixed term" "ACTIVE" "data_offset" `
            1 0 100 $false
        New-Workload 32 "accented-vietnamese-control-data" $true `
            ("Thi" + [char]0x1EBF + "t b" + [char]0x1ECB +
             " " + [char]0x0111 + "i" + [char]0x1EC7 + "n") `
            "ACTIVE" "data_offset" 1 0 100 $false
        New-Workload 33 "unaccented-vietnamese-control-data" $true `
            "Thiet bi dien" "ACTIVE" "data_offset" `
            1 0 100 $false
        New-Workload 34 "unicode-control-data" $true `
            ([string][char]0x691C + [char]0x7D22 +
             [char]0x57FA + [char]0x6E96) `
            "ACTIVE" "data_offset" 1 0 100 $false
        New-Workload 35 "underscore-control-data" $true `
            "_" "ACTIVE" "data_offset" $activeRows 0 100 $false
        New-Workload 36 "underscore-control-count" $true `
            "_" "ACTIVE" "count" $activeRows 0 100 $false
        New-Workload 37 "backslash-control-data" $true `
            "\" "ACTIVE" "data_offset" 1 0 100 $false
        New-Workload 38 "backslash-control-count" $true `
            "\" "ACTIVE" "count" 1 0 100 $false
        New-Workload 39 "long-input-control-data" $true `
            ("".PadRight(5000, [char]'x')) "ACTIVE" "data_offset" `
            0 0 100 $false
        New-Workload 40 "long-input-control-count" $true `
            ("".PadRight(5000, [char]'x')) "ACTIVE" "count" `
            0 0 100 $false
        New-Workload 41 "admin-pair-active-control-data" $true `
            "C1-ADMIN-PAIR" "ACTIVE" "data_offset" `
            1 0 100 $false
        New-Workload 42 "admin-pair-inactive-control-data" $true `
            "C1-ADMIN-PAIR" "INACTIVE" "data_offset" `
            1 0 100 $false
    )
}

function Get-SchemaState {
    $sql = @"
SELECT concat_ws(
    '|',
    'database=' || current_database(),
    'user=' || current_user,
    'postgres=' || current_setting('server_version'),
    'rows=' || (SELECT count(*) FROM products),
    'flyway=' || (
        SELECT coalesce(max(version::integer), 0)
        FROM flyway_schema_history
        WHERE success
    ),
    'pg_trgm_installed=' || (
        SELECT count(*) FROM pg_extension
        WHERE extname = 'pg_trgm'
    ),
    'indexes=' || (
        SELECT string_agg(
            indexname::text, ',' ORDER BY indexname::text
        )
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ),
    'table_bytes=' || pg_relation_size('public.products'),
    'index_bytes=' || pg_indexes_size('public.products'),
    'c3_candidate_bytes=' || coalesce((
        SELECT sum(pg_relation_size(indexrelid))
        FROM pg_index
        WHERE indexrelid IN (
            SELECT to_regclass('public.' || indexname)
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'products'
              AND indexname LIKE 'c3_%'
        )
    ), 0)
);
"@

    return Invoke-DockerCommand `
        -CommandArguments ($script:PsqlPrefix + @("-c", $sql))
}

function Invoke-DatasetReset {
    $parameters = @{
        Rows = $Rows
        Reset = $true
    }

    if ($AllowDirty) {
        $parameters.AllowDirty = $true
    }

    & $PrepareDatasetScript @parameters
}

function Initialize-TrigramWorkloads {
    $sessionSettings =
        "SET shop_benchmark.row_count = '$Rows'; " +
        "SET shop_benchmark.seed = '$Seed';"

    [void](Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-c", $sessionSettings,
                "-f", "/benchmark/seed-trigram-workloads.sql",
                "-c", "ANALYZE products;"
            )
        ))
}

function Invoke-CleanupCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$OutputPath
    )

    $result = Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-v", "expected_rows=$Rows",
                "-f",
                "/benchmark/cleanup-trigram-comparison-candidate.sql"
            )
        )

    $text = $result.Output -join [Environment]::NewLine
    Write-Utf8File -Path $OutputPath -Value $text

    if ($result.ExitCode -ne 0) {
        throw "C3 SQL cleanup failed: $text"
    }
    if ($text -notmatch "cleanup_result=success") {
        throw "C3 cleanup did not emit its success marker."
    }
}

function Get-CursorAnchor {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Workload
    )

    if ($Workload.Surface -ne "data_cursor") {
        return
    }
    if ($Workload.OffsetRows -le 0) {
        throw "C3 cursor workload requires a positive offset."
    }

    $term = ConvertTo-SqlLiteral $Workload.Term
    $contains = ConvertTo-SqlLiteral `
        ("%" + $Workload.Term + "%")
    $prefix = ConvertTo-SqlLiteral `
        ((Escape-LikeLiteral $Workload.Term) + "%")
    $statusClause = if ($Workload.Status -eq "ALL") {
        ""
    }
    else {
        "AND p.status = " +
        (ConvertTo-SqlLiteral $Workload.Status)
    }
    $anchorOffset = $Workload.OffsetRows - 1

    $sql = @"
SELECT concat_ws(
    '|',
    CASE
        WHEN lower(p.sku) = lower($term) THEN 0
        WHEN lower(p.name) LIKE lower($prefix)
             ESCAPE E'\\' THEN 1
        ELSE 2
    END,
    (extract(epoch FROM p.created_at) * 1000000)::bigint,
    p.id
)
FROM products AS p
WHERE (
    lower(p.sku) LIKE lower($contains) ESCAPE ''
    OR lower(p.name) LIKE lower($contains) ESCAPE ''
)
$statusClause
ORDER BY
    CASE
        WHEN lower(p.sku) = lower($term) THEN 0
        WHEN lower(p.name) LIKE lower($prefix)
             ESCAPE E'\\' THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
OFFSET $anchorOffset ROWS
FETCH FIRST 1 ROW ONLY;
"@

    $result = Invoke-DockerCommand `
        -CommandArguments ($script:PsqlPrefix + @("-c", $sql))
    [string[]]$lines = @(
        $result.Output | Where-Object {
            $_ -match "^[0-2]\|\d+\|[0-9a-fA-F-]{36}$"
        }
    )

    if ($lines.Count -ne 1) {
        throw "Could not derive the cursor anchor for $($Workload.Name)."
    }

    $parts = $lines[0].Split([char]"|")
    if ($parts.Count -ne 3) {
        throw "Cursor anchor returned an unexpected field count."
    }

    $Workload.AnchorPriority = [int]$parts[0]
    $Workload.AnchorEpochMicros = [long]$parts[1]
    $Workload.AnchorId = $parts[2]
}

function New-PgbenchArguments {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Candidate,
        [Parameter(Mandatory = $true)]
        [object]$Workload,
        [Parameter(Mandatory = $true)]
        [int]$Clients,
        [Parameter(Mandatory = $true)]
        [int]$TransactionsPerClient,
        [AllowNull()]
        [string]$LogPrefix
    )

    if (-not $Workload.HasKeyword) {
        throw "Blank browse is a plan control, not a repeated workload."
    }

    $isCount = if ($Workload.Surface -eq "count") { 1 } else { 0 }
    $isCursor = if (
        $Workload.Surface -eq "data_cursor"
    ) { 1 } else { 0 }
    $hasStatus = if ($Workload.Status -eq "ALL") { 0 } else { 1 }
    $status = if ($hasStatus -eq 1) {
        $Workload.Status
    }
    else {
        "ACTIVE"
    }
    $keywordPattern = "%" + $Workload.Term + "%"
    $namePrefixPattern =
        (Escape-LikeLiteral $Workload.Term) + "%"

    [string[]]$arguments = @(
        "exec", "-T",
        "-e",
        "PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=30000",
        "-e", "PGAPPNAME=shop-v2-ps-c3-$Candidate",
        "postgres", "pgbench",
        "-n", "-M", "prepared",
        "-c", "$Clients", "-j", "$Clients",
        "-t", "$TransactionsPerClient",
        "-r", "--failures-detailed", "--verbose-errors"
    )

    if (-not [string]::IsNullOrWhiteSpace($LogPrefix)) {
        $arguments += @("-l", "--log-prefix=$LogPrefix")
    }

    $arguments += @(
        "-D", "is_count=$isCount",
        "-D", "is_cursor=$isCursor",
        "-D", "has_status=$hasStatus",
        "-D", "keyword_pattern=$keywordPattern",
        "-D", "exact_keyword=$($Workload.Term)",
        "-D", "name_prefix_pattern=$namePrefixPattern",
        "-D", "status=$status",
        "-D", "offset_rows=$($Workload.OffsetRows)",
        "-D", "page_size=$($Workload.PageSize)",
        "-D", "anchor_priority=$($Workload.AnchorPriority)",
        "-D", "anchor_epoch_micros=$($Workload.AnchorEpochMicros)",
        "-D", "anchor_id=$($Workload.AnchorId)",
        "-U", "shop_benchmark",
        "-f", "/benchmark/benchmark-trigram-comparison.sql",
        "shop_search_benchmark"
    )

    return $script:ComposePrefix + $arguments
}

function Remove-ContainerLogs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prefix
    )

    if ($Prefix -notmatch "^/tmp/psc3-[A-Za-z0-9-]+$") {
        throw "Unsafe container log prefix: $Prefix"
    }

    [void](Invoke-DockerCommand `
        -CommandArguments (
            $script:ComposePrefix + @(
                "exec", "-T", "postgres", "sh", "-lc",
                "rm -f $Prefix.*"
            )
        ))
}

function Get-StringSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $algorithm = [System.Security.Cryptography.SHA256]::Create()

    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $algorithm.ComputeHash($bytes)
        return (($hash | ForEach-Object {
            $_.ToString("x2")
        }) -join "")
    }
    finally {
        $algorithm.Dispose()
    }
}

foreach ($commandName in @("git", "docker")) {
    $command = Get-Command `
        $commandName `
        -CommandType Application `
        -ErrorAction SilentlyContinue

    if ($null -eq $command) {
        throw "Required command was not found: $commandName"
    }
}

if (-not $Reset) {
    throw (
        "C3 recreates and removes only the isolated benchmark " +
        "database. Pass -Reset explicitly."
    )
}

foreach ($requiredFile in @(
    $ComposeFile,
    $PrepareDatasetScript,
    $SeedOverlayFile,
    $BaselineVerifyFile,
    $CreateFile,
    $VerifyFile,
    $ExplainFile,
    $BenchmarkFile,
    $CleanupFile,
    $PSCommandPath
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file was not found: $requiredFile"
    }
}

foreach ($clients in $ClientCounts) {
    if (
        $WarmupExecutions % $clients -ne 0 -or
        $MeasuredExecutions % $clients -ne 0
    ) {
        throw "Execution counts must be divisible by clients=$clients."
    }
}

$branch = (
    @(Invoke-GitCommand @("branch", "--show-current")) -join ""
).Trim()
$head = (
    @(Invoke-GitCommand @("rev-parse", "HEAD")) -join ""
).Trim()
[string[]]$workingTree = @(
    Invoke-GitCommand @("status", "--porcelain=v1")
)

if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "C3 must run from a branch, not detached HEAD."
}

$isDirty = $workingTree.Count -gt 0

if ($isDirty -and (-not $AllowDirty)) {
    throw (
        "Working tree is not clean. Commit the six C3 artifacts " +
        "first, or use -AllowDirty only for the pre-commit run."
    )
}

if ($isDirty) {
    foreach ($entry in $workingTree) {
        if ($entry.Length -lt 4) {
            throw "Unexpected Git status entry: $entry"
        }

        $path = $entry.Substring(3).Trim().Replace("\", "/")

        if ($path.Contains(" -> ")) {
            $path = ($path -split " -> ")[-1]
        }

        if ($AllowedDirtyPaths -notcontains $path) {
            throw "C3 working tree contains an unauthorized path: $path"
        }
    }
}

$workingTreeState = if ($isDirty) {
    "dirty-allowed"
}
else {
    "clean"
}

$script:ComposePrefix = @(
    "compose", "--project-name", $ProjectName,
    "--file", $ComposeFile
)
$script:PsqlPrefix = $script:ComposePrefix + @(
    "exec", "-T", "postgres", "psql",
    "-X", "-q", "-A", "-t", "-P", "pager=off",
    "-v", "ON_ERROR_STOP=1",
    "-U", "shop_benchmark",
    "-d", "shop_search_benchmark"
)

$timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$evidenceDirectory = Join-Path `
    $EvidenceRoot `
    "trigram-comparison-$Rows-$Mode-$timestamp"

New-Item -ItemType Directory `
    -Path $evidenceDirectory -Force | Out-Null

$manifestFile = Join-Path $evidenceDirectory "manifest.txt"
$environmentFile = Join-Path $evidenceDirectory "environment.txt"
$planSummaryFile = Join-Path $evidenceDirectory "plans.csv"
$roundsFile = Join-Path $evidenceDirectory "rounds.csv"
$summaryFile = Join-Path $evidenceDirectory "summary.csv"
$comparisonFile = Join-Path $evidenceDirectory "comparison.csv"
$survivorFile = Join-Path $evidenceDirectory "c4-review-candidates.txt"

$artifactFiles = @(
    $PSCommandPath,
    $CreateFile,
    $VerifyFile,
    $ExplainFile,
    $BenchmarkFile,
    $CleanupFile,
    $SeedOverlayFile,
    $BaselineVerifyFile
)

$manifestLines = @(
    "work_package=V2-PS-C3",
    "generated_at_utc=$timestamp",
    "mode=$Mode",
    "branch=$branch",
    "head=$head",
    "working_tree=$workingTreeState",
    "rows=$Rows",
    "seed=$Seed",
    "page_size=$PageSize",
    "candidates=baseline,gin,gist",
    "clients=$($ClientCounts -join ',')",
    "warmup_executions=$WarmupExecutions",
    "measured_executions_per_round=$MeasuredExecutions",
    "rounds=$RoundCount",
    "query_mode=prepared",
    "load_model=closed_loop_unthrottled",
    "cache_state=warm",
    "cold_cache_claim=none",
    "comparison_metric=per-round-p50",
    "noise_budget=max(10_percent,2x_baseline_mad_over_median)",
    "production_contract_changed=false",
    "planner_forcing=false"
)

foreach ($file in $artifactFiles) {
    $relativePath = $file.Substring(
        $RepoRoot.Length
    ).TrimStart([char]'\', [char]'/').Replace("\", "/")
    $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifestLines += "sha256[$relativePath]=$hash"
}

if ($isDirty) {
    $manifestLines += "working_tree_entries:"
    foreach ($entry in $workingTree) {
        $manifestLines += $entry
    }
}

Write-Utf8File $manifestFile (
    ($manifestLines -join [Environment]::NewLine) +
    [Environment]::NewLine
)
Write-Utf8File $planSummaryFile (
    "candidate|workload|surface|status|expected_membership|" +
    "actual_membership|planning_time_ms|execution_time_ms|" +
    "actual_rows|shared_hit_blocks|shared_read_blocks|" +
    "shared_dirtied_blocks|shared_written_blocks|" +
    "rows_removed_by_filter|node_types|index_names|" +
    "candidate_path_used|term_sha256|plan_file" +
    [Environment]::NewLine
)
Write-Utf8File $roundsFile (
    "candidate|workload|surface|clients|round|successes|failures|" +
    "error_rate_percent|min_ms|p50_ms|p95_ms|p99_ms|max_ms|" +
    "average_ms|throughput_tps|raw_base" +
    [Environment]::NewLine
)
Write-Utf8File $summaryFile (
    "candidate|workload|surface|clients|rounds|" +
    "median_p50_ms|median_p95_ms|median_p99_ms|" +
    "median_average_ms|median_tps|total_failures" +
    [Environment]::NewLine
)
Write-Utf8File $comparisonFile (
    "candidate|workload|surface|clients|baseline_median_p50_ms|" +
    "candidate_median_p50_ms|baseline_mad_ms|noise_budget_percent|" +
    "latency_change_percent|direction|outside_budget|" +
    "candidate_path_used|classification" +
    [Environment]::NewLine
)

$workloads = @(Get-C3Workloads)
$repeatedWorkloads = @($workloads | Where-Object Repeated)
$roundResults =
    New-Object "System.Collections.Generic.List[object]"
$planRecords =
    New-Object "System.Collections.Generic.List[object]"

$mainFailure = $null
$finalCleanupFailure = $null
$volumeCleanupFailure = $null
$databaseLifecycleStarted = $false
$overallPlanCount = 0

Push-Location $RepoRoot

try {
    foreach ($candidate in @("baseline", "gin", "gist")) {
        Write-Host ""
        Write-Host "C3 candidate: $candidate"

        $candidateDirectory = Join-Path `
            $evidenceDirectory `
            $candidate
        $planDirectory = Join-Path $candidateDirectory "plans"
        $rawDirectory = Join-Path $candidateDirectory "raw"

        New-Item -ItemType Directory -Path $planDirectory -Force |
            Out-Null
        New-Item -ItemType Directory -Path $rawDirectory -Force |
            Out-Null

        $candidateFailure = $null
        $candidateCleanupFailure = $null
        $candidatePlanCount = 0

        try {
            $databaseLifecycleStarted = $true
            Write-Host "Reset dataset: $candidate, rows=$Rows"
            Invoke-DatasetReset
            Initialize-TrigramWorkloads

            if ($candidate -eq "baseline") {
                $sessionSettings =
                    "SET shop_benchmark.row_count = '$Rows'; " +
                    "SET shop_benchmark.seed = '$Seed';"
                $oracleResult = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:PsqlPrefix + @(
                            "-c", $sessionSettings,
                            "-v", "expected_rows=$Rows",
                            "-f", "/benchmark/verify-trigram-workloads.sql"
                        )
                    )
                $oracleText =
                    $oracleResult.Output -join [Environment]::NewLine

                Write-Utf8File `
                    (Join-Path $candidateDirectory "c1-oracle.txt") `
                    $oracleText

                if ($oracleText -notmatch "verification_result=success") {
                    throw "C1 oracle did not emit its success marker."
                }
            }
            else {
                $createResult = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:PsqlPrefix + @(
                            "-v", "expected_rows=$Rows",
                            "-v", "candidate=$candidate",
                            "-f",
                            "/benchmark/create-trigram-comparison-candidate.sql"
                        )
                    )
                $createText =
                    $createResult.Output -join [Environment]::NewLine
                Write-Utf8File `
                    (Join-Path $candidateDirectory "create.txt") `
                    $createText

                if ($createText -notmatch "create_result=success") {
                    throw "$candidate creation did not emit success."
                }
            }

            $verificationResult = Invoke-DockerCommand `
                -CommandArguments (
                    $script:PsqlPrefix + @(
                        "-v", "expected_rows=$Rows",
                        "-v", "candidate=$candidate",
                        "-f", "/benchmark/verify-trigram-comparison.sql"
                    )
                )
            $verificationText =
                $verificationResult.Output -join [Environment]::NewLine
            Write-Utf8File `
                (Join-Path $candidateDirectory "verification.txt") `
                $verificationText

            if ($verificationText -notmatch "verification_result=success") {
                throw "$candidate verification did not emit success."
            }

            $activeState = Get-SchemaState
            Write-Utf8File `
                (Join-Path $candidateDirectory "state-active.txt") `
                ($activeState.Output -join [Environment]::NewLine)

            if ($candidate -eq "baseline") {
                $pgbenchVersion = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:ComposePrefix + @(
                            "exec", "-T", "postgres",
                            "pgbench", "--version"
                        )
                    )
                $postgresVersion = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:PsqlPrefix + @(
                            "-c",
                            "SELECT version(); SHOW shared_buffers; " +
                            "SHOW work_mem; SHOW effective_cache_size; " +
                            "SHOW jit; SHOW plan_cache_mode;"
                        )
                    )
                $composeState = Invoke-DockerCommand `
                    -CommandArguments ($script:ComposePrefix + @("ps"))

                Write-Utf8File $environmentFile (
                    "powershell_version=$($PSVersionTable.PSVersion)" +
                    [Environment]::NewLine +
                    "host_logical_processors=$([Environment]::ProcessorCount)" +
                    [Environment]::NewLine +
                    "application_path_exercised=false" +
                    [Environment]::NewLine +
                    "dynamic_resource_sampling=false" +
                    [Environment]::NewLine +
                    ($pgbenchVersion.Output -join [Environment]::NewLine) +
                    [Environment]::NewLine +
                    ($postgresVersion.Output -join [Environment]::NewLine) +
                    [Environment]::NewLine +
                    ($composeState.Output -join [Environment]::NewLine)
                )
            }

            $cursorPreflightWorkload = @(
                $repeatedWorkloads | Where-Object {
                    $_.Surface -eq "data_cursor"
                }
            ) | Select-Object -First 1

            if ($null -eq $cursorPreflightWorkload) {
                throw "C3 cursor transport preflight workload was not found."
            }

            Write-Host "Preflight: $candidate / cursor parameter transport"
            Get-CursorAnchor -Workload $cursorPreflightWorkload
            [string[]]$cursorPreflightArguments = @(
                New-PgbenchArguments `
                    -Candidate $candidate `
                    -Workload $cursorPreflightWorkload `
                    -Clients 1 `
                    -TransactionsPerClient 1 `
                    -LogPrefix $null
            )
            $cursorPreflightResult = Invoke-NativeCapture `
                -FilePath "docker" `
                -CommandArguments $cursorPreflightArguments
            Write-Utf8File `
                (Join-Path $candidateDirectory `
                    "cursor-transport-preflight.txt") `
                ($cursorPreflightResult.Output -join `
                    [Environment]::NewLine)

            if ($cursorPreflightResult.ExitCode -ne 0) {
                throw (
                    "Cursor transport preflight failed for ${candidate}: " +
                    ($cursorPreflightResult.Output -join `
                        [Environment]::NewLine)
                )
            }
            Add-Utf8Line $manifestFile (
                "candidate[$candidate].cursor_transport_preflight=success"
            )

            foreach ($workload in $workloads) {
                Write-Host (
                    "Plan: $candidate / $($workload.Name)"
                )

                $hasKeyword = if ($workload.HasKeyword) { 1 } else { 0 }
                $planResult = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:PsqlPrefix + @(
                            "-v", "expected_rows=$Rows",
                            "-v", "candidate=$candidate",
                            "-v", "has_keyword=$hasKeyword",
                            "-v", "term=$($workload.Term)",
                            "-v", "status=$($workload.Status)",
                            "-v", "surface=$($workload.Surface)",
                            "-v", "offset_rows=$($workload.OffsetRows)",
                            "-v", "page_size=$($workload.PageSize)",
                            "-f", "/benchmark/explain-trigram-comparison.sql"
                        )
                    )

                $payload = Get-PlanPayload -Output $planResult.Output

                if ($payload.Candidate -ne $candidate) {
                    throw "Plan candidate mismatch for $($workload.Name)."
                }
                if (
                    $payload.MembershipRows -ne
                    $workload.ExpectedMembership
                ) {
                    throw (
                        "$($workload.Name) expected " +
                        "$($workload.ExpectedMembership) members, found " +
                        "$($payload.MembershipRows)."
                    )
                }

                $planFileName = (
                    "{0:D3}-{1}.json" -f
                    $workload.Number,
                    $workload.Name
                )
                Write-Utf8File `
                    (Join-Path $planDirectory $planFileName) `
                    $payload.JsonText

                $metadata = Get-PlanMetadata -Root $payload.Root
                $candidatePathUsed = $false

                if ($candidate -ne "baseline") {
                    [string[]]$indexes = @(
                        $metadata.IndexNames -split "," |
                        Where-Object {
                            -not [string]::IsNullOrWhiteSpace($_)
                        }
                    )
                    $candidatePathUsed = @(
                        $indexes | Where-Object {
                            $_ -like (
                                "c3_products_*_" +
                                $candidate + "_trgm"
                            )
                        }
                    ).Count -gt 0
                }

                $termHash = Get-StringSha256 $workload.Term
                Add-Utf8Line $planSummaryFile (
                    "$candidate|$($workload.Name)|" +
                    "$($workload.Surface)|$($workload.Status)|" +
                    "$($workload.ExpectedMembership)|" +
                    "$($payload.MembershipRows)|" +
                    "$($payload.Root.'Planning Time')|" +
                    "$($payload.Root.'Execution Time')|" +
                    "$($metadata.ActualRows)|" +
                    "$($metadata.SharedHitBlocks)|" +
                    "$($metadata.SharedReadBlocks)|" +
                    "$($metadata.SharedDirtiedBlocks)|" +
                    "$($metadata.SharedWrittenBlocks)|" +
                    "$($metadata.RowsRemovedByFilter)|" +
                    "$($metadata.NodeTypes)|$($metadata.IndexNames)|" +
                    "$candidatePathUsed|$termHash|" +
                    "$candidate/$planFileName"
                )

                [void]$planRecords.Add([pscustomobject]@{
                    Candidate = $candidate
                    Workload = $workload.Name
                    Surface = $workload.Surface
                    CandidatePathUsed = $candidatePathUsed
                    IndexNames = $metadata.IndexNames
                })
                $candidatePlanCount++
                $overallPlanCount++
            }

            if ($candidatePlanCount -ne $workloads.Count) {
                throw (
                    "Expected $($workloads.Count) $candidate plans, " +
                    "captured $candidatePlanCount."
                )
            }

            foreach ($workload in $repeatedWorkloads) {
                if ($workload.Surface -eq "data_cursor") {
                    Get-CursorAnchor -Workload $workload
                }

                foreach ($clients in $ClientCounts) {
                    $warmupPerClient = $WarmupExecutions / $clients
                    $measuredPerClient = $MeasuredExecutions / $clients

                    for (
                        $round = 1;
                        $round -le $RoundCount;
                        $round++
                    ) {
                        $rawBase = (
                            "{0}-{1:D3}-{2}-c{3}-r{4}" -f
                            $candidate,
                            $workload.Number,
                            $workload.Surface,
                            $clients,
                            $round
                        )

                        Write-Host (
                            "Warm-up: $candidate / " +
                            "$($workload.Name), clients=$clients, " +
                            "round=$round"
                        )
                        [string[]]$warmupArguments = @(
                            New-PgbenchArguments `
                                -Candidate $candidate `
                                -Workload $workload `
                                -Clients $clients `
                                -TransactionsPerClient $warmupPerClient `
                                -LogPrefix $null
                        )
                        $warmupResult = Invoke-NativeCapture `
                            -FilePath "docker" `
                            -CommandArguments $warmupArguments
                        Write-Utf8File `
                            (Join-Path $rawDirectory "$rawBase-warmup.txt") `
                            ($warmupResult.Output -join [Environment]::NewLine)

                        if ($warmupResult.ExitCode -ne 0) {
                            throw (
                                "Warm-up failed for ${rawBase}: " +
                                ($warmupResult.Output -join
                                    [Environment]::NewLine)
                            )
                        }

                        $containerPrefix = (
                            "/tmp/psc3-{0}-{1}-{2:D3}-c{3}-r{4}" -f
                            $timestamp.ToLowerInvariant(),
                            $candidate,
                            $workload.Number,
                            $clients,
                            $round
                        )
                        Remove-ContainerLogs -Prefix $containerPrefix

                        Write-Host (
                            "Measure: $candidate / " +
                            "$($workload.Name), clients=$clients, " +
                            "round=$round"
                        )
                        [string[]]$measuredArguments = @(
                            New-PgbenchArguments `
                                -Candidate $candidate `
                                -Workload $workload `
                                -Clients $clients `
                                -TransactionsPerClient $measuredPerClient `
                                -LogPrefix $containerPrefix
                        )
                        $measuredResult = Invoke-NativeCapture `
                            -FilePath "docker" `
                            -CommandArguments $measuredArguments
                        Write-Utf8File `
                            (Join-Path $rawDirectory "$rawBase-pgbench.txt") `
                            ($measuredResult.Output -join
                                [Environment]::NewLine)

                        $logResult = Invoke-NativeCapture `
                            -FilePath "docker" `
                            -CommandArguments (
                                $script:ComposePrefix + @(
                                    "exec", "-T", "postgres",
                                    "sh", "-lc", "cat $containerPrefix.*"
                                )
                            )
                        Remove-ContainerLogs -Prefix $containerPrefix

                        Write-Utf8File `
                            (Join-Path $rawDirectory `
                                "$rawBase-transactions.txt") `
                            ($logResult.Output -join
                                [Environment]::NewLine)

                        if ($measuredResult.ExitCode -ne 0) {
                            throw (
                                "Measured run failed for ${rawBase}: " +
                                ($measuredResult.Output -join
                                    [Environment]::NewLine)
                            )
                        }
                        if ($logResult.ExitCode -ne 0) {
                            throw "Could not read logs for ${rawBase}."
                        }

                        $metrics = Get-RoundMetrics `
                            -LogLines $logResult.Output `
                            -Clients $clients `
                            -TransactionsPerClient $measuredPerClient `
                            -ExpectedTransactions $MeasuredExecutions

                        $record = [pscustomobject]@{
                            Candidate = $candidate
                            Workload = $workload.Name
                            Surface = $workload.Surface
                            Clients = $clients
                            Round = $round
                            Successes = $metrics.Successes
                            Failures = $metrics.Failures
                            ErrorRatePercent = $metrics.ErrorRatePercent
                            MinimumMs = $metrics.MinimumMs
                            P50Ms = $metrics.P50Ms
                            P95Ms = $metrics.P95Ms
                            P99Ms = $metrics.P99Ms
                            MaximumMs = $metrics.MaximumMs
                            AverageMs = $metrics.AverageMs
                            ThroughputTps = $metrics.ThroughputTps
                            RawBase = "$candidate/raw/$rawBase"
                        }
                        [void]$roundResults.Add($record)

                        Add-Utf8Line $roundsFile (
                            "$($record.Candidate)|$($record.Workload)|" +
                            "$($record.Surface)|$($record.Clients)|" +
                            "$($record.Round)|$($record.Successes)|" +
                            "$($record.Failures)|" +
                            "$(Format-Decimal $record.ErrorRatePercent)|" +
                            "$(Format-Decimal $record.MinimumMs)|" +
                            "$(Format-Decimal $record.P50Ms)|" +
                            "$(Format-Decimal $record.P95Ms)|" +
                            "$(Format-Decimal $record.P99Ms)|" +
                            "$(Format-Decimal $record.MaximumMs)|" +
                            "$(Format-Decimal $record.AverageMs)|" +
                            "$(Format-Decimal $record.ThroughputTps)|" +
                            "$($record.RawBase)"
                        )
                    }
                }
            }
        }
        catch {
            $candidateFailure = $_
        }

        try {
            Invoke-CleanupCapture `
                -OutputPath (Join-Path `
                    $candidateDirectory "cleanup.txt")
            $postCleanupState = Get-SchemaState
            Write-Utf8File `
                (Join-Path $candidateDirectory `
                    "state-after-cleanup.txt") `
                ($postCleanupState.Output -join
                    [Environment]::NewLine)
        }
        catch {
            $candidateCleanupFailure = $_
        }

        if (
            $null -ne $candidateFailure -or
            $null -ne $candidateCleanupFailure
        ) {
            $operationMessage = if ($null -ne $candidateFailure) {
                $candidateFailure.Exception.Message
            }
            else {
                "none"
            }
            $cleanupMessage = if ($null -ne $candidateCleanupFailure) {
                $candidateCleanupFailure.Exception.Message
            }
            else {
                "none"
            }

            throw (
                "C3 candidate $candidate failed. " +
                "operation=[$operationMessage] " +
                "cleanup=[$cleanupMessage]"
            )
        }

        Add-Utf8Line $manifestFile "candidate[$candidate]=success"
        Add-Utf8Line $manifestFile (
            "candidate[$candidate].plans=$candidatePlanCount"
        )
    }

    $summaryRecords =
        New-Object "System.Collections.Generic.List[object]"

    foreach ($group in ($roundResults | Group-Object {
        "$($_.Candidate)|$($_.Workload)|$($_.Surface)|$($_.Clients)"
    })) {
        [object[]]$records = @($group.Group)
        $first = $records[0]
        $summary = [pscustomobject]@{
            Candidate = $first.Candidate
            Workload = $first.Workload
            Surface = $first.Surface
            Clients = $first.Clients
            Rounds = $records.Count
            MedianP50Ms = Get-Median @($records.P50Ms)
            MedianP95Ms = Get-Median @($records.P95Ms)
            MedianP99Ms = Get-Median @($records.P99Ms)
            MedianAverageMs = Get-Median @($records.AverageMs)
            MedianTps = Get-Median @($records.ThroughputTps)
            TotalFailures =
                ($records.Failures | Measure-Object -Sum).Sum
        }
        [void]$summaryRecords.Add($summary)

        Add-Utf8Line $summaryFile (
            "$($summary.Candidate)|$($summary.Workload)|" +
            "$($summary.Surface)|$($summary.Clients)|" +
            "$($summary.Rounds)|" +
            "$(Format-Decimal $summary.MedianP50Ms)|" +
            "$(Format-Decimal $summary.MedianP95Ms)|" +
            "$(Format-Decimal $summary.MedianP99Ms)|" +
            "$(Format-Decimal $summary.MedianAverageMs)|" +
            "$(Format-Decimal $summary.MedianTps)|" +
            "$($summary.TotalFailures)"
        )
    }

    $candidateReview = @{}
    foreach ($candidate in @("gin", "gist")) {
        $candidateReview[$candidate] = [pscustomobject]@{
            IntendedImprovement = $false
            CriticalRegression = $false
            CrediblePath = $false
        }
    }

    foreach ($candidate in @("gin", "gist")) {
        foreach ($workload in $repeatedWorkloads) {
            foreach ($clients in $ClientCounts) {
                [object[]]$baselineRounds = @(
                    $roundResults | Where-Object {
                        $_.Candidate -eq "baseline" -and
                        $_.Workload -eq $workload.Name -and
                        $_.Clients -eq $clients
                    } | Sort-Object Round
                )
                [object[]]$candidateRounds = @(
                    $roundResults | Where-Object {
                        $_.Candidate -eq $candidate -and
                        $_.Workload -eq $workload.Name -and
                        $_.Clients -eq $clients
                    } | Sort-Object Round
                )

                if (
                    $baselineRounds.Count -ne $RoundCount -or
                    $candidateRounds.Count -ne $RoundCount
                ) {
                    throw (
                        "Missing paired rounds for $candidate/" +
                        "$($workload.Name)/clients=$clients."
                    )
                }

                [double[]]$baselineP50 = @($baselineRounds.P50Ms)
                [double[]]$candidateP50 = @($candidateRounds.P50Ms)
                $baselineMedian = Get-Median $baselineP50
                $candidateMedian = Get-Median $candidateP50
                $baselineMad = Get-MedianAbsoluteDeviation $baselineP50

                $noiseBudget = if ($baselineMedian -le 0) {
                    10.0
                }
                else {
                    [Math]::Max(
                        10.0,
                        200.0 * $baselineMad / $baselineMedian
                    )
                }
                $changePercent = if ($baselineMedian -le 0) {
                    0.0
                }
                else {
                    100.0 *
                    ($candidateMedian - $baselineMedian) /
                    $baselineMedian
                }

                [double[]]$pairedChanges = @(
                    for ($index = 0; $index -lt $RoundCount; $index++) {
                        if ($baselineRounds[$index].P50Ms -le 0) {
                            0.0
                        }
                        else {
                            100.0 *
                            ($candidateRounds[$index].P50Ms -
                             $baselineRounds[$index].P50Ms) /
                            $baselineRounds[$index].P50Ms
                        }
                    }
                )
                $allImproved = @(
                    $pairedChanges | Where-Object { $_ -lt 0 }
                ).Count -eq $RoundCount
                $allRegressed = @(
                    $pairedChanges | Where-Object { $_ -gt 0 }
                ).Count -eq $RoundCount
                $outsideBudget =
                    [Math]::Abs($changePercent) -gt $noiseBudget
                $direction = if ($allImproved) {
                    "improvement"
                }
                elseif ($allRegressed) {
                    "regression"
                }
                else {
                    "mixed"
                }

                $plan = @(
                    $planRecords | Where-Object {
                        $_.Candidate -eq $candidate -and
                        $_.Workload -eq $workload.Name
                    }
                ) | Select-Object -First 1
                $pathUsed =
                    $null -ne $plan -and $plan.CandidatePathUsed

                if ($pathUsed) {
                    $candidateReview[$candidate].CrediblePath = $true
                }

                $isIntendedInfix = $workload.Name -match (
                    "sku-infix|rare-name-infix|" +
                    "medium-name-infix|common-name-infix"
                )
                $criticalControl = $workload.Name -match (
                    "exact-sku|percent-wildcard|admin-|" +
                    "deep-offset|deep-cursor|keyword-miss|" +
                    "one-character|two-character|three-character"
                )

                $classification = "inside-noise-or-mixed"
                if (
                    $outsideBudget -and
                    $direction -eq "improvement" -and
                    $pathUsed
                ) {
                    $classification = "credible-improvement"
                    if ($isIntendedInfix) {
                        $candidateReview[$candidate].IntendedImprovement =
                            $true
                    }
                }
                elseif (
                    $outsideBudget -and
                    $direction -eq "regression"
                ) {
                    $classification = "outside-budget-regression"
                    if ($criticalControl -or $isIntendedInfix) {
                        $candidateReview[$candidate].CriticalRegression =
                            $true
                    }
                }
                elseif ($outsideBudget) {
                    $classification = "outside-budget-mixed-direction"
                }

                Add-Utf8Line $comparisonFile (
                    "$candidate|$($workload.Name)|" +
                    "$($workload.Surface)|$clients|" +
                    "$(Format-Decimal $baselineMedian)|" +
                    "$(Format-Decimal $candidateMedian)|" +
                    "$(Format-Decimal $baselineMad)|" +
                    "$(Format-Decimal $noiseBudget)|" +
                    "$(Format-Decimal $changePercent)|$direction|" +
                    "$outsideBudget|$pathUsed|$classification"
                )
            }
        }
    }

    $survivors = New-Object "System.Collections.Generic.List[string]"

    foreach ($candidate in @("gin", "gist")) {
        $review = $candidateReview[$candidate]
        $advances =
            $review.CrediblePath -and
            $review.IntendedImprovement -and
            (-not $review.CriticalRegression)

        if ($advances) {
            [void]$survivors.Add($candidate)
        }

        Add-Utf8Line $survivorFile (
            "candidate=$candidate|credible_path=" +
            "$($review.CrediblePath)|intended_improvement=" +
            "$($review.IntendedImprovement)|critical_regression=" +
            "$($review.CriticalRegression)|c4_review=$advances"
        )
    }

    Add-Utf8Line $survivorFile (
        "status=provisional_harness_evidence_not_c3_closeout"
    )
    Add-Utf8Line $manifestFile "plans=$overallPlanCount"
    Add-Utf8Line $manifestFile "round_results=$($roundResults.Count)"
    Add-Utf8Line $manifestFile (
        "provisional_c4_review_candidates=" +
        $(if ($survivors.Count -eq 0) {
            "none"
        }
        else {
            $survivors -join ","
        })
    )
}
catch {
    $mainFailure = $_
}
finally {
    if ($databaseLifecycleStarted) {
        try {
            Invoke-CleanupCapture `
                -OutputPath (Join-Path `
                    $evidenceDirectory "cleanup-final.txt")
        }
        catch {
            $finalCleanupFailure = $_
        }

        $downResult = Invoke-NativeCapture `
            -FilePath "docker" `
            -CommandArguments (
                $script:ComposePrefix + @(
                    "down", "--volumes", "--remove-orphans"
                )
            )
        $downText = $downResult.Output -join [Environment]::NewLine
        $volumeFile = Join-Path `
            $evidenceDirectory "volume-cleanup.txt"
        Write-Utf8File $volumeFile $downText

        if ($downResult.ExitCode -ne 0) {
            $volumeCleanupFailure = [System.Exception]::new(
                "Docker Compose volume cleanup failed: $downText"
            )
        }
        else {
            $volumeResult = Invoke-NativeCapture `
                -FilePath "docker" `
                -CommandArguments @(
                    "volume", "ls", "--filter",
                    "name=$ExpectedVolumeName", "--format", "{{.Name}}"
                )
            $remaining = @(
                $volumeResult.Output | Where-Object {
                    $_.Trim() -eq $ExpectedVolumeName
                }
            )
            Add-Utf8Line $volumeFile (
                "expected_volume=$ExpectedVolumeName"
            )
            Add-Utf8Line $volumeFile (
                "remaining_exact_volume_count=$($remaining.Count)"
            )

            if (
                $volumeResult.ExitCode -ne 0 -or
                $remaining.Count -ne 0
            ) {
                $volumeCleanupFailure = [System.Exception]::new(
                    "The isolated benchmark volume remains or " +
                    "could not be inspected."
                )
            }
        }
    }

    if ($null -ne $manifestFile) {
        Add-Utf8Line $manifestFile (
            "main_status=" +
            $(if ($null -eq $mainFailure) { "success" } else { "failed" })
        )
        Add-Utf8Line $manifestFile (
            "final_sql_cleanup=" +
            $(if ($null -eq $finalCleanupFailure) {
                "success"
            }
            else {
                "failed"
            })
        )
        Add-Utf8Line $manifestFile (
            "volume_cleanup=" +
            $(if ($null -eq $volumeCleanupFailure) {
                "success"
            }
            else {
                "failed"
            })
        )

        foreach ($failure in @(
            [pscustomobject]@{
                Name = "failure"; Value = $mainFailure
            },
            [pscustomobject]@{
                Name = "final_cleanup_failure";
                Value = $finalCleanupFailure
            },
            [pscustomobject]@{
                Name = "volume_cleanup_failure";
                Value = $volumeCleanupFailure
            }
        )) {
            if ($null -ne $failure.Value) {
                $message = if (
                    $failure.Value -is
                    [System.Management.Automation.ErrorRecord]
                ) {
                    $failure.Value.Exception.Message
                }
                else {
                    $failure.Value.Message
                }
                Add-Utf8Line $manifestFile (
                    "$($failure.Name)=" +
                    $message.Replace("`r", " ").Replace("`n", " ")
                )
            }
        }

        $overallResult = if (
            $null -eq $mainFailure -and
            $null -eq $finalCleanupFailure -and
            $null -eq $volumeCleanupFailure
        ) {
            "success"
        }
        else {
            "failed"
        }
        Add-Utf8Line $manifestFile "result=$overallResult"
    }

    Pop-Location
}

if (
    $null -ne $mainFailure -or
    $null -ne $finalCleanupFailure -or
    $null -ne $volumeCleanupFailure
) {
    $messages = @(
        if ($null -ne $mainFailure) {
            "main=$($mainFailure.Exception.Message)"
        }
        if ($null -ne $finalCleanupFailure) {
            "sql_cleanup=$($finalCleanupFailure.Exception.Message)"
        }
        if ($null -ne $volumeCleanupFailure) {
            "volume_cleanup=$($volumeCleanupFailure.Message)"
        }
    )

    throw (
        "C3 trigram comparison failed: " +
        ($messages -join " | ")
    )
}

Write-Host ""
Write-Host "C3 trigram candidate comparison succeeded."
Write-Host "Rows: $Rows"
Write-Host "Mode: $Mode"
Write-Host "Candidates: baseline,gin,gist"
Write-Host "Plans: $overallPlanCount"
Write-Host "Round results: $($roundResults.Count)"
Write-Host "Evidence: $evidenceDirectory"
Write-Host "No candidate index, extension, or benchmark volume remains."
