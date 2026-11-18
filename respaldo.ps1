# Respaldo y restauración del esquema public de db_grupo05sa (tablas, claves y datos del CRM).
#   .\respaldo.ps1                          saca un respaldo en ..\.secrets\respaldos\public_AAAAMMDD_HHMMSS.dump
#   .\respaldo.ps1 -Restaurar archivo.dump  vuelve a crear lo que falte o se haya borrado y repone los datos
#                                           de ese respaldo (todo o nada: una sola transacción)
# Usa la misma configuración que ejecutar.ps1 (por omisión ..\.secrets\crm-mail.properties) y pg_dump/pg_restore 18.
param(
    [string]$Config = "$PSScriptRoot\..\.secrets\crm-mail.properties",
    [string]$Restaurar
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Config)) { throw "No existe la configuración $Config" }
$p = @{}
foreach ($l in Get-Content $Config) { if ($l -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $p[$Matches[1]] = $Matches[2] } }
if ($p['db.url'] -notmatch '//([^:/]+):(\d+)/([^?]+)') { throw "db.url no tiene la forma //host:puerto/base" }
$conexion = @('-h', $Matches[1], '-p', $Matches[2], '-U', $p['db.usuario'], '-d', $Matches[3])
$env:PGPASSWORD = $p['db.password']
try {
    if ($Restaurar) {
        if (-not (Test-Path $Restaurar)) { throw "No existe el respaldo $Restaurar" }
        & pg_restore @conexion --clean --if-exists --no-owner --no-privileges --single-transaction --exit-on-error $Restaurar
        if ($LASTEXITCODE) { throw "pg_restore falló; la base quedó como estaba (transacción revertida)" }
        Write-Host "Restaurado desde $Restaurar. Comprobar con .\ejecutar.ps1 -Diagnostico"
    } else {
        $dir = "$PSScriptRoot\..\.secrets\respaldos"
        $archivo = Join-Path $dir ("public_{0:yyyyMMdd_HHmmss}.dump" -f (Get-Date))
        & pg_dump @conexion -n public -Fc --no-owner --no-privileges -f $archivo
        if ($LASTEXITCODE) { throw "pg_dump falló" }
        Write-Host "Respaldo: $((Resolve-Path $archivo).Path)"
    }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
exit $LASTEXITCODE
