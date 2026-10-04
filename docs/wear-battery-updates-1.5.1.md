# Fluidify 1.5.1: batteria, telefono e aggiornamenti Wear

## Comportamento

- Il motore osserva le reti senza una richiesta permanente di Wi-Fi. Preferisce il proxy Bluetooth; offline non richiede una radio. Senza una rotta, l'avvio può tentare brevemente il Wi-Fi. Solo il ripiego di un download o una preferenza Wi-Fi esplicita mantiene la richiesta per la durata del lavoro, con rilascio nel `finally`.
- I download usano prima il file sul telefono o lo staging sul telefono, anche quando manca il file o cambia la qualità. Le preferenze già scelte dall'utente restano rispettate.
- Una riproduzione nuova e recente del telefono riporta Wear al controllo remoto e ferma il servizio locale. Snapshot vecchi, lo specchio Connect dell'orologio e gli aggiornamenti durante un handoff esplicito non annullano la scelta locale.
- L'invio dell'APK ha un worker con notifica, percentuale dai byte inviati e riscontri del ricevente. Attende la chiusura normale dello stream: il successo del Task `sendFile` significa solo che l'invio è iniziato. Un guardiano chiude il canale dopo 30 secondi senza avanzamento; il budget totale è 15 minuti.
- L'APK verificato e lo stato di installazione sono in `filesDir`, non nella cache. La conferma di Android viene esposta da una notifica e da Altro: nessuna apertura di activity dal background. La sessione e il PendingIntent possono essere recuperati dopo la ricreazione del processo. Un errore conserva il file per un nuovo tentativo locale; il successo lo elimina.
- L'AOD usa una dissolvenza di 220 ms, fondo nero, titolo nella stessa posizione del lettore e dismissione della composizione interattiva al termine.

## Verifica automatica

- Protocollo: 57 test; Wear dev: 66 test; app dev: 9 test; Wear debug: 100 test, inclusi 34 render. Tutti senza errori o test saltati.
- Regressioni dedicate: priorità Bluetooth, richieste sovrapposte/annullate, ritorno al telefono, conferma senza apertura dal background, APK mantenuto dopo un errore, avanzamento monotono, scrittura bloccata interrotta e posizione del titolo/disposizione del player in AOD.
- Build release minificate di telefono e Wear; firma e versione confrontate prima della pubblicazione.
- Lint mirato: `NewApi`, `InlinedApi`, `UnspecifiedImmutableFlag`, `MutableImplicitPendingIntent`, `ForegroundServiceType`, `Instantiatable`. Controllo completo separato: debito preesistente di permessi, API ristrette ProtoLayout, opt-in Media3 e risorse Compose; il nuovo avviso nel test AOD è stato corretto. Non dichiarato verde il lint completo.

## Prova fisica da completare

Nessun dispositivo ADB collegato durante questa patch. I test JVM e i render non misurano batteria, fluidità sul pannello o trasferimenti Bluetooth reali. Seguire la sezione 1.5.1 di `wear-qa-checklist.md`, soprattutto consumo, ritorno al telefono in auto, conferma con schermo spento e nuovo tentativo senza reinviare l'APK.

Riferimenti: [reti Wear](https://developer.android.com/training/wearables/data/network-communication), [ChannelClient](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient), [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller).
