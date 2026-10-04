# Fase 3 Wear OS — ripresa dell'implementazione

Piano fornito dall'utente il 4 ottobre 2026. Ramo: `claude/sweet-ride-gmzu9g`.
Punto di partenza: `eaa9f01` (ponte del telefono). La modifica preesistente a
`.gitignore` resta esclusa dai commit di questa ripresa.

## Blocchi ereditati

- [x] Crash e blocchi: `55af464`.
- [x] Quattro richieste dai render: `4b5ce82`.
- [x] Passaggi e motore: `3641166`.
- [x] Ponte del telefono: `eaa9f01`.

Queste spunte indicano i commit presenti, non una certificazione su dispositivo.

## Pezzo 5 — download e aggiornamenti (sezione 6 del piano)

- [x] Applicare soltanto liste lette per intero, inclusa una playlist svuotata.
- [x] Cache del contesto sul telefono durante la paginazione.
- [x] Identità del `.part`, `fileId` nella richiesta, offset effettivo nella risposta.
- [x] Guardiano delle letture bloccanti di file e miniature; chiusura dei canali.
- [x] Contatore persistente: tre passate per brani realmente non disponibili; gli errori di rete non contano.
- [x] Mutazioni dello store sincronizzate e misura dello spazio su IO, non a ogni brano.
- [x] Staging con binding del servizio fino alla fine e directory distinte per trasferimento; pulizia degli abbandonati.
- [x] Marcatore Wear, checksum obbligatorio e firma verificati sui due lati e nell'installatore ADB.
- [x] Offerta salvata sul telefono e scadenza sull'orologio; download dell'aggiornamento in WorkManager.
- [x] Discovery ADB legata al lifecycle e installatore mantenuto in un ViewModel.
- [x] Test dell'esatto albero del blocco; commit/push `3d876f1`.

## Pezzo 6 — interfaccia e stato (sezioni 7 e 8)

Commit/push: `1a02143`.

- [x] Tile: esito dei comandi, dimensioni, nonce persistente, intent e copertine.
- [x] Firme delle superfici distinte; rinnovo dell'icona ongoing; stack autonomo pigro.
- [x] Volume: livello iniziale, collegamento, cambio dispositivo/modalità e TalkBack.
- [x] Errori ripetuti, cache Home, ritorno dalla ricerca, copertine e pin in tutti gli scaffali.
- [x] Lifecycle degli anelli, layout 192 dp/font 1.3/stato e trasporto LTR.
- [x] Ghiera immersiva, navigazione single-top, sfondi opachi, ellissi e motivi degli errori.
- [x] Permessi al ritorno, collector legati alla composizione.
- [x] Ambient unico nero, low-bit e protezione burn-in.
- [x] Azioni TalkBack della copertina e etichetta Fissa.
- [x] VolumeCoalescer, ipotesi play/pausa, stima dello sfasamento da hello/ack, ArtStore atomico.
- [x] Collisione degli ID di notifica.
- [x] Patch facoltativa dell'accento rinviata: engine 2.11.0 invariato; nessuna modifica engine richiesta per questo blocco.

## Pezzo 7 — igiene e verifica

- [x] Log senza query/URI/corpi dei comandi.
- [x] Stringhe nelle otto lingue Wear e lingue del telefono.
- [x] Checklist sul dispositivo aggiornata per tutti i casi della fase 3.
- [x] Test protocollo, watch, telefono, engine; regressioni nuove.
- [x] Render 192/216/240 dp, font 1.3 con stato, volume, tile, conferma, playlist e ambient.
- [x] Lint API e release minificate dei due APK; engine-doctor.
- [x] Verifica Rust solo se il codice nativo viene modificato nella ripresa.
- [ ] Prove sul Galaxy Watch: ADB non rileva dispositivi; usare la checklist senza considerare i render una prova reale.

## Ambiente di verifica

Robolectric API 36 richiede Java 21. Usare per la sessione:
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`.
Il Java 17 nel PATH compila ma non esegue i test Robolectric dell'orologio.
Il confronto dei percorsi nel test dello store deve normalizzare i separatori Windows.

## Esito della verifica automatica

- Protocollo: 57 test; Wear dev: 56; telefono dev: 5; engine-wear: 26. Nessun fallimento.
- Render debug: 33 catture (14 lettore, 11 schermate, 8 tile); font 1.3 a 192/216/240 dp.
  I test con font grandi controllano anche la separazione prev/play/next e play/coda.
- `:app:lintDev`: zero errori. Restano avvisi non bloccanti (dipendenze, risorse, stile).
- `:app:assembleRelease` e `:wear:assembleRelease`: R8 e shrinkResources attivi.
  APK firmati con lo stesso certificato; il marcatore Wear è presente soltanto nell'APK Watch.
- Rust: `cargo +stable-x86_64-pc-windows-msvc check --locked` e `test --locked`, 5 test verdi.
  Il toolchain GNU predefinito non aveva gcc; per la verifica host è stato aggiunto MSVC,
  con target temporanei in `C:\Users\casua\.cargo-target\fluidify-host-msvc`.
  La build Android usa il toolchain/NDK già configurato, senza cambiare il predefinito.
- Engine doctor: controlli superati, engine 2.11.0 invariato e nessuna modifica locale.
  Il doctor segnala il branch di lavoro non agganciato direttamente a un tag.
- Tutte le stringhe traducibili sono presenti nelle otto lingue, sul telefono e su Wear.
- Diff senza errori di whitespace; `.gitignore` preesistente resta escluso.

La verifica ha portato anche a correggere il certificato hello su API 26/27, gli accessi
RemoteViews API 31 (sui telefoni precedenti restano le dimensioni/tinte XML), le dichiarazioni
API dei componenti già protetti, opt-in Media3 e vecchi codici di errore. La ricerca del telefono
ora azzera il filtro con stato legato alla query, senza mutare stato dentro remember.
Il dialogo unlike viene chiuso se cambia brano e non può rimuovere il like del brano successivo.

Log locali in `build/phase3-exact-tree.log`, `build/phase3-cargo-check.log`,
`build/phase3-cargo-test.log` e `build/phase3-engine-doctor.log`.
Le prove fisiche, incluse handoff, rete, audio, installazione e TalkBack, restano da eseguire
in `docs/wear-qa-checklist.md`. Non è stata pubblicata una release sullo store.
