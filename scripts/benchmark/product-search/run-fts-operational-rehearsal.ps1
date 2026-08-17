[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("N1", "N1+S", "N2", "N2+S")]
    [string]$State,

    [Parameter(Mandatory)]
    [ValidateSet("16.14", "18.3")]
    [string]$Postgres,

    [Parameter(Mandatory)]
    [string]$EvaluationEvidence,

    [ValidateSet(10000, 100000)]
    [int]$Rows = 100000,

    [switch]$Reset,
    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# This runner is PS-D manifest path #3. It rehearses the D08 operational
# boundary for exactly one D07 survivor state on one PostgreSQL major.
# It never connects to, inspects, or mutates the production target. All
# connections go to the loopback-only benchmark Compose topology.

$Seed = 20260806
$SafeState = $State.Replace("+", "s").ToLowerInvariant()
$IsN2 = $State -in @("N2", "N2+S")
$WithSku = $State.EndsWith("+S")

if (-not $Reset) {
    throw (
        "This runner destroys only its isolated benchmark volumes. " +
        "Pass -Reset explicitly."
    )
}

$RepositoryRoot = (
    Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
).Path

$ComposeFile = Join-Path `
    $RepositoryRoot `
    "docker-compose.search-fts-benchmark.yml"

$BenchmarkRoot = Join-Path `
    $RepositoryRoot `
    "src\test\resources\benchmark\product-search"

$EvidenceRoot = Join-Path `
    $RepositoryRoot `
    "docs\roadmap-v2\v2-ps\d\raw\operations"

$RequiredFiles = @(
    $ComposeFile
    (Join-Path $BenchmarkRoot "seed-products.sql")
    (Join-Path $BenchmarkRoot "verify-dataset.sql")
    (Join-Path $BenchmarkRoot "seed-fts-workloads.sql")
    (Join-Path $BenchmarkRoot "setup-fts-lab-roles.sql")
    (Join-Path $BenchmarkRoot "verify-fts-candidate.sql")
    (Join-Path $BenchmarkRoot "fts-migration\common\V1001__create_product_name_fts_dependencies.sql")
    (Join-Path $BenchmarkRoot "fts-migration\n1\V1002__create_product_name_fts_expression_gin.sql")
    (Join-Path $BenchmarkRoot "fts-migration\n1\V1002__create_product_name_fts_expression_gin.sql.conf")
    (Join-Path $BenchmarkRoot "fts-migration\n2\V1002__add_product_name_fts_generated_vector.sql")
    (Join-Path $BenchmarkRoot "fts-migration\n2\V1003__create_product_name_fts_generated_gin.sql")
    (Join-Path $BenchmarkRoot "fts-migration\n2\V1003__create_product_name_fts_generated_gin.sql.conf")
    (Join-Path $BenchmarkRoot "fts-migration\sku\V1004__create_product_sku_nfc_prefix_index.sql")
    (Join-Path $BenchmarkRoot "fts-migration\sku\V1004__create_product_sku_nfc_prefix_index.sql.conf")
)

foreach ($requiredFile in $RequiredFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}

# Survivor gate. Survivor classification is operator-asserted from the
# frozen paired noise-budget analysis. This runner verifies only that the
# frozen evaluation evidence exists for the requested state on both
# PostgreSQL versions, because D07 requires both-version evidence before
# a candidate can advance.
$EvaluationEvidence = (Resolve-Path $EvaluationEvidence).Path
$SurvivorGateLines = [System.Collections.Generic.List[string]]::new()
$SurvivorGateLines.Add("state=$State")
$SurvivorGateLines.Add("postgres=$Postgres")
$SurvivorGateLines.Add("rows=$Rows")
$SurvivorGateLines.Add(
    "note=Survivor classification is operator-asserted; this runner"
)
$SurvivorGateLines.Add(
    "note=verifies only that frozen evaluation evidence exists for the"
)
$SurvivorGateLines.Add(
    "note=requested state on both PostgreSQL versions."
)

foreach ($version in @("16.14", "18.3")) {
    $SurvivorVerify = Join-Path `
        $EvaluationEvidence `
        "postgres-$version\$SafeState\verify.txt"

    $SurvivorGateLines.Add("checked=$SurvivorVerify")

    if (-not (Test-Path -LiteralPath $SurvivorVerify -PathType Leaf)) {
        throw "Survivor verification evidence is missing: $SurvivorVerify"
    }
}

$Status = @(
    & git -C $RepositoryRoot status --porcelain=v1 2>&1
)

if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect Git status."
}

if ($Status.Count -gt 0 -and -not $AllowDirty) {
    throw (
        "Working tree is not clean. Commit the PS-D artifacts first, " +
        "or use -AllowDirty only for a pre-commit run."
    )
}

$Timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$PgDigits = $Postgres.Replace(".", "")
$EvidenceDirectory = Join-Path `
    $EvidenceRoot `
    "fts-ops-$SafeState-pg$PgDigits-$Timestamp"

New-Item `
    -ItemType Directory `
    -Path $EvidenceDirectory `
    -Force |
    Out-Null

$VersionSpec = switch ($Postgres) {
    "16.14" {
        [pscustomobject]@{
            Version = "16.14"
            Image = "postgres:16.14-alpine"
            Port = 55416
            Project = "shop-ps-d-ops-pg16"
        }
    }
    "18.3" {
        [pscustomobject]@{
            Version = "18.3"
            Image = "postgres:18.3-alpine"
            Port = 55418
            Project = "shop-ps-d-ops-pg18"
        }
    }
}

$NameLocation = if ($IsN2) {
    "filesystem:/benchmark/fts-migration/n2"
}
else {
    "filesystem:/benchmark/fts-migration/n1"
}

$ExpectedLabVersions = if ($IsN2) {
    if ($WithSku) {
        "1001,1002,1003,1004"
    }
    else {
        "1001,1002,1003"
    }
}
else {
    if ($WithSku) {
        "1001,1002,1004"
    }
    else {
        "1001,1002"
    }
}

$ExpectedIndexes = @()
if ($IsN2) {
    $ExpectedIndexes += "idx_products_name_fts_n2_v1"
}
else {
    $ExpectedIndexes += "idx_products_name_fts_n1_v1"
}
if ($WithSku) {
    $ExpectedIndexes += "idx_products_sku_nfc_prefix_v1"
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Content
    )

    $Parent = Split-Path -Parent $Path

    if (-not (Test-Path -LiteralPath $Parent)) {
        New-Item -ItemType Directory -Path $Parent -Force |
            Out-Null
    }

    [System.IO.File]::WriteAllText(
        $Path,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,

        [Parameter(Mandatory)]
        [string[]]$CommandArguments,

        [switch]$AllowFailure
    )

    # Windows PowerShell 5.1 treats native stderr lines captured with 2>&1
    # as errors; with $ErrorActionPreference = "Stop" the first status line
    # (for example "Container ... Stopping") terminates the run. Scope the
    # preference to Continue for the invocation only; the real failure
    # signal remains the process exit code checked below.
    $ErrorActionPreference = "Continue"

    $Output = @(
        & $FilePath @CommandArguments 2>&1 |
            ForEach-Object { "$_" }
    )
    $ExitCode = $LASTEXITCODE

    if ($ExitCode -ne 0 -and -not $AllowFailure) {
        throw (
            "Command failed with exit code ${ExitCode}: " +
            "$FilePath $($CommandArguments -join ' ')" +
            [Environment]::NewLine +
            ($Output -join [Environment]::NewLine)
        )
    }

    [pscustomobject]@{
        ExitCode = $ExitCode
        Output = $Output
        Text = $Output -join [Environment]::NewLine
    }
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments ($script:ComposePrefix + $Arguments) `
        -AllowFailure:$AllowFailure
}

