# AirPods, tablet e audit — stato implementazione

## Configurazione

- Richiesta di implementazione approvata il 2026-11-07.
- Base: a9247286a8bb86d7b8a83c5c30ffea0a2fd1b2d3; engine 3.0.5.
- Fino a 3 implementatori contemporanei; 2 reviewer indipendenti per fase/giro; GPT-6.1 Sol, reasoning high.
- Soglia: ogni criterio valutato >=8, nessun difetto grave confermato; prove obbligatorie mancanti restano NV.
- Massimo 3 correzioni dopo la review iniziale di ogni fase.
- File preesistenti da preservare: README.md modificato; data/ListeningDatabase.kt, ListeningHistory.kt, ListeningRepository.kt non tracciati all'avvio. Non appartengono a questo intervento.

## Responsabilità

- AirPods: nuovo package non-UI airpods, codec/trasporto/repository/preferenze, test e POC diagnostico debug. Nessuna capsula o impostazioni di prodotto prima del PASS hardware.
- UI: PlayerScreen.kt, PlayerPanel.kt, DevicePicker.kt, SquareApp.kt, fix Connect in MainViewModel.kt e relativi test/helper. Eventuali altre modifiche a MainViewModel serializzate dal root.
- Audit iniziale: sola lettura su funzionalità generali; esclusi i nuovi file Listening*. Successivi implementatori riceveranno aree circoscritte.
- Root: integrazione app/servizio/manifest/build/CMake, Theme.kt per motion policy, documentazione e collaudi. Build onerose e dispositivi esclusivi del root fino a handoff.
- Nessuna modifica engine, pubblicazione, commit o push automatico.

## Fasi

| Fase | Stato | Evidenza |
|---|---|---|
| Piano P1 | Approvato con due precisazioni minori recepite | Due review Sol high, giro 2; minimi CP1..6: 8,9,9,9,8,9 |
| POC AirPods/backend | G1: gate NV confermato da2/2 reviewer; CP5=8/8 | Connect concorrente noRCB con AirPods indossate, LP/audio osservati funzionanti. UI sospesa; resoconto finale in airpods-gate-review-G1.md |
| Fullscreen tablet + Connect | Nuova UX integrata e osservata sul tablet | Suite app PASS; ripristino pannelli verificato automaticamente e sul tablet |
| Audit generale | Cinque fix integrati e testati; aggiunto fix SPAKE2 16KB | Auth/logout, indice/cancellazione download, richieste Wear; smoke account A/B e pairing Wear NV |
| Integrazione/build/test | APK finale I3 installato e avviato su S25 e tablet | Suite completa167/167 prima del listener; auth23/23 dopo; UI25/25 e Wear150/150 conservati. DEX auth27/27 e allineamento16KB PASS |
| Review implementazione | I3 approvato nel perimetro software verificato | 2/2 reviewer Sol high, minimi CP1.a/2/3/4/5/6: 9/8/8/8/9/8. CP1.b NV per entrambi; una lacuna test bassa non bloccante |
| UI AirPods | Bloccata dal gate previsto | Richiede PASS hardware |

## Dispositivi iniziali

- S25: host local, RFCY61FZDHW, SM-S931B Android17; fingerprint samsung/pa1qxeea/pa1q:17/CP2A.260605.016/S931BXXUDDZJB_OXMDDZJB:user/release-keys. Presenti LibrePods e Fluidify. Firmware/versione LibrePods e baseline da registrare.
- Tablet: host local, R52X50DKRBW, SM-X710 Android16, target dichiarato incompatibile.
- Gli emulatori non sono prova della compatibilità Bluetooth.

## Review piano — giro 1 / P0

UI e Bluetooth: GPT-6.1 Sol high. Qualità del piano verificata, runtime NV.

| Criterio | UI | Bluetooth | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| CP1 | 8 | 7 | 7.5 | 7 | Corretto in P1 |
| CP2 | 7 | 8 | 7.5 | 7 | Corretto in P1 |
| CP3 | 7 | 8 | 7.5 | 7 | Corretto in P1 |
| CP4 | 8 | 8 | 8 | 8 | Adeguato |
| CP5 | 7 | 7 | 7 | 7 | Corretto in P1 |
| CP6 | 9 | 9 | 9 | 9 | Adeguato |

## Review piano — giro 2 / P1

| Criterio | UI | Bluetooth | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| CP1 | 8 | 8 | 8 | 8 | Piano adeguato, runtime NV |
| CP2 | 9 | 9 | 9 | 9 | Adeguato |
| CP3 | 9 | 9 | 9 | 9 | Adeguato, layout runtime NV |
| CP4 | 9 | 9 | 9 | 9 | Adeguato |
| CP5 | 8 | 8 | 8 | 8 | Precisati ownership PlayerPanel/ViewModel e ciclo CA ON/OFF bidirezionale |
| CP6 | 9 | 9 | 9 | 9 | Adeguato |

