$ErrorActionPreference='Stop'
. E:/posedmcp/tools/DisplayDeviceSession.ps1
$pkg='com.microsoft.emmx'; $profileId='screenshot-callback-test'; $report=[ordered]@{}
function Invoke-EdgeScreenshot([string]$Method){
    & $script:displayAdb -s $script:displaySerial shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg | Out-Null
    $reply=Invoke-DisplayTool plugin_invoke @{package=$pkg;class_name='dev.display.tests.AdvancedProbe';method=$Method;args_json='[]';reason='在 Edge 验证有权限的真实 Activity 截图回调注册、模拟与注销，不截取图片或读取网页。'}
    if(!$reply.result){throw "Edge callback probe failed: $Method ($($reply.error.message))"}
    return ($reply.result | ConvertFrom-Json)
}
function Set-EdgeScreenshot([bool]$Value){
    $old=Invoke-DisplayTool display_get_profile @{profileId=$profileId}; $rev=if($old.ok){$old.data.revision}else{0}
    $saved=Invoke-DisplayTool display_put_profile @{profile=@{schemaVersion=1;id=$profileId;rules=@(@{key='capture.screenshotEvent';value=$Value})};expectedRevision=$rev;requestId=[guid]::NewGuid().ToString();reason='保存单条截图回调规则，用于 Edge 实机验证，不使用混合配置。'}
    if(!$saved.ok){throw 'Save failed'}
    $target=Invoke-DisplayTool display_get_target @{package=$pkg}
    $applied=Invoke-DisplayTool display_apply_profile @{package=$pkg;profileId=$profileId;profileRevision=$saved.data.revision;expectedTargetRevision=$target.data.revision;requestId=[guid]::NewGuid().ToString();reason='仅在 Edge 临时启用一条截图事件规则，验证后撤回，不实际截图。'}
    if(!$applied.ok){throw 'Apply failed'}
    Start-Sleep -Milliseconds 1700
}
Open-DisplaySession
$started=$false
try{
    $target=Invoke-DisplayTool display_get_target @{package=$pkg}; if(!$target.ok -or $target.data.profileId){throw 'Preserve existing Edge configuration'}
    $dex=[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:/Display/.tools/device-test-dex/classes.dex'))
    $loaded=Invoke-DisplayTool plugin_load @{package=$pkg;class_name='dev.display.tests.AdvancedProbe';dex_base64=$dex;reason='载入只验证 Activity 截图回调的探针，不读取网页内容或真实位置，不实际截图。'}
    if($loaded.loadedIn -lt 1){throw 'Edge plugin load failed'}
    $report.registered=Invoke-EdgeScreenshot realScreenshotStart; $started=$true
    $report.unconfigured=Invoke-EdgeScreenshot realScreenshotRead
    Set-EdgeScreenshot $true
    $report.activated=Invoke-EdgeScreenshot realScreenshotRead
    $report.duplicate=Invoke-EdgeScreenshot realScreenshotDuplicate
    $report.afterDuplicate=Invoke-EdgeScreenshot realScreenshotRead
    Set-EdgeScreenshot $false
    $report.suppressed=Invoke-EdgeScreenshot realScreenshotRead
    $report.stopped=Invoke-EdgeScreenshot realScreenshotStop; $started=$false
    Set-EdgeScreenshot $true
    $report.afterUnregister=Invoke-EdgeScreenshot realScreenshotRead
    $report.passed=($report.registered.realRegistration -and $report.registered.capturePermissionGranted -and
        $report.registered.pid -eq $report.unconfigured.pid -and $report.registered.pid -eq $report.activated.pid -and
        $report.registered.pid -eq $report.afterDuplicate.pid -and $report.registered.pid -eq $report.suppressed.pid -and
        $report.registered.pid -eq $report.afterUnregister.pid -and
        $report.unconfigured.events -eq 0 -and $report.activated.events -eq 1 -and
        $report.duplicate.duplicateRegistrationRejected -and $report.afterDuplicate.events -eq 1 -and
        $report.suppressed.events -eq 1 -and $report.afterUnregister.events -eq 1)
} catch {$report.failure=$_.Exception.Message; throw}
finally{
    try{
        if($started){$report.stopped=Invoke-EdgeScreenshot realScreenshotStop}
        $target=Invoke-DisplayTool display_get_target @{package=$pkg}
        if($target.ok -and $target.data.profileId -eq $profileId){
            $clear=Invoke-DisplayTool display_clear_target @{package=$pkg;expectedTargetRevision=$target.data.revision;requestId=[guid]::NewGuid().ToString();reason='结束 Edge 单条截图回调测试，恢复原行为。'}
            if(!$clear.ok){throw 'Edge cleanup failed'}
            Start-Sleep -Milliseconds 1700
        }
        $report.finalTarget=Invoke-DisplayTool display_get_target @{package=$pkg}
        $report.finalRuntime=Invoke-DisplayTool display_runtime_state @{package=$pkg}
    }finally{
        $report | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 D:/Display/docs/expanded-edge-screenshot.json
        Close-DisplaySession
    }
}
@{passed=$report.passed;registered=$report.registered;unconfigured=$report.unconfigured;activated=$report.activated;duplicate=$report.duplicate;afterDuplicate=$report.afterDuplicate;suppressed=$report.suppressed;afterUnregister=$report.afterUnregister;finalTarget=$report.finalTarget.data} | ConvertTo-Json -Depth 6
if(!$report.passed){throw 'Real Activity callback assertions failed; configuration cleared'}