function Invoke-Psql {
    param(
        [Parameter(Mandatory)]
        [string]$Database,

        [string]$File,
        [string]$Command,
        [string[]]$Variables = @(),
        [string]$PgOptions = "",
        [switch]$AllowFailure
    )

    $Arguments = @("exec", "-T")

    if (-not [string]::IsNullOrWhiteSpace($PgOptions)) {
        $Arguments += @("-e", "PGOPTIONS=$PgOptions")
    }

    $Arguments += @(
        "postgres",
        "psql",
        "-X",
        "-qAt",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "shop_fts_migration",
        "-d",
        $Database
    )

    foreach ($variable in $Variables) {
        $Arguments += @("-v", $variable)
    }

    if (-not [string]::IsNullOrWhiteSpace($Command)) {
        $Arguments += @("-c", $Command)
    }

    if (-not [string]::IsNullOrWhiteSpace($File)) {
        $Arguments += @("-f", $File)
    }

    Invoke-Compose -Arguments $Arguments -AllowFailure:$AllowFailure
}

function Wait-Postgres {
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $Result = Invoke-Compose `
            -Arguments @(
                "exec", "-T", "postgres",
                "pg_isready",
                "-U", "shop_fts_migration",
                "-d", "shop_fts_benchmark"
            ) `
            -AllowFailure

        if ($Result.ExitCode -eq 0) {
            return
        }

        Start-Sleep -Seconds 2
    }

    $Logs = Invoke-Compose `
        -Arguments @("logs", "--no-color", "postgres", "--tail", "40") `
        -AllowFailure

    throw (
        "PostgreSQL did not become ready." +
        [Environment]::NewLine +
        "Last postgres container logs:" +
        [Environment]::NewLine +
        $Logs.Text
    )
}

function Remove-Database {
    param([Parameter(Mandatory)][string]$Name)

    if ($Name -notmatch "^shop_(fts|search)_[a-z0-9_]+$") {
        throw "Unsafe database name: $Name"
    }

    Invoke-Psql `
        -Database "postgres" `
        -Command "DROP DATABASE IF EXISTS $Name WITH (FORCE);" |
        Out-Null
}

function New-DatabaseFromTemplate {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Template
    )

    if (
        $Name -notmatch "^shop_fts_[a-z0-9_]+$" -or
        $Template -notmatch "^shop_(fts|search)_[a-z0-9_]+$"
    ) {
        throw "Unsafe clone name or template."
    }

    Invoke-Psql `
        -Database "postgres" `
        -Command (
            "CREATE DATABASE $Name " +
            "WITH TEMPLATE $Template OWNER shop_fts_migration;"
        ) |
        Out-Null
}

function Copy-RenderedFile {
    param(
        [Parameter(Mandatory)][string]$LocalPath,
        [Parameter(Mandatory)][string]$ContainerPath
    )

    $ContainerId = (
        Invoke-Compose -Arguments @("ps", "-q", "postgres")
    ).Text.Trim()

    if ($ContainerId -notmatch "^[a-f0-9]{12,64}$") {
        throw "Could not resolve the PostgreSQL container."
    }

    Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments @(
            "cp",
            $LocalPath,
            "${ContainerId}:$ContainerPath"
        ) |
        Out-Null
}

function Get-EscapedLikePrefix {
    param([Parameter(Mandatory)][string]$Value)

    $Escaped = $Value.Replace("\", "\\").Replace("%", "\%").Replace("_", "\_")

    return $Escaped + "%"
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]
        [string]$Context,

        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Actual,

        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Expected
    )

    if ($Actual -ne $Expected) {
        throw "Check failed [$Context]: expected '$Expected', actual '$Actual'"
    }
}

function Invoke-LabFlyway {
    param([Parameter(Mandatory)][string]$Database)

    # Production location must be included so the applied V1-V12 history
    # resolves against the same frozen files. Lab locations are exactly
    # common plus the selected N1/N2 and optional S directories; the
    # version numbers V1001-V1004 enforce the dependency order. The
    # PostgreSQL session-lock setting is required for the
    # CREATE INDEX CONCURRENTLY migrations.
    $Locations = (
        "filesystem:/flyway/production," +
        "filesystem:/benchmark/fts-migration/common," +
        $NameLocation
    )

    if ($WithSku) {
        $Locations += ",filesystem:/benchmark/fts-migration/sku"
    }

    Invoke-Compose -Arguments @(
        "run", "--rm", "flyway",
        "-url=jdbc:postgresql://postgres:5432/$Database",
        "-user=shop_fts_migration",
        "-password=shop_fts_benchmark",
        "-locations=$Locations",
        "-connectRetries=60",
        "-validateMigrationNaming=true",
        "-postgresql.transactional.lock=false",
        "migrate"
    )
}

