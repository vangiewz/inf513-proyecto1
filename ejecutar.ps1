# Ejecuta el CRM vía e-mail en este equipo: revisa el buzón cada 10 s y muestra el log en la consola.
# El log queda también en logs\access.log (cada ciclo y cada correo) y logs\error.log (fallas y excepciones).
#   .\ejecutar.ps1                 servicio (Ctrl+C para detener)
#   .\ejecutar.ps1 -Diagnostico    solo revisa DNS, puertos, POP3, SMTP y la BD, y termina
# La configuración con contraseñas no está en el repositorio: por omisión se lee de ..\.secrets\.
param(
    [string]$Config = "$PSScriptRoot\..\.secrets\crm-mail.properties",
    [switch]$Diagnostico
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [Text.Encoding]::UTF8
if (-not (Test-Path $Config)) { throw "No existe la configuración $Config (ver crm-mail.properties.example)" }
if (-not (Test-Path 'target\crm-mail.jar')) { mvn -q package -DskipTests; if ($LASTEXITCODE) { exit $LASTEXITCODE } }
$java = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', 'target\crm-mail.jar')
if ($Diagnostico) { & java @java --diagnostico $Config } else { & java @java $Config }
exit $LASTEXITCODE
