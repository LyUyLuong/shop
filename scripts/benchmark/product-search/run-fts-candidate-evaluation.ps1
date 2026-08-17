[CmdletBinding()]
param(
    [ValidateSet(10000, 100000)]
    [int]$Rows = 10000,

    [ValidateSet("Smoke", "Decision")]
    [string]$Mode = "Smoke",

    [ValidateSet("Both", "16.14", "18.3")]
    [string]$Postgres = "Both",

    [ValidateSet("N0", "N0+S", "N1", "N1+S", "N2", "N2+S")]
    [string]$Only,

    [switch]$Reset,
    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Seed = 20260806
$States = @("N0", "N0+S", "N1", "N1+S", "N2", "N2+S")

if ($Only) {
    $States = @($Only)
}

if (-not $Reset) {
    throw (
        "This runner destroys only its isolated benchmark volumes. " +
        "Pass -Reset explicitly."
    )
}

if (
    ($Rows -eq 10000 -and $Mode -ne "Smoke") -or
    ($Rows -eq 100000 -and $Mode -ne "Decision")
) {
    throw "Use 10000/Smoke or 100000/Decision."
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
    "docs\roadmap-v2\v2-ps\d\raw\evaluation"

$Timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$EvidenceDirectory = Join-Path `
    $EvidenceRoot `
    "fts-$Rows-$($Mode.ToLowerInvariant())-$Timestamp"

$RequiredFiles = @(
    $ComposeFile
    (Join-Path $BenchmarkRoot "seed-fts-workloads.sql")
    (Join-Path $BenchmarkRoot "setup-fts-lab-roles.sql")
    (Join-Path $BenchmarkRoot "create-fts-candidate.sql")
    (Join-Path $BenchmarkRoot "verify-fts-candidate.sql")
    (Join-Path $BenchmarkRoot "explain-fts-candidate.sql")
    (Join-Path $BenchmarkRoot "benchmark-fts-candidate.sql")
    (Join-Path $BenchmarkRoot "cleanup-fts-candidate.sql")
    (Join-Path $RepositoryRoot `
        "src\test\resources\benchmark\product-search\seed-products.sql")
    (Join-Path $RepositoryRoot `
        "src\test\resources\benchmark\product-search\verify-dataset.sql")
)

foreach ($requiredFile in $RequiredFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
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

New-Item `
    -ItemType Directory `
    -Path $EvidenceDirectory `
    -Force |
    Out-Null

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
        [string]$PgOptions = ""
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

    Invoke-Compose -Arguments $Arguments
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

function Invoke-LabSql {
    param(
        [Parameter(Mandatory)][string]$ContainerPath,
        [Parameter(Mandatory)][string]$State
    )

    $PgOptions = (
        "-c shop_benchmark.row_count=$Rows " +
        "-c shop_benchmark.seed=$Seed " +
        "-c shop_ps_d.candidate_state=$State"
    )

    Invoke-Psql `
        -Database "shop_fts_benchmark" `
        -File $ContainerPath `
        -PgOptions $PgOptions
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

function Reset-CandidateDatabase {
    Remove-Database -Name "shop_fts_benchmark"

    New-DatabaseFromTemplate `
        -Name "shop_fts_benchmark" `
        -Template "shop_fts_pristine"
}

function Render-Template {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$State
    )

    $DocumentExpression = if ($State -in @("N2", "N2+S")) {
        "product.name_search_vector_v1"
    }
    else {
        @"
pg_catalog.to_tsvector(
    'public.shop_product_name_unaccent_v1'::pg_catalog.regconfig,
    normalize(product.name, NFC)
)
"@.Trim()
    }

    $Content = [System.IO.File]::ReadAllText($Source)

    if (-not $Content.Contains("__PS_D_NAME_DOCUMENT__")) {
        throw "Template placeholder is missing: $Source"
    }

    $Rendered = $Content.Replace(
        "__PS_D_NAME_DOCUMENT__",
        $DocumentExpression
    )

    if ($Rendered.Contains("__PS_D_NAME_DOCUMENT__")) {
        throw "Template rendering is incomplete."
    }

    Write-Utf8File -Path $Destination -Content $Rendered
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

function Get-NearestRank {
    param([double[]]$Values, [int]$Percentile)

    $Sorted = @($Values | Sort-Object)
    $Index = [Math]::Ceiling(
        $Percentile / 100.0 * $Sorted.Count
    ) - 1

    $Sorted[[Math]::Max(0, $Index)]
}

function Get-PgbenchMetrics {
    param(
        [string[]]$LogLines,
        [int]$ExpectedTransactions
    )

    $Latencies = @()

    foreach ($line in $LogLines) {
        $Parts = @($line.Trim() -split "\s+")

        if ($Parts.Count -ne 6) {
            continue
        }

        if ($Parts[2] -match "^\d+$") {
            $Latencies += [double]$Parts[2] / 1000.0
        }
    }

    if ($Latencies.Count -ne $ExpectedTransactions) {
        throw (
            "Expected $ExpectedTransactions successful samples, " +
            "found $($Latencies.Count)."
        )
    }

    [pscustomobject]@{
        Samples = $Latencies.Count
        P50Ms = Get-NearestRank -Values $Latencies -Percentile 50
        P95Ms = Get-NearestRank -Values $Latencies -Percentile 95
        P99Ms = Get-NearestRank -Values $Latencies -Percentile 99
        AverageMs = (
            $Latencies | Measure-Object -Average
        ).Average
    }
}

function Get-Workloads {
    $DeepOffset = if ($Rows -eq 10000) { 5000 } else { 50000 }

    @(
        [pscustomobject]@{
            Name = "browse-data"; Browse = 1; Count = 0
            Cursor = 0; Keyword = "__browse__"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "browse-count"; Browse = 1; Count = 1
            Cursor = 0; Keyword = "__browse__"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "exact-sku-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "psd-exact-00101"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "sku-prefix-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "psd-prefix-alpha"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "sku-prefix-count"; Browse = 0; Count = 1
            Cursor = 0; Keyword = "psd-prefix-alpha"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "exact-name-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "precision camera"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "rare-name-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "rare orchid"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "rare-name-count"; Browse = 0; Count = 1
            Cursor = 0; Keyword = "rare orchid"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "multi-name-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "aurora headphones"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "multi-name-count"; Browse = 0; Count = 1
            Cursor = 0; Keyword = "aurora headphones"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "common-name-count"; Browse = 0; Count = 1
            Cursor = 0; Keyword = "common market"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "accent-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "dien thoai"
            Visibility = "PUBLIC"; Offset = 0
        }
        [pscustomobject]@{
            Name = "admin-data"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "visibility token"
            Visibility = "ADMIN_ALL"; Offset = 0
        }
        [pscustomobject]@{
            Name = "deep-offset"; Browse = 0; Count = 0
            Cursor = 0; Keyword = "common market"
            Visibility = "PUBLIC"; Offset = $DeepOffset
        }
    )
}

function Get-CommonVariables {
    param([object]$Workload)

    @(
        "is_browse=$($Workload.Browse)"
        "is_count=$($Workload.Count)"
        "is_cursor=$($Workload.Cursor)"
        "is_write=0"
        "is_insert=0"
        "is_name_update=0"
        "is_sku_update=0"
        "is_stock_update=0"
        "is_status_update=0"
        "is_image_update=0"
        "is_optimistic_conflict=0"
        "keyword=$($Workload.Keyword)"
        "visibility=$($Workload.Visibility)"
        "minimum_price=0"
        "maximum_price=0"
        "has_minimum_price=0"
        "has_maximum_price=0"
        "offset_rows=$($Workload.Offset)"
        "page_size=100"
        "anchor_tier=0"
        "anchor_surface=0"
        "anchor_score=0"
        "anchor_epoch_micros=0"
        "anchor_id=00000000-0000-0000-0000-000000000000"
        "target_id=00000000-0000-0000-0000-000000000000"
        "write_id=00000000-0000-0000-0000-000000000001"
        "write_sku=PS-D-WRITE-PROBE"
    )
}

function Invoke-ExplainWorkload {
    param(
        [object]$Workload,
        [string]$State,
        [string]$RenderedFile,
        [string]$OutputFile
    )

    $Variables = Get-CommonVariables -Workload $Workload
    $PgOptions = (
        "-c shop_ps_d.candidate_state=$State " +
        "-c statement_timeout=30000"
    )

    $Result = Invoke-Psql `
        -Database "shop_fts_benchmark" `
        -File $RenderedFile `
        -Variables $Variables `
        -PgOptions $PgOptions

    if (
        $Result.Text -notmatch '"Plan"' -or
        $Result.Text -notmatch '"Execution Time"'
    ) {
        throw "Invalid JSON plan for $State/$($Workload.Name)."
    }

    $null = $Result.Text | ConvertFrom-Json

    Write-Utf8File -Path $OutputFile -Content $Result.Text

    $IndexNames = @(
        [regex]::Matches(
            $Result.Text,
            '"Index Name"\s*:\s*"([^"]+)"'
        ) |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
    )

    [pscustomobject]@{
        State = $State
        Workload = $Workload.Name
        IndexNames = $IndexNames -join ","
        UsesN1 = $IndexNames -contains "idx_products_name_fts_n1_v1"
        UsesN2 = $IndexNames -contains "idx_products_name_fts_n2_v1"
        UsesSku = $IndexNames -contains "idx_products_sku_nfc_prefix_v1"
    }
}

function Invoke-PgbenchWorkload {
    param(
        [object]$Workload,
        [string]$State,
        [string]$RenderedFile,
        [int]$Clients,
        [int]$Round,
        [string]$RawDirectory
    )

    $WarmupTotal = if ($Rows -eq 10000) { 8 } else { 24 }
    $MeasuredTotal = if ($Rows -eq 10000) { 16 } else { 104 }
    $WarmupPerClient = [int]($WarmupTotal / $Clients)
    $MeasuredPerClient = [int]($MeasuredTotal / $Clients)

    # pgbench does not support psql's :'name' quoting. The rendered
    # benchmark file uses plain :name references. In prepared mode
    # pgbench binds each variable as a query parameter, so values must
    # be BARE: strings without SQL quotes, True/False booleans, and
    # plain numbers; PostgreSQL infers the parameter types.
    $PgbenchVariables = @(
        "-D", "is_browse=$($Workload.Browse -eq 1)",
        "-D", "is_count=$($Workload.Count -eq 1)",
        "-D", "is_cursor=$($Workload.Cursor -eq 1)",
        "-D", "is_write=False",
        "-D", "is_insert=False",
        "-D", "is_name_update=False",
        "-D", "is_sku_update=False",
        "-D", "is_stock_update=False",
        "-D", "is_status_update=False",
        "-D", "is_image_update=False",
        "-D", "is_optimistic_conflict=False",
        "-D", "keyword=$($Workload.Keyword)",
        "-D", "visibility=$($Workload.Visibility)",
        "-D", "minimum_price=0",
        "-D", "maximum_price=0",
        "-D", "has_minimum_price=False",
        "-D", "has_maximum_price=False",
        "-D", "offset_rows=$($Workload.Offset)",
        "-D", "page_size=100",
        "-D", "anchor_tier=0",
        "-D", "anchor_surface=0",
        "-D", "anchor_score=0",
        "-D", "anchor_epoch_micros=0",
        "-D", "anchor_id=00000000-0000-0000-0000-000000000000",
        "-D", "target_id=00000000-0000-0000-0000-000000000000",
        "-D", "write_id=00000000-0000-0000-0000-000000000001",
        "-D", "write_sku=PS-D-WRITE-PROBE"
    )

    $BaseArguments = @(
        "exec", "-T",
        "-e",
        (
            "PGOPTIONS=-c role=shop_fts_runtime " +
            "-c statement_timeout=30000"
        ),
        "postgres", "pgbench",
        "-n", "-M", "prepared",
        "-c", "$Clients",
        "-j", "$Clients",
        "-r",
        "--failures-detailed",
        "--verbose-errors"
    ) + $PgbenchVariables + @(
        "-U", "shop_fts_migration",
        "-f", $RenderedFile,
        "shop_fts_benchmark"
    )

    $Warmup = Invoke-Compose -Arguments (
        $BaseArguments[0..10] +
        @("-t", "$WarmupPerClient") +
        $BaseArguments[11..($BaseArguments.Count - 1)]
    )

    $SafeState = $State.Replace("+", "s").ToLowerInvariant()
    $Prefix = (
        "/tmp/psd-$SafeState-$($Workload.Name)-" +
        "c$Clients-r$Round"
    )

    Invoke-Compose `
        -Arguments @(
            "exec", "-T", "postgres",
            "sh", "-lc", "rm -f $Prefix.*"
        ) |
        Out-Null

    $MeasuredArguments = (
        $BaseArguments[0..10] +
        @(
            "-t", "$MeasuredPerClient",
            "-l",
            "--log-prefix=$Prefix"
        ) +
        $BaseArguments[11..($BaseArguments.Count - 1)]
    )

    $Measured = Invoke-Compose -Arguments $MeasuredArguments

    $Logs = Invoke-Compose -Arguments @(
        "exec", "-T", "postgres",
        "sh", "-lc", "cat $Prefix.*"
    )

    Invoke-Compose `
        -Arguments @(
            "exec", "-T", "postgres",
            "sh", "-lc", "rm -f $Prefix.*"
        ) |
        Out-Null

    $RawBase = (
        "$SafeState-$($Workload.Name)-" +
        "c$Clients-r$Round"
    )

    Write-Utf8File `
        -Path (Join-Path $RawDirectory "$RawBase-warmup.txt") `
        -Content $Warmup.Text

    Write-Utf8File `
        -Path (Join-Path $RawDirectory "$RawBase-pgbench.txt") `
        -Content $Measured.Text

    Write-Utf8File `
        -Path (Join-Path $RawDirectory "$RawBase-transactions.txt") `
        -Content $Logs.Text

    $Metrics = Get-PgbenchMetrics `
        -LogLines $Logs.Output `
        -ExpectedTransactions $MeasuredTotal

    [pscustomobject]@{
        State = $State
        Workload = $Workload.Name
        Clients = $Clients
        Round = $Round
        Samples = $Metrics.Samples
        P50Ms = $Metrics.P50Ms
        P95Ms = $Metrics.P95Ms
        P99Ms = $Metrics.P99Ms
        AverageMs = $Metrics.AverageMs
    }
}

$Versions = switch ($Postgres) {
    "16.14" {
        @([pscustomobject]@{
            Version = "16.14"
            Image = "postgres:16.14-alpine"
            Port = 55416
            Project = "shop-ps-d-pg16"
        })
    }
    "18.3" {
        @([pscustomobject]@{
            Version = "18.3"
            Image = "postgres:18.3-alpine"
            Port = 55418
            Project = "shop-ps-d-pg18"
        })
    }
    default {
        @(
            [pscustomobject]@{
                Version = "16.14"
                Image = "postgres:16.14-alpine"
                Port = 55416
                Project = "shop-ps-d-pg16"
            }
            [pscustomobject]@{
                Version = "18.3"
                Image = "postgres:18.3-alpine"
                Port = 55418
                Project = "shop-ps-d-pg18"
            }
        )
    }
}

$PreviousImage = $env:PS_D_POSTGRES_IMAGE
$PreviousPort = $env:PS_D_POSTGRES_PORT

$AllMetrics = [System.Collections.Generic.List[object]]::new()
$AllPlans = [System.Collections.Generic.List[object]]::new()
$AllOutcomes = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($VersionSpec in $Versions) {
        $env:PS_D_POSTGRES_IMAGE = $VersionSpec.Image
        $env:PS_D_POSTGRES_PORT = "$($VersionSpec.Port)"

        $script:ComposePrefix = @(
            "compose",
            "--project-name", $VersionSpec.Project,
            "-f", $ComposeFile
        )

        $VersionDirectory = Join-Path `
            $EvidenceDirectory `
            "postgres-$($VersionSpec.Version)"

        New-Item `
            -ItemType Directory `
            -Path $VersionDirectory `
            -Force |
            Out-Null

        Invoke-Compose `
            -Arguments @("down", "--volumes", "--remove-orphans") `
            -AllowFailure |
            Out-Null

        try {
            Invoke-Compose -Arguments @("config", "--quiet") |
                Out-Null

            Invoke-Compose -Arguments @("up", "-d", "postgres") |
                Out-Null

            Wait-Postgres
            Initialize-PristineDatabase

            $Identity = Invoke-Compose -Arguments @(
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
                -Path (Join-Path $VersionDirectory "environment.txt") `
                -Content $Identity.Text

            foreach ($State in $States) {
                $SafeState = $State.Replace(
                    "+",
                    "s"
                ).ToLowerInvariant()

                $StateDirectory = Join-Path `
                    $VersionDirectory `
                    $SafeState

                $PlanDirectory = Join-Path `
                    $StateDirectory `
                    "plans"

                $RawDirectory = Join-Path `
                    $StateDirectory `
                    "benchmark"

                New-Item `
                    -ItemType Directory `
                    -Path $PlanDirectory, $RawDirectory `
                    -Force |
                    Out-Null

                $Outcome = "INCONCLUSIVE"
                $Reason = "Execution did not finish."

                try {
                    Reset-CandidateDatabase

                    Invoke-LabSql `
                        -ContainerPath "/benchmark/setup-fts-lab-roles.sql" `
                        -State $State |
                        Out-Null

                    $Create = Invoke-LabSql `
                        -ContainerPath "/benchmark/create-fts-candidate.sql" `
                        -State $State

                    Write-Utf8File `
                        -Path (Join-Path $StateDirectory "create.txt") `
                        -Content $Create.Text

                    $Verify = Invoke-LabSql `
                        -ContainerPath "/benchmark/verify-fts-candidate.sql" `
                        -State $State

                    Write-Utf8File `
                        -Path (Join-Path $StateDirectory "verify.txt") `
                        -Content $Verify.Text

                    $RenderedExplain = Join-Path `
                        $StateDirectory `
                        "explain-rendered.sql"

                    $RenderedBenchmark = Join-Path `
                        $StateDirectory `
                        "benchmark-rendered.sql"

                    Render-Template `
                        -Source (
                            Join-Path $BenchmarkRoot `
                                "explain-fts-candidate.sql"
                        ) `
                        -Destination $RenderedExplain `
                        -State $State

                    Render-Template `
                        -Source (
                            Join-Path $BenchmarkRoot `
                                "benchmark-fts-candidate.sql"
                        ) `
                        -Destination $RenderedBenchmark `
                        -State $State

                    $ContainerExplain = "/tmp/psd-explain-$SafeState.sql"
                    $ContainerBenchmark = "/tmp/psd-benchmark-$SafeState.sql"

                    Copy-RenderedFile `
                        -LocalPath $RenderedExplain `
                        -ContainerPath $ContainerExplain

                    Copy-RenderedFile `
                        -LocalPath $RenderedBenchmark `
                        -ContainerPath $ContainerBenchmark

                    $Workloads = @(Get-Workloads)

                    foreach ($Workload in $Workloads) {
                        $Plan = Invoke-ExplainWorkload `
                            -Workload $Workload `
                            -State $State `
                            -RenderedFile $ContainerExplain `
                            -OutputFile (
                                Join-Path $PlanDirectory `
                                    "$($Workload.Name).json"
                            )

                        $Plan |
                            Add-Member `
                                -NotePropertyName PostgresVersion `
                                -NotePropertyValue $VersionSpec.Version

                        $AllPlans.Add($Plan)
                    }

                    $Rounds = if ($Rows -eq 10000) { 1 } else { 3 }

                    foreach ($Workload in $Workloads) {
                        foreach ($Clients in @(1, 8)) {
                            for (
                                $Round = 1;
                                $Round -le $Rounds;
                                $Round++
                            ) {
                                $Metric = Invoke-PgbenchWorkload `
                                    -Workload $Workload `
                                    -State $State `
                                    -RenderedFile $ContainerBenchmark `
                                    -Clients $Clients `
                                    -Round $Round `
                                    -RawDirectory $RawDirectory

                                $Metric |
                                    Add-Member `
                                        -NotePropertyName PostgresVersion `
                                        -NotePropertyValue $VersionSpec.Version

                                $AllMetrics.Add($Metric)
                            }
                        }
                    }

                    if ($Rows -eq 10000) {
                        $Outcome = "INCONCLUSIVE"
                        $Reason = (
                            "Mandatory smoke passed; " +
                            "100k decision evidence remains."
                        )
                    }
                    else {
                        $Outcome = "INCONCLUSIVE"
                        $Reason = (
                            "Decision evidence captured; paired " +
                            "noise-budget analysis and survivor selection remain."
                        )
                    }
                }
                catch {
                    $Outcome = "INCONCLUSIVE"
                    $Reason = $_.Exception.Message

                    Write-Utf8File `
                        -Path (Join-Path $StateDirectory "failure.txt") `
                        -Content $Reason
                }
                finally {
                    try {
                        $Cleanup = Invoke-LabSql `
                            -ContainerPath (
                                "/benchmark/cleanup-fts-candidate.sql"
                            ) `
                            -State $State

                        Write-Utf8File `
                            -Path (Join-Path $StateDirectory "cleanup.txt") `
                            -Content $Cleanup.Text
                    }
                    catch {
                        Write-Utf8File `
                            -Path (
                                Join-Path $StateDirectory `
                                    "cleanup-failure.txt"
                            ) `
                            -Content $_.Exception.Message
                    }
                }

                $AllOutcomes.Add([pscustomobject]@{
                    PostgresVersion = $VersionSpec.Version
                    State = $State
                    Outcome = $Outcome
                    Reason = $Reason
                })
            }
        }
        finally {
            Invoke-Compose `
                -Arguments @(
                    "down",
                    "--volumes",
                    "--remove-orphans"
                ) `
                -AllowFailure |
                Out-Null
        }
    }
}
finally {
    $env:PS_D_POSTGRES_IMAGE = $PreviousImage
    $env:PS_D_POSTGRES_PORT = $PreviousPort
}

$AllPlans |
    Export-Csv `
        -LiteralPath (Join-Path $EvidenceDirectory "plans.csv") `
        -NoTypeInformation `
        -Encoding utf8

$AllMetrics |
    Export-Csv `
        -LiteralPath (Join-Path $EvidenceDirectory "metrics.csv") `
        -NoTypeInformation `
        -Encoding utf8

$AllOutcomes |
    Export-Csv `
        -LiteralPath (Join-Path $EvidenceDirectory "state-outcomes.csv") `
        -NoTypeInformation `
        -Encoding utf8

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
Write-Host "PS-D candidate evaluation capture succeeded."
Write-Host "Rows: $Rows"
Write-Host "Mode: $Mode"
Write-Host "PostgreSQL selection: $Postgres"
Write-Host "States attempted: $($States.Count)"
Write-Host "Evidence: $EvidenceDirectory"
Write-Host "All isolated benchmark volumes were removed."

$FailedOutcomes = @(
    $AllOutcomes |
        Where-Object {
            $_.Reason -notmatch "^Mandatory smoke passed" -and
            $_.Reason -notmatch "^Decision evidence captured"
        }
)

if ($FailedOutcomes.Count -gt 0) {
    Write-Host ""
    Write-Host (
        "WARNING: $($FailedOutcomes.Count) state run(s) failed " +
        "with execution errors. The capture still succeeded, but " +
        "these states have no literal-oracle evidence:"
    )

    foreach ($failedOutcome in $FailedOutcomes) {
        Write-Host (
            "  - $($failedOutcome.PostgresVersion)/" +
            "$($failedOutcome.State)"
        )
    }

    Write-Host (
        "See <state>/failure.txt under the evidence directory " +
        "for the first error of each failed state."
    )
}

Write-Host (
    "State outcomes remain INCONCLUSIVE until the frozen " +
    "paired decision gates are applied."
)