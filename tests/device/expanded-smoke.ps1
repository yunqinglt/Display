param([switch]$SkipLoad)
$ErrorActionPreference='Stop'
. E:/posedmcp/tools/DisplayDeviceSession.ps1
$pkg='com.x1y9.probe'
$profileId='advanced-framework-test'
$report=[ordered]@{}
function Save-ExpandedReport([string]$Name,$Value) {
    $Value | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 "D:/Display/docs/expanded-$Name.json"
}
function Invoke-AdvancedProbe([string]$Method) {
    & $script:displayAdb -s $script:displaySerial shell am start -W -n com.x1y9.probe/com.x1y9.app.MainActivity | Out-Null
    $reply=Invoke-DisplayTool plugin_invoke @{package=$pkg;class_name='dev.display.tests.AdvancedProbe';method=$Method;args_json='[]';reason='验证 Display 扩展接口的基础值、权限和隔离框架回调；不启动截图或录屏，不输出真实设备数据。'}
    if (!$reply.result) { throw "Probe failed: $Method ($($reply.error.message))" }
    return ($reply.result | ConvertFrom-Json)
}
function Save-AdvancedProfile([bool]$Capture) {
    $values=[ordered]@{
        'capture.screenshotEvent'=$Capture; 'capture.recordingVisible'=$Capture
        'security.keyguardLocked'=$true; 'security.deviceLocked'=$true; 'security.keyguardSecure'=$true; 'security.deviceSecure'=$true
        'audio.ringerMode'=0; 'audio.mode'=3; 'audio.musicActive'=$true; 'audio.microphoneMute'=$true; 'audio.speakerphoneOn'=$true
        'audio.musicVolumePercent'=67; 'audio.alarmVolumePercent'=23
        'display.rotation'=1; 'display.refreshRate'=75.5; 'display.state'=3
        'state.thermalStatus'=2; 'state.lightDeviceIdle'=$true
        'settings.airplaneMode'=$true; 'settings.autoRotate'=$false; 'settings.time24Hour'=$true; 'settings.brightness'=77
        'network.wifiEnabled'=$false; 'network.wifiRssi'=-42; 'network.wifiLinkSpeed'=866
        'sensors.linearAcceleration'=@(1.1,2.2,3.3); 'sensors.gravity'=@(0,0,9.8)
        'sensors.ambientTemperature'=@(19.5); 'sensors.relativeHumidity'=@(42); 'sensors.stepCounter'=@(1234)
    }
    $rules=@($values.GetEnumerator() | ForEach-Object {@{key=$_.Key;value=$_.Value}})
    $old=Invoke-DisplayTool display_get_profile @{profileId=$profileId}
    $rev=if($old.ok){$old.data.revision}else{0}
    $saved=Invoke-DisplayTool display_put_profile @{profile=@{schemaVersion=1;id=$profileId;rules=$rules};expectedRevision=$rev;requestId=[guid]::NewGuid().ToString();reason='保存隐秘参数专用扩展接口测试配置，不给 Edge 应用整组配置。'}
    if(!$saved.ok){throw 'Profile save failed'}
    $target=Invoke-DisplayTool display_get_target @{package=$pkg}
    $applied=Invoke-DisplayTool display_apply_profile @{package=$pkg;profileId=$profileId;profileRevision=$saved.data.revision;expectedTargetRevision=$target.data.revision;requestId=[guid]::NewGuid().ToString();reason='临时在隐秘参数验证扩展钩子，测试结束撤回绑定，保留实际系统设置。'}
    if(!$applied.ok){throw 'Apply failed'}
    & $script:displayAdb -s $script:displaySerial shell am start -W -n com.x1y9.probe/com.x1y9.app.MainActivity | Out-Null
    for($attempt=0;$attempt -lt 16;$attempt++){
        Start-Sleep -Milliseconds 400
        $runtime=Invoke-DisplayTool display_runtime_state @{package=$pkg}
        $ready=@($runtime.data.processes | Where-Object {$_.ackRevision -eq $applied.data.revision -and $_.state -eq 'active'})
        if($ready.Count -gt 0){Save-ExpandedReport "runtime-$Capture" $runtime;return}
    }
    throw 'Runtime did not acknowledge test configuration'
}
Open-DisplaySession
$started=$false
try {
    $initial=Invoke-DisplayTool display_get_target @{package=$pkg}
    if(!$initial.ok -or $initial.data.profileId){throw 'Target already configured; preserve user configuration'}
    if(!$SkipLoad){
        $dex=[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:/Display/.tools/device-test-dex/classes.dex'))
        $loaded=Invoke-DisplayTool plugin_load @{package=$pkg;class_name='dev.display.tests.AdvancedProbe';dex_base64=$dex;reason='载入只读扩展接口探针与隔离回调测试，不开始截图或录屏。'}
        if($loaded.loadedIn -lt 1){throw 'Probe load failed'}
        $report.baseline=Invoke-AdvancedProbe baseline
    }
    Save-AdvancedProfile $true
    $report.getters=Invoke-AdvancedProbe sample
    $report.sensors=Invoke-AdvancedProbe sensors
    $report.permission=Invoke-AdvancedProbe capturePermission
    $report.captureStart=Invoke-AdvancedProbe captureStart; $started=$true
    $report.captureTrueEvent=Invoke-AdvancedProbe captureEvent
    Save-AdvancedProfile $false
    $report.captureFalse=Invoke-AdvancedProbe captureRead
    $report.captureFalseEvent=Invoke-AdvancedProbe captureEvent
} catch {
    $report.failure=$_.Exception.Message
    throw
} finally {
    try {
        $target=Invoke-DisplayTool display_get_target @{package=$pkg}
        if($target.ok -and $target.data.profileId -eq $profileId){
            $clear=Invoke-DisplayTool display_clear_target @{package=$pkg;expectedTargetRevision=$target.data.revision;requestId=[guid]::NewGuid().ToString();reason='结束扩展接口测试，恢复隐秘参数原始系统框架返回值。'}
            if(!$clear.ok){throw 'Test cleanup failed'}
            Start-Sleep -Milliseconds 1800
        }
        $report.restored=Invoke-AdvancedProbe restored
        if($started){
            $report.captureRestored=Invoke-AdvancedProbe captureRead
            $report.captureStopped=Invoke-AdvancedProbe captureStop
            $report.postUnregister=Invoke-AdvancedProbe captureEvent
        }
        $report.finalTarget=Invoke-DisplayTool display_get_target @{package=$pkg}
        $report.finalRuntime=Invoke-DisplayTool display_runtime_state @{package=$pkg}
    } catch {
        $report.cleanupFailure=$_.Exception.Message
        throw
    } finally {
        $report.finalTarget=Invoke-DisplayTool display_get_target @{package=$pkg}
        $report.finalRuntime=Invoke-DisplayTool display_runtime_state @{package=$pkg}
        Save-ExpandedReport smoke $report
        Close-DisplaySession
    }
}
$badGetters=@($report.getters.psobject.Properties | Where-Object Value -ne $true)
$badRestore=@($report.restored.psobject.Properties | Where-Object Value -ne $true)
$report.passed=($badGetters.Count -eq 0 -and $badRestore.Count -eq 0 -and
    $report.permission.recordingPermissionExceptionPreserved -and $report.captureStart.initialRecording -eq 1 -and
    $report.captureStart.screenshotEvents -eq 1 -and $report.captureTrueEvent.screenshotEvents -eq 2 -and
    $report.captureTrueEvent.lastRecording -eq 1 -and $report.captureFalseEvent.screenshotEvents -eq 2 -and
    $report.captureFalseEvent.lastRecording -eq 0 -and $report.captureRestored.lastRecording -eq 0 -and
    $report.postUnregister.screenshotEvents -eq $report.captureRestored.screenshotEvents -and
    $report.postUnregister.recordingEvents -eq $report.captureRestored.recordingEvents -and
    !$report.finalTarget.data.profileId)
foreach($row in $report.sensors.psobject.Properties){
    if($row.Value -is [string]){if($row.Value -notin @('hardware-absent','permission-not-granted-not-bypassed')){$report.passed=$false}}
    elseif(!$row.Value.matches -or !$row.Value.monotonicTimestamp){$report.passed=$false}
}
Save-ExpandedReport smoke $report
@{passed=$report.passed;getters=$report.getters;restored=$report.restored;sensors=$report.sensors;captureStart=$report.captureStart;captureTrueEvent=$report.captureTrueEvent;captureFalseEvent=$report.captureFalseEvent;captureRestored=$report.captureRestored;postUnregister=$report.postUnregister;permission=$report.permission;finalTarget=$report.finalTarget.data} | ConvertTo-Json -Depth 7
if(!$report.passed){throw 'Expanded device assertions failed; configuration has been cleared'}
