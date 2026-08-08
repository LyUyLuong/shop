[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(10000, 100000)]
    [int]$Rows,

    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$PageSize = 100
$ProjectName = "shop-product-search-benchmark"

$RepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..\..")
)

$ComposeFile = Join-Path `
    $RepoRoot `
    "docker-compose.search-benchmark.yml"

$PlanSqlFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-current-search.sql"

$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\a\raw\plans"

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

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

function Get-PlanPayload {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Output
    )

    $combined = (
        $Output -join [Environment]::NewLine
    ).Trim()

    $start = $combined.IndexOf("[")
    $end = $combined.LastIndexOf("]")

    if ($start -lt 0 -or $end -le $start) {
        throw (
            "EXPLAIN output did not contain a JSON array: " +
            $combined
        )
    }

    $jsonText = $combined.Substring(
        $start,
        $end - $start + 1
    )

    try {
        $parsed = ConvertFrom-Json -InputObject $jsonText
    }
    catch {
        throw (
            "EXPLAIN output was not valid JSON: " +
            $_.Exception.Message
        )
    }

    $root = if ($parsed -is [System.Array]) {
        $parsed[0]
    }
    else {
        $parsed
    }

    if ($null -eq $root.PSObject.Properties["Plan"]) {
        throw "EXPLAIN JSON did not contain a Plan object."
    }

    if ($null -eq $root.PSObject.Properties["Execution Time"]) {
        throw "EXPLAIN JSON did not contain Execution Time."
    }

    return [pscustomobject]@{
        JsonText = $jsonText
        Root     = $root
    }
}

function Get-PlanMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Root
    )

    $nodeTypes = New-Object `
        "System.Collections.Generic.List[string]"

    $indexNames = New-Object `
        "System.Collections.Generic.List[string]"

    $rowsRemoved = 0.0
    $stack = New-Object System.Collections.Stack
    $stack.Push($Root.Plan)

    while ($stack.Count -gt 0) {
        $node = $stack.Pop()

        $nodeType = Get-OptionalProperty `
            -InputObject $node `
            -Name "Node Type"

        if ($null -ne $nodeType) {
            [void]$nodeTypes.Add([string]$nodeType)
        }

        $indexName = Get-OptionalProperty `
            -InputObject $node `
            -Name "Index Name"

        if ($null -ne $indexName) {
            [void]$indexNames.Add([string]$indexName)
        }

        $removed = Get-OptionalProperty `
            -InputObject $node `
            -Name "Rows Removed by Filter" `
            -DefaultValue 0

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

    return [pscustomobject]@{
        NodeTypes = (
            $nodeTypes |
            Sort-Object -Unique
        ) -join ","

        IndexNames = (
            $indexNames |
            Sort-Object -Unique
        ) -join ","

        RowsRemovedByFilter = $rowsRemoved
    }
}

foreach ($requiredCommand in @("git", "docker")) {
    $command = Get-Command `
        $requiredCommand `
        -CommandType Application `
        -ErrorAction SilentlyContinue

    if ($null -eq $command) {
        throw "Required command was not found: $requiredCommand"
    }
}

foreach ($requiredFile in @($ComposeFile, $PlanSqlFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file was not found: $requiredFile"
    }
}

$branch = (
    @(
        Invoke-GitCommand -CommandArguments @(
            "branch",
            "--show-current"
        )
    ) -join ""
).Trim()

if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "Plan capture must run from a branch, not detached HEAD."
}

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

$isDirty = $workingTree.Count -gt 0

if ($isDirty -and (-not $AllowDirty)) {
    throw (
        "Working tree is not clean. Commit the harness first, " +
        "or use -AllowDirty only for the pre-commit verification."
    )
}

$workingTreeState = if ($isDirty) {
    "dirty-allowed"
}
else {
    "clean"
}

$composePrefix = @(
    "compose",
    "--project-name",
    $ProjectName,
    "--file",
    $ComposeFile
)

