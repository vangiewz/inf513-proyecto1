# Ejecuta el CRM vía e-mail con Java 21 y el intervalo de la configuración.
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
# El PATH de algunas terminales todavía apunta a Java 17. Preferir JAVA_HOME 21;
# si no lo es, usar el Microsoft OpenJDK 21 instalado en este equipo, sin cambiar el PATH global.
$javaHome21 = $env:JAVA_HOME
$release21 = if ($javaHome21) { Join-Path $javaHome21 'release' } else { $null }
if (-not $release21 -or -not (Test-Path $release21) -or
    -not (Select-String -LiteralPath $release21 -Pattern '^JAVA_VERSION="21[.]' -Quiet)) {
    $jdk21 = Get-ChildItem -LiteralPath "$env:ProgramFiles\Microsoft" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jdk21) { throw 'No se encontró Java 21. Configure JAVA_HOME con un JDK 21 instalado.' }
    $javaHome21 = $jdk21.FullName
}
$javaExe = Join-Path $javaHome21 'bin\java.exe'
if (-not (Test-Path $javaExe)) { throw "No existe $javaExe" }
$env:JAVA_HOME = $javaHome21
$env:PATH = "$javaHome21\bin;$env:PATH"
if (-not (Test-Path 'target\crm-mail.jar')) { mvn -q package -DskipTests; if ($LASTEXITCODE) { exit $LASTEXITCODE } }
$java = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', 'target\crm-mail.jar')
if ($Diagnostico) { & $javaExe @java --diagnostico $Config } else { & $javaExe @java $Config }
exit $LASTEXITCODE