## Prossime azioni

Review software I3 chiusa; tablet, vetro, lyrics, Connect e audit integrati. G1 concluso da due reviewer: CP1.bNV/NV, CP5=8/8. Connect concorrente Fluidify fallito prima dell’AACP, LP/audio osservati funzionanti; evento server OBEX e limiti documentali espliciti. Raccolta congelata invariata, musica in pausa e dispositivo libero. Il piano obbliga a sospendere UI/A2DP/lifecycle AirPods senza PASS: serve una scelta esplicita se mantenere convivenza simultanea obbligatoria o valutare uso autonomo esclusivo con gestione manuale e nuovi criteri. Nessuna modifica automatica LibrePods/stack/bridge/fork. Restano NV i flussi reali account A/B, cancellazione download JNI, pairing Wear completo e decoder al resize.

Aggiornamento preferenze: l’utente preferisce mantenere LibrePods ufficiale e gli aggiornamenti; l’uso esclusivo è un ripiego, non una scelta definitiva. Esaminare hosting dei suoi widget e interfacce esistenti prima di proporre una revisione del requisito. Due provider batteria/mode sono presenti nell’APK installato; fattibilità runtime ancora da collaudare. Dettagli e prova tecnica separata in airpods-companion-investigation.md. P1/G1 non vengono riscritti né promossi a PASS.

## Discovery audit generale (runtime NV)

1. P1 logout non chiude sessione playback nativa: root coordina servizio/ViewModel dopo rilascio owner UI.
2. P1 refresh OAuth può riscrivere token dopo clear/cambio client: TokenStore/WebApiAccount, da assegnare.
3. P1 scritture indice download concorrenti sul medesimo temporaneo, fallback può cancellare indice valido: DownloadStore.
4. P2 stop coda non cancella trasferimento JNI e può sovrapporre pump: DownloadQueue, coordinato con3.
5. P2 flush richieste Wear può perdere append concorrente: WatchDownloadsRemote, da assegnare.

## Aggiornamento ambiente

Lavoro esterno sulla cronologia ascolti sta modificando anche SquareApplication, PlaybackService, MainViewModel, SettingsScreen, SpotifyApi, LibrespotPlayer e file Gradle/catalogo. Preservare tali cambi; integrarvi soltanto hunks assegnati e coordinare snapshot finale. Non attribuire questi cambi al presente incarico.

S25: LibrePods1.0.4-play, versionCode100040301; Fluidify2.0.1/code24. Device panel aperto ma comando open ha restituito DEVICE_IN_USE per altra sessione. È consentito attendere brevemente il rilascio, non riusare o chiudere la sessione altrui. Baseline UI/hardware resta NV.

Il retry autorizzato con attesa1000ms ha restituito ancora DEVICE_IN_USE. Session list identifica t3-2ce4f139babfc9991238fda4: aperta per ClasseViva il 2026-11-07 alle14:30:32 Europe/Rome, ultimo evento screenshot14:30:44. Utente riferisce nessuna sessione attualmente in uso; metadati compatibili con blocco residuo. Nessuna chiusura/takeover della sessione altrui eseguita. Riprendere controlli dispositivo solo dopo rilascio confermato.

Discovery download assegnata a implementatore dedicato: DownloadStore/DownloadQueue e regressioni; stopAndJoin compatibile richiesto per il successivo logout. Auth e Wear saranno assegnati allo slot successivo.

## Integrazione POC e UI

- Target CMake separato airpods_socket; permessi e Activity exported=false soltanto manifest debug.
- Ingresso interno debug in Impostazioni > Informazioni > Debug > Diagnostica AirPods (POC). Release/dev non mostrano la riga.
- Harness: normale uscita chiude, finestra diagnostica background90s solo su pulsante esplicito per osservare LibrePods. Questa finestra non prova il lifecycle del prodotto; quest'ultimo richiede hook servizio/lease e hardware, ancora NV.
- Flag Gradle -PsignDebugWithRelease=true usa la configurazione firma esistente, se presente, per installazione -r senza cancellare dati. Non cambia firma debug predefinita; nessuna disinstallazione autorizzata.
- UI consegnata: PlayerScreen, AdaptivePlayerLayout, PlayerPanel, DevicePicker, ConnectRefreshState e hunk refreshDevices del ViewModel; root assume integrazione. Nessuna capsula AirPods.
- Suite predisposte AirPods codec/repository/session owner, adaptive layout/resize/Connect/screenshots, motion provider. Nessuna esecuzione ancora.
- Handoff account lifecycle: nuovo agente può modificare TokenStore/WebApiAccount e solo auth/logout/connect lifecycle di MainViewModel/PlaybackService; preservare tutti gli hunks cronologia e UI. Handoff Wear: WatchDownloadsRemote e helper/test dedicati, nessun protocollo.

