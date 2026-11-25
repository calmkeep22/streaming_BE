param(
    [ValidateSet('initialize', 'migrate-seat-pricing')]
    [string]$Operation = 'initialize',
    [string]$DbHost = '10.0.1.219',
    [int]$DbPort = 3306,
    [string]$DbAdminUser = 'admin',
    [string]$BackendHost = '129.225.185.153',
    [string]$BackendKey = "$env:USERPROFILE\Downloads\ssh-key-2026-11-24 (1).key",
    [string]$KnownHosts = "$env:TEMP/streaming-oci-new-known-hosts"
)
$ErrorActionPreference = 'Stop'
$sshOptions = @('-i', $BackendKey, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
    '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10', '-o', "UserKnownHostsFile=$KnownHosts")
if ($Operation -eq 'migrate-seat-pricing') {
    # Upload only the reviewed migration and runner. Do not rerun CI key/bootstrap setup.
    ssh @sshOptions "opc@$BackendHost" 'mkdir -p /opt/streaming/secure-ci-setup/deploy/backend/migrations'
    if ($LASTEXITCODE -ne 0) { throw 'Migration staging failed.' }
    scp @sshOptions "$PSScriptRoot/scripts/migrate-seat-pricing.py" `
        "opc@${BackendHost}:/opt/streaming/secure-ci-setup/deploy/scripts/migrate-seat-pricing.py"
    if ($LASTEXITCODE -ne 0) { throw 'Migration runner upload failed.' }
    scp @sshOptions "$PSScriptRoot/backend/migrations/002-seat-grade-pricing.sql" `
        "opc@${BackendHost}:/opt/streaming/secure-ci-setup/deploy/backend/migrations/002-seat-grade-pricing.sql"
    if ($LASTEXITCODE -ne 0) { throw 'Migration SQL upload failed.' }
}
$remoteScript = if ($Operation -eq 'migrate-seat-pricing') {
    'sudo -n python3 -I /opt/streaming/secure-ci-setup/deploy/scripts/migrate-seat-pricing.py'
} else {
    'sudo -n python3 -I /opt/streaming/secure-ci-setup/deploy/scripts/configure-managed-mysql.py'
}
$securePassword = Read-Host 'MySQL administrator password (hidden)' -AsSecureString
$passwordPointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $settings = @{ host = $DbHost; port = $DbPort; username = $DbAdminUser; password = $plainPassword }
    $json = $settings | ConvertTo-Json -Compress
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    $payload | ssh @sshOptions "opc@$BackendHost" $remoteScript
    if ($LASTEXITCODE -ne 0) { throw 'Database operation failed. See the sanitized server error above.' }
} finally {
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $plainPassword = $null
    $json = $null
    $payload = $null
    if ($settings) { $settings.Clear() }
    $securePassword.Dispose()
}
