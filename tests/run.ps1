param(
    [string]$JavaDirectory = 'C:/Program Files/Java/jdk-22',
    [string]$JsonJar = "$PSScriptRoot/../.tools/test-libs/json-20240303.jar"
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Provide org.json:json:20240303 via -JsonJar.' }
if ((Get-FileHash -LiteralPath $JsonJar).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected JSON jar checksum' }
$classes = Join-Path $taskRoot '.tools/test-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& "$JavaDirectory/bin/javac.exe" -encoding UTF-8 -cp $JsonJar -d $classes "$taskRoot/tests/FrontendRegression.java" "$taskRoot/tests/CoreRegression.java" "$taskRoot/tests/ProjectRegression.java" "$taskRoot/tests/AdvancedRegression.java" "$taskRoot/display/src/main/java/dev/display/hooks/CaptureCallbacks.java" "$taskRoot/display-api/src/main/java/dev/display/api/DisplayContract.java" "$taskRoot/display-core-api/src/main/java/dev/display/core/DisplayCore.java" "$taskRoot/display-core-api/src/main/java/dev/display/core/ProjectCore.java" "$taskRoot/display-core-api/src/main/java/dev/display/core/CoreException.java" "$taskRoot/display/src/main/java/dev/display/core/ProfileCore.java" "$taskRoot/display/src/main/java/dev/display/ui/InterfaceCatalog.java" "$taskRoot/display/src/main/java/dev/display/DisplayEndpoint.java"
if ($LASTEXITCODE -ne 0) { throw 'Regression compilation failed' }
& "$JavaDirectory/bin/java.exe" -cp "$classes;$JsonJar" dev.display.tests.FrontendRegression "$taskRoot/examples/travel-test.json"
if ($LASTEXITCODE -ne 0) { throw 'Regression failed' }
& "$JavaDirectory/bin/java.exe" -cp "$classes;$JsonJar" dev.display.tests.CoreRegression
if ($LASTEXITCODE -ne 0) { throw 'Core regression failed' }
& "$JavaDirectory/bin/java.exe" -cp "$classes;$JsonJar" dev.display.tests.ProjectRegression
if ($LASTEXITCODE -ne 0) { throw 'Project regression failed' }

& "$JavaDirectory/bin/java.exe" -cp "$classes;$JsonJar" dev.display.tests.AdvancedRegression
if ($LASTEXITCODE -ne 0) { throw 'Advanced regression failed' }
