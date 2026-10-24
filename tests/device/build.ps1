$ErrorActionPreference = 'Stop'
$taskRoot = 'D:/Display'
$java = 'C:/Program Files/Java/jdk-22/bin'
$sdk = 'E:/SDK'
$classes = "$taskRoot/.tools/framework-probe-classes"
$dex = "$taskRoot/.tools/device-test-dex"
New-Item -ItemType Directory -Force $classes,$dex | Out-Null
& "$java/javac.exe" --release 17 -encoding UTF-8 -cp "$sdk/platforms/android-36/android.jar;E:/posedmcp/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes" -d $classes "$taskRoot/tests/device/FrameworkProbe.java" "$taskRoot/tests/device/AdvancedProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'Probe javac failed' }
& "$java/jar.exe" cf "$taskRoot/.tools/device-test.jar" -C $classes .
$env:JAVA_HOME='C:/Program Files/Java/jdk-22'
& "$sdk/build-tools/36.1.0/d8.bat" --min-api 31 --lib "$sdk/platforms/android-36/android.jar" --output $dex "$taskRoot/.tools/device-test.jar"
if ($LASTEXITCODE -ne 0) { throw 'Probe dex failed' }