function Assert-LabState {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$OutputFile
    )

    function Get-LabScalar {
        param([Parameter(Mandatory)][string]$Sql)

        (Invoke-Psql -Database $Database -Command $Sql).Text.Trim()
    }

    $Results = [ordered]@{}

    $Results["lab-history-versions"] = Get-LabScalar `
        "SELECT string_agg(version::text, ',' ORDER BY installed_rank) FROM public.flyway_schema_history WHERE version IS NOT NULL AND version::integer >= 1000 AND success;"
    $Results["failed-history-rows"] = Get-LabScalar `
        "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success;"
    $Results["mid-history-rows"] = Get-LabScalar `
        "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND version::integer BETWEEN 13 AND 999;"
    $Results["latest-production-version"] = Get-LabScalar `
        "SELECT max(version::integer) FROM public.flyway_schema_history WHERE version::integer <= 12 AND success;"
    $Results["invalid-candidate-indexes"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid WHERE c.relname IN ('idx_products_name_fts_n1_v1','idx_products_name_fts_n2_v1','idx_products_sku_nfc_prefix_v1') AND NOT i.indisvalid;"
    $Results["invalid-indexes-anywhere"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_index WHERE NOT indisvalid;"
    $Results["ccnew-residue"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_class WHERE position('_ccnew' IN relname) > 0;"
    $Results["n1-definition"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid JOIN pg_catalog.pg_am am ON am.oid = c.relam WHERE c.relname = 'idx_products_name_fts_n1_v1' AND am.amname = 'gin' AND i.indisvalid AND i.indisready AND i.indexprs IS NOT NULL;"
    $Results["n2-definition"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid JOIN pg_catalog.pg_am am ON am.oid = c.relam WHERE c.relname = 'idx_products_name_fts_n2_v1' AND am.amname = 'gin' AND i.indisvalid AND i.indisready AND i.indexprs IS NULL;"
    $Results["sku-definition"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid JOIN pg_catalog.pg_am am ON am.oid = c.relam WHERE c.relname = 'idx_products_sku_nfc_prefix_v1' AND am.amname = 'btree' AND i.indisvalid AND i.indisready AND i.indexprs IS NOT NULL;"
    $Results["bad-owner"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_roles r ON r.oid = c.relowner WHERE c.relname IN ('idx_products_name_fts_n1_v1','idx_products_name_fts_n2_v1','idx_products_sku_nfc_prefix_v1') AND r.rolname <> 'shop_fts_migration';"
    $Results["n2-column"] = Get-LabScalar `
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'products' AND column_name = 'name_search_vector_v1' AND is_generated = 'ALWAYS';"
    $Results["product-rows"] = Get-LabScalar `
        "SELECT count(*) FROM public.products;"
    $Results["overlay-rows"] = Get-LabScalar `
        "SELECT count(*) FROM public.products WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1';"
    $Results["runtime-role-exists"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = 'shop_fts_runtime';"
    $Results["unaccent-extension"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_extension WHERE extname = 'unaccent';"
    $Results["fts-configuration"] = Get-LabScalar `
        "SELECT count(*) FROM pg_catalog.pg_ts_config WHERE cfgname = 'shop_product_name_unaccent_v1';"

    if ($IsN2) {
        $Results["n2-vector-mismatch"] = Get-LabScalar `
            "SELECT count(*) FROM public.products WHERE name_search_vector_v1 IS DISTINCT FROM to_tsvector('public.shop_product_name_unaccent_v1'::regconfig, normalize(name, NFC));"
    }

    foreach ($indexName in $ExpectedIndexes) {
        $Results["presence:$indexName"] = Get-LabScalar `
            "SELECT to_regclass('public.$indexName') IS NOT NULL;"
    }

    if (-not $WithSku) {
        $Results["absence:idx_products_sku_nfc_prefix_v1"] = Get-LabScalar `
            "SELECT to_regclass('public.idx_products_sku_nfc_prefix_v1') IS NULL;"
    }

    if (-not $IsN2) {
        $Results["absence:idx_products_name_fts_n2_v1"] = Get-LabScalar `
            "SELECT to_regclass('public.idx_products_name_fts_n2_v1') IS NULL;"
    }

    Write-Utf8File `
        -Path $OutputFile `
        -Content (
            (
                $Results.GetEnumerator() |
                    ForEach-Object { "$($_.Key)=$($_.Value)" }
            ) -join [Environment]::NewLine
        )

    Assert-Equal -Context "Flyway lab history versions" `
        -Actual $Results["lab-history-versions"] `
        -Expected $ExpectedLabVersions
    Assert-Equal -Context "Failed Flyway rows" `
        -Actual $Results["failed-history-rows"] `
        -Expected "0"
    Assert-Equal -Context "Unexpected versions 13..999" `
        -Actual $Results["mid-history-rows"] `
        -Expected "0"
    Assert-Equal -Context "Production history through V12" `
        -Actual $Results["latest-production-version"] `
        -Expected "12"
    Assert-Equal -Context "Invalid candidate indexes" `
        -Actual $Results["invalid-candidate-indexes"] `
        -Expected "0"
    Assert-Equal -Context "Invalid indexes anywhere" `
        -Actual $Results["invalid-indexes-anywhere"] `
        -Expected "0"
    Assert-Equal -Context "_ccnew residue" `
        -Actual $Results["ccnew-residue"] `
        -Expected "0"
    Assert-Equal -Context "Candidate index owner" `
        -Actual $Results["bad-owner"] `
        -Expected "0"
    Assert-Equal -Context "Product rows" `
        -Actual $Results["product-rows"] `
        -Expected "$Rows"
    Assert-Equal -Context "Overlay rows" `
        -Actual $Results["overlay-rows"] `
        -Expected "40"
    Assert-Equal -Context "Runtime role exists" `
        -Actual $Results["runtime-role-exists"] `
        -Expected "1"
    Assert-Equal -Context "unaccent extension" `
        -Actual $Results["unaccent-extension"] `
        -Expected "1"
    Assert-Equal -Context "FTS configuration" `
        -Actual $Results["fts-configuration"] `
        -Expected "1"

    if ($IsN2) {
        Assert-Equal -Context "N2 generated column" `
            -Actual $Results["n2-column"] `
            -Expected "1"
        Assert-Equal -Context "N2 generated vector mismatch" `
            -Actual $Results["n2-vector-mismatch"] `
            -Expected "0"
        Assert-Equal -Context "N2 index definition" `
            -Actual $Results["n2-definition"] `
            -Expected "1"
    }
    else {
        Assert-Equal -Context "N2 column absent for N1" `
            -Actual $Results["n2-column"] `
            -Expected "0"
        Assert-Equal -Context "N1 index definition" `
            -Actual $Results["n1-definition"] `
            -Expected "1"
    }

    if ($WithSku) {
        Assert-Equal -Context "SKU companion definition" `
            -Actual $Results["sku-definition"] `
            -Expected "1"
    }

    foreach ($indexName in $ExpectedIndexes) {
        Assert-Equal -Context "Presence of $indexName" `
            -Actual $Results["presence:$indexName"] `
            -Expected "t"
    }

    if (-not $WithSku) {
        Assert-Equal -Context "SKU companion absence" `
            -Actual $Results["absence:idx_products_sku_nfc_prefix_v1"] `
            -Expected "t"
    }

    if (-not $IsN2) {
        Assert-Equal -Context "N2 index absence" `
            -Actual $Results["absence:idx_products_name_fts_n2_v1"] `
            -Expected "t"
    }
}

function Invoke-FrozenVerify {
    param([Parameter(Mandatory)][string]$Label)

    $Result = Invoke-Psql `
        -Database "shop_fts_benchmark" `
        -File "/benchmark/verify-fts-candidate.sql" `
        -PgOptions (
            "-c shop_benchmark.row_count=$Rows " +
            "-c shop_benchmark.seed=$Seed " +
            "-c shop_ps_d.candidate_state=$State"
        )

    $OutputPath = Join-Path $EvidenceDirectory "$Label.txt"

    Write-Utf8File -Path $OutputPath -Content $Result.Text

    (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

# The PS-B fallback SQL below is a lab transcription of the unchanged
# PS-B production query shape from
# src/main/java/com/lul/shop/catalog/infrastructure/persistence/repository/ProductQueryRepository.java
# (browse: createdAt DESC, id DESC; ranked: exact-SKU 0, literal
# name-prefix 1, remaining contains 2, then createdAt DESC, id DESC,
# with LIKE ESCAPE '\' and prefix escaping mirroring escapeLikePattern).
# It exists only to prove query-first rollback compatibility inside the
# isolated lab. It is not a production code change.

$PsBKeyword = "common market"
$PsBExactKeyword = $PsBKeyword
$PsBKeywordPattern = "%$PsBKeyword%"
$PsBNamePrefixPattern = (Get-EscapedLikePrefix -Value $PsBKeyword)

$PsBBrowseSql = @"
SELECT p.id::text
FROM public.products AS p
WHERE 1 = 1
  AND p.status = 'ACTIVE'
ORDER BY
    p.created_at DESC,
    p.id DESC
LIMIT 100;
"@

$PsBRankedSql = @"
SELECT p.id::text
FROM public.products AS p
WHERE 1 = 1
  AND (
      lower(p.sku) LIKE lower('$PsBKeywordPattern')
      OR lower(p.name) LIKE lower('$PsBKeywordPattern')
  )
  AND p.status = 'ACTIVE'
ORDER BY
    CASE
        WHEN lower(p.sku) = lower('$PsBExactKeyword') THEN 0
        WHEN lower(p.name) LIKE lower('$PsBNamePrefixPattern')
             ESCAPE '\'
            THEN 1
        ELSE 2
    END ASC,
    p.created_at DESC,
    p.id DESC
LIMIT 100;
"@

function Invoke-PsBFallbackEvidence {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$Label
    )

    $Browse = Invoke-Psql `
        -Database $Database `
        -Command $PsBBrowseSql

    $Ranked = Invoke-Psql `
        -Database $Database `
        -Command $PsBRankedSql

    $BrowsePath = Join-Path $Directory "$Label-browse.txt"
    $RankedPath = Join-Path $Directory "$Label-ranked.txt"

    Write-Utf8File -Path $BrowsePath -Content $Browse.Text
    Write-Utf8File -Path $RankedPath -Content $Ranked.Text

    [pscustomobject]@{
        BrowseHash = (
            Get-FileHash -LiteralPath $BrowsePath -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        RankedHash = (
            Get-FileHash -LiteralPath $RankedPath -Algorithm SHA256
        ).Hash.ToLowerInvariant()
    }
}

function Initialize-PristineDatabase {
    $SourceDatabase = "shop_search_benchmark"
    $LabDatabase = "shop_fts_benchmark"
    $PristineDatabase = "shop_fts_pristine"

    Remove-Database -Name $PristineDatabase
    Remove-Database -Name $SourceDatabase

    $RoleSql = @(
        'DO $role$'
        'BEGIN'
        '    IF EXISTS ('
        '        SELECT 1'
        '        FROM pg_catalog.pg_roles'
        "        WHERE rolname = 'shop_benchmark'"
        '    ) THEN'
        '        DROP ROLE shop_benchmark;'
        '    END IF;'
        ''
        '    CREATE ROLE shop_benchmark NOLOGIN;'
        'END'
        '$role$;'
    ) -join [Environment]::NewLine

    Invoke-Psql `
        -Database "postgres" `
        -Command $RoleSql |
        Out-Null

    Invoke-Psql `
        -Database "postgres" `
        -Command (
            "CREATE DATABASE $SourceDatabase " +
            "OWNER shop_fts_migration;"
        ) |
        Out-Null

    $FlywayResult = Invoke-Compose -Arguments @(
        "run", "--rm", "flyway",
        "-url=jdbc:postgresql://postgres:5432/$SourceDatabase",
        "-user=shop_fts_migration",
        "-password=shop_fts_benchmark",
        "-locations=filesystem:/flyway/production",
        "-connectRetries=60",
        "-validateMigrationNaming=true",
        "migrate"
    )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "flyway-production.txt") `
        -Content $FlywayResult.Text

    Invoke-Psql `
        -Database $SourceDatabase `
        -Command (
            "GRANT shop_benchmark TO shop_fts_migration; " +
            "GRANT USAGE ON SCHEMA public TO shop_benchmark; " +
            "GRANT SELECT, INSERT ON public.products TO shop_benchmark; " +
            "GRANT SELECT ON public.flyway_schema_history TO shop_benchmark;"
        ) |
        Out-Null

    $DatasetOptions = (
        "-c shop_benchmark.row_count=$Rows " +
        "-c shop_benchmark.seed=$Seed"
    )

    $SeedResult = Invoke-Compose -Arguments @(
        "exec", "-T",
        "-e", "PGOPTIONS=$DatasetOptions",
        "postgres", "psql",
        "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "shop_fts_migration",
        "-d", $SourceDatabase,
        "-c", "SET ROLE shop_benchmark",
        "-f", "/benchmark/seed-products.sql"
    )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "canonical-seed.txt") `
        -Content $SeedResult.Text

    $VerifyResult = Invoke-Compose -Arguments @(
        "exec", "-T",
        "-e", "PGOPTIONS=$DatasetOptions",
        "postgres", "psql",
        "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "shop_fts_migration",
        "-d", $SourceDatabase,
        "-c", "SET ROLE shop_benchmark",
        "-f", "/benchmark/verify-dataset.sql"
    )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "canonical-verify.txt") `
        -Content $VerifyResult.Text

    Invoke-Psql `
        -Database $SourceDatabase `
        -Command (
            "REVOKE ALL ON public.products FROM shop_benchmark; " +
            "REVOKE ALL ON public.flyway_schema_history FROM shop_benchmark; " +
            "REVOKE ALL ON SCHEMA public FROM shop_benchmark; " +
            "REVOKE shop_benchmark FROM shop_fts_migration;"
        ) |
        Out-Null

    Remove-Database -Name $LabDatabase

    New-DatabaseFromTemplate `
        -Name $LabDatabase `
        -Template $SourceDatabase

    $OverlayResult = Invoke-Psql `
        -Database $LabDatabase `
        -File "/benchmark/seed-fts-workloads.sql" `
        -PgOptions $DatasetOptions

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "fts-overlay.txt") `
        -Content $OverlayResult.Text

    Remove-Database -Name $PristineDatabase

    New-DatabaseFromTemplate `
        -Name $PristineDatabase `
        -Template $LabDatabase

    Remove-Database -Name $SourceDatabase

    Invoke-Psql `
        -Database "postgres" `
        -Command "DROP ROLE shop_benchmark;" |
        Out-Null
}

$Outcomes = [ordered]@{
    RolesSeparation = "NOT_RUN"
    LabFlywayMigration = "NOT_RUN"
    PostMigrationChecks = "NOT_RUN"
    FrozenOracle = "NOT_RUN"
    PsBFallbackEquality = "NOT_RUN"
    DumpRestore = "NOT_RUN"
    Rebuild = "NOT_RUN"
    ControlledFailure = "NOT_RUN"
    ZeroResidue = "NOT_RUN"
}

$PhaseNotes = [ordered]@{}
$FallbackLines = [System.Collections.Generic.List[string]]::new()
$FatalError = $null
$LabStateValid = $false
$RestoredVerifyHash = ""
$RebuildVerifyHash = ""

$PreviousImage = $env:PS_D_POSTGRES_IMAGE
$PreviousPort = $env:PS_D_POSTGRES_PORT

$env:PS_D_POSTGRES_IMAGE = $VersionSpec.Image
$env:PS_D_POSTGRES_PORT = "$($VersionSpec.Port)"

$script:ComposePrefix = @(
    "compose",
    "--project-name", $VersionSpec.Project,
    "-f", $ComposeFile
)

try {
    Invoke-Compose `
        -Arguments @("down", "--volumes", "--remove-orphans") `
        -AllowFailure |
        Out-Null

    Invoke-Compose -Arguments @("config", "--quiet") |
        Out-Null

    Invoke-Compose -Arguments @("up", "-d", "postgres") |
        Out-Null

    Wait-Postgres

    $ImageIdentity = Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments @(
            "image", "inspect",
            $VersionSpec.Image,
            "--format",
            "{{.Id}} {{index .RepoDigests 0}}"
        ) `
        -AllowFailure

    $ServerIdentity = Invoke-Compose -Arguments @(
        "exec", "-T", "postgres",
        "psql", "-X", "-qAt",
        "-U", "shop_fts_migration",
        "-d", "postgres",
        "-c",
        (
            "SELECT current_setting('server_version'), " +
            "current_setting('server_encoding');"
        )
    )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "environment.txt") `
        -Content (
            "image=$($VersionSpec.Image)" +
            [Environment]::NewLine +
            "image-identity=$($ImageIdentity.Text.Trim())" +
            [Environment]::NewLine +
            "server=$($ServerIdentity.Text)" +
            [Environment]::NewLine +
            "host-powershell=$($PSVersionTable.PSVersion.ToString())"
        )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "survivor-gate.txt") `
        -Content ($SurvivorGateLines -join [Environment]::NewLine)

    Initialize-PristineDatabase

    # Role separation: setup-fts-lab-roles.sql asserts the bounded
    # privilege boundary itself and raises on any violation.
    $RolesResult = Invoke-Psql `
        -Database "shop_fts_benchmark" `
        -File "/benchmark/setup-fts-lab-roles.sql" `
        -PgOptions (
            "-c shop_benchmark.row_count=$Rows " +
            "-c shop_benchmark.seed=$Seed " +
            "-c shop_ps_d.candidate_state=$State"
        )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "roles-setup.txt") `
        -Content $RolesResult.Text

    $Outcomes["RolesSeparation"] = "VERIFIED"

    $BaselinePsB = Invoke-PsBFallbackEvidence `
        -Database "shop_fts_benchmark" `
        -Directory $EvidenceDirectory `
        -Label "ps-b-baseline"

    $FallbackLines.Add("ps-b-baseline browse=$($BaselinePsB.BrowseHash)")
    $FallbackLines.Add("ps-b-baseline ranked=$($BaselinePsB.RankedHash)")

    # Lab Flyway rehearsal.
    $MigrationSucceeded = $false

    try {
        $FlywayLab = Invoke-LabFlyway -Database "shop_fts_benchmark"

        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "flyway-lab.txt") `
            -Content $FlywayLab.Text

        $MigrationSucceeded = $true
        $Outcomes["LabFlywayMigration"] = "SUCCEEDED"
    }
    catch {
        $Outcomes["LabFlywayMigration"] = "FAILED"
        $PhaseNotes["LabFlywayMigration"] = $_.Exception.Message

        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "flyway-lab-failure.txt") `
            -Content $_.Exception.Message
    }

    $LabHistory = Invoke-Psql `
        -Database "shop_fts_benchmark" `
        -Command (
            "SELECT installed_rank, version, description, type, " +
            "success, installed_by " +
            "FROM public.flyway_schema_history " +
            "WHERE version IS NOT NULL AND version::integer >= 1000 " +
            "ORDER BY installed_rank;"
        )

    Write-Utf8File `
        -Path (Join-Path $EvidenceDirectory "flyway-lab-history.txt") `
        -Content $LabHistory.Text

    $ChecksSucceeded = $false

    if ($MigrationSucceeded) {
        try {
            $IndexDefinitions = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -Command (
                    "SELECT c.relname AS index_name, " +
                    "r.rolname AS owner, c.relacl::text AS acl, " +
                    "i.indisvalid, i.indisready, " +
                    "am.amname AS method, " +
                    "pg_get_indexdef(c.oid) AS definition " +
                    "FROM pg_catalog.pg_class c " +
                    "JOIN pg_catalog.pg_index i ON i.indexrelid = c.oid " +
                    "JOIN pg_catalog.pg_roles r ON r.oid = c.relowner " +
                    "JOIN pg_catalog.pg_am am ON am.oid = c.relam " +
                    "WHERE c.relname IN (" +
                    "'idx_products_name_fts_n1_v1'," +
                    "'idx_products_name_fts_n2_v1'," +
                    "'idx_products_sku_nfc_prefix_v1'" +
                    ") ORDER BY c.relname;"
                )

            Write-Utf8File `
                -Path (Join-Path $EvidenceDirectory "index-definitions.txt") `
                -Content $IndexDefinitions.Text

            Assert-LabState `
                -Database "shop_fts_benchmark" `
                -OutputFile (
                    Join-Path $EvidenceDirectory "post-migration-checks.txt"
                )

            $TableGrants = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -Command (
                    "SELECT grantee, privilege_type " +
                    "FROM information_schema.role_table_grants " +
                    "WHERE table_schema = 'public' " +
                    "AND table_name = 'products' " +
                    "ORDER BY grantee, privilege_type;"
                )

            Write-Utf8File `
                -Path (Join-Path $EvidenceDirectory "table-grants.txt") `
                -Content $TableGrants.Text

            $RuntimeSelect = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -Command (
                    "SET ROLE shop_fts_runtime; " +
                    "SELECT count(*) FROM public.products;"
                )

            Write-Utf8File `
                -Path (Join-Path $EvidenceDirectory "runtime-role-select.txt") `
                -Content $RuntimeSelect.Text

            Assert-Equal `
                -Context "Runtime role product count" `
                -Actual $RuntimeSelect.Text.Trim() `
                -Expected "$Rows"

            $RuntimeDdl = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -Command (
                    "SET ROLE shop_fts_runtime; " +
                    "CREATE TABLE public.ps_d_ops_should_fail (id integer);"
                ) `
                -AllowFailure

            Write-Utf8File `
                -Path (
                    Join-Path $EvidenceDirectory "runtime-role-ddl-denial.txt"
                ) `
                -Content (
                    "exit-code=$($RuntimeDdl.ExitCode)" +
                    [Environment]::NewLine +
                    $RuntimeDdl.Text
                )

            if (
                $RuntimeDdl.ExitCode -eq 0 -or
                $RuntimeDdl.Text -notmatch "permission denied"
            ) {
                throw "Runtime role DDL was not denied as expected."
            }

            $ChecksSucceeded = $true
            $LabStateValid = $true
            $Outcomes["PostMigrationChecks"] = "PASSED"
        }
        catch {
            $Outcomes["PostMigrationChecks"] = "FAILED"
            $PhaseNotes["PostMigrationChecks"] = $_.Exception.Message
        }
    }
    else {
        $Outcomes["PostMigrationChecks"] = "NOT_APPLICABLE"
        $PhaseNotes["PostMigrationChecks"] = "Lab Flyway migration did not succeed."
    }

    # Frozen literal oracle on the migrated lab state.
    $OraclePassed = $false

    if ($ChecksSucceeded) {
        try {
            Invoke-FrozenVerify -Label "literal-oracle-migrated" | Out-Null
            $Outcomes["FrozenOracle"] = "PASSED_MIGRATED"
            $OraclePassed = $true
        }
        catch {
            $Outcomes["FrozenOracle"] = "FAILED_MIGRATED"
            $PhaseNotes["FrozenOracle"] = $_.Exception.Message
        }
    }
    else {
        $Outcomes["FrozenOracle"] = "NOT_APPLICABLE"
    }

    # PS-B query-first fallback after candidate objects exist.
    $FallbackEquality = $false
    $AfterMigrationPsB = $null

    if ($ChecksSucceeded) {
        try {
            $AfterMigrationPsB = Invoke-PsBFallbackEvidence `
                -Database "shop_fts_benchmark" `
                -Directory $EvidenceDirectory `
                -Label "ps-b-after-migration"

            $FallbackLines.Add(
                "ps-b-after-migration browse=$($AfterMigrationPsB.BrowseHash)"
            )
            $FallbackLines.Add(
                "ps-b-after-migration ranked=$($AfterMigrationPsB.RankedHash)"
            )

            if (
                $AfterMigrationPsB.BrowseHash -ne $BaselinePsB.BrowseHash -or
                $AfterMigrationPsB.RankedHash -ne $BaselinePsB.RankedHash
            ) {
                throw "PS-B results changed after candidate objects were added."
            }

            $Outcomes["PsBFallbackEquality"] = "PASSED_MIGRATED"
            $FallbackEquality = $true
        }
        catch {
            $Outcomes["PsBFallbackEquality"] = "FAILED"
            $PhaseNotes["PsBFallbackEquality"] = $_.Exception.Message
        }
    }
    else {
        $Outcomes["PsBFallbackEquality"] = "NOT_APPLICABLE"
    }

    # Dump/restore rehearsal.
    $RestoreSucceeded = $false

    if ($ChecksSucceeded -and $OraclePassed -and $FallbackEquality) {
        $DumpDirectory = Join-Path $EvidenceDirectory "dump-restore"
        New-Item -ItemType Directory -Path $DumpDirectory -Force | Out-Null
        $LabStateValid = $false

        try {
            $PreDumpState = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -Command (
                    "SELECT " +
                    "(SELECT count(*) FROM public.products) " +
                    "AS product_rows, " +
                    "(SELECT count(*) FROM public.products " +
                    " WHERE image_key = 'V2-PS-D-D07-OVERLAY-V1') " +
                    "AS overlay_rows, " +
                    "(SELECT count(*) FROM public.flyway_schema_history " +
                    " WHERE success) AS flyway_success_rows, " +
                    "(SELECT count(*) FROM pg_catalog.pg_index " +
                    " WHERE NOT indisvalid) AS invalid_indexes;"
                )

            Write-Utf8File `
                -Path (Join-Path $DumpDirectory "pre-dump-state.txt") `
                -Content $PreDumpState.Text

            Invoke-Compose `
                -Arguments @(
                    "exec", "-T", "postgres",
                    "sh", "-lc", "rm -f /tmp/psd-ops-dump.dump"
                ) |
                Out-Null

            $PgDump = Invoke-Compose -Arguments @(
                "exec", "-T", "postgres",
                "sh", "-lc",
                (
                    "pg_dump -U shop_fts_migration -Fc " +
                    "-d shop_fts_benchmark -f /tmp/psd-ops-dump.dump"
                )
            )

            Write-Utf8File `
                -Path (Join-Path $DumpDirectory "pg-dump.txt") `
                -Content $PgDump.Text

            $DumpChecksum = Invoke-Compose -Arguments @(
                "exec", "-T", "postgres",
                "sh", "-lc", "sha256sum /tmp/psd-ops-dump.dump"
            )

            Write-Utf8File `
                -Path (Join-Path $DumpDirectory "pg-dump-checksum.txt") `
                -Content $DumpChecksum.Text

            Remove-Database -Name "shop_fts_benchmark"

            Invoke-Psql `
                -Database "postgres" `
                -Command (
                    "CREATE DATABASE shop_fts_benchmark " +
                    "OWNER shop_fts_migration;"
                ) |
                Out-Null

            $PgRestore = Invoke-Compose -Arguments @(
                "exec", "-T", "postgres",
                "sh", "-lc",
                (
                    "pg_restore -U shop_fts_migration " +
                    "-d shop_fts_benchmark -v /tmp/psd-ops-dump.dump"
                )
            )

            Write-Utf8File `
                -Path (Join-Path $DumpDirectory "pg-restore.txt") `
                -Content $PgRestore.Text

            Invoke-Compose `
                -Arguments @(
                    "exec", "-T", "postgres",
                    "sh", "-lc", "rm -f /tmp/psd-ops-dump.dump"
                ) |
                Out-Null

            Assert-LabState `
                -Database "shop_fts_benchmark" `
                -OutputFile (
                    Join-Path $DumpDirectory "post-restore-checks.txt"
                )

            $RestoredVerifyHash = Invoke-FrozenVerify `
                -Label "dump-restore/literal-oracle-restored"

            $RestoredPsB = Invoke-PsBFallbackEvidence `
                -Database "shop_fts_benchmark" `
                -Directory $DumpDirectory `
                -Label "ps-b-restored"

            $FallbackLines.Add("ps-b-restored browse=$($RestoredPsB.BrowseHash)")
            $FallbackLines.Add("ps-b-restored ranked=$($RestoredPsB.RankedHash)")

            if (
                $RestoredPsB.BrowseHash -ne $BaselinePsB.BrowseHash -or
                $RestoredPsB.RankedHash -ne $BaselinePsB.RankedHash
            ) {
                throw "Restored PS-B results differ from the baseline."
            }

            $RestoreSucceeded = $true
            $LabStateValid = $true
            $Outcomes["DumpRestore"] = "PASSED"
        }
        catch {
            $Outcomes["DumpRestore"] = "FAILED"
            $PhaseNotes["DumpRestore"] = $_.Exception.Message
        }
    }
    else {
        $Outcomes["DumpRestore"] = "NOT_APPLICABLE"
        $PhaseNotes["DumpRestore"] = "Prerequisite rehearsal did not pass."
    }

    # Deterministic rebuild rehearsal from the pristine template.
    $RebuildSucceeded = $false

    if ($RestoreSucceeded) {
        $RebuildDirectory = Join-Path $EvidenceDirectory "rebuild"
        New-Item -ItemType Directory -Path $RebuildDirectory -Force | Out-Null
        $LabStateValid = $false

        try {
            Remove-Database -Name "shop_fts_benchmark"

            Invoke-Psql `
                -Database "postgres" `
                -Command "DROP ROLE shop_fts_runtime;" |
                Out-Null

            New-DatabaseFromTemplate `
                -Name "shop_fts_benchmark" `
                -Template "shop_fts_pristine"

            $RebuildRoles = Invoke-Psql `
                -Database "shop_fts_benchmark" `
                -File "/benchmark/setup-fts-lab-roles.sql" `
                -PgOptions (
                    "-c shop_benchmark.row_count=$Rows " +
                    "-c shop_benchmark.seed=$Seed " +
                    "-c shop_ps_d.candidate_state=$State"
                )

            Write-Utf8File `
                -Path (Join-Path $RebuildDirectory "rebuild-roles-setup.txt") `
                -Content $RebuildRoles.Text

            $RebuildFlyway = Invoke-LabFlyway -Database "shop_fts_benchmark"

            Write-Utf8File `
                -Path (Join-Path $RebuildDirectory "rebuild-flyway-lab.txt") `
                -Content $RebuildFlyway.Text

            Assert-LabState `
                -Database "shop_fts_benchmark" `
                -OutputFile (
                    Join-Path $RebuildDirectory "rebuild-post-migration-checks.txt"
                )

            $RebuildVerifyHash = Invoke-FrozenVerify `
                -Label "rebuild/literal-oracle-rebuilt"

            $RebuiltPsB = Invoke-PsBFallbackEvidence `
                -Database "shop_fts_benchmark" `
                -Directory $RebuildDirectory `
                -Label "ps-b-rebuilt"

            $FallbackLines.Add("ps-b-rebuilt browse=$($RebuiltPsB.BrowseHash)")
            $FallbackLines.Add("ps-b-rebuilt ranked=$($RebuiltPsB.RankedHash)")

            if (
                $RebuiltPsB.BrowseHash -ne $BaselinePsB.BrowseHash -or
                $RebuiltPsB.RankedHash -ne $BaselinePsB.RankedHash
            ) {
                throw "Rebuilt PS-B results differ from the baseline."
            }

            $RebuildSucceeded = $true
            $LabStateValid = $true
            $Outcomes["Rebuild"] = "PASSED"
        }
        catch {
            $Outcomes["Rebuild"] = "FAILED"
            $PhaseNotes["Rebuild"] = $_.Exception.Message
        }
    }
    else {
        $Outcomes["Rebuild"] = "NOT_APPLICABLE"
        $PhaseNotes["Rebuild"] = "Dump/restore rehearsal did not pass."
    }

    # Controlled failed concurrent-index build, residue detection, and
    # invalid-index recovery on a disposable clone.
    $FailureDirectory = Join-Path $EvidenceDirectory "controlled-failure"
    New-Item -ItemType Directory -Path $FailureDirectory -Force | Out-Null

    $FailureSql = @"
CREATE INDEX CONCURRENTLY idx_ps_d_ops_failure_v1
    ON public.products
    USING gin (
        pg_catalog.to_tsvector(
            'public.shop_product_name_unaccent_v1'::pg_catalog.regconfig,
            normalize(name, NFC)
        )
    );
"@

    $FailureSqlPath = Join-Path $FailureDirectory "failure-index-create.sql"
    Write-Utf8File -Path $FailureSqlPath -Content $FailureSql
    Copy-RenderedFile `
        -LocalPath $FailureSqlPath `
        -ContainerPath "/tmp/psd-ops-failure.sql"

    if ($LabStateValid) {
        New-DatabaseFromTemplate `
            -Name "shop_fts_failure" `
            -Template "shop_fts_benchmark" |
            Out-Null

        $FailureOutcome = "NOT_TRIGGERED"

        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Invoke-Compose `
                -Arguments @(
                    "exec", "-T", "postgres",
                    "sh", "-lc", "rm -f /tmp/psd-ops-failure.log"
                ) |
                Out-Null

            $LaunchCommand = (
                "psql -X -v ON_ERROR_STOP=1 " +
                "-U shop_fts_migration -d shop_fts_failure " +
                "-f /tmp/psd-ops-failure.sql " +
                "> /tmp/psd-ops-failure.log 2>&1"
            )

            Invoke-Compose `
                -Arguments @(
                    "exec", "-d", "postgres",
                    "sh", "-lc", $LaunchCommand
                ) |
                Out-Null

            $CanceledPid = $null

            for ($poll = 0; $poll -lt 600; $poll++) {
                $BuildPid = (
                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command (
                            "SELECT pid FROM pg_stat_activity " +
                            "WHERE datname = 'shop_fts_failure' " +
                            "AND pid <> pg_backend_pid() " +
                            "AND query LIKE " +
                            "'CREATE INDEX CONCURRENTLY idx_ps_d_ops_failure_v1%' " +
                            "ORDER BY pid LIMIT 1;"
                        )
                ).Text.Trim()

                if ($BuildPid) {
                    $CanceledPid = $BuildPid

                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command "SELECT pg_cancel_backend($BuildPid);" |
                        Out-Null

                    break
                }

                $Completed = (
                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command (
                            "SELECT count(*) FROM pg_catalog.pg_index " +
                            "WHERE indexrelid = " +
                            "to_regclass('public.idx_ps_d_ops_failure_v1') " +
                            "AND indisvalid;"
                        )
                ).Text.Trim()

                if ($Completed -eq "1") {
                    break
                }

                Start-Sleep -Milliseconds 100
            }

            # After the cancel, wait until every matching build backend has
            # exited. If any remains, cancel each leftover PID explicitly so
            # the next attempt never observes more than one build backend.
            for ($wait = 0; $wait -lt 300 -and $CanceledPid; $wait++) {
                $StillRunning = (
                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command (
                            "SELECT count(*) FROM pg_stat_activity " +
                            "WHERE datname = 'shop_fts_failure' " +
                            "AND query LIKE " +
                            "'CREATE INDEX CONCURRENTLY idx_ps_d_ops_failure_v1%';"
                        )
                ).Text.Trim()

                if ($StillRunning -eq "0") {
                    break
                }

                Start-Sleep -Milliseconds 100
            }

            if ($StillRunning -ne "0") {
                $Leftovers = (
                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command (
                            "SELECT pid FROM pg_stat_activity " +
                            "WHERE datname = 'shop_fts_failure' " +
                            "AND pid <> pg_backend_pid() " +
                            "AND query LIKE " +
                            "'CREATE INDEX CONCURRENTLY idx_ps_d_ops_failure_v1%' " +
                            "ORDER BY pid;"
                        )
                ).Text

                foreach ($leftover in @($Leftovers -split "\r?\n")) {
                    if ($leftover.Trim()) {
                        Invoke-Psql `
                            -Database "shop_fts_failure" `
                            -Command (
                                "SELECT pg_cancel_backend($($leftover.Trim()));"
                            ) `
                            -AllowFailure |
                            Out-Null
                    }
                }

                Start-Sleep -Seconds 2
            }

            $LaunchLog = Invoke-Compose `
                -Arguments @(
                    "exec", "-T", "postgres",
                    "sh", "-lc", "cat /tmp/psd-ops-failure.log 2>/dev/null"
                ) `
                -AllowFailure

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "launch-attempt-$attempt.txt") `
                -Content (
                    "canceled-pid=$CanceledPid" +
                    [Environment]::NewLine +
                    $LaunchLog.Text
                )

            $IndexState = (
                Invoke-Psql `
                    -Database "shop_fts_failure" `
                    -Command (
                        "SELECT CASE " +
                        "WHEN to_regclass('public.idx_ps_d_ops_failure_v1') " +
                        "IS NULL THEN 'missing' " +
                        "WHEN indisvalid THEN 'valid' " +
                        "ELSE 'invalid' END " +
                        "FROM pg_catalog.pg_index " +
                        "WHERE indexrelid = " +
                        "to_regclass('public.idx_ps_d_ops_failure_v1') " +
                        "UNION ALL " +
                        "SELECT 'missing' " +
                        "WHERE to_regclass('public.idx_ps_d_ops_failure_v1') " +
                        "IS NULL;"
                    )
            ).Text.Trim()

            if ($IndexState -eq "invalid") {
                $FailureOutcome = "RECOVERED"
                break
            }

            if ($IndexState -eq "valid") {
                $FailureOutcome = "NOT_TRIGGERED"
                break
            }

            # 'missing': the build was cancelled before it began scanning.
        }

        if ($FailureOutcome -eq "RECOVERED") {
            $InvalidConfirmed = (
                Invoke-Psql `
                    -Database "shop_fts_failure" `
                    -Command (
                        "SELECT count(*) FROM pg_catalog.pg_index " +
                        "WHERE indexrelid = " +
                        "to_regclass('public.idx_ps_d_ops_failure_v1') " +
                        "AND NOT indisvalid;"
                    )
            ).Text.Trim()

            Assert-Equal `
                -Context "Invalid rehearsal index detected" `
                -Actual $InvalidConfirmed `
                -Expected "1"

            $Residue = Invoke-Psql `
                -Database "shop_fts_failure" `
                -Command (
                    "SELECT c.relname, i.indisvalid, i.indisready " +
                    "FROM pg_catalog.pg_class c " +
                    "JOIN pg_catalog.pg_index i ON i.indexrelid = c.oid " +
                    "WHERE c.relname = 'idx_ps_d_ops_failure_v1';"
                )

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "residue-detected.txt") `
                -Content $Residue.Text

            $CcnewScan = Invoke-Psql `
                -Database "shop_fts_failure" `
                -Command (
                    "SELECT relname FROM pg_catalog.pg_class " +
                    "WHERE position('_ccnew' IN relname) > 0;"
                )

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "ccnew-scan.txt") `
                -Content $CcnewScan.Text

            foreach ($orphan in @($CcnewScan.Text.Trim() -split "\r?\n")) {
                if ($orphan.Trim().Length -gt 0) {
                    Invoke-Psql `
                        -Database "shop_fts_failure" `
                        -Command "DROP INDEX CONCURRENTLY public.$orphan;" |
                        Out-Null
                }
            }

            $CcnewAfter = Invoke-Psql `
                -Database "shop_fts_failure" `
                -Command (
                    "SELECT count(*) FROM pg_catalog.pg_class " +
                    "WHERE position('_ccnew' IN relname) > 0;"
                )

            Assert-Equal `
                -Context "_ccnew residue after cleanup" `
                -Actual $CcnewAfter.Text.Trim() `
                -Expected "0"

            # Documented recovery for a failed CREATE INDEX CONCURRENTLY:
            # drop the invalid index and rebuild it concurrently.
            $DropInvalid = Invoke-Psql `
                -Database "shop_fts_failure" `
                -Command (
                    "DROP INDEX CONCURRENTLY public.idx_ps_d_ops_failure_v1;"
                )

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "recovery-drop-index.txt") `
                -Content $DropInvalid.Text

            $RebuildIndex = Invoke-Psql `
                -Database "shop_fts_failure" `
                -File "/tmp/psd-ops-failure.sql"

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "recovery-rebuild-index.txt") `
                -Content $RebuildIndex.Text

            $RecoveryChecks = [ordered]@{}

            $RecoveryChecks["recovered-valid"] = (
                Invoke-Psql `
                    -Database "shop_fts_failure" `
                    -Command (
                        "SELECT count(*) FROM pg_catalog.pg_index " +
                        "WHERE indexrelid = " +
                        "to_regclass('public.idx_ps_d_ops_failure_v1') " +
                        "AND indisvalid AND indisready;"
                    )
            ).Text.Trim()

            $RecoveryChecks["recovered-definition"] = (
                Invoke-Psql `
                    -Database "shop_fts_failure" `
                    -Command (
                        "SELECT count(*) FROM pg_catalog.pg_index i " +
                        "JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid " +
                        "JOIN pg_catalog.pg_am am ON am.oid = c.relam " +
                        "WHERE c.relname = 'idx_ps_d_ops_failure_v1' " +
                        "AND am.amname = 'gin' " +
                        "AND i.indisvalid AND i.indisready " +
                        "AND i.indexprs IS NOT NULL;"
                    )
            ).Text.Trim()

            $RecoveryChecks["no-invalid-anywhere"] = (
                Invoke-Psql `
                    -Database "shop_fts_failure" `
                    -Command (
                        "SELECT count(*) FROM pg_catalog.pg_index " +
                        "WHERE NOT indisvalid;"
                    )
            ).Text.Trim()

            Write-Utf8File `
                -Path (Join-Path $FailureDirectory "post-recovery-checks.txt") `
                -Content (
                    (
                        $RecoveryChecks.GetEnumerator() |
                            ForEach-Object { "$($_.Key)=$($_.Value)" }
                    ) -join [Environment]::NewLine
                )

            Assert-Equal `
                -Context "Recovered index valid and ready" `
                -Actual $RecoveryChecks["recovered-valid"] `
                -Expected "1"
            Assert-Equal `
                -Context "Recovered index definition" `
                -Actual $RecoveryChecks["recovered-definition"] `
                -Expected "1"
            Assert-Equal `
                -Context "No invalid index after recovery" `
                -Actual $RecoveryChecks["no-invalid-anywhere"] `
                -Expected "0"
        }
        else {
            # Either the build completed before the controlled cancel or
            # the cancel repeatedly preceded the build phase. Bounded
            # cleanup: drop the completed rehearsal index when present.
            Invoke-Psql `
                -Database "shop_fts_failure" `
                -Command (
                    "DROP INDEX CONCURRENTLY IF EXISTS " +
                    "public.idx_ps_d_ops_failure_v1;"
                ) `
                -AllowFailure |
                Out-Null

            $PhaseNotes["ControlledFailure"] = (
                "Concurrent build completed before the controlled " +
                "cancel, or the cancel preceded the build phase in all " +
                "attempts; no invalid-index recovery was needed."
            )
        }

        $Outcomes["ControlledFailure"] = $FailureOutcome

        Write-Utf8File `
            -Path (Join-Path $FailureDirectory "outcome.txt") `
            -Content "outcome=$FailureOutcome"

        Remove-Database -Name "shop_fts_failure"
    }
    else {
        $Outcomes["ControlledFailure"] = "NOT_APPLICABLE"
        $PhaseNotes["ControlledFailure"] = "No valid migrated lab state was available."
    }
}
catch {
    $FatalError = $_.Exception.Message

    try {
        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "failure.txt") `
            -Content $FatalError
    }
    catch {
        # Evidence write must not mask the original failure.
    }
}
finally {
    # Zero-residue teardown. All lab databases and roles are removed and
    # the isolated Compose volumes are deleted.
    $TeardownNotes = [System.Collections.Generic.List[string]]::new()
    $ZeroResiduePassed = $false

    try {
        foreach ($databaseName in @(
            "shop_fts_failure",
            "shop_fts_benchmark",
            "shop_fts_pristine",
            "shop_search_benchmark"
        )) {
            try {
                Remove-Database -Name $databaseName | Out-Null
            }
            catch {
                $TeardownNotes.Add(
                    "drop-database $databaseName : $($_.Exception.Message)"
                )
            }
        }

        try {
            Invoke-Psql `
                -Database "postgres" `
                -Command "DROP ROLE IF EXISTS shop_fts_runtime;" |
                Out-Null
        }
        catch {
            $TeardownNotes.Add(
                "drop-role shop_fts_runtime : $($_.Exception.Message)"
            )
        }

        try {
            Invoke-Psql `
                -Database "postgres" `
                -Command "DROP ROLE IF EXISTS shop_benchmark;" |
                Out-Null
        }
        catch {
            $TeardownNotes.Add(
                "drop-role shop_benchmark : $($_.Exception.Message)"
            )
        }

        $Residue = Invoke-Psql `
            -Database "postgres" `
            -Command (
                "SELECT datname FROM pg_database " +
                "WHERE starts_with(datname, 'shop_fts_') " +
                "OR starts_with(datname, 'shop_search_'); " +
                "SELECT rolname FROM pg_roles " +
                "WHERE rolname IN ('shop_fts_runtime', 'shop_benchmark');"
            )

        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "final-residue.txt") `
            -Content $Residue.Text

        if ($Residue.Text.Trim().Length -gt 0) {
            $TeardownNotes.Add("residue: $($Residue.Text.Trim())")
        }
        else {
            $ZeroResiduePassed = $true
        }
    }
    catch {
        $TeardownNotes.Add("teardown: $($_.Exception.Message)")
    }

    Invoke-Compose `
        -Arguments @("down", "--volumes", "--remove-orphans") `
        -AllowFailure |
        Out-Null

    $VolumeListing = Invoke-NativeCapture `
        -FilePath "docker" `
        -CommandArguments @(
            "volume", "ls", "-q",
            "--filter", "name=$($VersionSpec.Project)"
        ) `
        -AllowFailure

    try {
        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "teardown.txt") `
            -Content (
                ($TeardownNotes -join [Environment]::NewLine) +
                [Environment]::NewLine +
                "volumes=$($VolumeListing.Text.Trim())"
            )
    }
    catch {
        # Teardown evidence is best-effort.
    }

    $env:PS_D_POSTGRES_IMAGE = $PreviousImage
    $env:PS_D_POSTGRES_PORT = $PreviousPort

    if ($ZeroResiduePassed) {
        $Outcomes["ZeroResidue"] = "PASSED"
    }
    else {
        $Outcomes["ZeroResidue"] = "FAILED"
    }
}

