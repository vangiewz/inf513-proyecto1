# Ejecuta el CRM: sondeo serial, al menos 3 s después de terminar el ciclo anterior.
# El log queda también en logs\crm-mail.0.log (rotativo).
#   .\ejecutar.ps1                 servicio (Ctrl+C para detener)
#   .\ejecutar.ps1 -Diagnostico    solo revisa DNS, puertos, POP3, SMTP y la BD, y termina
# La configuración con contraseñas no está en el repositorio: por omisión se lee de ..\.secrets\.
param(
    [string]$Config = "$PSScriptRoot\..\.secrets\crm-mail.properties",
    [switch]$Diagnostico,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [Text.Encoding]::UTF8
if ($JavaHome) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JavaHome no contiene un JDK válido' }
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
}
# --version escribe en stdout; -version en stderr dispara NativeCommandError en PowerShell 5
# con ErrorActionPreference=Stop, aunque el JDK sea correcto.
$version = (& java --version | Out-String)
if ($version -notmatch '(?m)^(?:openjdk|java)\s+(\d+)' -or [int]$Matches[1] -lt 21) {
    throw 'P1 requiere JDK 21 o posterior. Use -JavaHome RUTA_JDK o configure JAVA_HOME.'
}
if (-not (Test-Path $Config)) { throw "No existe la configuración $Config (ver crm-mail.properties.example)" }
if (-not (Test-Path 'target\crm-mail.jar')) { mvn -q package -DskipTests; if ($LASTEXITCODE) { exit $LASTEXITCODE } }
$java = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', 'target\crm-mail.jar')
if ($Diagnostico) { & java @java --diagnostico $Config } else { & java @java $Config }
exit $LASTEXITCODE
