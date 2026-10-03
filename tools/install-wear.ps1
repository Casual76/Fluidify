<#
.SYNOPSIS
  Installa Fluidify sul Galaxy Watch (o qualsiasi Wear OS) dal PC, via debug wireless.

.DESCRIPTION
  La via di riserva: la prima installazione normale si fara' dal telefono, ma questa
  funziona sempre e serve anche durante lo sviluppo.

  Fa tre cose, nell'ordine in cui servono:
   1. installa l'APK (scaricato dalla release, o quello indicato con -Apk);
   2. lo reinstalla dichiarando se stesso come installatore (-i dev.pampa.fluidify), cosi'
      Android lo considera l'installatore di riferimento e gli aggiornamenti successivi
      possono avvenire senza conferma;
   3. gli concede REQUEST_INSTALL_PACKAGES con appops, perche' Wear OS nasconde la
      schermata "Installa app sconosciute" e senza quel permesso l'orologio non potrebbe
      mai aggiornarsi da solo.

  Sul Galaxy Watch, una volta sola:
   - Impostazioni > Info orologio > Info sul software > tocca "Versione software" 5 volte;
   - Impostazioni > Opzioni sviluppatore > Debug ADB: attivo; Debug wireless: attivo;
   - il Wi-Fi deve restare acceso e sulla stessa rete del PC durante l'installazione
     (Impostazioni > Connessioni > Wi-Fi: "Sempre attivo", poi rimettilo su automatico).
   - Debug wireless > "Associa nuovo dispositivo" mostra IP:porta e un codice a 6 cifre (-Pair, -Code);
     la schermata del debug wireless mostra un altro IP:porta, quello di connessione (-Connect).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-wear.ps1 -Version 1.5.0 -Pair 192.168.1.40:37145 -Code 123456 -Connect 192.168.1.40:41633

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-wear.ps1 -Apk wear\build\outputs\apk\dev\wear-dev.apk -Connect 192.168.1.40:41633
#>
param(
  [string]$Apk,
  [string]$Version,
  [string]$Pair,
  [string]$Code,
  [string]$Connect,
  [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$PackageName = "dev.pampa.fluidify"
$Activity = "dev.pampa.fluidify/dev.pampa.fluidify.wear.MainActivity"
$Repository = "Casual76/Fluidify"

function Fail($message) {
  Write-Host "ERRORE: $message" -ForegroundColor Red
  exit 1
}

function Run-Adb {
  param([string[]]$Arguments)
  $output = & $Adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }
  return [pscustomobject]@{ Code = $LASTEXITCODE; Lines = @($output) }
}

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
  Fail "adb non trovato. Installa le Android Platform Tools e mettile nel PATH, oppure passa -Adb <percorso>."
}

if (-not $Apk -and -not $Version) { Fail "serve -Apk <file> oppure -Version <x.y.z>." }

if ($Version) {
  $asset = "fluidify-wear-$Version.apk"
  $url = "https://github.com/$Repository/releases/download/stable-fluidify-v$Version/$asset"
  $Apk = Join-Path $env:TEMP $asset
  Write-Host "Scarico $url" -ForegroundColor Cyan
  Invoke-WebRequest -Uri $url -OutFile $Apk -UseBasicParsing
}
if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK non trovato: $Apk" }

if ($Pair) {
  if (-not $Code) { Fail "-Pair vuole anche -Code (il codice a 6 cifre mostrato dall'orologio)." }
  Write-Host "Associo $Pair..." -ForegroundColor Cyan
  $result = Run-Adb @("pair", $Pair, $Code)
  $result.Lines | ForEach-Object { Write-Host "  $_" }
  if ($result.Code -ne 0 -or -not (($result.Lines -join " ") -match "Successfully")) {
    Fail "associazione non riuscita. Controlla codice, porta e che PC e orologio siano sulla stessa rete."
  }
}

$target = @()
if ($Connect) {
  Write-Host "Mi collego a $Connect..." -ForegroundColor Cyan
  $result = Run-Adb @("connect", $Connect)
  $result.Lines | ForEach-Object { Write-Host "  $_" }
  if (-not (($result.Lines -join " ") -match "connected")) {
    Fail "connessione non riuscita. La porta di connessione cambia ogni volta che il debug wireless si riaccende."
  }
  $target = @("-s", $Connect)
}

Write-Host "Installo $(Split-Path $Apk -Leaf)..." -ForegroundColor Cyan
$install = Run-Adb ($target + @("install", "-r", "-g", $Apk))
$install.Lines | ForEach-Object { Write-Host "  $_" }
if ($install.Code -ne 0) {
  if (($install.Lines -join " ") -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Fail "sull'orologio c'e' una copia firmata con un'altra chiave. Disinstallala (adb uninstall $PackageName) e riprova."
  }
  Fail "installazione non riuscita."
}

# Ora che il pacchetto esiste, puo' essere il proprio installatore di riferimento.
$owner = Run-Adb ($target + @("install", "-r", "-g", "-i", $PackageName, $Apk))
if ($owner.Code -ne 0) {
  Write-Host "  (non sono riuscito a registrarlo come proprio installatore: gli aggiornamenti chiederanno una conferma)" -ForegroundColor Yellow
}

$appops = Run-Adb ($target + @("shell", "appops", "set", $PackageName, "REQUEST_INSTALL_PACKAGES", "allow"))
if ($appops.Code -ne 0) {
  Write-Host "  (permesso di aggiornamento non concesso: gli aggiornamenti senza PC non funzioneranno)" -ForegroundColor Yellow
}

Run-Adb ($target + @("shell", "am", "start", "-n", $Activity)) | Out-Null

Write-Host ""
Write-Host "Fatto. Fluidify e' sull'orologio e si aggiornera' dal telefono." -ForegroundColor Green
Write-Host "Puoi spegnere il debug wireless e rimettere il Wi-Fi su automatico." -ForegroundColor Green