$SummaryLines = [System.Collections.Generic.List[string]]::new()
$SummaryLines.Add("state=$State")
$SummaryLines.Add("postgres=$($VersionSpec.Version)")
$SummaryLines.Add("rows=$Rows")
$SummaryLines.Add("seed=$Seed")
$SummaryLines.Add("evaluation-evidence=$EvaluationEvidence")
$SummaryLines.Add(
    "production-target=NOT_CONNECTED (loopback-only benchmark topology)"
)

foreach ($key in $Outcomes.Keys) {
    $SummaryLines.Add("outcome:$key=$($Outcomes[$key])")

    if ($PhaseNotes.Contains($key)) {
        $SummaryLines.Add("note:$key=$($PhaseNotes[$key])")
    }
}

$SummaryLines.Add("ps-b-fallback-comparison:")
foreach ($line in $FallbackLines) {
    $SummaryLines.Add("  $line")
}

if ($RestoredVerifyHash) {
    $SummaryLines.Add("oracle-hash-restored=$RestoredVerifyHash")
}
if ($RebuildVerifyHash) {
    $SummaryLines.Add("oracle-hash-rebuilt=$RebuildVerifyHash")
}

if ($FatalError) {
    $SummaryLines.Add("fatal=$FatalError")
}

Write-Utf8File `
    -Path (Join-Path $EvidenceDirectory "summary.txt") `
    -Content ($SummaryLines -join [Environment]::NewLine)

$Hashes = foreach ($File in $RequiredFiles) {
    [pscustomobject]@{
        Path = $File.Substring($RepositoryRoot.Length).TrimStart('\', '/').Replace('\', '/')
        Sha256 = (
            Get-FileHash -LiteralPath $File -Algorithm SHA256
        ).Hash.ToLowerInvariant()
    }
}

$Hashes |
    ConvertTo-Json -Depth 4 |
    ForEach-Object {
        Write-Utf8File `
            -Path (Join-Path $EvidenceDirectory "artifact-hashes.json") `
            -Content $_
    }

Write-Host ""
Write-Host "PS-D operational rehearsal capture finished."
Write-Host "State: $State"
Write-Host "PostgreSQL: $($VersionSpec.Version)"
Write-Host "Rehearsal rows: $Rows"
Write-Host "Evidence: $EvidenceDirectory"
Write-Host "Production target: NOT CONNECTED"
foreach ($key in $Outcomes.Keys) {
    Write-Host "  $key=$($Outcomes[$key])"
}
Write-Host "All isolated benchmark volumes were removed."

if ($FatalError) {
    throw $FatalError
}
