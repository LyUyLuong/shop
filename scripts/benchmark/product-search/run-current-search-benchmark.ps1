[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(10000, 100000)]
    [int]$Rows,

    [switch]$AllowDirty,

    [switch]$Smoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$PageSize = 100
$ProjectName = "shop-product-search-benchmark"
$ClientCounts = @(1, 8)
$InvariantCulture =
        [System.Globalization.CultureInfo]::InvariantCulture
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$RepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..\..")
)

$ComposeFile = Join-Path `
    $RepoRoot `
    "docker-compose.search-benchmark.yml"

$BenchmarkSqlFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\benchmark-current-search.sql"

$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\a\raw\latency"

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

    return $Value.ToString(
        "F3",
        $InvariantCulture
    )
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
        ($Percentile / 100.0) *
        $SortedValues.Count
    )

    $index = [Math]::Max(
        0,
        [Math]::Min(
            $SortedValues.Count - 1,
            $rank - 1
        )
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

    [double[]]$sorted = @(
        $Values | Sort-Object
    )

    $middle = [int][Math]::Floor(
        $sorted.Count / 2
    )

    if ($sorted.Count % 2 -eq 1) {
        return $sorted[$middle]
    }

    return (
        $sorted[$middle - 1] +
        $sorted[$middle]
    ) / 2.0
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

            [string[]]$parts = @(
                $trimmed -split "\s+"
            )

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
                FailureType = if ($succeeded) {
                    ""
                }
                else {
                    $timeToken
                }
            }
        }
    )

    if ($records.Count -ne $ExpectedTransactions) {
        throw (
            "Expected $ExpectedTransactions transaction logs, " +
            "found $($records.Count)."
        )
    }

    for ($clientId = 0;
         $clientId -lt $Clients;
         $clientId++) {

        [int[]]$transactionNumbers = @(
            $records |
            Where-Object {
                $_.ClientId -eq $clientId
            } |
            Sort-Object TransactionNumber |
            Select-Object -ExpandProperty TransactionNumber
        )

        if (
            $transactionNumbers.Count -ne
            $TransactionsPerClient
        ) {
            throw (
                "Client $clientId produced " +
                "$($transactionNumbers.Count) transactions; " +
                "expected $TransactionsPerClient."
            )
        }

        for ($number = 1;
             $number -le $TransactionsPerClient;
             $number++) {

            if (
                $transactionNumbers[$number - 1] -ne
                $number
            ) {
                throw (
                    "Client $clientId transaction sequence " +
                    "is incomplete."
                )
            }
        }
    }

    [object[]]$successful = @(
        $records |
        Where-Object Succeeded
    )

    $failures = $records.Count - $successful.Count

    if ($successful.Count -eq 0) {
        throw "No successful measured transactions were found."
    }

    [double[]]$latenciesMs = @(
        $successful |
        ForEach-Object {
            [double]$_.LatencyUs / 1000.0
        } |
        Sort-Object
    )

    [double[]]$startTimes = @(
        $successful |
        ForEach-Object {
            [double]$_.CompletionUs -
            [double]$_.LatencyUs
        }
    )

    [double[]]$completionTimes = @(
        $successful |
        Select-Object -ExpandProperty CompletionUs
    )

    $firstStart = (
        $startTimes |
        Measure-Object -Minimum
    ).Minimum

    $lastCompletion = (
        $completionTimes |
        Measure-Object -Maximum
    ).Maximum

    $measurementWindowUs =
            $lastCompletion - $firstStart

    if ($measurementWindowUs -le 0) {
        throw "Measured transaction window is invalid."
    }

    return [pscustomobject]@{
        Successes = $successful.Count
        Failures = $failures
        ErrorRatePercent = (
            [double]$failures /
            $records.Count
        ) * 100.0
        MinimumMs = $latenciesMs[0]
        P50Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs `
            -Percentile 50
        P95Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs `
            -Percentile 95
        P99Ms = Get-NearestRankPercentile `
            -SortedValues $latenciesMs `
            -Percentile 99
        MaximumMs =
            $latenciesMs[$latenciesMs.Count - 1]
        AverageMs = (
            $latenciesMs |
            Measure-Object -Average
        ).Average
        ThroughputTps = (
            [double]$successful.Count /
            ($measurementWindowUs / 1000000.0)
        )
    }
}