## Verifiche integrate del 2026-11-07

- Engine doctor PASS, versione 3.0.5 invariata.
- CMake debug arm64-v8a e x86_64 PASS, incluso airpods_socket.
- Compilazione Kotlin app/Wear e relativi test PASS. Corrette firma callback permessi del POC e inferenza Deferred<Unit> del teardown.
- Primo avvio test con Java17 non valido per sandbox Robolectric SDK36; usato JBR21.0.10 già installato con Android Studio, senza cambiare dipendenze.
- Test app: 127/127 PASS con screenshot registrati tramite `-Proborazzi.test.record=true`; Wear: 150/150 PASS; wear-protocol:test PASS (cache). I risultati XML sono nelle directory build/test-results dei moduli.
- Il test preesistente compactMiniPlayer leggeva immagini del 5 novembre quando la registrazione era disabilitata; con immagini aggiornate passa. Per le verifiche visuali usare la modalità record. Corretto inoltre lo sfondo del fixture AdaptivePlayerScreenshots, che prima ereditava il bianco della finestra anche in tema scuro; rigenerazione da eseguire.
- APK debug app e Wear costruiti. App installata con `adb install -r -t` sul tablet, firma release esistente, nessuna cancellazione dati. Avvio e fullscreen reale a due colonne con coda verificati; screenshot `build/tablet-fullscreen-queue.png`. Nessuna capsula AirPods. Playback/decoder al resize e collaudo account A/B restano NV.
- Integrazione logout: DownloadPump controlla il gate account sotto il proprio lock sia al wake sia al riavvio; DownloadService controlla start e onStartCommand. Nuovo test wake tardivo dopo stopAndJoin PASS.
- Nuovo rilievo runtime: Android segnala allineamento ELF 16KB. Ispezione degli header LOAD dell'APK: solo libspake2.so ha allineamento 4096; airpods_socket, squarecore, spotstretch, androidx.graphics.path e conscrypt sono a16384. La dipendenza preesistente libadb3.0.0 importa spake2-java:android2.0.0. Correzione circoscritta da verificare prima della review.
- Build/log: `build/fluidify-integration-jdk21.log` (prima esecuzione con immagini obsolete), `build/fluidify-integration-record.log` (PASS). Nessuna review di implementazione ancora consumata.

## Verifiche successive e correzione UX richiesta dall'utente

- SPAKE2 sostituita puntualmente con spake2-android2.2.1, libadb resta3.0.0. `dependencyInsight` contiene soltanto il nuovo modulo. Checker `tools/check-spake2-alignment.py` PASS sull'APK perarm64/x86_64; zipalign -c -P16 PASS. Sul tablet scomparso l'avviso16KB.
- Un lavoro esterno ha aggiunto contemporaneamente `bridge/`, provider FluidifyToolHost nel manifest e dipendenza engine-ai-bridge. Prima build incoerente con provider dichiarato ma classe ancora assente: ClassNotFoundException reale. Ricostruzione dopo completamento sorgenti: avvio riuscito, nessuna modifica root al bridge esterno. Preservare anche queste modifiche.
- `build/fluidify-review-baseline.log`: suite app completa142/142 PASS (include15 testaggiuntivi del lavoroesterno); Wear150/150 eprotocollo invariati. `build/fluidify-coherent-rebuild.log` assembleDebug PASS.
- Screenshot adaptive rigenerati con sfondo opaco del fixture coerente con AppBackdrop; visione diretta telefono360x640, AMOLEDfont2, tablet1280x800, finestrabassa720x360. Test 9/9 PASS in `build/fluidify-layout-final.log`.
- Tablet reale: font1.75 porta a colonna unica con transport/selettori raggiungibili; font ripristinato a1.0. Screenshot `build/tablet-font175.png`. Playback avviato (posizione2:04->2:27 osservata), poi riportato in pausa; lyrics e coda si aprono. Resizeconvideo e logoutA/B restanoNV.
- L'utente ha esplicitamente rifiutato l'organizzazione della **colonna destra del fullscreen tablet** (non pannello Home). Nuova correzione richiesta: gerarchia titolo/artista, avanzamento+transport, una fila coerente di azioni secondarie, spazio ordinato per coda/lyrics/crediti. Ridurre frammentazione in capsule separate, mantenere stileFluid, soglieadattive, continuitàstato e target48dp. Non considerare approvata la UX precedente; reviewI1 rimandata allo snapshot corretto. Nessun giroreviewimplementazione consumato.

