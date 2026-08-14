[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(10000, 100000)]
    [int]$Rows,

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

$PrepareDatasetScript = Join-Path `
    $RepoRoot `
    "scripts\benchmark\product-search\prepare-dataset.ps1"

$SeedOverlayFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-trigram-workloads.sql"

$VerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-trigram-workloads.sql"

$ExplainFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-trigram-baseline.sql"

$BaseSeedFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-products.sql"

$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\c\c1\raw\baseline"

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

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

function Get-PlanPayload {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Output
    )

    $combined = (
        $Output -join [Environment]::NewLine
    ).Trim()

    $start = $combined.IndexOf("{")
    $end = $combined.LastIndexOf("}")

    if ($start -lt 0 -or $end -le $start) {
        throw (
            "Plan output did not contain a JSON object: " +
            $combined
        )
    }

    $jsonText = $combined.Substring(
        $start,
        $end - $start + 1
    )

    try {
        $parsed = ConvertFrom-Json `
            -InputObject $jsonText
    }
    catch {
        throw (
            "Plan output was not valid JSON: " +
            $_.Exception.Message
        )
    }

    $membershipRows = Get-OptionalProperty `
        -InputObject $parsed `
        -Name "membershipRows"

    $planArray = Get-OptionalProperty `
        -InputObject $parsed `
        -Name "plan"

    if ($null -eq $membershipRows) {
        throw "Plan payload did not contain membershipRows."
    }

    if ($null -eq $planArray) {
        throw "Plan payload did not contain plan."
    }

    $root = if ($planArray -is [System.Array]) {
        $planArray[0]
    }
    else {
        $planArray
    }

    if ($null -eq $root.PSObject.Properties["Plan"]) {
        throw "EXPLAIN JSON did not contain a Plan object."
    }

    if (
        $null -eq
        $root.PSObject.Properties["Planning Time"]
    ) {
        throw "EXPLAIN JSON did not contain Planning Time."
    }

    if (
        $null -eq
        $root.PSObject.Properties["Execution Time"]
    ) {
        throw "EXPLAIN JSON did not contain Execution Time."
    }

    return [pscustomobject]@{
        JsonText       = $jsonText
        MembershipRows = [long]$membershipRows
        Root           = $root
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
            [void]$nodeTypes.Add(
                [string]$nodeType
            )
        }

        $indexName = Get-OptionalProperty `
            -InputObject $node `
            -Name "Index Name"

        if ($null -ne $indexName) {
            [void]$indexNames.Add(
                [string]$indexName
            )
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

function New-Workload {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Term,

        [Parameter(Mandatory = $true)]
        [ValidateSet("ACTIVE", "INACTIVE", "ALL")]
        [string]$Status,

        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "count",
            "data_offset",
            "data_cursor"
        )]
        [string]$Surface,

        [Parameter(Mandatory = $true)]
        [long]$ExpectedMembership,

        [Parameter(Mandatory = $true)]
        [long]$OffsetRows,

        [Parameter(Mandatory = $true)]
        [int]$PageSize
    )

    return [pscustomobject]@{
        Name               = $Name
        Term               = $Term
        Status             = $Status
        Surface            = $Surface
        ExpectedMembership = $ExpectedMembership
        OffsetRows         = $OffsetRows
        PageSize           = $PageSize
    }
}

foreach ($requiredCommand in @(
    "git",
    "docker"
)) {
    $command = Get-Command `
        $requiredCommand `
        -CommandType Application `
        -ErrorAction SilentlyContinue

    if ($null -eq $command) {
        throw (
            "Required command was not found: " +
            $requiredCommand
        )
    }
}

if (-not $Reset) {
    throw (
        "C1 baseline capture recreates only the isolated " +
        "benchmark database. Pass -Reset explicitly."
    )
}

