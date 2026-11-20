# ==============================================================================
# MONITOR DE CORREOS EN TIEMPO REAL - CRM GRUPO 05SA (INF513)
# ==============================================================================
# Uso:
#   .\monitorear.ps1              # Monitorea eventos en tiempo real desde el final del log
#   .\monitorear.ps1 -Historico   # Lee eventos previos del log y luego continúa en vivo
#   .\monitorear.ps1 -Test        # Ejecuta autoverificación visual con eventos de prueba
# ==============================================================================

param(
    [string]$Config = $(
        if (Test-Path "$PSScriptRoot\crm-mail.properties") { "$PSScriptRoot\crm-mail.properties" }
        elseif (Test-Path "$PSScriptRoot\..\.secrets\crm-mail.properties") { "$PSScriptRoot\..\.secrets\crm-mail.properties" }
        else { "$PSScriptRoot\crm-mail.properties" }
    ),
    [string]$LogFile = "$PSScriptRoot\logs\access.log",
    [switch]$Historico,
    [switch]$Test
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ------------------------------------------------------------------------------
# 1. Configuración de Base de Datos y Localización de psql
# ------------------------------------------------------------------------------
$script:dbHost = $null
$script:dbPort = "5432"
$script:dbName = $null
$script:dbUser = $null
$script:dbPass = $null
$script:psqlExe = $null

if (Test-Path $Config) {
    try {
        $props = @{}
        Get-Content -LiteralPath $Config -Encoding UTF8 | Where-Object { $_ -match '^\s*([^#=]+)=(.*)$' } | ForEach-Object {
            $props[$matches[1].Trim()] = $matches[2].Trim()
        }
        if ($props['db.url'] -match 'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)') {
            $script:dbHost = $matches[1]
            if ($matches[2]) { $script:dbPort = $matches[2] }
            $script:dbName = $matches[3]
            $script:dbUser = $props['db.usuario']
            $script:dbPass = $props['db.password']
        }
    } catch {
        # Si falla la lectura, el monitor continúa funcionando solo con los logs
    }
}

# Localizar binario psql en el sistema
$cmdPsql = Get-Command psql -ErrorAction SilentlyContinue
if ($cmdPsql) {
    $script:psqlExe = $cmdPsql.Source
} else {
    $cands = @(
        "$env:ProgramFiles\PostgreSQL\*\bin\psql.exe",
        "$env:ProgramFiles(x86)\PostgreSQL\*\bin\psql.exe",
        "$env:LOCALAPPDATA\Programs\PostgreSQL\*\bin\psql.exe"
    )
    foreach ($cand in $cands) {
        $found = Get-ChildItem -Path $cand -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $script:psqlExe = $found.FullName; break }
    }
}

# ------------------------------------------------------------------------------
# 2. Consulta Eficiente a PostgreSQL (log_comandos)
# ------------------------------------------------------------------------------
function Get-InfoComandoBD {
    param([string]$AsuntoFiltro)

    if (-not $script:psqlExe -or -not $script:dbHost -or -not $script:dbUser -or -not $script:dbPass) {
        return $null
    }

    try {
        $env:PGPASSWORD = $script:dbPass
        $env:PGCONNECT_TIMEOUT = "2"
        $asuntoEsc = if ($AsuntoFiltro) { $AsuntoFiltro.Replace("'", "''") } else { "" }
        
        $sql = if ($asuntoEsc) {
            "SELECT nro_registro, remitente, asunto, resultado, detalle, to_char(fecha, 'HH24:MI:SS') FROM public.log_comandos WHERE asunto = '$asuntoEsc' ORDER BY nro_registro DESC LIMIT 1;"
        } else {
            "SELECT nro_registro, remitente, asunto, resultado, detalle, to_char(fecha, 'HH24:MI:SS') FROM public.log_comandos ORDER BY nro_registro DESC LIMIT 1;"
        }

        $raw = ($sql | & $script:psqlExe -h $script:dbHost -p $script:dbPort -U $script:dbUser -d $script:dbName -t -A -F "`t" 2>$null) -join "`n"
        if ($LASTEXITCODE -eq 0 -and [string]::IsNullOrWhiteSpace($raw) -eq $false) {
            $line = ($raw.Trim() -split "`r?`n")[0]
            $parts = $line -split "`t"
            if ($parts.Count -ge 5) {
                return [PSCustomObject]@{
                    NroRegistro = [int]$parts[0]
                    Remitente   = $parts[1]
                    Asunto      = $parts[2]
                    Resultado   = $parts[3]
                    Detalle     = $parts[4]
                    Hora        = if ($parts.Count -ge 6) { $parts[5] } else { $null }
                }
            }
        }
    } catch {
        # En caso de caída de BD o timeout, continúa silencioso
    }
    return $null
}