## Snapshot I1 — risultato integrato per la review iniziale

- Nuova colonna destra: gruppo Fluid unico con titolo/artista, anteprima testo integrata, scrubber, transport e fila di azioni; pannelli aperti con spazio riservato. Verifica visiva reale sul tablet e screenshot con coda/lyrics nei temi previsti. Non è ancora un'approvazione estetica dell'utente.
- La prima esecuzione dopo la revisione UX aveva due errori nel fixture lyrics (`Application` al posto di `SquareApplication`): accesso a LanguageStore reso utilizzabile anche nel fixture, mantenendo il contenitore reale in produzione. Lavori Gradle esterni concorrenti hanno inoltre interferito con gli output. Dopo loro termine: `build/fluidify-ux-corrected.log` PASS, 156 test, zero errori/fallimenti letti dagli XML prima della successiva sovrascrittura esterna.
- Prova reale successiva: un cambio di scala font ricreava l'Activity e perdeva il pannello selezionato. `panel` e `immersive` ora usano stato salvabile. Nuovo test di ripristino lyrics PASS; suite UI completa21/21 PASS in `build/fluidify-restore-state.log`, XML conservati in `build/review-I1/test-results/`. Sul tablet, Testo rimane selezionato e visibile dopo il cambio; font ripristinato a1.0.
- Ultimo steering utente: accessibilità a priorità inferiore; nessuna estensione di quel filone. Priorità a AirPods, organizzazione fullscreen e bug funzionali. La regressione già corretta riguarda la continuità dello stato anche alla ricreazione della schermata.
- APK I1 conservato in `build/review-I1/fluidify-I1.apk`, SHA256 `7d980e6607b977672fdc684a207bfce79d293d4a6337ac65e99d3ab6eaac7e6a`, installato senza cancellare dati sul tablet. SPAKE2 ELF16KB e zipalign16KB PASS anche su questo artefatto.
- Gli screenshot AudioLight preesistenti rigenerati dai test sono stati copiati in `build/review-I1/audio-light-screenshots/`, poi ripristinati alla base per non includere modifiche binarie incidentali. Gli screenshot adaptive rimangono artefatti ignorati da Git.
- POC reale S25: costruttore JNI riuscito, due tentativi manuali falliti nella registrazione locale PSM0x1001; nessun handshake o comando inviato. LibrePods rimane collegata. Uscita esplicita e oltre2min senza nuove aperture attribuite a Fluidify. Dettagli e limiti in `airpods-poc-s25.md`. **Gate NV**: nessuna UI AirPods di prodotto, nessun adattatore A2DP o hook lifecycle prodotto introdotto.
- Il lavoro esterno continua su listening history/AI bridge, inclusi file engine e build. Queste modifiche non appartengono al presente intervento. Lint esterno osservato falliva sulle nuove traduzioni history; non attribuire quel log a un lint eseguito da questo incarico.
- Review I1 iniziale: due reviewer indipendenti Sol high da avviare sul medesimo snapshot `build/review-I1/source/`; nessuna correzione di review consumata. Prove ancora NV: logout reale A→B, download nativo annullato su hardware, pairing/smoke Wear completo, decoder video durante resize e gate AirPods completo.

## Steering UX successivo — prima del congelamento I1

L'utente ha rilevato sul tablet due problemi da correggere ora: il vetro campiona uno sfondo apparentemente più chiaro/nitido della pagina già sfocata; il **testo della canzone** risulta troppo separato dal gruppo controlli (chiarito esplicitamente, non titolo/artista). Screenshot reale: `build/tablet-glass-lyrics-reported.png`. Nuova implementazione circoscritta del fondale campionato e della composizione lyrics/controlli prima della review finale. I1 non è ancora congelato, nessun reviewer lanciato e nessun giro consumato. Preservare le correzioni di ripristino già verificate. Entrambi i pannelli dispositivo sono stati rilasciati; font tablet1.0 ripristinato.

## I1 definitivo: vetro e lyrics corretti, review iniziale

