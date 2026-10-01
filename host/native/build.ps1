param([switch]$PrepareOnly, [switch]$UsePinnedArchive, [switch]$ChildStartup)
$ErrorActionPreference = 'Stop'
$revision = '03f63972b7238a179123f4cb3fa2c2539d5a6dea'
$nativeSource = Join-Path $PSScriptRoot 'glfw-lwjgl'
$nativeBuild = Join-Path $PSScriptRoot 'build-lwjgl'
if ($UsePinnedArchive -or -not (Test-Path -LiteralPath (Join-Path $nativeSource 'CMakeLists.txt'))) {
    # An isolated dependency directory: existing checkouts and builds stay intact.
    $dependencyRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('dependencies/' + [Guid]::NewGuid().ToString('N'))))
    New-Item -ItemType Directory -Path $dependencyRoot | Out-Null
    $archivePath = Join-Path $dependencyRoot 'glfw.zip'
    Invoke-WebRequest -Uri ('https://github.com/LWJGL-CI/GLFW/archive/' + $revision + '.zip') -OutFile $archivePath
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            if ([IO.Path]::IsPathRooted($entry.FullName) -or $entry.FullName.Contains(':')) { throw 'Archive contains an invalid rooted path' }
            $resolvedEntry = [IO.Path]::GetFullPath((Join-Path $dependencyRoot $entry.FullName))
            if (-not $resolvedEntry.StartsWith($dependencyRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive path escapes the dependency directory' }
        }
    } finally { $archive.Dispose() }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $dependencyRoot
    $nativeSource = [IO.Path]::GetFullPath((Join-Path $dependencyRoot ('GLFW-' + $revision)))
    if (-not $nativeSource.StartsWith($dependencyRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid extracted source path' }
    if (-not (Test-Path -LiteralPath (Join-Path $nativeSource 'CMakeLists.txt'))) { throw 'Pinned archive has unexpected contents' }
    $patchPath = Join-Path $PSScriptRoot 'epoch-glfw.patch'
    & git apply --check --unsafe-paths --directory=$nativeSource $patchPath
    if ($LASTEXITCODE -ne 0) { throw 'Pinned GLFW patch validation failed' }
    & git apply --unsafe-paths --directory=$nativeSource $patchPath
    if ($LASTEXITCODE -ne 0) { throw 'Pinned GLFW patch application failed' }
    $nativeBuild = Join-Path $dependencyRoot 'build'
} else {
    $sourceText = Get-Content -LiteralPath (Join-Path $nativeSource 'src/win32_window.c') -Raw
    if (-not $sourceText.Contains('Epoch: cross-process parenting joins input queues')) { throw 'Existing GLFW source lacks Epoch patch; preserved unchanged. Use -UsePinnedArchive to build a fresh dependency.' }
    if ($ChildStartup -and -not $sourceText.Contains('EPOCH_HIDDEN_START')) { throw 'Existing source lacks hidden child startup; use -UsePinnedArchive.' }
}
Write-Output ('Prepared patched GLFW source: ' + $nativeSource)
if ($PrepareOnly) { return }
& cmake -S $nativeSource -B $nativeBuild -G 'MinGW Makefiles' '-DCMAKE_C_COMPILER=C:/mingw64/bin/gcc.exe' '-DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe' '-DCMAKE_POLICY_VERSION_MINIMUM=3.5' -DBUILD_SHARED_LIBS=ON -DGLFW_BUILD_EXAMPLES=OFF -DGLFW_BUILD_TESTS=OFF -DGLFW_BUILD_DOCS=OFF -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'GLFW configure failed' }
& cmake --build $nativeBuild --parallel 8
if ($LASTEXITCODE -ne 0) { throw 'GLFW build failed' }
$outputName = if ($ChildStartup) { 'epoch-glfw-child.dll' } else { 'epoch-glfw.dll' }
$reportName = if ($ChildStartup) { 'export-verification-child.json' } else { 'export-verification.json' }
Copy-Item -LiteralPath (Join-Path $nativeBuild 'src/glfw3.dll') -Destination (Join-Path $PSScriptRoot $outputName) -Force
python (Join-Path $PSScriptRoot 'verify_exports.py') --dll (Join-Path $PSScriptRoot $outputName) --output (Join-Path $PSScriptRoot $reportName)
if ($LASTEXITCODE -ne 0) { throw 'LWJGL export validation failed' }
