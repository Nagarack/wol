# WOL_AGENT
# Agent : extinction + Chroniques (lancer / arreter / etat). Lance au demarrage, en SYSTEM.
$cfg    = Get-Content "C:\wol\config.json" -Raw | ConvertFrom-Json
$agentVersion = 6
$task   = "WOL Chroniques"
$folder = "C:\Users\Baka\Desktop\Chroniques"
# Programmes ouverts "sur" le dossier mais qui ne sont pas le serveur : ignores
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
if (-not (Test-Path $docker)) { $docker = "docker" }
$dstatus = "C:\wol\docker_status.json"
$ignore = @("explorer.exe","code.exe","notepad.exe","notepad++.exe","msedge.exe","chrome.exe","firefox.exe","conhost.exe","git.exe")

function Get-GameProcs {
    $all = @(Get-CimInstance Win32_Process)
    $set = @{}
    foreach ($p in $all) {
        if ($p.CommandLine -and $p.CommandLine -like "*$folder*" -and ($ignore -notcontains $p.Name.ToLower())) {
            $set[[int]$p.ProcessId] = $p
        }
    }
    do {   # ajoute les processus enfants
        $added = $false
        foreach ($p in $all) {
            if (-not $set.ContainsKey([int]$p.ProcessId) -and $set.ContainsKey([int]$p.ParentProcessId) -and $p.Name.ToLower() -ne "conhost.exe") {
                $set[[int]$p.ProcessId] = $p; $added = $true
            }
        }
    } while ($added)
    return @($set.Values)
}
function Test-GameRunning {
    if ((Get-GameProcs).Count -gt 0) { return $true }
    $t = Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    return [bool]($t -and $t.State -eq "Running")
}

function Get-DockerDir {
    foreach ($id in @(& $docker ps -q 2>$null)) {
        $i = (& $docker inspect $id | ConvertFrom-Json)[0]
        $d = $i.Config.Labels.'com.docker.compose.project.working_dir'
        if ($d) { return $d }
    }
    return $null
}
# Mise a jour des conteneurs : pull + up -d, etat ecrit dans un fichier (lu via /docker/status)
$dockerJob = {
    param($docker, $dir, $statusFile)
    function St($s, $u, $m) { (@{ state = $s; updated = @($u); msg = $m } | ConvertTo-Json -Compress) | Set-Content $statusFile -Encoding UTF8 }
    function Imgs { $h = @{}; foreach ($id in @(& $docker ps -q)) { $i = (& $docker inspect $id | ConvertFrom-Json)[0]; $h[$i.Name.TrimStart('/')] = $i.Image }; return $h }
    try {
        Set-Location $dir
        St "running" @() "Telechargement des images"
        $before = Imgs
        & $docker compose pull 2>&1 | Out-Null
        St "running" @() "Redemarrage des conteneurs"
        & $docker compose up -d 2>&1 | Out-Null
        $after = Imgs
        $upd = @(); foreach ($n in $after.Keys) { if ($before.ContainsKey($n) -and $before[$n] -ne $after[$n]) { $upd += $n } }
        St "done" $upd ""
    } catch { St "error" @() $_.Exception.Message }
}

$l = New-Object System.Net.HttpListener
$l.Prefixes.Add("http://+:$($cfg.port)/")
$l.Start()
while ($l.IsListening) {
    $doShutdown = $false
    $doRestart = $false
    $doSelfRestart = $false
    try {
        $c = $l.GetContext()
        $path = $c.Request.Url.AbsolutePath
        $code = 200; $text = "ok"
        if ($c.Request.HttpMethod -eq "GET" -and $path -eq "/version") {
            $text = (@{ version = $agentVersion } | ConvertTo-Json -Compress)
        }
        elseif ($c.Request.HttpMethod -eq "GET" -and $path -eq "/game/status") {
            $text = (@{ running = (Test-GameRunning) } | ConvertTo-Json -Compress)
        }
        elseif ($c.Request.HttpMethod -eq "GET" -and $path -eq "/docker/status") {
            if (Test-Path $dstatus) { $text = Get-Content $dstatus -Raw } else { $text = '{"state":"idle"}' }
        }
        elseif ($c.Request.HttpMethod -eq "POST") {
            if ($c.Request.Headers["X-Secret"] -ne $cfg.secret) { $code = 403; $text = "interdit" }
            elseif ($path -eq "/shutdown") { $text = "extinction"; $doShutdown = $true }
            elseif ($path -eq "/restart") { $text = "redemarrage"; $doRestart = $true }
            elseif ($path -eq "/self-update") {
                try {
                    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                    $new = (Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/Nagarack/wol/main/server/agent.ps1" -TimeoutSec 30).Content
                    $tmp = "C:\wol\agent.ps1.new"
                    Set-Content -Path $tmp -Value $new -Encoding UTF8
                    $perr = $null
                    [void][System.Management.Automation.Language.Parser]::ParseFile($tmp, [ref]$null, [ref]$perr)
                    if ($perr.Count -gt 0 -or $new -notmatch "WOL_AGENT") { Remove-Item $tmp -Force; $text = '{"result":"invalid"}' }
                    else {
                        Copy-Item "C:\wol\agent.ps1" "C:\wol\agent.ps1.bak" -Force
                        Move-Item $tmp "C:\wol\agent.ps1" -Force
                        $doSelfRestart = $true
                        $text = '{"result":"updated"}'
                    }
                } catch { $text = '{"result":"error"}' }
            }
            elseif ($path -eq "/screens/off") {
                if (Get-ScheduledTask -TaskName "WOL Ecrans" -ErrorAction SilentlyContinue) { Start-ScheduledTask -TaskName "WOL Ecrans"; $text = '{"result":"started"}' }
                else { $text = '{"result":"notask"}' }
            }
            elseif ($path -eq "/docker/update") {
                if (Get-Job -State Running -ErrorAction SilentlyContinue) { $text = '{"result":"busy"}' }
                else {
                    Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue
                    $dir = Get-DockerDir
                    if (-not $dir) { $text = '{"result":"nodir"}' }
                    else { Start-Job -ScriptBlock $dockerJob -ArgumentList $docker, $dir, $dstatus | Out-Null; $text = '{"result":"started"}' }
                }
            }
            elseif ($path -eq "/game/start") {
                if (Test-GameRunning) { $r = "already" }
                else { Start-ScheduledTask -TaskName $task; $r = "started" }
                $text = (@{ result = $r } | ConvertTo-Json -Compress)
            }
            elseif ($path -eq "/game/stop") {
                if (Test-GameRunning) {
                    Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
                    foreach ($p in (Get-GameProcs)) { & taskkill /PID $p.ProcessId /T /F 2>$null | Out-Null }
                    $r = "stopped"
                } else { $r = "nothing" }
                $text = (@{ result = $r } | ConvertTo-Json -Compress)
            }
            else { $code = 404; $text = "inconnu" }
        }
        $buf = [Text.Encoding]::UTF8.GetBytes($text)
        $c.Response.StatusCode = $code
        $c.Response.OutputStream.Write($buf, 0, $buf.Length)
        $c.Response.Close()
    } catch { }
    if ($doShutdown) { Start-Process shutdown -ArgumentList "/s","/t","5" }
    if ($doSelfRestart) {
        # processus independant (hors de la tache) : arrete puis relance la tache de l'agent
        Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep 3; Stop-ScheduledTask -TaskName ''WOL agent''; Start-Sleep 2; Start-ScheduledTask -TaskName ''WOL agent''"' } | Out-Null
    }
    if ($doRestart) { Start-Process shutdown -ArgumentList "/r","/t","5" }
}