- La sorgente del fondale ora registra il layer **dopo** blur/trasformazioni dello stage; `LocalGlassBackdrop` e `LocalFluidCanvasBackdrop` ricevono il medesimo fondale composto. Correzione app in GlassSurface/PlayerScreen, nessuna modifica all'engine. Freeze e controlli restano esclusi dalla cattura di sé stessi.
- Lyrics tablet: ancoraggio del verso attivo vicino ai controlli, margini/fade più contenuti, spazio per traduzione/karaoke. Telefono conserva centraggio. La stessa lista reagisce ai cambi di viewport.
- `build/fluidify-glass-lyrics.log`: build app e25/25 test UI PASS (4layout,18screenshot/stato,3Connect). Nuovi casi verificano distanza, contesto, seek e resize. Fixture contrastata con blur reale nei temi light/dark/AMOLED; non equivale alla fedeltà AGSL Samsung.
- APK definitivo `build/review-I1/fluidify-glass-lyrics.apk`, SHA256 `294591a209ceea7149f7ddcf90c4b7ce114a75262abb4a14eae6ef085f703588`; ELF SPAKE2 e ZIP16KB PASS, installazione-r senza perdita dati sul tablet.
- Osservazione reale Samsung: sfocatura presente anche nel materiale del pannello, lyrics vicine al gruppo controlli, Coda e Testo apribili; screenshot `build/tablet-glass-lyrics-after.png` e `build/tablet-glass-lyrics-I1.png`. La pellicola dell'engine conserva la propria tinta; non si pretende uguale luminanza pixel per pixel. Costo GPU e continuità decoder video al resize restano NV.
- Snapshot sorgenti I1 e hash sotto `build/review-I1/source/` e `source-manifest.json`. Due review indipendenti Sol high, fase implementazione, giro iniziale1 (zero giri correttivi consumati), sul medesimo snapshot; esiti ancora in attesa. Gate AirPods rimaneNV e impedisce la UI AirPods di prodotto.

## Review I1 parziale e correzione R1

- Ricevuto reviewer A, Sol high: CP1.a9, CP1.bNV, CP2=8, CP3=8, CP4=7, CP5=9, CP6=8. Reviewer B ancora in esecuzione; nessun polling. Report integrale e tabella parziale in `airpods-review-I1.md`. Nessuna approvazione software aggregata ancora attribuita.
- R1 confermato: `connectWebApi()` conservava un'autorizzazione sospesa senza job annullabile e salvava la risposta incondizionatamente. Il clear di logout/disconnect poteva essere annullato dalla risposta tardiva. È un percorso preesistente che lascia incompleto l'audit auth autorizzato.
- Correzione root circoscritta, successiva allo snapshot I1: `webApiJob`/numero tentativo; annullamento a nuova autorizzazione, cambio client in UI, disconnect e logout; stato connecting azzerato senza farlo riscrivere da vecchi tentativi; cancellazione propagata. `TokenStore.authorizeIfCurrent()` cattura generazione e client prima della sospensione, controlla cancellazione e commit atomico tramite `saveIfCurrent` prima delle pubblicazioni UI/caricamenti. Logout azzera anche lo stato Web API.
- Sei regressioni aggiunte: autorizzazione corrente, clear durante attesa (comune a logout/disconnect), cambio client, client cambiato senza invalidazione esplicita, sessione sostitutiva e risposta non cooperativa dopo cancellazione. `build/fluidify-review-R1-oauth.log`: build app e19/19 test auth PASS (16 TokenStore,3AccountLifecycle). XML e APK conservati in `build/review-I2/`.
- I1 congelato resta invariato per il reviewer B; le modifiche live dopo il congelamento riguardano esclusivamente TokenStore, MainViewModel e TokenStoreTest. Nuovo snapshot e due nuove review correttive solo dopo raccolta del report B. Primo giro correttivo in preparazione, nessuno concluso.

## Chiusura I1 e avvio I2 — primo giro correttivo

- Ricevuto anche reviewer B, Sol high: CP1.a8, CP1.bNV, CP2=8, CP3=8, CP4=8, CP5=9, CP6=9; nessun rilievo correttivo concreto aggiuntivo. Report B integrale e tabella aggregata definitiva in `airpods-review-I1.md`.
- I1 non supera la soglia CP4 di A (7). R1 è confermato e corretto; la maggioranza non viene usata per ignorarlo. Tutti gli altri criteri software raggiungono la soglia di entrambi. CP1.b rimaneNV e mantiene chiuso il passaggio alla UI AirPods.
- Versione I2: stesso perimetro software I1 con sole modifiche funzionali successive a TokenStore, MainViewModel e TokenStoreTest per R1. Manifest sorgenti congelati in `build/review-I2/source-manifest.json`. Build e19/19 test auth PASS; nessun cambiamento UI successivo ai25/25 test e QA Samsung I1.
- Avvio di due nuovi reviewer indipendenti Sol high per I2, giro correttivo1 dopo l'iniziale (massimo3). Nessuna approvazione I2 finché non sono disponibili entrambi i report. Restano gli stessi NV hardware riportati in precedenza.

## R2 runtime e ricostruzione isolata I3

