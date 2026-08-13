[CmdletBinding()]
param(
    [ValidateSet(10000)]
    [int]$Rows = 10000,

    [switch]$Reset,

    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$ProjectName = "shop-product-search-benchmark"
$ExpectedVolumeName =
        "${ProjectName}_product-search-postgres-data"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$RepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..\..")
)

$ComposeFile = Join-Path `
    $RepoRoot `
    "docker-compose.search-benchmark.yml"

$PrepareDatasetScript = Join-Path `
    $PSScriptRoot `
    "prepare-dataset.ps1"

$BaseSeedFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-products.sql"

$SeedOverlayFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\seed-trigram-workloads.sql"

$BaselineVerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-trigram-workloads.sql"

$CreateFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\create-trigram-prototype.sql"

$VerifyFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\verify-trigram-prototype.sql"

$ExplainFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\explain-trigram-prototype.sql"

$CleanupFile = Join-Path `
    $RepoRoot `
    "src\test\resources\benchmark\product-search\cleanup-trigram-prototype.sql"

$EvidenceRoot = Join-Path `
    $RepoRoot `
    "docs\roadmap-v2\v2-ps\c\c2\raw\prototype"

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
        $parsed = ConvertFrom-Json -InputObject $jsonText
    }
    catch {
        throw (
            "Plan output was not valid JSON: " +
            $_.Exception.Message
        )
    }

    $candidate = Get-OptionalProperty `
        -InputObject $parsed `
        -Name "candidate"

    $membershipRows = Get-OptionalProperty `
        -InputObject $parsed `
        -Name "membershipRows"

    $planArray = Get-OptionalProperty `
        -InputObject $parsed `
        -Name "plan"

    if ([string]::IsNullOrWhiteSpace([string]$candidate)) {
        throw "Plan payload did not contain candidate."
    }

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
        Candidate      = [string]$candidate
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
            $nodeTypes | Sort-Object -Unique
        ) -join ","
        IndexNames = (
            $indexNames | Sort-Object -Unique
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
        [ValidateSet("count", "data_offset", "data_cursor")]
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

function Get-SchemaState {
    $schemaSql = @"
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
        SELECT count(*)
        FROM pg_extension
        WHERE extname = 'pg_trgm'
    ),
    'indexes=' || (
        SELECT string_agg(
            indexname::text,
            ','
            ORDER BY indexname::text
        )
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ),
    'index_definitions=' || (
        SELECT string_agg(
            indexdef,
            ' ; '
            ORDER BY indexname::text
        )
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'products'
    ),
    'index_bytes=' || pg_indexes_size('public.products'),
    'public_c2_routines=' || (
        SELECT count(*)
        FROM pg_proc AS routine
        JOIN pg_namespace AS namespace
          ON namespace.oid = routine.pronamespace
        WHERE namespace.nspname = 'public'
          AND routine.proname LIKE 'c2_%'
    )
);
"@

    return Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @("-c", $schemaSql)
        )
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
                "-v",
                "expected_rows=$Rows",
                "-f",
                "/benchmark/cleanup-trigram-prototype.sql"
            )
        )

    $text = $result.Output -join [Environment]::NewLine

    Write-Utf8File -Path $OutputPath -Value $text

    if ($result.ExitCode -ne 0) {
        throw (
            "C2 SQL cleanup failed: " +
            $text
        )
    }

    if ($text -notmatch "cleanup_result=success") {
        throw "C2 cleanup did not emit its success marker."
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

if (-not $Reset) {
    throw (
        "C2 recreates and removes only the isolated benchmark " +
        "database. Pass -Reset explicitly."
    )
}

foreach ($requiredFile in @(
    $ComposeFile,
    $PrepareDatasetScript,
    $BaseSeedFile,
    $SeedOverlayFile,
    $BaselineVerifyFile,
    $CreateFile,
    $VerifyFile,
    $ExplainFile,
    $CleanupFile,
    $PSCommandPath
)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required file was not found: $requiredFile"
    }
}

$script:ComposePrefix = @(
    "compose",
    "--project-name",
    $ProjectName,
    "--file",
    $ComposeFile
)

