# Dot-source after the existing PosEdMCP session helper. UI access retains its confirmation policy.
function Get-ProjectNodes {
    $reply = Invoke-DisplayTool ui_dump @{mode='a11y';max_nodes=500;simplify=$true;reason='读取 Display Project UI 控件以验证应用、类别、基础值编辑和开关流程。'}
    if ($reply.ok -eq $false) { throw 'UI tree unavailable' }
    function Walk-ProjectNode($node) {
        if ($node.text -or $node.desc) { $node }
        foreach ($child in $node.children) { Walk-ProjectNode $child }
    }
    return @(Walk-ProjectNode $reply.node)
}
function Tap-ProjectControl([string]$Label, [switch]$Description, [string]$Reason='测试 Display Project UI 的控件操作。') {
    $nodes = Get-ProjectNodes
    $match = $nodes | Where-Object {
        $labelMatches = if ($Description) { $_.desc -eq $Label } else { $_.text -eq $Label }
        $labelMatches -and $_.center.y -gt 130 -and $_.center.y -lt 2630 -and $_.bounds -match '^\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]$'
    } | Select-Object -First 1
    if (!$match) { throw "Visible control unavailable: $Label" }
    $result = Invoke-DisplayTool input_inject @{action='tap';x=$match.center.x;y=$match.center.y;mode='a11y';reason=$Reason}
    if ($result.injected -ne $true) { throw 'Input rejected' }
    Start-Sleep -Milliseconds 600
}
function Save-ProjectScreen([string]$Path, [string]$Reason) {
    $reply = Invoke-DisplayRpc 'tools/call' @{name='screen_capture';arguments=@{mode='a11y';format='png';max_dimension=1200;reason=$Reason}}
    $picture = $reply.content | Where-Object type -eq 'image' | Select-Object -First 1
    if (!$picture) { throw 'Screen capture unavailable' }
    [IO.File]::WriteAllBytes($Path,[Convert]::FromBase64String($picture.data))
}
function Wait-ProjectTarget([string]$Package, [bool]$Enabled) {
    for ($attempt=0; $attempt -lt 16; $attempt++) {
        $target = Invoke-DisplayTool display_get_target @{package=$Package}
        if ($target.ok -and (($null -ne $target.data.profileId) -eq $Enabled)) { return $target.data }
        Start-Sleep -Milliseconds 300
    }
    throw 'Target state did not reach expected enablement'
}