- Ricevuto B2: R1 chiuso, nessun nuovo rilievo software statico; voti CP1.a8, CP1.bNV, CP2=8, CP3=8, CP4=8, CP5=9, CP6=9. A2 ancora in attesa. Report e tabella parziale in `airpods-review-I2.md`.
- Installando l'APK I2, root rileva crash reale tablet `NoClassDefFoundError: TokenStore$2`. Tre classi generate auth mancano effettivamente dalle definizioni DEX. L'APK I2 è respinto indipendentemente dai voti statici. Ripristino immediato di I1 funzionante su tablet e S25, senza cancellare dati; avvio verificato.
- Ricostruzione app da output separati con init script `build/review-I3/isolated-app.gradle`, senza build cache e con Kotlin non incrementale. Prima invocazione fallita per quoting PowerShell dei parametri con punto; corretta senza cambiare sorgenti. Log finale `build/fluidify-isolated-I3.log`: BUILD SUCCESSFUL,167/167 test app, zero failure/error/skip; XML immutabili in `build/review-I3/test-results/`.
- Nuovo `tools/check-apk-class-coverage.py`, confronto classi compilate/definizioni DEX per APK non minimizzati. Regressione vera: su I2 exit1 con3/22 classi auth assenti; su I3 exit0 con22/22 presenti. Evidenza JSON `build/review-I3/artifact-verification.json`. Riferimento formato DEX AOSP nel checker. Non misura tutti i percorsi runtime né dichiara compatibilità con APK minimizzati.
- APK I3 `build/review-I3/fluidify-I3.apk`, SHA256 `f5ac0692b1b9e60426d23cdfd0dd6b2deb1ce9f97d4d9a8fb64df8525664efad`: ELF SPAKE2/ZIP16KB PASS; installato-r su tablet e S25. Avvio reale riuscito su entrambi; MainActivity verificata, fullscreen telefono e lyrics tablet raggiunti. Screenshot `build/review-I3/s25-I3.png` e `tablet-I3.png`. Nessun nuovo crash Fluidify nel buffer esaminato dopo avvio I3. Entrambi i pannelli dispositivo rilasciati.
- Il codice applicativo è identico a I2; aggiunti checker di artefatto e documentazione. La causa precisa degli intermedi incoerenti non è dimostrata, mentre il contenuto incompleto di I2 e la correzione dell'artefatto con build isolata lo sono. Non attribuire automaticamente il difetto a un altro autore.
- Attendere A2 per chiudere il giro I2, poi due nuove review I3 del pacchetto verificato (secondo giro correttivo, nei3 previsti). Nessuna approvazione retroattiva di I2. AirPods gate rimaneNV; nessuna UI di prodotto.

## I2 chiuso, candidato I3 con cancellazione del listener

- A2 ricevuto: R1 chiuso; CP4=7 per listener OAuth che conserva la porta dopo cancellazione. Altri voti A2: CP1.a8, CP1.bNV, CP2=8, CP3=8, CP5=9, CP6=8. Aggregato I2 in `airpods-review-I2.md`: correzione necessaria. Il suo rilievo R2 viene chiamato qui **R2-L**; il crash dell'APK rilevato da root è **R2-P**.
- R2-L confermato nel receiver preesistente e corretto da root in SpotifyOAuth e nuovo OAuthLoopbackReceiver: handler di cancellazione registrato prima dell'I/O, chiusura di listener e peer con lock breve, mutex comune ai flussi per aspettare use/close prima del nuovo bind, rilascio prima dello scambio token HTTP. Lettura peer con timeout. Nessuna nuova automazione o cambiamento delle credenziali richieste.
- Nuovo `OAuthLoopbackReceiverTest`: quattro prove JVM con socket IPv4 reali su porta effimera, comprendenti accept, richiesta parziale/peer chiuso, sostituzione prima del bind e redirect UTF8. `build/fluidify-listener-I3.log`: build app e23/23 test auth PASS (16TokenStore,3AccountLifecycle,4loopback). Test sul helper/trasporto, non login Android A→B.
- Candidato finale I3: `build/review-I3/fluidify-I3-listener.apk`, SHA256 `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`. Copertura classi auth27/27, ELF SPAKE2 e ZIP16KB PASS. Installato-r e avviato su tablet e S25 con account preservati; fullscreen telefono e lyrics tablet osservati. Screenshot `build/review-I3/tablet-listener-final.png` e `s25-listener-final.png`. Device panel rilasciati.
- La suite app completa167/167 resta associata alla ricostruzione isolata precedente alla modifica del listener; i23/23 test auth e il nuovo APK sono successivi. Nessuna somma artificiale dei conteggi. XML completi precedenti in `build/review-I3/test-results/`, auth finali in `auth-listener-test-results/`; Wear150/150 invariato.
- Il controllo negativo del vecchio APK I2 è riproducibile contro le22 classi compilate corrispondenti, conservate in `build/review-I2/compiled-classes/` (3 mancanti). Non confrontarlo con le nuove27 aspettandosi il solo difetto originario: includono il nuovo trasporto. Gli screenshot AudioLight rigenerati restano archiviati, file tracciati ripristinati alla base.
- Nuovo congelamento I3 include anche SpotifyOAuth, OAuthLoopbackReceiver, il suo test e il checker APK (80 file). Avvio due nuovi reviewer indipendenti Sol high: secondo giro correttivo dei3 previsti. Non fornire loro voti o conclusioni altrui; usare requisiti ed evidenze tecniche. Tutti i gate hardware precedenti rimangonoNV.