# ------------------------------------------------------------------------------
# 3. Presentación Visual en Consola
# ------------------------------------------------------------------------------
function Mostrar-Encabezado {
    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "              MONITOR DE CORREOS - GRUPO 05SA               " -ForegroundColor Yellow -BackgroundColor DarkBlue
    Write-Host "============================================================" -ForegroundColor Cyan
    
    $bdEstado = if ($script:psqlExe -and $script:dbHost) { "Conectado a $($script:dbHost) (log_comandos)" } else { "Solo logs locales (sin psql)" }
    Write-Host " Log     : $LogFile" -ForegroundColor DarkGray
    Write-Host " Base BD : $bdEstado" -ForegroundColor DarkGray
    Write-Host " Estado  : Esperando correos entrantes... (Ctrl+C para salir)" -ForegroundColor Green
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host ""
}

function Escribir-Recibido {
    param([string]$Hora, [string]$Remitente, [string]$Comando)
    Write-Host "[$Hora] " -NoNewline -ForegroundColor DarkGray
    Write-Host "CORREO RECIBIDO" -ForegroundColor Yellow
    Write-Host "Remitente : " -NoNewline -ForegroundColor Gray
    Write-Host "$Remitente" -ForegroundColor White
    Write-Host "Comando   : " -NoNewline -ForegroundColor Gray
    Write-Host "$Comando" -ForegroundColor Cyan
    Write-Host ""
}

function Escribir-Procesamiento {
    param([string]$Hora, [string]$Resultado, [string]$Respuesta)
    $color = switch -Regex ($Resultado) {
        'Éxito|Exito' { 'Green' }
        'Denegado'    { 'Yellow' }
        'Pendiente'   { 'Magenta' }
        default       { 'Red' }
    }
    $textoResultado = switch -Regex ($Resultado) {
        'Éxito|Exito' { 'PROCESAMIENTO EXITOSO' }
        'Denegado'    { 'PROCESAMIENTO DENEGADO' }
        'Pendiente'   { 'PROCESAMIENTO PENDIENTE' }
        default       { "PROCESAMIENTO ERROR ($Resultado)" }
    }
    Write-Host "[$Hora] " -NoNewline -ForegroundColor DarkGray
    Write-Host "$textoResultado" -ForegroundColor $color
    Write-Host "Respuesta : " -NoNewline -ForegroundColor Gray
    Write-Host "$Respuesta" -ForegroundColor White
    Write-Host ""
}

function Escribir-Enviado {
    param([string]$Hora, [string]$Para, [string]$Smtp, [string]$Duracion)
    Write-Host "[$Hora] " -NoNewline -ForegroundColor DarkGray
    Write-Host "RESPUESTA ENVIADA" -ForegroundColor Cyan
    Write-Host "Para      : " -NoNewline -ForegroundColor Gray
    Write-Host "$Para" -ForegroundColor White
    Write-Host "SMTP      : " -NoNewline -ForegroundColor Gray
    Write-Host "$Smtp" -ForegroundColor Green
    Write-Host "Duracion  : " -NoNewline -ForegroundColor Gray
    Write-Host "$Duracion" -ForegroundColor White
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host ""
}

function Escribir-Recordatorio {
    param([string]$Hora, [string]$Nro, [string]$Para)
    Write-Host "[$Hora] " -NoNewline -ForegroundColor DarkGray
    Write-Host "RECORDATORIO ENVIADO" -ForegroundColor Magenta
    Write-Host "Nro       : " -NoNewline -ForegroundColor Gray
    Write-Host "#$Nro" -ForegroundColor White
    Write-Host "Para      : " -NoNewline -ForegroundColor Gray
    Write-Host "$Para" -ForegroundColor White
    Write-Host "SMTP      : " -NoNewline -ForegroundColor Gray
    Write-Host "Aceptado" -ForegroundColor Green
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host ""
}

# ------------------------------------------------------------------------------
# 4. Máquina de Estados de Correlación
# ------------------------------------------------------------------------------
$script:inFlight = @{}
$script:currentId = $null

