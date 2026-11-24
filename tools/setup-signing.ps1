# Run locally in PowerShell, outside the repository. Never commit the signing key.
$ErrorActionPreference = 'Stop'
$repo = 'contigo/LyriLayer'
$dir = Join-Path $env:LOCALAPPDATA 'LyriLayerSigning'
$ks = Join-Path $dir 'lyrilayer.p12'
$pwFile = Join-Path $dir 'password.dpapi'
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) { throw 'Install GitHub CLI: winget install --id GitHub.cli -e ; reopen PowerShell.' }
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if ($keytool) { $kt = $keytool.Source } else {
    $kt = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'
    if (-not (Test-Path $kt)) { throw 'JDK keytool not found. Install JDK 17 or Android Studio and add its bin directory to PATH.' }
}
& gh auth status
if ($LASTEXITCODE -ne 0) {
    & gh auth login --hostname github.com --web --git-protocol https
    if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed.' }
}
$existing = & gh secret list --repo $repo --json name
if ($LASTEXITCODE -ne 0) { throw 'Could not read repository secrets. Check GitHub permissions.' }
$names = @($existing | ConvertFrom-Json | ForEach-Object { $_.name })
$hasKey = $names -contains 'LYRILAYER_KEYSTORE_B64'
$hasPassword = $names -contains 'LYRILAYER_KEYSTORE_PASSWORD'
if ($hasKey -and $hasPassword) {
    Write-Host 'Signing secrets already exist. No rotation performed.'
    Write-Host 'Use GitHub Actions -> LyriLayer APK Build -> Run workflow.'
    exit 0
}
if (($hasKey -or $hasPassword) -and (-not (Test-Path $ks) -or -not (Test-Path $pwFile))) { throw 'Partial remote secrets exist but matching local key is missing. Do not generate a replacement key.' }
New-Item -ItemType Directory -Path $dir -Force | Out-Null
if ((Test-Path $ks) -xor (Test-Path $pwFile)) { throw 'Local key or password is missing. Restore the matching backup before proceeding.' }
if (-not (Test-Path $ks)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $pw = [Convert]::ToBase64String($bytes)
    $env:LYRI_KEY_PASS = $pw
    try {
        & $kt -genkeypair -storetype PKCS12 -keystore $ks -storepass:env LYRI_KEY_PASS -keypass:env LYRI_KEY_PASS -alias lyrilayer -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=LyriLayer, O=contigo' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Key creation failed.' }
        ConvertTo-SecureString $pw -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -Path $pwFile -Encoding ASCII
    } finally { Remove-Item Env:\LYRI_KEY_PASS -ErrorAction SilentlyContinue }
} else {
    $secure = Get-Content $pwFile | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $pw = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
# Back up the keystore and password securely, separately from GitHub.
$env:LYRI_KEY_PASS = $pw
try {
    & $kt -list -keystore $ks -storetype PKCS12 -storepass:env LYRI_KEY_PASS -alias lyrilayer
    if ($LASTEXITCODE -ne 0) { throw 'Local key validation failed.' }
    if (-not $hasKey) {
        [Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | & gh secret set LYRILAYER_KEYSTORE_B64 --repo $repo
        if ($LASTEXITCODE -ne 0) { throw 'Key secret upload failed.' }
    }
    if (-not $hasPassword) {
        $pw | & gh secret set LYRILAYER_KEYSTORE_PASSWORD --repo $repo
        if ($LASTEXITCODE -ne 0) { throw 'Password secret upload failed.' }
    }
} finally { Remove-Item Env:\LYRI_KEY_PASS -ErrorAction SilentlyContinue; Remove-Variable pw -ErrorAction SilentlyContinue }
Write-Host "Signing secrets configured. Key location: $ks"
Write-Host 'Keep an offline backup of the key and its password. The DPAPI file can only be decrypted by this Windows user on this PC.'
& gh workflow run android-build.yml --repo $repo --ref main
if ($LASTEXITCODE -ne 0) { throw 'Secrets saved, but workflow dispatch failed. Run the workflow from GitHub Actions.' }
Write-Host 'Build requested. Check GitHub Actions for result.'
