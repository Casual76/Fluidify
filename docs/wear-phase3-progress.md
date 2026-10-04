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
- [ ] Test dell'esatto albero finale e commit/push verde del blocco.

## Pezzo 6 — interfaccia e stato (sezioni 7 e 8)

- [ ] Tile: esito dei comandi, dimensioni, nonce persistente, intent e copertine.
- [ ] Firme delle superfici distinte; rinnovo dell'icona ongoing; stack autonomo pigro.
- [ ] Volume: livello iniziale, collegamento, cambio dispositivo/modalità e TalkBack.
- [ ] Errori ripetuti, cache Home, ritorno dalla ricerca, copertine e pin in tutti gli scaffali.
- [ ] Lifecycle degli anelli, layout 192 dp/font 1.3/stato e trasporto LTR.
- [ ] Ghiera immersiva, navigazione single-top, sfondi opachi, ellissi e motivi degli errori.
- [ ] Permessi al ritorno, collector legati alla composizione.
- [ ] Ambient unico nero, low-bit e protezione burn-in.
- [ ] Azioni TalkBack della copertina e etichetta Fissa.
- [ ] VolumeCoalescer, ipotesi play/pausa, stima dello sfasamento da hello/ack, ArtStore atomico.
- [ ] Collisione degli ID di notifica.
- [ ] Valutare patch facoltativa dell'accento engine; evitare un rilascio se non necessario.

## Pezzo 7 — igiene e verifica

- [ ] Log senza query/URI/corpi dei comandi.
- [ ] Stringhe nelle otto lingue Wear e lingue del telefono.
- [ ] Checklist sul dispositivo aggiornata per tutti i casi della fase 3.
- [ ] Test protocollo, watch, telefono, engine; regressioni nuove.
- [ ] Render 192/216/240 dp, font 1.3 con stato, volume, tile, conferma, playlist e ambient.
- [ ] Lint API e release minificate dei due APK; engine-doctor.
- [ ] Verifica Rust solo se il codice nativo viene modificato nella ripresa.
- [ ] Prove sul Galaxy Watch: ADB non vedeva alcun dispositivo all'inizio della ripresa.

## Ambiente di verifica

Robolectric API 36 richiede Java 21. Usare per la sessione:
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`.
Il Java 17 nel PATH compila ma non esegue i test Robolectric dell'orologio.
Il confronto dei percorsi nel test dello store deve normalizzare i separatori Windows.