function Procesar-LineaLog {
    param([string]$Linea)

    if ([string]::IsNullOrWhiteSpace($Linea)) { return }

    # Extraer timestamp estándar del log: yyyy-MM-dd HH:mm:ss
    $hora = if ($Linea -match '^\d{4}-\d{2}-\d{2} (\d{2}:\d{2}:\d{2})') { $matches[1] } else { (Get-Date -Format "HH:mm:ss") }

    # EVENTO 1: Inicio de procesamiento de correo por huella
    if ($Linea -match 'PERF inicio id=([a-f0-9]{16})') {
        $id = $matches[1]
        $script:currentId = $id
        $script:inFlight[$id] = [PSCustomObject]@{
            Id           = $id
            StartHora    = $hora
            Asunto       = $null
            Resultado    = "Recibido"
            Remitente    = $null
            Respuesta    = $null
            Para         = $null
            Smtp         = "Aceptado"
            Duracion     = $null
            ImpresoRecib = $false
        }
        return
    }

    # EVENTO 2: Respuesta preparada (asunto y resultado conocidos)
    if ($Linea -match 'Respuesta preparada · (.+?) · (.+)') {
        $asunto = $matches[1].Trim()
        $resultado = $matches[2].Trim()

        # Determinar el ID correspondiente
        $id = $script:currentId
        if (-not $id -or -not $script:inFlight.ContainsKey($id)) {
            # Buscar en vuelo sin asunto asignado
            foreach ($k in $script:inFlight.Keys) {
                if ($null -eq $script:inFlight[$k].Asunto) { $id = $k; break }
            }
        }

        # Consultar remitente y detalle de log_comandos
        $bdInfo = Get-InfoComandoBD -AsuntoFiltro $asunto
        $remitente = if ($bdInfo -and $bdInfo.Remitente) { $bdInfo.Remitente } else { "No disponible (sin registro BD)" }
        $detalle = if ($bdInfo -and $bdInfo.Detalle) { $bdInfo.Detalle } else { "[Resultado: $resultado]" }

        if ($id -and $script:inFlight.ContainsKey($id)) {
            $item = $script:inFlight[$id]
            $item.Asunto = $asunto
            $item.Resultado = $resultado
            $item.Remitente = $remitente
            $item.Respuesta = $detalle
            $item.Para = $remitente
            $item.ImpresoRecib = $true

            Escribir-Recibido -Hora $item.StartHora -Remitente $remitente -Comando $asunto
            Escribir-Procesamiento -Hora $hora -Resultado $resultado -Respuesta $detalle
        } else {
            # Si no hubo PERF inicio previo
            Escribir-Recibido -Hora $hora -Remitente $remitente -Comando $asunto
            Escribir-Procesamiento -Hora $hora -Resultado $resultado -Respuesta $detalle
        }
        return
    }

    # EVENTO 3: Aceptación SMTP
    if ($Linea -match 'SMTP aceptado id=([a-f0-9]{16})(?:.*duracion_ms=(\d+))?') {
        $id = $matches[1]
        $durMs = if ($matches[2]) { "$($matches[2]) ms" } else { "OK" }
        if ($script:inFlight.ContainsKey($id)) {
            $script:inFlight[$id].Smtp = "Aceptado ($durMs)"
        }
        return
    }

    # EVENTO 4: Fin de procesamiento y envío completado
    if ($Linea -match 'PERF fin id=([a-f0-9]{16})(?:.*total_ms=([\d.]+))?') {
        $id = $matches[1]
        $totalMs = if ($matches[2]) { [double]$matches[2] } else { 0.0 }
        $durSec = if ($totalMs -gt 0) { "{0:N2} segundos" -f ($totalMs / 1000.0) } else { "No disponible" }

        if ($script:inFlight.ContainsKey($id)) {
            $item = $script:inFlight[$id]
            $para = if ($item.Para -and $item.Para -ne "No disponible (sin registro BD)") { $item.Para } else { "Remitente original" }
            $smtp = if ($item.Smtp) { $item.Smtp } else { "Aceptado" }

            Escribir-Enviado -Hora $hora -Para $para -Smtp $smtp -Duracion $durSec
            $script:inFlight.Remove($id)
        }
        if ($script:currentId -eq $id) { $script:currentId = $null }
        return
    }

    # EVENTO 5: Correo conservado en POP3 (pendiente por BD o SMTP)
    if ($Linea -match '(?:PENDIENTE BD|Correo conservado en POP3) id=([a-f0-9]{16})') {
        $id = $matches[1]
        if ($script:inFlight.ContainsKey($id)) {
            $item = $script:inFlight[$id]
            Write-Host "[$hora] " -NoNewline -ForegroundColor DarkGray
            Write-Host "PROCESAMIENTO PENDIENTE" -ForegroundColor Magenta
            Write-Host "Estado    : " -NoNewline -ForegroundColor Gray
            Write-Host "Conservado en POP3 (BD o SMTP temporalmente no disponible)" -ForegroundColor Yellow
            Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
            Write-Host ""
            $script:inFlight.Remove($id)
        }
        if ($script:currentId -eq $id) { $script:currentId = $null }
        return
    }

    # EVENTO 6: Recordatorio periódico enviado
    if ($Linea -match 'Recordatorio (\d+) enviado a ([^ ]+)') {
        Escribir-Recordatorio -Hora $hora -Nro $matches[1] -Para $matches[2]
        return
    }
}