## Chiusura review software I3 e consegna

- Ricevuti A3 e B3, entrambi GPT-6.1 Sol high, indipendenti e sulla stessa versione congelata. Minimi e medie coincidenti: CP1.a9, CP2=8, CP3=8, CP4=8, CP5=9, CP6=8; due voti validi per criterio. CP1.bNV/NV, zero voti numerici validi. Riepilogo e report integrali in airpods-review-I3.md.
- Review software chiusa al secondo giro correttivo, entro il limite previsto. R1, R2-L e R2-P risolti nel perimetro verificato. A3 segnala soltanto T1, precisione non bloccante del test accept: la cancellazione può precedere l’I/O. Nessun bug del listener dimostrato e nessun test deterministico durante accept rivendicato.
- APK definitivo invariato: build/review-I3/fluidify-I3-listener.apk, SHA256 dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2. I documenti live vengono aggiornati dopo la review senza alterare snapshot, manifest o APK congelati.
- Tablet/Connect/vetro/lyrics e audit software consegnati con prove e limiti descritti sopra. L’intero piano non è dichiarato completato: gate AirPods e prove runtime residue rimangono aperti. Nessuna pubblicazione o modifica engine inclusa.

## Prosecuzione scelta dall’utente — client diretto

- L’utente sceglie di approfondire il client diretto e autonomo da LibrePods. Nessuna alternativa bridge/fork viene avviata.
- Sul medesimo APK I3, alle18:42:28 il socket Fluidify si connette; handshake e sottoscrizione ricevono A3064, firmware e batterie L78/R80/custodia100. Nessuna scrittura di modalità/CA. Un tentativo LibrePods successivo fallisce nella registrazione locale, correlato a UID/socket diversi. La precedente sessione LibrePods era già terminata prima del tentativo Fluidify.
- POC chiuso alle18:44:45.975; LibrePods riconnessa normalmente alle18:45:37.074. Evidenze e distinzioni aggiornate in airpods-poc-s25.md. Gate totale NV: nessuna promozione a PASS della convivenza. Richiesta conferma per AirPods indossate durante la prossima baseline.
- Approfondire il pool RCB e possibili soluzioni nel solo client, senza alterare stack, LibrePods o servizi altrui. Il default AOSP15 non è una misura della build Samsung. Nessuna nuova modifica prodotto o build in questa ricognizione.

## Diagnosi diretta completata — successiva alla prova delle18:42

- Ricognizione Sol high conclusa; root ha letto il report e riprodotto l’analisi ELF. La libreria Samsung esaminata contiene15RCB classici. Restano distinti capienza compilata e occupazione effettiva non misurata.
- Non emerso un percorso nel solo client che eviti la registrazione aggiuntiva; nessun workaround su costruttori/UUID/Binder viene implementato senza evidenza. Sorgenti prodotto e APK I3 invariati; nessun nuovo giro correttivo o azzeramento dei contatori.
- Prossima prova pronta: LibrePods stabilmente collegata, audio locale e AirPods indossate; singolo connect Fluidify, quindi handshake/sottoscrizione soltanto se entrambi i canali restano aperti. Conferma fisica utente ancora in attesa. Tutti i dettagli e il report sono in airpods-direct-diagnosis.md. Gate complessivo NV.
- Il controllo live rispetto allo snapshot I3 segnala soltanto PlaybackService nelle aree di continuità history, esterne a questa ricognizione; preservate. L’APK I3 congelato conserva il suo hash. Nessun rebuild viene eseguito per incorporare lavoro concorrente in una prova AirPods senza necessità.

## Prova con auricolari indossati — dalle19:50

