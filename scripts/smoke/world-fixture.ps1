# Read-only ZIP fixture import into a single isolated smoke world.
function Initialize-SmokeWorldFixture {
    param([string]$ZipPath, [string]$Target, [string]$ServerRunDir, [string]$ArchiveDir)
    $root = [IO.Path]::GetFullPath($ServerRunDir).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $destination = [IO.Path]::GetFullPath($Target)
    if ([IO.Path]::GetDirectoryName($destination) -ne $root -or
        -not [IO.Path]::GetFileName($destination).StartsWith('smoke_fixture_')) {
        throw 'ZIP fixture target must be an isolated smoke_fixture_ world under the server run directory'
    }
    $zip = [IO.Compression.ZipFile]::OpenRead((Get-Item -LiteralPath $ZipPath -ErrorAction Stop).FullName)
    try {
        $levels = @($zip.Entries | Where-Object { $_.FullName -eq 'level.dat' -or $_.FullName.EndsWith('/level.dat') })
        if ($levels.Count -ne 1) { throw 'Fixture ZIP must contain exactly one level.dat' }
        $prefix = $levels[0].FullName.Substring(0, $levels[0].FullName.Length - 'level.dat'.Length)
        $files = @()
        foreach ($entry in $zip.Entries) {
            if (-not $entry.FullName.StartsWith($prefix) -or $entry.FullName.EndsWith('/')) { continue }
            $relative = $entry.FullName.Substring($prefix.Length)
            if ($relative -eq 'session.lock') { continue }
            $file = [IO.Path]::GetFullPath([IO.Path]::Combine($destination, $relative))
            if (-not $file.StartsWith($destination + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Unsafe ZIP entry: $($entry.FullName)"
            }
            $files += @{ Entry = $entry; Path = $file }
        }
        # Keep previous evidence recoverable; never modify the source save or recursively delete a computed path.
        if (Test-Path -LiteralPath $destination) {
            New-Item -ItemType Directory -Force -Path $ArchiveDir | Out-Null
            $archiveRoot = [IO.Path]::GetFullPath($ArchiveDir).TrimEnd([IO.Path]::DirectorySeparatorChar)
            $archive = [IO.Path]::GetFullPath((Join-Path $archiveRoot ([IO.Path]::GetFileName($destination) + '_' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss-ffff'))))
            if ([IO.Path]::GetDirectoryName($archive) -ne $archiveRoot) { throw 'Invalid fixture archive target' }
            Move-Item -LiteralPath $destination -Destination $archive -ErrorAction Stop
        }
        foreach ($file in $files) {
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file.Path)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($file.Entry, $file.Path, $false)
        }
        Write-Host "Fixture restored: files=$($files.Count) SHA256=$((Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash) target=$destination"
    } finally { $zip.Dispose() }
}