foreach ($commandName in @("git", "docker")) {
    if (
        $null -eq (
            Get-Command `
                $commandName `
                -CommandType Application `
                -ErrorAction SilentlyContinue
        )
    ) {
        throw "Required command was not found: $commandName"
    }
}

foreach ($requiredFile in @(
    $ComposeFile,
    $BenchmarkSqlFile
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file was not found: $requiredFile"
    }
}

$gitBranchResult = Invoke-CheckedNative `
    -FilePath "git" `
    -CommandArguments @(
        "-C", $RepoRoot,
        "branch", "--show-current"
    )

$branch = (
    $gitBranchResult.Output -join ""
).Trim()

if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "Benchmark must run from a branch, not detached HEAD."
}

$gitHeadResult = Invoke-CheckedNative `
    -FilePath "git" `
    -CommandArguments @(
        "-C", $RepoRoot,
        "rev-parse", "HEAD"
    )

$head = (
    $gitHeadResult.Output -join ""
).Trim()

$gitStatusResult = Invoke-CheckedNative `
    -FilePath "git" `
    -CommandArguments @(
        "-C", $RepoRoot,
        "status", "--porcelain=v1"
    )

[string[]]$workingTree = @(
    $gitStatusResult.Output
)

$isDirty = $workingTree.Count -gt 0

if ($isDirty -and (-not $AllowDirty)) {
    throw (
        "Working tree is not clean. Commit the harness first, " +
        "or use -AllowDirty only for pre-commit verification."
    )
}

$workingTreeState = if ($isDirty) {
    "dirty-allowed"
}
else {
    "clean"
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

$guardSql = @"
SELECT concat_ws(
    '|',
    current_database(),
    current_user,
    (SELECT count(*) FROM products),
    (SELECT count(*) FROM products WHERE status = 'ACTIVE'),
    (SELECT count(*) FROM products WHERE status = 'INACTIVE'),
    (
        SELECT count(*)
        FROM products
        WHERE description =
            'V2-PS-A deterministic dataset seed $Seed'
    ),
    (
        SELECT coalesce(max(version::INTEGER), 0)
        FROM flyway_schema_history
        WHERE success
    ),
    (
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ),
    (
        SELECT string_agg(indexname, ',' ORDER BY indexname)
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ),
    (
        SELECT count(*)
        FROM products
        WHERE status = 'ACTIVE'
          AND lower(name) LIKE '%common market%'
    ),
    (
        SELECT CASE
            WHEN last_analyze IS NULL THEN 'false'
            ELSE 'true'
        END
        FROM pg_stat_user_tables
        WHERE schemaname = 'public'
          AND relname = 'products'
    )
);
"@

$guardResult = Invoke-CheckedNative `
    -FilePath "docker" `
    -CommandArguments (
        $PsqlPrefix + @("-c", $guardSql)
    )

[string[]]$guardLines = @(
    $guardResult.Output |
    Where-Object {
        $_ -like "shop_search_benchmark|shop_benchmark|*"
    }
)

if ($guardLines.Count -ne 1) {
    throw "Could not identify the benchmark database guard result."
}

$guardParts = $guardLines[0].Split([char]"|")

if ($guardParts.Count -ne 11) {
    throw "Database guard returned an unexpected field count."
}

$actualRows = [long]$guardParts[2]
$activeRows = [long]$guardParts[3]
$inactiveRows = [long]$guardParts[4]
$markerRows = [long]$guardParts[5]
$flywayVersion = [int]$guardParts[6]
$trigramExtensions = [int]$guardParts[7]
$actualIndexes = $guardParts[8]
$commonActiveRows = [long]$guardParts[9]
$analyzed = $guardParts[10]

$expectedIndexes = (
    "idx_products_created_at," +
    "idx_products_name_lower," +
    "idx_products_sku_lower," +
    "idx_products_status," +
    "products_pkey"
)

if ($actualRows -ne $Rows) {
    throw "Expected $Rows products, found $actualRows."
}

if (
    $activeRows -ne [long]($Rows * 4 / 5) -or
    $inactiveRows -ne [long]($Rows / 5)
) {
    throw "ACTIVE/INACTIVE distribution is invalid."
}

if ($markerRows -ne $Rows) {
    throw "Dataset seed marker does not cover every product."
}

if ($flywayVersion -ne 12) {
    throw "Expected Flyway version 12, found $flywayVersion."
}

if ($trigramExtensions -ne 0) {
    throw "pg_trgm must not exist during PS-A."
}

if ($actualIndexes -ne $expectedIndexes) {
    throw "Product index inventory changed: $actualIndexes"
}

if ($commonActiveRows -le 0 -or $analyzed -ne "true") {
    throw "Required distribution/statistics are missing."
}

$publicDeepOffset = [int](
    [Math]::Floor(
        ($activeRows * 0.90) / $PageSize
    ) * $PageSize
)

$commonDeepOffset = [int](
    [Math]::Floor(
        ($commonActiveRows * 0.90) / $PageSize
    ) * $PageSize
)

$vietnamesePattern =
        "%" +
        ([string][char]0x0111) +
        "i" +
        ([string][char]0x1EC7) +
        "n tho" +
        ([string][char]0x1EA1) +
        "i%"

$cases = @(
    [pscustomobject]@{ Name = "public-active-first"; Keyword = $null; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "public-active-deep"; Keyword = $null; Status = "ACTIVE"; Offset = $publicDeepOffset; Count = $false }
    [pscustomobject]@{ Name = "exact-looking-sku"; Keyword = "%delta-20260806-00000007-green%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "prefix-sku"; Keyword = "%alpha-20260806%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "suffix-sku"; Keyword = "%-red%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "contains-sku"; Keyword = "%20260806-00000007%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "keyword-miss"; Keyword = "%psa-no-match-20260806%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "rare-name"; Keyword = "%rare orchid%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "medium-name"; Keyword = "%medium cedar%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "common-name"; Keyword = "%common market%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "common-name-deep"; Keyword = "%common market%"; Status = "ACTIVE"; Offset = $commonDeepOffset; Count = $false }
    [pscustomobject]@{ Name = "mixed-case-normalized"; Keyword = "%mixed case%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "vietnamese-accented"; Keyword = $vietnamesePattern; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "vietnamese-unaccented"; Keyword = "%dien thoai%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "wildcard-percent"; Keyword = "%%%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "wildcard-underscore"; Keyword = "%_%"; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "admin-all"; Keyword = $null; Status = $null; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "admin-keyword-all-statuses"; Keyword = "%medium cedar%"; Status = $null; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "admin-active"; Keyword = $null; Status = "ACTIVE"; Offset = 0; Count = $true }
    [pscustomobject]@{ Name = "admin-inactive"; Keyword = $null; Status = "INACTIVE"; Offset = 0; Count = $true }
)

$workloadNumber = 0

[object[]]$workloads = @(
    foreach ($case in $cases) {
        foreach ($queryType in @("data", "count")) {
            if (
                $queryType -eq "count" -and
                (-not $case.Count)
            ) {
                continue
            }

            $workloadNumber++

            [pscustomobject]@{
                Number = $workloadNumber
                CaseName = $case.Name
                QueryType = $queryType
                Keyword = $case.Keyword
                Status = $case.Status
                Offset = $case.Offset
            }
        }
    }
)

if ($Smoke) {
    [object[]]$workloads = @(
        $workloads |
        Where-Object {
            $_.CaseName -eq "public-active-first"
        }
    )
}

foreach ($clients in $ClientCounts) {
    if (
        $WarmupExecutions % $clients -ne 0 -or
        $MeasuredExecutions % $clients -ne 0
    ) {
        throw "Execution counts must be divisible by clients=$clients."
    }
}

$timestamp = [DateTime]::UtcNow.ToString(
    "yyyyMMddTHHmmssZ"
)

$directoryPrefix = if ($Smoke) {
    "smoke"
}
else {
    "latency"
}

$evidenceDirectory = Join-Path `
    $EvidenceRoot `
    "$directoryPrefix-$Rows-$timestamp"

New-Item `
    -ItemType Directory `
    -Path $evidenceDirectory `
    -Force |
    Out-Null

$manifestFile = Join-Path $evidenceDirectory "manifest.txt"
$environmentFile = Join-Path $evidenceDirectory "environment.txt"
$roundsFile = Join-Path $evidenceDirectory "rounds.txt"
$summaryFile = Join-Path $evidenceDirectory "summary.txt"

$manifestLines = @(
    "work_package=V2-PS-A4"
    "generated_at_utc=$timestamp"
    "mode=$Mode"
    "branch=$branch"
    "head=$head"
    "working_tree=$workingTreeState"
    "rows=$Rows"
    "seed=$Seed"
    "page_size=$PageSize"
    "public_deep_offset=$publicDeepOffset"
    "common_deep_offset=$commonDeepOffset"
    "clients=1,8"
    "warmup_executions_per_case=$WarmupExecutions"
    "measured_executions_per_case=$MeasuredExecutions"
    "rounds=$RoundCount"
    "protocol=prepared"
    "load_model=closed_loop_unthrottled"
    "percentile_method=nearest-rank"
    "round_aggregation=median"
    "cache_state=warm"
    "cold_cache_observation=not_claimed"
    "query_scope=direct-postgresql"
    "hikari_saturation=not_applicable"
    "dynamic_cpu_memory=not_captured"
    "sql_sha256=$((Get-FileHash $BenchmarkSqlFile -Algorithm SHA256).Hash)"
    "runner_sha256=$((Get-FileHash $PSCommandPath -Algorithm SHA256).Hash)"
)

if ($isDirty) {
    $manifestLines += "working_tree_entries:"

    foreach ($entry in $workingTree) {
        $manifestLines += $entry
    }
}

Write-Utf8File `
    -Path $manifestFile `
    -Value (
        ($manifestLines -join [Environment]::NewLine) +
        [Environment]::NewLine
    )

$environmentSql = @"
SELECT 'server_version=' || current_setting('server_version');
SELECT 'server_encoding=' || current_setting('server_encoding');
SELECT 'shared_buffers=' || current_setting('shared_buffers')
    || ',work_mem=' || current_setting('work_mem')
    || ',effective_cache_size=' || current_setting('effective_cache_size')
    || ',max_connections=' || current_setting('max_connections');
SELECT 'plan_cache_mode=' || current_setting('plan_cache_mode')
    || ',jit=' || current_setting('jit')
    || ',track_io_timing=' || current_setting('track_io_timing');
SELECT 'table_size=' || pg_size_pretty(pg_relation_size('products'))
    || ',indexes_size=' || pg_size_pretty(pg_indexes_size('products'))
    || ',total_size=' || pg_size_pretty(pg_total_relation_size('products'));
SELECT 'index=' || indexrelname
    || ',size=' || pg_size_pretty(pg_relation_size(indexrelid))
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND relname = 'products'
ORDER BY indexrelname;
"@

$environmentResult = Invoke-CheckedNative `
    -FilePath "docker" `
    -CommandArguments (
        $PsqlPrefix + @("-c", $environmentSql)
    )

$pgbenchVersion = Invoke-CheckedNative `
    -FilePath "docker" `
    -CommandArguments (
        $ComposePrefix + @(
            "exec", "-T",
            "postgres",
            "pgbench", "--version"
        )
    )

$containerResources = Invoke-CheckedNative `
    -FilePath "docker" `
    -CommandArguments (
        $ComposePrefix + @(
            "exec", "-T",
            "postgres",
            "sh", "-lc",
            "printf 'container_cpus='; nproc; grep '^MemTotal:' /proc/meminfo"
        )
    )

$composeStatus = Invoke-CheckedNative `
    -FilePath "docker" `
    -CommandArguments (
        $ComposePrefix + @("ps")
    )

$environmentLines = @(
    "powershell_version=$($PSVersionTable.PSVersion)"
    "host_logical_processors=$([Environment]::ProcessorCount)"
    "dynamic_resource_sampling=not_captured"
    "application_and_hikari_path=not_exercised"
    ""
    $pgbenchVersion.Output
    ""
    $containerResources.Output
    ""
    $environmentResult.Output
    ""
    $composeStatus.Output
)

Write-Utf8File `
    -Path $environmentFile `
    -Value (
        $environmentLines -join [Environment]::NewLine
    )

Write-Utf8File `
    -Path $roundsFile `
    -Value (
        "case|query_type|clients|round|successes|failures|" +
        "error_rate_percent|min_ms|p50_ms|p95_ms|p99_ms|" +
        "max_ms|average_ms|throughput_tps|raw_base" +
        [Environment]::NewLine
    )

Write-Utf8File `
    -Path $summaryFile `
    -Value (
        "case|query_type|clients|rounds|measured_per_round|" +
        "median_p50_ms|median_p95_ms|median_p99_ms|" +
        "median_average_ms|median_throughput_tps|" +
        "total_failures|error_rate_percent" +
        [Environment]::NewLine
    )

function New-PgbenchArguments {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Workload,

        [Parameter(Mandatory = $true)]
        [int]$Clients,

        [Parameter(Mandatory = $true)]
        [int]$TransactionsPerClient,

        [AllowNull()]
        [string]$LogPrefix
    )

    $isCount = if ($Workload.QueryType -eq "count") {
        1
    }
    else {
        0
    }

    $hasKeyword = if ($null -ne $Workload.Keyword) {
        1
    }
    else {
        0
    }

    $hasStatus = if ($null -ne $Workload.Status) {
        1
    }
    else {
        0
    }

    $keyword = if ($null -eq $Workload.Keyword) {
        "%__psa_unused_keyword__%"
    }
    else {
        [string]$Workload.Keyword
    }

    $status = if ($null -eq $Workload.Status) {
        "ACTIVE"
    }
    else {
        [string]$Workload.Status
    }

    [string[]]$pgbenchArguments = @(
        "exec", "-T",
        "-e",
        "PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=30000",
        "-e",
        "PGAPPNAME=shop-v2-ps-a4",
        "postgres",
        "pgbench",
        "-n",
        "-M", "prepared",
        "-c", "$Clients",
        "-j", "$Clients",
        "-t", "$TransactionsPerClient",
        "-r",
        "--failures-detailed",
        "--verbose-errors"
    )

    if (-not [string]::IsNullOrWhiteSpace($LogPrefix)) {
        $pgbenchArguments += @(
            "-l",
            "--log-prefix=$LogPrefix"
        )
    }

    $pgbenchArguments += @(
        "-D", "is_count=$isCount",
        "-D", "has_keyword=$hasKeyword",
        "-D", "has_status=$hasStatus",
        "-D", "keyword_pattern=$keyword",
        "-D", "status=$status",
        "-D", "offset_rows=$($Workload.Offset)",
        "-D", "page_size=$PageSize",
        "-U", "shop_benchmark",
        "-f", "/benchmark/benchmark-current-search.sql",
        "shop_search_benchmark"
    )

    return $ComposePrefix + $pgbenchArguments
}

function Remove-ContainerLogs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prefix
    )

    if ($Prefix -notmatch "^/tmp/psa4-[A-Za-z0-9-]+$") {
        throw "Unsafe container log prefix: $Prefix"
    }

    [void](
        Invoke-CheckedNative `
            -FilePath "docker" `
            -CommandArguments (
                $ComposePrefix + @(
                    "exec", "-T",
                    "postgres",
                    "sh", "-lc",
                    "rm -f $Prefix.*"
                )
            )
    )
}