$script:PsqlPrefix = $script:ComposePrefix + @(
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

$mainFailure = $null
$finalCleanupFailure = $null
$volumeCleanupFailure = $null
$databaseLifecycleStarted = $false
$evidenceDirectory = $null
$manifestFile = $null
$overallPlans = 0

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
        throw "C2 must run from a branch, not detached HEAD."
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
            "Working tree is not clean. Use -AllowDirty only for " +
            "the pre-commit C2 lifecycle run, or commit the five " +
            "frozen artifacts first."
        )
    }

    $workingTreeState = if ($isDirty) {
        "dirty-allowed"
    }
    else {
        "clean"
    }

    $timestamp = [DateTime]::UtcNow.ToString(
        "yyyyMMddTHHmmssZ"
    )

    $evidenceDirectory = Join-Path `
        $EvidenceRoot `
        "trigram-prototype-$Rows-smoke-$timestamp"

    New-Item `
        -ItemType Directory `
        -Path $evidenceDirectory `
        -Force |
        Out-Null

    $manifestFile = Join-Path `
        $evidenceDirectory `
        "manifest.txt"

    Write-Utf8File `
        -Path $manifestFile `
        -Value ((@(
            "work_package=V2-PS-C2"
            "generated_at_utc=$timestamp"
            "branch=$branch"
            "head=$head"
            "rows=$Rows"
            "seed=$Seed"
            "mode=smoke"
            "working_tree=$workingTreeState"
            "postgres_image=postgres:16-alpine"
            "flyway_expected_version=12"
            "candidate_order=gin,gist"
            "production_source_changes=none"
            "migration_changes=none"
            "status_at_start=running"
        ) -join [Environment]::NewLine) +
        [Environment]::NewLine)

    if ($workingTree.Count -gt 0) {
        Add-Utf8Line `
            -Path $manifestFile `
            -Value "working_tree_entries:"

        foreach ($entry in $workingTree) {
            Add-Utf8Line `
                -Path $manifestFile `
                -Value $entry
        }
    }

    foreach ($hashFile in @(
        $ComposeFile,
        $PrepareDatasetScript,
        $BaseSeedFile,
        $SeedOverlayFile,
        $BaselineVerifyFile,
        $CreateFile,
        $VerifyFile,
        $ExplainFile,
        $CleanupFile,
        $PSCommandPath
    )) {
        $hash = (
            Get-FileHash `
                -LiteralPath $hashFile `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()

        $relativePath = $hashFile.Substring(
            $RepoRoot.Length
        ).TrimStart([char]'\', [char]'/')

        Add-Utf8Line `
            -Path $manifestFile `
            -Value "sha256[$relativePath]=$hash"
    }

    $prepareParameters = @{
        Rows  = $Rows
        Reset = $true
    }

    if ($AllowDirty) {
        $prepareParameters.AllowDirty = $true
    }

    $databaseLifecycleStarted = $true

    & $PrepareDatasetScript @prepareParameters

    $sessionSettings = (
        "SET shop_benchmark.row_count = '$Rows'; " +
        "SET shop_benchmark.seed = '$Seed';"
    )

    Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-c",
                $sessionSettings,
                "-f",
                "/benchmark/seed-trigram-workloads.sql"
            )
        ) |
        Out-Null

    Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-c",
                "ANALYZE products;"
            )
        ) |
        Out-Null

    $baselineVerification = Invoke-DockerCommand `
        -CommandArguments (
            $script:PsqlPrefix + @(
                "-c",
                $sessionSettings,
                "-v",
                "expected_rows=$Rows",
                "-f",
                "/benchmark/verify-trigram-workloads.sql"
            )
        )

    $baselineText = (
        $baselineVerification.Output -join
        [Environment]::NewLine
    )

    if ($baselineText -notmatch "verification_result=success") {
        throw "C1 baseline verification did not emit success."
    }

    Write-Utf8File `
        -Path (Join-Path `
            $evidenceDirectory `
            "verification-baseline.txt") `
        -Value $baselineText

    $preCreateState = Get-SchemaState
    Write-Utf8File `
        -Path (Join-Path `
            $evidenceDirectory `
            "state-before-candidates.txt") `
        -Value (
            $preCreateState.Output -join
            [Environment]::NewLine
        )

    [long]$activeRows = $Rows * 4L / 5L
    [long]$mediumRows = ($Rows / 10L) - 4L
    [long]$commonActiveRows =
            ($Rows * 2L / 5L) - 11L
    [long]$commonInactiveRows =
            ($Rows / 10L) - 2L
    [long]$deepOffset = $Rows / 4L

    [object[]]$workloads = @(
        New-Workload `
            -Name "exact-rank-active-data" `
            -Term "C1-RANK-TERM" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership 5 `
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
            -Name "common-active-data" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "data_offset" `
            -ExpectedMembership $commonActiveRows `
            -OffsetRows 0 `
            -PageSize 100

        New-Workload `
            -Name "common-active-count" `
            -Term "C1-COMMON-INFIX" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership $commonActiveRows `
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
            -Name "percent-active-count" `
            -Term "%" `
            -Status "ACTIVE" `
            -Surface "count" `
            -ExpectedMembership $activeRows `
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
    )

    foreach ($candidate in @("gin", "gist")) {
        Write-Host "C2 candidate: $candidate"

        $candidateDirectory = Join-Path `
            $evidenceDirectory `
            $candidate

        New-Item `
            -ItemType Directory `
            -Path $candidateDirectory `
            -Force |
            Out-Null

        $candidateFailure = $null
        $candidateCleanupFailure = $null
        $candidatePlanCount = 0

        try {
            $createResult = Invoke-DockerCommand `
                -CommandArguments (
                    $script:PsqlPrefix + @(
                        "-v",
                        "expected_rows=$Rows",
                        "-v",
                        "candidate=$candidate",
                        "-f",
                        "/benchmark/create-trigram-prototype.sql"
                    )
                )

            $createText = (
                $createResult.Output -join
                [Environment]::NewLine
            )

            Write-Utf8File `
                -Path (Join-Path `
                    $candidateDirectory `
                    "create.txt") `
                -Value $createText

            if ($createText -notmatch "create_result=success") {
                throw (
                    "C2 $candidate creation did not emit success."
                )
            }

            $activeState = Get-SchemaState
            Write-Utf8File `
                -Path (Join-Path `
                    $candidateDirectory `
                    "state-active.txt") `
                -Value (
                    $activeState.Output -join
                    [Environment]::NewLine
                )

            $verificationResult = Invoke-DockerCommand `
                -CommandArguments (
                    $script:PsqlPrefix + @(
                        "-v",
                        "expected_rows=$Rows",
                        "-v",
                        "candidate=$candidate",
                        "-f",
                        "/benchmark/verify-trigram-prototype.sql"
                    )
                )

            $verificationText = (
                $verificationResult.Output -join
                [Environment]::NewLine
            )

            Write-Utf8File `
                -Path (Join-Path `
                    $candidateDirectory `
                    "verification.txt") `
                -Value $verificationText

            if (
                $verificationText -notmatch
                "verification_result=success"
            ) {
                throw (
                    "C2 $candidate verification did not emit success."
                )
            }

            $planSummaryFile = Join-Path `
                $candidateDirectory `
                "plans.csv"

            Write-Utf8File `
                -Path $planSummaryFile `
                -Value (
                    "candidate|workload|surface|status|term|" +
                    "expected_membership|actual_membership|" +
                    "offset_rows|page_size|planning_time_ms|" +
                    "execution_time_ms|node_types|index_names|" +
                    "rows_removed_by_filter|plan_file" +
                    [Environment]::NewLine
                )

            foreach ($workload in $workloads) {
                Write-Host (
                    "Capture: $candidate / " +
                    $workload.Name
                )

                $planResult = Invoke-DockerCommand `
                    -CommandArguments (
                        $script:PsqlPrefix + @(
                            "-v",
                            "expected_rows=$Rows",
                            "-v",
                            "candidate=$candidate",
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
                            "/benchmark/explain-trigram-prototype.sql"
                        )
                    )

                $payload = Get-PlanPayload `
                    -Output $planResult.Output

                if ($payload.Candidate -ne $candidate) {
                    throw (
                        "Plan candidate mismatch for " +
                        $workload.Name
                    )
                }

                if (
                    $payload.MembershipRows -ne
                    $workload.ExpectedMembership
                ) {
                    throw (
                        "Workload $($workload.Name) expected " +
                        "$($workload.ExpectedMembership) members, " +
                        "found $($payload.MembershipRows)."
                    )
                }

                $planFileName = (
                    "{0:D3}-{1}.json" -f
                    ($candidatePlanCount + 1),
                    $workload.Name
                )

                Write-Utf8File `
                    -Path (Join-Path `
                        $candidateDirectory `
                        $planFileName) `
                    -Value $payload.JsonText

                $metadata = Get-PlanMetadata `
                    -Root $payload.Root

                Add-Utf8Line `
                    -Path $planSummaryFile `
                    -Value (
                        "$candidate|$($workload.Name)|" +
                        "$($workload.Surface)|" +
                        "$($workload.Status)|" +
                        "$($workload.Term)|" +
                        "$($workload.ExpectedMembership)|" +
                        "$($payload.MembershipRows)|" +
                        "$($workload.OffsetRows)|" +
                        "$($workload.PageSize)|" +
                        "$($payload.Root.'Planning Time')|" +
                        "$($payload.Root.'Execution Time')|" +
                        "$($metadata.NodeTypes)|" +
                        "$($metadata.IndexNames)|" +
                        "$($metadata.RowsRemovedByFilter)|" +
                        $planFileName
                    )

                $candidatePlanCount++
                $overallPlans++
            }

            if ($candidatePlanCount -ne $workloads.Count) {
                throw (
                    "Expected $($workloads.Count) $candidate plans, " +
                    "captured $candidatePlanCount."
                )
            }
        }
        catch {
            $candidateFailure = $_
        }

        try {
            Invoke-CleanupCapture `
                -OutputPath (Join-Path `
                    $candidateDirectory `
                    "cleanup.txt")

            $postCleanupState = Get-SchemaState
            Write-Utf8File `
                -Path (Join-Path `
                    $candidateDirectory `
                    "state-after-cleanup.txt") `
                -Value (
                    $postCleanupState.Output -join
                    [Environment]::NewLine
                )
        }
        catch {
            $candidateCleanupFailure = $_
        }

        if (
            $null -ne $candidateFailure -or
            $null -ne $candidateCleanupFailure
        ) {
            $operationMessage = if (
                $null -ne $candidateFailure
            ) {
                $candidateFailure.Exception.Message
            }
            else {
                "none"
            }

            $cleanupMessage = if (
                $null -ne $candidateCleanupFailure
            ) {
                $candidateCleanupFailure.Exception.Message
            }
            else {
                "none"
            }

            throw (
                "C2 candidate $candidate failed. " +
                "operation=[$operationMessage] " +
                "cleanup=[$cleanupMessage]"
            )
        }

        Add-Utf8Line `
            -Path $manifestFile `
            -Value "candidate[$candidate]=success"
        Add-Utf8Line `
            -Path $manifestFile `
            -Value (
                "candidate[$candidate].plans=" +
                $candidatePlanCount
            )
    }

    Add-Utf8Line `
        -Path $manifestFile `
        -Value "plans=$overallPlans"
    Add-Utf8Line `
        -Path $manifestFile `
        -Value "candidate_lifecycles=2"
    Add-Utf8Line `
        -Path $manifestFile `
        -Value "c1_parity=success"
}
catch {
    $mainFailure = $_
}
finally {
    if (
        $databaseLifecycleStarted -and
        $null -ne $evidenceDirectory
    ) {
        try {
            Invoke-CleanupCapture `
                -OutputPath (Join-Path `
                    $evidenceDirectory `
                    "cleanup-final.txt")
        }
        catch {
            $finalCleanupFailure = $_
        }

        $downResult = Invoke-NativeCapture `
            -FilePath "docker" `
            -CommandArguments (
                $script:ComposePrefix + @(
                    "down",
                    "--volumes",
                    "--remove-orphans"
                )
            )

        $downText = (
            $downResult.Output -join
            [Environment]::NewLine
        )

        Write-Utf8File `
            -Path (Join-Path `
                $evidenceDirectory `
                "volume-cleanup.txt") `
            -Value $downText

        if ($downResult.ExitCode -ne 0) {
            $volumeCleanupFailure = [System.Exception]::new(
                "Docker Compose volume cleanup failed: $downText"
            )
        }
        else {
            $volumeResult = Invoke-NativeCapture `
                -FilePath "docker" `
                -CommandArguments @(
                    "volume",
                    "ls",
                    "--filter",
                    "name=$ExpectedVolumeName",
                    "--format",
                    "{{.Name}}"
                )

            $remainingVolumes = @(
                $volumeResult.Output |
                Where-Object {
                    $_.Trim() -eq $ExpectedVolumeName
                }
            )

            Add-Utf8Line `
                -Path (Join-Path `
                    $evidenceDirectory `
                    "volume-cleanup.txt") `
                -Value (
                    "expected_volume=$ExpectedVolumeName"
                )
            Add-Utf8Line `
                -Path (Join-Path `
                    $evidenceDirectory `
                    "volume-cleanup.txt") `
                -Value (
                    "remaining_exact_volume_count=" +
                    $remainingVolumes.Count
                )

            if (
                $volumeResult.ExitCode -ne 0 -or
                $remainingVolumes.Count -ne 0
            ) {
                $volumeCleanupFailure =
                    [System.Exception]::new(
                        "The isolated benchmark volume remains or " +
                        "could not be inspected."
                    )
            }
        }
    }

    if ($null -ne $manifestFile) {
        $mainStatus = if ($null -eq $mainFailure) {
            "success"
        }
        else {
            "failed"
        }

        $finalCleanupStatus = if (
            $null -eq $finalCleanupFailure
        ) {
            "success"
        }
        else {
            "failed"
        }

        $volumeCleanupStatus = if (
            $null -eq $volumeCleanupFailure
        ) {
            "success"
        }
        else {
            "failed"
        }

        Add-Utf8Line `
            -Path $manifestFile `
            -Value "main_status=$mainStatus"
        Add-Utf8Line `
            -Path $manifestFile `
            -Value "final_sql_cleanup=$finalCleanupStatus"
        Add-Utf8Line `
            -Path $manifestFile `
            -Value "volume_cleanup=$volumeCleanupStatus"

        if ($null -ne $mainFailure) {
            Add-Utf8Line `
                -Path $manifestFile `
                -Value (
                    "failure=" +
                    $mainFailure.Exception.Message.
                        Replace("`r", " ").
                        Replace("`n", " ")
                )
        }

        if ($null -ne $finalCleanupFailure) {
            Add-Utf8Line `
                -Path $manifestFile `
                -Value (
                    "final_cleanup_failure=" +
                    $finalCleanupFailure.Exception.Message.
                        Replace("`r", " ").
                        Replace("`n", " ")
                )
        }

        if ($null -ne $volumeCleanupFailure) {
            Add-Utf8Line `
                -Path $manifestFile `
                -Value (
                    "volume_cleanup_failure=" +
                    $volumeCleanupFailure.Message.
                        Replace("`r", " ").
                        Replace("`n", " ")
                )
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

        Add-Utf8Line `
            -Path $manifestFile `
            -Value "result=$overallResult"
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
        "C2 trigram prototype lifecycle failed: " +
        ($messages -join " | ")
    )
}

Write-Host ""
Write-Host "C2 trigram prototype lifecycle succeeded."
Write-Host "Rows: $Rows"
Write-Host "Candidates: gin,gist"
Write-Host "Plans: $overallPlans"
Write-Host "Evidence: $evidenceDirectory"
Write-Host "pg_trgm installed after cleanup: 0"
Write-Host "Candidate indexes after cleanup: 0"
Write-Host "Benchmark volume after cleanup: absent"