- Utente conferma indossate/collegate, baseline audio e cambi regolari e nessuna interruzione durante il tentativo. APK realmente installato letto e verificato identico a I3; LibrePods e build OS ricontrollati.
- Nove transazioni baseline, tre per ANC/Trasparenza/Adaptive, ricevute hardware in LibrePods entro5s (massimo3.385s). Riproduzione locale Fluidify e route AirPods A2DP osservate. Gesti e tre conversazioni CA non attestati.
- Connect manuale Fluidify unico alle19:57:11.419, UID10595 socket83: noRCB vPSM1004 e cleanup immediato, prima dell’handshake. LibrePods non si chiude né si riconnette nella cattura; due ricevute modalità successive e conferma audio utente. Nel contesto anche eventi server UID1002, effetto su altri profili non verificato.
- Diagnostica uscita19:58:57.677; osservazione fino20:02:00.774 senza nuovo connect Fluidify. Musica di test in pausa e pannello rilasciato. Nessuna modifica codice/build/root/policy/LibrePods. Evidenze locali congelate e report in airpods-worn-gate.md.
- Non PASS hardware; classificazione e portata da esaminare con due reviewer indipendenti sulle stesse evidenze. Nessuna riapertura artificiale dei giri software I3, nessuna UI anticipata.

## Review evidenze G1 — parziale

- B ricevuto: CP1.bNV, CP5=8. Verificati16/16 artefatti G1 e80/80 I3; connect negativo documentato prima dell’AACP, gate incompleto. Non dimostrato FAIL complessivo come definito dal piano, né PASS.
- Confermata pertubazione server UID1002/OBEX nel contesto; nessuna chiusura LP rilevata e audio percepito regolare. Non attribuire assenza di effetti su ogni altro profilo. A in attesa; riepilogo e report B in airpods-gate-review-G1.md. Nessun’altra prova hardware avviata.

## Chiusura review evidenze G1

- A e B ricevuti, entrambi Sol high: CP1.bNV e CP5=8 ciascuno; media/minimo8 su2/2 voti CP5, nessun aggregato numerico per NV. Esito operativo NV, nessun PASS UI. Non dimostrato il degrado reversibile richiesto dal FAIL complessivo, pur essendo dimostrato il connect negativo.
- I reviewer hanno verificato manifest16/16 G1 e80/80 I3; nessun nuovo difetto software bloccante segnalato. Limite documentale: initialModeAdaptive è dichiarato mentre foto baseline G1 mostra ANC; non usato come prova. Ripristino Adaptive confermato; nessuna riscrittura della raccolta congelata.
- Dati e report completi in airpods-gate-review-G1.md. Nessun ulteriore tentativo fisico o patch speculativa. Ramo AirPods di prodotto sospeso in attesa della decisione sul requisito di convivenza; ogni eventuale modalità esclusiva richiede criteri e collaudi aggiornati. Nessun reset dei giri software.

## Ricerca ulteriore autorizzata — otto ore

- Dopo aver rifiutato widget/workaround e considerato il ripiego esclusivo, l’utente
  assegna otto ore per cercare ancora la convivenza completa con LibrePods ufficiale,
  invariata e attiva anche in background. Termine 2026-11-08 03:52:42 UTC, 04:52:42 Roma.
- Il telefono serve all’utente: nessun dispositivo è disponibile durante questa ricerca.
  Probe temporaneo disinstallato, sue associazioni rimosse e pannello S25 rilasciato.
- Incarico e criteri in `airpods-coexistence-8h.md`. G1 resta NV, I3 resta approvato nel suo
  perimetro; nessuna promozione di UI o reset dei contatori. L’uso esclusivo è il ripiego
  se non emerge una soluzione, non una conclusione prematura della nuova ricerca.

## Chiusura delle otto ore — 2026-11-08

- Termine trascorso alle03:52:42 UTC. Nessuna soluzione completa verificata per
  LibrePods ufficiale invariata e attiva insieme a Fluidify. G1/CP1.b resta NV;
  il connect concorrente negativo è conservato e non prova un'impossibilità universale.
- C4, terzo giro correttivo della ricerca, ha entrambe le review previste; nessun
  rilievo correttivo confermato. Alla chiusura ricontrollati771 file e7 input esterni
  per dimensione/SHA256, senza mismatch. L'appendice root successiva resta separata
  dalle review C4 e non fornisce un nuovo contratto completo.
- Task_cancel sulle25 deleghe proprie:24 completed e1 failed, nessun errore; tutte
  già terminali. Nessun nuovo agente/timer, device o build. Pannelli già rilasciati
  prima della finestra; nessuna risorsa altrui fermata o acquisita.
- Si applica la preferenza utente per controlli esclusivi. Non sono implementati o
  collaudati come prodotto: serve l'emendamento P1 con due review e gate esclusivo,
  mantenendo batterie/stato ricevuto/lifecycle e prove audio/gesti/CA. Nessuna UI o
  A2DP sbloccata automaticamente, nessuna modifica a LibrePods o alle sue impostazioni.
- Decisione, fonti, hash, cleanup e confini della fase successiva sono in
  `airpods-coexistence-decision.md`. Si chiude la ricerca a tempo, non l'intero piano
  AirPods; I3 e i congelati storici restano immutati.