$roundResults =
        New-Object "System.Collections.Generic.List[object]"

try {
    foreach ($workload in $workloads) {
        foreach ($clients in $ClientCounts) {
            $warmupPerClient =
                    $WarmupExecutions / $clients

            $measuredPerClient =
                    $MeasuredExecutions / $clients

            for ($round = 1;
                 $round -le $RoundCount;
                 $round++) {

                $rawBase = (
                    "{0:D3}-{1}-{2}-c{3}-r{4}" -f
                    $workload.Number,
                    $workload.CaseName,
                    $workload.QueryType,
                    $clients,
                    $round
                )

                Write-Host (
                    "Warm-up: $($workload.CaseName) " +
                    "[$($workload.QueryType)], " +
                    "clients=$clients, round=$round"
                )

                [string[]]$warmupArguments = @(
                    New-PgbenchArguments `
                        -Workload $workload `
                        -Clients $clients `
                        -TransactionsPerClient $warmupPerClient `
                        -LogPrefix $null
                )

                $warmupResult = Invoke-NativeCapture `
                    -FilePath "docker" `
                    -CommandArguments $warmupArguments

                Write-Utf8File `
                    -Path (
                        Join-Path `
                            $evidenceDirectory `
                            "$rawBase-warmup.txt"
                    ) `
                    -Value (
                        $warmupResult.Output -join
                        [Environment]::NewLine
                    )

                if ($warmupResult.ExitCode -ne 0) {
                    throw (
                        "Warm-up failed for ${rawBase}: " +
                        ($warmupResult.Output -join
                            [Environment]::NewLine)
                    )
                }

                $containerPrefix = (
                    "/tmp/psa4-{0}-{1:D3}-{2}-c{3}-r{4}" -f
                    $timestamp.ToLowerInvariant(),
                    $workload.Number,
                    $workload.QueryType,
                    $clients,
                    $round
                )

                Remove-ContainerLogs -Prefix $containerPrefix

                Write-Host (
                    "Measure: $($workload.CaseName) " +
                    "[$($workload.QueryType)], " +
                    "clients=$clients, round=$round"
                )

                [string[]]$measuredArguments = @(
                    New-PgbenchArguments `
                        -Workload $workload `
                        -Clients $clients `
                        -TransactionsPerClient $measuredPerClient `
                        -LogPrefix $containerPrefix
                )

                $measuredResult = Invoke-NativeCapture `
                    -FilePath "docker" `
                    -CommandArguments $measuredArguments

                Write-Utf8File `
                    -Path (
                        Join-Path `
                            $evidenceDirectory `
                            "$rawBase-pgbench.txt"
                    ) `
                    -Value (
                        $measuredResult.Output -join
                        [Environment]::NewLine
                    )

                $logResult = Invoke-NativeCapture `
                    -FilePath "docker" `
                    -CommandArguments (
                        $ComposePrefix + @(
                            "exec", "-T",
                            "postgres",
                            "sh", "-lc",
                            "cat $containerPrefix.*"
                        )
                    )

                Remove-ContainerLogs -Prefix $containerPrefix

                Write-Utf8File `
                    -Path (
                        Join-Path `
                            $evidenceDirectory `
                            "$rawBase-transactions.txt"
                    ) `
                    -Value (
                        $logResult.Output -join
                        [Environment]::NewLine
                    )

                if ($measuredResult.ExitCode -ne 0) {
                    throw (
                        "Measured pgbench run failed for ${rawBase}: " +
                        ($measuredResult.Output -join
                            [Environment]::NewLine)
                    )
                }

                if ($logResult.ExitCode -ne 0) {
                    throw (
                        "Could not read transaction logs for ${rawBase}: " +
                        ($logResult.Output -join
                            [Environment]::NewLine)
                    )
                }

                $metrics = Get-RoundMetrics `
                    -LogLines $logResult.Output `
                    -Clients $clients `
                    -TransactionsPerClient $measuredPerClient `
                    -ExpectedTransactions $MeasuredExecutions

                $result = [pscustomobject]@{
                    CaseName = $workload.CaseName
                    QueryType = $workload.QueryType
                    Clients = $clients
                    Round = $round
                    Successes = $metrics.Successes
                    Failures = $metrics.Failures
                    ErrorRatePercent =
                            $metrics.ErrorRatePercent
                    MinimumMs = $metrics.MinimumMs
                    P50Ms = $metrics.P50Ms
                    P95Ms = $metrics.P95Ms
                    P99Ms = $metrics.P99Ms
                    MaximumMs = $metrics.MaximumMs
                    AverageMs = $metrics.AverageMs
                    ThroughputTps = $metrics.ThroughputTps
                    RawBase = $rawBase
                }

                [void]$roundResults.Add($result)

                Add-Utf8Line `
                    -Path $roundsFile `
                    -Value (
                        @(
                            $result.CaseName
                            $result.QueryType
                            $result.Clients
                            $result.Round
                            $result.Successes
                            $result.Failures
                            (Format-Decimal $result.ErrorRatePercent)
                            (Format-Decimal $result.MinimumMs)
                            (Format-Decimal $result.P50Ms)
                            (Format-Decimal $result.P95Ms)
                            (Format-Decimal $result.P99Ms)
                            (Format-Decimal $result.MaximumMs)
                            (Format-Decimal $result.AverageMs)
                            (Format-Decimal $result.ThroughputTps)
                            $result.RawBase
                        ) -join "|"
                    )
            }
        }
    }

    $expectedRoundResults =
            $workloads.Count *
            $ClientCounts.Count *
            $RoundCount

    if ($roundResults.Count -ne $expectedRoundResults) {
        throw (
            "Expected $expectedRoundResults round results, " +
            "found $($roundResults.Count)."
        )
    }

    foreach ($workload in $workloads) {
        foreach ($clients in $ClientCounts) {
            [object[]]$selectedRounds = @(
                $roundResults |
                Where-Object {
                    $_.CaseName -eq $workload.CaseName -and
                    $_.QueryType -eq $workload.QueryType -and
                    $_.Clients -eq $clients
                }
            )

            if ($selectedRounds.Count -ne $RoundCount) {
                throw "Missing rounds for $($workload.CaseName)."
            }

            $totalFailures = (
                $selectedRounds |
                Measure-Object -Property Failures -Sum
            ).Sum

            $totalTransactions =
                    $MeasuredExecutions *
                    $RoundCount

            $errorRate = (
                [double]$totalFailures /
                $totalTransactions
            ) * 100.0

            Add-Utf8Line `
                -Path $summaryFile `
                -Value (
                    @(
                        $workload.CaseName
                        $workload.QueryType
                        $clients
                        $RoundCount
                        $MeasuredExecutions
                        (
                            Format-Decimal (
                                Get-Median (
                                    [double[]]$selectedRounds.P50Ms
                                )
                            )
                        )
                        (
                            Format-Decimal (
                                Get-Median (
                                    [double[]]$selectedRounds.P95Ms
                                )
                            )
                        )
                        (
                            Format-Decimal (
                                Get-Median (
                                    [double[]]$selectedRounds.P99Ms
                                )
                            )
                        )
                        (
                            Format-Decimal (
                                Get-Median (
                                    [double[]]$selectedRounds.AverageMs
                                )
                            )
                        )
                        (
                            Format-Decimal (
                                Get-Median (
                                    [double[]]$selectedRounds.ThroughputTps
                                )
                            )
                        )
                        $totalFailures
                        (Format-Decimal $errorRate)
                    ) -join "|"
                )
        }
    }

    Add-Utf8Line `
        -Path $manifestFile `
        -Value "result=success"

    Write-Host ""
    Write-Host "Current-search benchmark succeeded."
    Write-Host "Mode: $Mode"
    Write-Host "Rows: $Rows"
    Write-Host "Workloads: $($workloads.Count)"
    Write-Host "Round results: $($roundResults.Count)"
    Write-Host "Evidence: $evidenceDirectory"
}
catch {
    $failure = $_.Exception.Message.
        Replace("`r", " ").
        Replace("`n", " ")

    Add-Utf8Line `
        -Path $manifestFile `
        -Value "result=failed"

    Add-Utf8Line `
        -Path $manifestFile `
        -Value "failure=$failure"

    throw
}