# ------------------------------------------------------------------------------
# 5. Modo de Autoverificación (-Test)
# ------------------------------------------------------------------------------
if ($Test) {
    Mostrar-Encabezado
    Write-Host "[MODO PRUEBA] Simulando eventos de correo aislados..." -ForegroundColor DarkYellow
    Write-Host ""

    $eventosPrueba = @(
        # Caso 1: HELP exitoso
        "2026-11-20 12:30:15 INFORMACIÓN PERF inicio id=1111222233334444 utc=2026-11-20T16:30:15Z",
        "2026-11-20 12:30:16 INFORMACIÓN Respuesta preparada · HELP[] · Éxito",
        "2026-11-20 12:30:18 INFORMACIÓN SMTP aceptado id=1111222233334444 utc=2026-11-20T16:30:18Z duracion_ms=1850",
        "2026-11-20 12:30:19 INFORMACIÓN PERF fin id=1111222233334444 utc=2026-11-20T16:30:19Z total_ms=4200.000",

        # Caso 2: Comando denegado por rol
        "2026-11-20 12:31:00 INFORMACIÓN PERF inicio id=aaaa5555bbbb6666 utc=2026-11-20T16:31:00Z",
        "2026-11-20 12:31:01 INFORMACIÓN Respuesta preparada · APROBARPAGO[`"2`", `"x`"] · Denegado",
        "2026-11-20 12:31:02 INFORMACIÓN SMTP aceptado id=aaaa5555bbbb6666 utc=2026-11-20T16:31:02Z duracion_ms=1200",
        "2026-11-20 12:31:03 INFORMACIÓN PERF fin id=aaaa5555bbbb6666 utc=2026-11-20T16:31:03Z total_ms=3100.000"
    )

    foreach ($e in $eventosPrueba) {
        Procesar-LineaLog -Linea $e
        Start-Sleep -Milliseconds 250
    }

    Write-Host "[PRUEBA FINALIZADA] La presentación visual funciona correctamente." -ForegroundColor Green
    exit 0
}

# ------------------------------------------------------------------------------
# 6. Ciclo Principal de Monitoreo en Vivo
# ------------------------------------------------------------------------------
Mostrar-Encabezado

# Asegurar existencia del directorio logs y del archivo
$logsDir = Split-Path -Parent $LogFile
if (-not (Test-Path $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
}
if (-not (Test-Path $LogFile)) {
    New-Item -ItemType File -Path $LogFile -Force | Out-Null
}

try {
    $fs = [System.IO.File]::Open($LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $reader = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)

    # Si no se pide histórico, posicionar el lector al final actual del archivo
    if (-not $Historico) {
        $null = $fs.Seek(0, [System.IO.SeekOrigin]::End)
    }

    while ($true) {
        # Detección de truncamiento o rotación del log
        if ($fs.Length -lt $fs.Position) {
            $null = $fs.Seek(0, [System.IO.SeekOrigin]::Begin)
            $reader.DiscardBufferedData()
        }

        $linea = $reader.ReadLine()
        if ($null -ne $linea) {
            Procesar-LineaLog -Linea $linea
        } else {
            Start-Sleep -Milliseconds 400
        }
    }
}
catch [System.Management.Automation.PipelineStoppedException] {
    # Salida por Ctrl+C
}
finally {
    if ($reader) { $reader.Close() }
    if ($fs) { $fs.Close() }
    Write-Host "`nMonitor detenido.`n" -ForegroundColor Yellow
}