$script:PsqlPrefix = $composePrefix + @(
    "exec",
    "-T",
    "postgres",
    "psql",
    "-X",
    "-q",
    "-A",
    "-t",
    "-P",
    "pager=off",
    "-v",
    "ON_ERROR_STOP=1"
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

$guardResult = Invoke-DockerCommand `
    -CommandArguments (
        $script:PsqlPrefix + @(
            "-U",
            "shop_benchmark",
            "-d",
            "shop_search_benchmark",
            "-c",
            $guardSql
        )
    )

[string[]]$guardLines = @(
    $guardResult.Output |
    Where-Object {
        $_ -like "shop_search_benchmark|shop_benchmark|*"
    }
)

if ($guardLines.Count -ne 1) {
    throw (
        "Could not identify the database guard result: " +
        ($guardResult.Output -join [Environment]::NewLine)
    )
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

$expectedActiveRows = [long]($Rows * 4 / 5)
$expectedInactiveRows = [long]($Rows / 5)

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

if ($activeRows -ne $expectedActiveRows) {
    throw (
        "Expected $expectedActiveRows ACTIVE rows, " +
        "found $activeRows."
    )
}

if ($inactiveRows -ne $expectedInactiveRows) {
    throw (
        "Expected $expectedInactiveRows INACTIVE rows, " +
        "found $inactiveRows."
    )
}

if ($markerRows -ne $Rows) {
    throw (
        "Dataset marker matched $markerRows rows, " +
        "expected $Rows."
    )
}

if ($flywayVersion -ne 12) {
    throw "Expected Flyway version 12, found $flywayVersion."
}

if ($trigramExtensions -ne 0) {
    throw "pg_trgm must not exist during the current-query baseline."
}

if ($actualIndexes -ne $expectedIndexes) {
    throw (
        "Unexpected product index inventory: " +
        $actualIndexes
    )
}

if ($commonActiveRows -le 0) {
    throw "Common-match ACTIVE distribution was not found."
}

if ($analyzed -ne "true") {
    throw "products statistics are missing; run ANALYZE first."
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

$cases = @(
    [pscustomobject]@{
        Name = "public-active-first"
        KeywordPattern = $null
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "public-active-deep"
        KeywordPattern = $null
        Status = "ACTIVE"
        Offset = $publicDeepOffset
        IncludeCount = $false
    },
    [pscustomobject]@{
        Name = "exact-looking-sku"
        KeywordPattern =
            "%delta-20260806-00000007-green%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "prefix-sku"
        KeywordPattern = "%alpha-20260806%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "suffix-sku"
        KeywordPattern = "%-red%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "contains-sku"
        KeywordPattern = "%20260806-00000007%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "keyword-miss"
        KeywordPattern = "%psa-no-match-20260806%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "rare-name"
        KeywordPattern = "%rare orchid%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "medium-name"
        KeywordPattern = "%medium cedar%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "common-name"
        KeywordPattern = "%common market%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "common-name-deep"
        KeywordPattern = "%common market%"
        Status = "ACTIVE"
        Offset = $commonDeepOffset
        IncludeCount = $false
    },
    [pscustomobject]@{
        Name = "wildcard-percent"
        KeywordPattern = "%%%"
        Status = "ACTIVE"
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "admin-all"
        KeywordPattern = $null
        Status = $null
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "admin-keyword-all-statuses"
        KeywordPattern = "%medium cedar%"
        Status = $null
        Offset = 0
        IncludeCount = $true
    },
    [pscustomobject]@{
        Name = "admin-inactive"
        KeywordPattern = $null
        Status = "INACTIVE"
        Offset = 0
        IncludeCount = $true
    }
)

$countCases = @(
    $cases |
    Where-Object {
        $_.IncludeCount
    }
).Count

$expectedPlanFiles = $cases.Count + $countCases

$timestamp = [DateTime]::UtcNow.ToString(
    "yyyyMMddTHHmmssZ"
)

$evidenceDirectory = Join-Path `
    $EvidenceRoot `
    "plans-$Rows-$timestamp"

New-Item `
    -ItemType Directory `
    -Path $evidenceDirectory `
    -Force |
    Out-Null

$manifestFile = Join-Path `
    $evidenceDirectory `
    "manifest.txt"

$environmentFile = Join-Path `
    $evidenceDirectory `
    "environment.txt"

$summaryFile = Join-Path `
    $evidenceDirectory `
    "summary.txt"

$manifestLines = @(
    "work_package=V2-PS-A3"
    "generated_at_utc=$timestamp"
    "branch=$branch"
    "head=$head"
    "working_tree=$workingTreeState"
    "rows=$Rows"
    "seed=$Seed"
    "page_size=$PageSize"
    "public_deep_offset=$publicDeepOffset"
    "common_deep_offset=$commonDeepOffset"
    "plan_cases=$($cases.Count)"
    "expected_plan_files=$expectedPlanFiles"
    "warmup_executions_per_plan=1"
    "retained_executions_per_plan=1"
    "cache_state=warm"
    "cold_cache_observation=not_claimed"
    "plan_mode=value-specific"
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
        $manifestLines -join [Environment]::NewLine
    )

$environmentSql = @"
SELECT 'server_version='
    || current_setting('server_version');
SELECT 'server_encoding='
    || current_setting('server_encoding');
SELECT 'collation='
    || datcollate
    || ',ctype='
    || datctype
FROM pg_database
WHERE datname = current_database();
SELECT 'shared_buffers='
    || current_setting('shared_buffers')
    || ',work_mem='
    || current_setting('work_mem')
    || ',effective_cache_size='
    || current_setting('effective_cache_size')
    || ',random_page_cost='
    || current_setting('random_page_cost');
SELECT 'plan_cache_mode='
    || current_setting('plan_cache_mode')
    || ',track_io_timing='
    || current_setting('track_io_timing')
    || ',jit='
    || current_setting('jit')
    || ',max_parallel_workers_per_gather='
    || current_setting('max_parallel_workers_per_gather');
SELECT 'table_size='
    || pg_size_pretty(pg_relation_size('products'))
    || ',indexes_size='
    || pg_size_pretty(pg_indexes_size('products'))
    || ',total_size='
    || pg_size_pretty(pg_total_relation_size('products'));
SELECT 'index='
    || indexrelname
    || ',scans='
    || idx_scan
    || ',size='
    || pg_size_pretty(pg_relation_size(indexrelid))
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND relname = 'products'
ORDER BY indexrelname;
SELECT 'last_analyze='
    || last_analyze
FROM pg_stat_user_tables
WHERE schemaname = 'public'
  AND relname = 'products';
"@

$environmentResult = Invoke-DockerCommand `
    -CommandArguments (
        $script:PsqlPrefix + @(
            "-U",
            "shop_benchmark",
            "-d",
            "shop_search_benchmark",
            "-c",
            $environmentSql
        )
    )

$composeStatus = Invoke-DockerCommand `
    -CommandArguments (
        $composePrefix + @("ps")
    )

$environmentLines = @(
    $guardLines[0]
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
    -Path $summaryFile `
    -Value (
        (
            "file|case|query_type|offset|keyword_pattern|" +
            "status|top_node|node_types|indexes_used|" +
            "plan_rows|actual_rows|rows_removed_by_filter|" +
            "shared_hit_blocks|shared_read_blocks|" +
            "planning_ms|execution_ms"
        ) + [Environment]::NewLine
    )

function Invoke-CurrentSearchPlan {
    param(
        [Parameter(Mandatory = $true)]
        [object]$PlanCase,

        [Parameter(Mandatory = $true)]
        [ValidateSet("data", "count")]
        [string]$QueryType
    )

    $hasKeyword = if (
        $null -ne $PlanCase.KeywordPattern
    ) {
        "true"
    }
    else {
        "false"
    }

    $hasStatus = if (
        $null -ne $PlanCase.Status
    ) {
        "true"
    }
    else {
        "false"
    }

    $isCount = if ($QueryType -eq "count") {
        "true"
    }
    else {
        "false"
    }

    $keywordPattern = if (
        $null -eq $PlanCase.KeywordPattern
    ) {
        ""
    }
    else {
        [string]$PlanCase.KeywordPattern
    }

    $status = if ($null -eq $PlanCase.Status) {
        ""
    }
    else {
        [string]$PlanCase.Status
    }

    $result = Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-v",
                "is_count=$isCount",
                "-v",
                "has_keyword=$hasKeyword",
                "-v",
                "has_status=$hasStatus",
                "-v",
                "keyword_pattern=$keywordPattern",
                "-v",
                "status=$status",
                "-v",
                "offset_rows=$($PlanCase.Offset)",
                "-v",
                "page_size=$PageSize",
                "-U",
                "shop_benchmark",
                "-d",
                "shop_search_benchmark",
                "-f",
                "/benchmark/explain-current-search.sql"
            )
        )

    return Get-PlanPayload -Output $result.Output
}

$fileNumber = 0

foreach ($planCase in $cases) {
    $queryTypes = @("data")

    if ($planCase.IncludeCount) {
        $queryTypes += "count"
    }

    foreach ($queryType in $queryTypes) {
        Write-Host (
            "Warm-up: " +
            "$($planCase.Name) [$queryType]"
        )

        [void](
            Invoke-CurrentSearchPlan `
                -PlanCase $planCase `
                -QueryType $queryType
        )

        Write-Host (
            "Capture: " +
            "$($planCase.Name) [$queryType]"
        )

        $payload = Invoke-CurrentSearchPlan `
            -PlanCase $planCase `
            -QueryType $queryType

        $fileNumber++

        $fileName = (
            "{0:D3}-{1}-{2}.json" -f
            $fileNumber,
            $planCase.Name,
            $queryType
        )

        $planFile = Join-Path `
            $evidenceDirectory `
            $fileName

        Write-Utf8File `
            -Path $planFile `
            -Value $payload.JsonText

        $metadata = Get-PlanMetadata `
            -Root $payload.Root

        $topPlan = $payload.Root.Plan

        $keywordSummary = if (
            $null -eq $planCase.KeywordPattern
        ) {
            "<none>"
        }
        else {
            [string]$planCase.KeywordPattern
        }

        $statusSummary = if (
            $null -eq $planCase.Status
        ) {
            "<none>"
        }
        else {
            [string]$planCase.Status
        }

        $indexesUsedSummary = if (
            [string]::IsNullOrWhiteSpace(
                $metadata.IndexNames
            )
        ) {
            "<none>"
        }
        else {
            $metadata.IndexNames
        }

        $summaryValues = @(
            $fileName
            $planCase.Name
            $queryType
            $planCase.Offset
            $keywordSummary
            $statusSummary
            (
                Get-OptionalProperty `
                    -InputObject $topPlan `
                    -Name "Node Type" `
                    -DefaultValue "<missing>"
            )
            $metadata.NodeTypes
            $indexesUsedSummary
            (
                Get-OptionalProperty `
                    -InputObject $topPlan `
                    -Name "Plan Rows" `
                    -DefaultValue 0
            )
            (
                Get-OptionalProperty `
                    -InputObject $topPlan `
                    -Name "Actual Rows" `
                    -DefaultValue 0
            )
            $metadata.RowsRemovedByFilter
            (
                Get-OptionalProperty `
                    -InputObject $topPlan `
                    -Name "Shared Hit Blocks" `
                    -DefaultValue 0
            )
            (
                Get-OptionalProperty `
                    -InputObject $topPlan `
                    -Name "Shared Read Blocks" `
                    -DefaultValue 0
            )
            (
                Get-OptionalProperty `
                    -InputObject $payload.Root `
                    -Name "Planning Time" `
                    -DefaultValue 0
            )
            (
                Get-OptionalProperty `
                    -InputObject $payload.Root `
                    -Name "Execution Time" `
                    -DefaultValue 0
            )
        )

        $summaryLine = $summaryValues -join "|"

        [System.IO.File]::AppendAllText(
            $summaryFile,
            $summaryLine + [Environment]::NewLine,
            $Utf8NoBom
        )
    }
}

$actualPlanFiles = @(
    Get-ChildItem `
        -LiteralPath $evidenceDirectory `
        -Filter "*.json" `
        -File
).Count

if ($actualPlanFiles -ne $expectedPlanFiles) {
    throw (
        "Expected $expectedPlanFiles JSON plans, " +
        "found $actualPlanFiles."
    )
}

Write-Host ""
Write-Host "Current-query plan capture succeeded."
Write-Host "Rows: $Rows"
Write-Host "Plans: $actualPlanFiles"
Write-Host "Evidence: $evidenceDirectory"
Write-Host (
    "The benchmark database was read only and remains running."
)