foreach ($requiredFile in @(
    $ComposeFile,
    $PrepareDatasetScript,
    $BaseSeedFile,
    $SeedOverlayFile,
    $VerifyFile,
    $ExplainFile
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw (
            "Required file was not found: " +
            $requiredFile
        )
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

    if ([string]::IsNullOrWhiteSpace($branch)) {
        throw (
            "C1 capture must run from a branch, " +
            "not detached HEAD."
        )
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
            "Working tree is not clean. Commit the C1 " +
            "harness first, or use -AllowDirty only " +
            "for the pre-commit 10k smoke run."
        )
    }

    $workingTreeState = if ($isDirty) {
        "dirty-allowed"
    }
    else {
        "clean"
    }

    $prepareParameters = @{
        Rows  = $Rows
        Reset = $true
    }

    if ($AllowDirty) {
        $prepareParameters.AllowDirty = $true
    }

    & $PrepareDatasetScript @prepareParameters

    $timestamp = [DateTime]::UtcNow.ToString(
        "yyyyMMddTHHmmssZ"
    )

    $mode = if ($Rows -eq 10000) {
        "smoke"
    }
    else {
        "decision"
    }

    $EvidenceDirectory = Join-Path `
        $EvidenceRoot `
        "trigram-baseline-$Rows-$mode-$timestamp"

    New-Item `
        -ItemType Directory `
        -Path $EvidenceDirectory `
        -Force |
        Out-Null

    $ManifestFile = Join-Path `
        $EvidenceDirectory `
        "manifest.txt"

    $VerificationBeforeFile = Join-Path `
        $EvidenceDirectory `
        "verification-before.txt"

    $VerificationAfterFile = Join-Path `
        $EvidenceDirectory `
        "verification-after.txt"

    $SchemaFile = Join-Path `
        $EvidenceDirectory `
        "schema-and-extension.txt"

    $PlanSummaryFile = Join-Path `
        $EvidenceDirectory `
        "plans.csv"

    @(
        "work_package=V2-PS-C1"
        "generated_at_utc=$timestamp"
        "branch=$branch"
        "head=$head"
        "rows=$Rows"
        "seed=$Seed"
        "mode=$mode"
        "working_tree=$workingTreeState"
        "postgres_image=postgres:16-alpine"
        "flyway_expected_version=12"
        "production_source_changes=none"
        "migration_changes=none"
        "extension_expected_installed=0"
        "candidate_indexes_expected=0"
    ) | Set-Content `
        -LiteralPath $ManifestFile `
        -Encoding UTF8

    if ($workingTree.Count -gt 0) {
        Add-Utf8Line `
            -Path $ManifestFile `
            -Value "working_tree_entries:"

        foreach ($entry in $workingTree) {
            Add-Utf8Line `
                -Path $ManifestFile `
                -Value $entry
        }
    }

    foreach ($hashFile in @(
        $BaseSeedFile,
        $SeedOverlayFile,
        $VerifyFile,
        $ExplainFile,
        $PSCommandPath
    )) {
        $hash = (
            Get-FileHash `
                -LiteralPath $hashFile `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()

        $relativePath = $hashFile.Substring(
            $RepoRoot.Length
        ).TrimStart(
            [char]'\',
            [char]'/'
        )

        Add-Utf8Line `
            -Path $ManifestFile `
            -Value (
                "sha256[$relativePath]=$hash"
            )
    }

    $composePrefix = @(
        "compose",
        "--project-name",
        $ProjectName,
        "--file",
        $ComposeFile
    )

    $psqlPrefix = $composePrefix + @(
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
        "ON_ERROR_STOP=1",
        "-U",
        "shop_benchmark",
        "-d",
        "shop_search_benchmark"
    )

    $sessionSettings = (
        "SET shop_benchmark.row_count = '$Rows'; " +
        "SET shop_benchmark.seed = '$Seed';"
    )

    $overlayResult = Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $sessionSettings,
                "-f",
                "/benchmark/seed-trigram-workloads.sql"
            )
        )

    Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                "ANALYZE products;"
            )
        ) |
        Out-Null

    $verificationBefore = Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $sessionSettings,
                "-v",
                "expected_rows=$Rows",
                "-f",
                "/benchmark/verify-trigram-workloads.sql"
            )
        )

    $verificationBeforeText = (
        $verificationBefore.Output -join
        [Environment]::NewLine
    )

    if (
        $verificationBeforeText -notmatch
        "verification_result=success"
    ) {
        throw (
            "C1 verification did not emit its " +
            "success marker before plan capture."
        )
    }

    Write-Utf8File `
        -Path $VerificationBeforeFile `
        -Value $verificationBeforeText

    $schemaSql = @"
SELECT concat_ws(
    '|',
    'database=' || current_database(),
    'user=' || current_user,
    'postgres=' || current_setting('server_version'),
    'encoding=' || current_setting('server_encoding'),
    'rows=' || (SELECT count(*) FROM products),
    'flyway=' || (
        SELECT coalesce(max(version::integer), 0)
        FROM flyway_schema_history
        WHERE success
    ),
    'pg_trgm_installed=' || (
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ),
    'pg_trgm_available=' || (
        SELECT count(*)
        FROM pg_available_extensions
        WHERE name = 'pg_trgm'
    ),
    'database_create_privilege=' ||
        has_database_privilege(
            current_user,
            current_database(),
            'CREATE'
        ),
    'indexes=' || (
        SELECT string_agg(
            indexname,
            ','
            ORDER BY indexname
        )
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ),
    'table_bytes=' ||
        pg_table_size('public.products'),
    'index_bytes=' ||
        pg_indexes_size('public.products')
);
"@

    $schemaResult = Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $schemaSql
            )
        )

    Write-Utf8File `
        -Path $SchemaFile `
        -Value (
            $schemaResult.Output -join
            [Environment]::NewLine
        )

    [long]$activeRows =
        $Rows * 4L / 5L

    [long]$mediumRows =
        ($Rows / 10L) - 4L

    [long]$commonAllRows =
        ($Rows / 2L) - 13L

    [long]$commonActiveRows =
        ($Rows * 2L / 5L) - 11L

    [long]$commonInactiveRows =
        ($Rows / 10L) - 2L

    [long]$deepOffset =
        $Rows / 4L

    [object[]]$workloads = @(
        New-Workload `
            -Name "blank-active-first-offset" `
            -Term "__C1_BROWSE__" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $activeRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "exact-rank-active-data" `
            -Term "C1-RANK-TERM" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 5 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "exact-rank-active-count" `
            -Term "C1-RANK-TERM" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership 5 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "sku-prefix-active-data" `
            -Term "C1-RANK-TERM-PREFIX-SKU" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "sku-infix-active-data" `
            -Term "SKU-MIDDLE" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "sku-infix-active-count" `
            -Term "SKU-MIDDLE" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "name-prefix-active-data" `
            -Term "C1-RANK-TERM Name" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "name-infix-active-data" `
            -Term "NAME-MIDDLE" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "name-infix-active-count" `
            -Term "NAME-MIDDLE" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "medium-active-data" `
            -Term "C1-MEDIUM-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $mediumRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "medium-active-count" `
            -Term "C1-MEDIUM-INFIX" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership $mediumRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-active-first-offset" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-active-first-cursor" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_cursor" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows 0 `
            -PageSize 101

        New-Workload `
            -Name "common-active-count" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-active-deep-offset" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows $deepOffset `
            -PageSize 100

        New-Workload `
            -Name "common-active-deep-cursor" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_cursor" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows $deepOffset `
            -PageSize 101

        New-Workload `
            -Name "miss-active-data" `
            -Term "C1-NO-RESULT-NEEDLE" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 0 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "miss-active-count" `
            -Term "C1-NO-RESULT-NEEDLE" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership 0 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "short-q-active-data" `
            -Term "q" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 3 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "short-qz-active-data" `
            -Term "qz" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 2 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "short-qzx-active-data" `
            -Term "qzx" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 1 `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "percent-active-data" `
            -Term "%" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $activeRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "percent-active-count" `
            -Term "%" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership $activeRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-admin-all-data" `
            -Term "C1-COMMON-INFIX" `
            -Status "ALL" `
            -Surface "data_offset" `
            -ExpectedMembership $commonAllRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-admin-all-count" `
            -Term "C1-COMMON-INFIX" `
            -Status "ALL" `
            -Surface "count" `
            -ExpectedMembership $commonAllRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-admin-inactive-data" `
            -Term "C1-COMMON-INFIX" `
            -Status "INACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $commonInactiveRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-admin-inactive-count" `
            -Term "C1-COMMON-INFIX" `
            -Status "INACTIVE" `
            -Surface "count" `
            -ExpectedMembership $commonInactiveRows `
            -OffsetRows 0 `
            -PageSize 100
    )

    Write-Utf8File `
        -Path $PlanSummaryFile `
        -Value (
            "workload|surface|status|term|" +
            "expected_membership|actual_membership|" +
            "offset_rows|page_size|root_actual_rows|" +
            "planning_time_ms|execution_time_ms|" +
            "node_types|index_names|" +
            "rows_removed_by_filter|plan_file" +
            [Environment]::NewLine
        )

    $planCount = 0

    foreach ($workload in $workloads) {
        Write-Host (
            "Capture: " +
            $workload.Name
        )

        $planResult = Invoke-DockerCommand `
            -CommandArguments (
                $psqlPrefix + @(
                    "-v",
                    "expected_rows=$Rows",
                    "-v",
                    "term=$($workload.Term)",
                    "-v",
                    "status=$($workload.Status)",
                    "-v",
                    "surface=$($workload.Surface)",
                    "-v",
                    "offset_rows=$($workload.OffsetRows)",
                    "-v",
                    "page_size=$($workload.PageSize)",
                    "-f",
                    "/benchmark/explain-trigram-baseline.sql"
                )
            )

        $payload = Get-PlanPayload `
            -Output $planResult.Output

        if (
            $payload.MembershipRows -ne
            $workload.ExpectedMembership
        ) {
            throw (
                "Workload " +
                $workload.Name +
                " expected membership " +
                $workload.ExpectedMembership +
                ", found " +
                $payload.MembershipRows +
                "."
            )
        }

        $planFileName = (
            "{0:D3}-{1}.json" -f
            (
                $planCount + 1
            ),
            $workload.Name
        )

        $planFile = Join-Path `
            $EvidenceDirectory `
            $planFileName

        Write-Utf8File `
            -Path $planFile `
            -Value $payload.JsonText

        $metadata = Get-PlanMetadata `
            -Root $payload.Root

        $rootActualRows = Get-OptionalProperty `
            -InputObject $payload.Root.Plan `
            -Name "Actual Rows" `
            -DefaultValue 0

        Add-Utf8Line `
            -Path $PlanSummaryFile `
            -Value (
                "$($workload.Name)|" +
                "$($workload.Surface)|" +
                "$($workload.Status)|" +
                "$($workload.Term)|" +
                "$($workload.ExpectedMembership)|" +
                "$($payload.MembershipRows)|" +
                "$($workload.OffsetRows)|" +
                "$($workload.PageSize)|" +
                "$rootActualRows|" +
                "$($payload.Root.'Planning Time')|" +
                "$($payload.Root.'Execution Time')|" +
                "$($metadata.NodeTypes)|" +
                "$($metadata.IndexNames)|" +
                "$($metadata.RowsRemovedByFilter)|" +
                "$planFileName"
            )

        $planCount++
    }

    if ($planCount -ne $workloads.Count) {
        throw (
            "Expected " +
            $workloads.Count +
            " plans, captured " +
            $planCount +
            "."
        )
    }

    $verificationAfter = Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $sessionSettings,
                "-v",
                "expected_rows=$Rows",
                "-f",
                "/benchmark/verify-trigram-workloads.sql"
            )
        )

    $verificationAfterText = (
        $verificationAfter.Output -join
        [Environment]::NewLine
    )

    if (
        $verificationAfterText -notmatch
        "verification_result=success"
    ) {
        throw (
            "C1 verification did not emit its " +
            "success marker after plan capture."
        )
    }

    Write-Utf8File `
        -Path $VerificationAfterFile `
        -Value $verificationAfterText

    $persistentObjectSql = @"
SELECT
    (
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ) AS installed_trigram_extensions,
    (
        SELECT count(*)
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
          AND (
              indexdef ILIKE '%gin_trgm_ops%'
              OR indexdef ILIKE '%gist_trgm_ops%'
              OR indexname LIKE 'c1_%'
              OR indexname LIKE 'c2_%'
          )
    ) AS trigram_candidate_indexes,
    (
        SELECT count(*)
        FROM pg_proc AS routine
        JOIN pg_namespace AS namespace
          ON namespace.oid = routine.pronamespace
        WHERE namespace.nspname = 'public'
          AND routine.proname LIKE 'c1_%'
    ) AS public_c1_routines;
"@

    $persistentResult = Invoke-DockerCommand `
        -CommandArguments (
            $psqlPrefix + @(
                "-c",
                $persistentObjectSql
            )
        )

    $persistentText = (
        $persistentResult.Output -join
        ""
    ).Trim()

    if ($persistentText -ne "0|0|0") {
        throw (
            "Unexpected persistent C1 object state: " +
            $persistentText
        )
    }

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "workloads=$($workloads.Count)"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "plans=$planCount"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "verification_before=success"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "verification_after=success"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "pg_trgm_installed_after=0"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "candidate_indexes_after=0"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "public_c1_routines_after=0"

    Add-Utf8Line `
        -Path $ManifestFile `
        -Value "result=success"

    Write-Host ""
    Write-Host "C1 trigram baseline capture succeeded."
    Write-Host "Rows: $Rows"
    Write-Host "Mode: $mode"
    Write-Host "Workloads: $($workloads.Count)"
    Write-Host "Plans: $planCount"
    Write-Host "Evidence: $EvidenceDirectory"
    Write-Host "pg_trgm installed: 0"
    Write-Host "Candidate indexes: 0"
    Write-Host (
        "The isolated benchmark database remains " +
        "running for inspection."
    )
}
catch {
    if (
        $null -ne
        (Get-Variable `
            -Name ManifestFile `
            -ErrorAction SilentlyContinue)
    ) {
        Add-Utf8Line `
            -Path $ManifestFile `
            -Value "result=failed"

        Add-Utf8Line `
            -Path $ManifestFile `
            -Value (
                "failure=" +
                $_.Exception.Message.
                    Replace("`r", " ").
                    Replace("`n", " ")
            )
    }

    throw
}
finally {
    Pop-Location
}
