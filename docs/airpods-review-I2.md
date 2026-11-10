# Review implementazione I2 — giro correttivo 1

Snapshot: `build/review-I2/source/`; manifest SHA256 `912405f1224682bf783efad924d8466c396b2e53f7cc3efea66aa70a75140936`.

Ricevuti entrambi i report, A2 e B2, Sol high. I voti sotto riguardano I2 e non vengono modificati retroattivamente dall'orchestratore. **I2 richiede correzione**: CP4 di A2 sotto soglia per il listener OAuth; inoltre il difetto runtime del pacchetto trovato da root impedisce la consegna indipendentemente dai voti statici.

| Criterio | A2 | B2 | Media | Minimo | Esito e problemi aperti |
|---|---|---:|---:|---:|---|
| CP1.a — POC software | 8 | 8 | 8 (2/2) | 8 | Soglia software raggiunta |
| CP1.b — gate hardware | NV | NV | — (0/2) | — | Gate aperto |
| CP2 — UX | 8 | 8 | 8 (2/2) | 8 | Soglia raggiunta |
| CP3 — tablet | 8 | 8 | 8 (2/2) | 8 | Decoder reale al resize NV |
| CP4 — audit | 7 | 8 | 7,5 (2/2) | 7 | R1 chiuso; listener OAuth da correggere |
| CP5 — verifiche | 9 | 9 | 9 (2/2) | 9 | R2-P runtime successivo blocca l'APK I2 |
| CP6 — Wear | 8 | 9 | 8,5 (2/2) | 8 | Pairing completo NV |

## R2 trovato da root dopo la review statica

L'APK I2 SHA256 `055cc522b628dba13c5651c457a56fdb50e33b26bd3d3ffb9d0f026efa78a55e` è **non consegnabile**: avvio reale tablet alle17:34:10 termina con `NoClassDefFoundError: TokenStore$2`, nel costruttore usato da SquareApplication/PlaybackService. Log `build/review-I2/tablet-startup-crash.log`.

La lettura delle definizioni DEX conferma tre classi compilate assenti: TokenStore$2, $3 e $authorizeIfCurrent$1. Non basta la loro presenza fra riferimenti/stringhe. I test JVM usavano il costruttore interno con dipendenze finte e non rilevavano l'incompletezza DEX. Gli intermedi sono stati usati da più build dello stesso checkout: l'attribuzione precisa del difetto incrementale/concorrenza resta un'inferenza, non una causa dimostrata.

Ripristinato subito l'APK I1 funzionante sui due dispositivi, senza cancellare dati. Ricostruzione da output app nuovi e separati, senza cache di build o incremental Kotlin: `build/review-I3/isolated-app.gradle`, log `build/fluidify-isolated-I3.log`. Nessuna modifica ulteriore al codice applicativo R1. Nuovo controllo `tools/check-apk-class-coverage.py`: legge le class_defs DEX e confronta le classi compilate del package auth. Fallisce correttamente su I2 (3/22 mancanti), passa sul nuovo APK (22/22 definite). Evidenza `build/review-I3/artifact-verification.json`.

Il nuovo APK I3 SHA256 `f5ac0692b1b9e60426d23cdfd0dd6b2deb1ce9f97d4d9a8fb64df8525664efad` supera167/167 test app completi, ELF/ZIP16KB, installazione-r e avvio reale su tablet e S25, inclusi player fullscreen e lyrics tablet. I3 richiede verifica indipendente dell'artefatto aggiornato. Le valutazioni numeriche I2 non eliminano il difetto runtime confermato nell'APK I2. Gate AirPods invariatoNV.

## Report B2 integrale

**Reviewer B2 — GPT-6.1 Sol, reasoning high. Fase IMPLEMENTAZIONE, giro CORRETTIVO1, versione I2.**

**Esito: R1 chiuso nel perimetro software verificato. Nessun nuovo rilievo correttivo concreto. Il gate hardware AirPods resta NV e non autorizza la UI AirPods di prodotto.**

Ho operato esclusivamente in lettura: nessuna modifica, delega, esecuzione Gradle, attività dispositivo, commit o push. Ho letto le tre skill richieste e i riferimenti Fluid pertinenti; non ho trovato AGENTS applicabili nei percorsi controllati.

Nota d’indipendenza: una ricerca nell’indice dello status ha restituito incidentalmente anche righe contenenti voti I1. Non ho aperto i report dei reviewer; quei voti non sono stati usati per formulare queste conclusioni.

Ho verificato direttamente:

- Manifest I2: SHA256 `912405f1224682bf783efad924d8466c396b2e53f7cc3efea66aa70a75140936`.
- **76 file presenti, 76 hash corrispondenti, nessun file aggiuntivo nello snapshot.**
- APK I2: SHA256 `055cc522b628dba13c5651c457a56fdb50e33b26bd3d3ffb9d0f026efa78a55e`.
- Confronto effettivo I1→I2: cambiano soltanto `TokenStore.kt`, `MainViewModel.kt`, `TokenStoreTest.kt` e il documento di stato, coerentemente con `changes-from-I1.json`. UI, tablet, vetro, lyrics e sorgenti Wear congelati sono identici a I1.

I voti numerici seguenti riguardano il codice e le evidenze disponibili; non incorporano come successi i sottocriteri runtime rimasti NV.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| CP1.a | **8** | Statico diretto; POC hardware documentato da root | Trasporto paired-only BR/EDR tipo 3, PSM `0x1001`, JNI caricato esplicitamente e limitato ai tipi Bluetooth. Timeout connect 10 s, pending osservativo 5 s e generazioni nel [repository congelato](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/airpods/AirPodsRepository.kt:75). Opt-in OFF, harness debug non esportato, attribuzione GPL e upstream pinned presenti. UI, A2DP e lifecycle di prodotto correttamente rinviati. | Nessun difetto concreto rilevato nel perimetro statico. Funzionamento AACP reale NV. |
| CP1.b | **NV** | Hardware incompleto | Il [POC S25](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/docs/airpods-poc-s25.md:1) documenta costruzione riuscita e due errori locali di registrazione PSM prima dell’handshake. Mancano baseline indossata, tre sessioni, conferme bidirezionali modalità/CA e audio/gesti. | Gate aperto; nessuna penalizzazione numerica e nessun PASS dedotto. |
| CP2 | **8** | Statico, immagini esistenti e XML root | Gruppo unico dei controlli tablet in [PlayerScreen](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/player/PlayerScreen.kt:1403). Sorgente prima del layer degli effetti in [GlassSurface](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/player/GlassSurface.kt:15); entrambi gli scope ricevono `playerGlass`. Lyrics sotto i controlli con margini/fade compatti e spazio traduzione/karaoke. DevicePicker mantiene utilizzabili i device Connect presenti quando fallisce il fallback. Identità I1→I2 verificata. | Nessuna regressione concreta rilevata. Nessuna nuova QA dispositivo I2. |
| CP3 | **8** | Statico e test simulati; osservazioni Samsung attribuite a root | [AdaptivePlayerLayout](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/player/AdaptivePlayerLayout.kt:12) applica soglie, rapporto 45/55, gap 24, limiti 1200/560 e fallback con viewport finita. I tre slot restano nello stesso albero; panel/immersive sono salvabili e gli scroll sono mantenuti sopra l’adattamento. Quattro test layout e diciotto screenshot/stato risultano PASS negli XML conservati. | Decoder reale durante resize e costo GPU NV. Il test con owner fittizio non prova la continuità del decoder. |
| CP4 | **8** | Wiring statico diretto; 19 test auth root verificati negli XML | R1 chiuso secondo l’analisi sotto. Logout invalida ownership e attende il download prima dello shutdown in [PlaybackService](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/playback/PlaybackService.kt:883). Writer dell’indice sotto mutex con pubblicazione atomica nondistruttiva; DownloadPump conserva ownership fino al completamento; Wear rimuove individualmente i successi persistiti. | Nessun nuovo rilievo concreto. Logout reale A→B e cancel JNI hardware NV. |
| CP5 | **9** | Integrità e lettura diretta degli artefatti | Snapshot verificabile e delta minimo. [XML auth I2](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/auth-test-results): 16 TokenStore + 3 AccountLifecycle, zero failure/error/skip. [XML UI I1](/C:/VibeCoded%20Projects/Fluidify/build/review-I1/glass-lyrics-test-results): 4 layout + 18 screenshot/stato + 3 Connect, tutti PASS. Log OAuth termina con `BUILD SUCCESSFUL`. Limiti esplicitati. | Nessun lint PASS disponibile; I2 non installato. Le prove helper non coprono il ViewModel/runtime. |
| CP6 | **9** | Statico e artefatti Wear precedenti | [PendingWatchDownloads](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/wear/PendingWatchDownloads.kt:27) separa persistenza e invio seriale, conserva la coda dopo errore/cancellazione e registra ogni successo. XML Wear conservati: 150 test, zero failure/error. Nessun delta I2 nei sorgenti Wear congelati; nessun cambiamento live rilevato a Wear/protocollo rispetto alla base. Manifest diagnostico `exported=false`; nessuna nuova API Android AirPods esportata. | Pairing e smoke Wear completi NV. AirPods Wear resta futuro. |

**Riesame di R1 dal lato chiamanti/UI.**

Il trigger originario era una risposta OAuth arrivata dopo logout, disconnect o cambio client, capace di ripristinare token e pubblicare nuovamente lo stato connected.

La correzione copre entrambi i livelli necessari:

1. In [TokenStore.kt:69](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/auth/TokenStore.kt:69), generazione e client vengono catturati prima della sospensione. Dopo la risposta viene controllata la cancellazione; confronto del client e `saveIfCurrent` avvengono sotto lo stesso monitor. Clear e sessione sostitutiva invalidano quindi il commit.
2. In [MainViewModel.kt:954](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/MainViewModel.kt:954), ogni nuova autorizzazione incrementa il tentativo e cancella il job precedente. Lo stesso avviene su modifica effettiva del campo client, disconnect e logout.
3. `setClientId` precede la cattura della sessione. Connected, caricamento feed e rilancio ricerca avvengono solo dopo `accepted` e controllo del tentativo corrente, senza ulteriori sospensioni interposte nel tratto di pubblicazione.
4. `CancellationException` viene propagata. Sia il ramo errore sia il `finally` verificano il tentativo: un vecchio completamento non può spegnere `connecting` del nuovo tentativo o sovrascriverne l’errore.
5. Disconnect azzera connecting/connected/incomplete/expired; logout sostituisce l’intero `WebApiState`. Le callback congelate di `SquareApp` collegano effettivamente le azioni UI a questi metodi.

**Conclusione su R1: risolto staticamente nei chiamanti effettivi.** Non ho individuato un percorso concreto, nel wiring UI esaminato, che consenta alla risposta abbandonata di salvare token o pubblicare connected dopo quelle invalidazioni.

I sei test nuovi verificano il contratto del helper, inclusa una risposta non cooperativa dopo cancellazione. Le variabili locali `connected` e `loads` dei test non costituiscono una prova del vero ViewModel, del suo `finally` o dell’uso browser: questi aspetti sono sostenuti qui dalla lettura statica; l’uso reale resta NV.

Ho inoltre confrontato visivamente gli screenshot tablet prima/dopo e una fixture light con sfondo contrastato. Sono coerenti con il cambiamento richiesto a vetro e posizione delle lyrics, ma non dimostrano prestazioni GPU o interazioni reali. Le osservazioni Samsung sul mantenimento dello stato al cambio font appartengono a root. Il totale storico app 156/156 resta un risultato riferito da root: non l’ho ricostruito dai soli XML UI finali.

**Rilievi correttivi aperti: nessuno.** Non propongo ulteriori fix o redesign. L’implementazione software I2 è approvabile nel perimetro verificato; il gate AirPods rimane separatamente **NV**, con UI/A2DP/lifecycle di prodotto ancora subordinati al PASS hardware completo.

## Report A2

**Reviewer A2 — GPT-6.1 Sol, reasoning high. Fase IMPLEMENTAZIONE, giro CORRETTIVO1, versione I2.**

**R1 è chiuso nel perimetro verificato: le risposte OAuth abbandonate non possono ripristinare i token. Resta un rilievo medio sulla cancellazione del listener OAuth, descritto sotto.** Il gate hardware AirPods rimane **NV**, separatamente dalla valutazione software.

Ho verificato SHA256 del manifest (`912405f1…75140936`), corrispondenza di **76/76 file** e hash dell’APK (`055cc522…fa78a55e`). Il confronto indipendente I1→I2 conferma soltanto i tre sorgenti dichiarati e il documento di stato. Nessun AGENTS applicabile trovato. Lette le skill richieste e i riferimenti Fluid su regole, vetro e movimento. Nessuna modifica, build, attività dispositivo o operazione Git di scrittura.

Nota d’indipendenza: non ho aperto i report degli altri reviewer. Una ricerca delle intestazioni nello status ha però restituito accidentalmente anche righe riepilogative con voti I1; non le ho utilizzate per formulare queste valutazioni.

I riferimenti relativi nella tabella indicano file sotto `build/review-I2/source/`.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| CP1.a | 8 | Statico; prove hardware distinte | `AirPodsSocket.kt:23` e seguenti: caricamento JNI lazy, controllo bonded, BR/EDR tipo 3 e PSM `0x1001`. `AirPodsRepository.kt:75` e seguenti: deadline connect 10s, pending 5s e generazioni. `AirPodsSessionOwner.kt`: lease 90s. Manifest debug nonexported e attribuzione upstream pinned in `LICENSES/LibrePods-AirPods.md`. | Nessun difetto concreto emerso nel controllo selettivo. Protocollo reale ancora NV. |
| CP1.b | NV | Hardware incompleto | `docs/airpods-poc-s25.md`: due fallimenti nella registrazione locale PSM, prima di handshake e notifiche. Mancano baseline indossata, tre sessioni e conferme bidirezionali CA/modalità. | Gate aperto; nessuna penalizzazione numerica. |
| CP2 | 8 | Statico e immagini esistenti; nessuna nuova QA | `SquareApp.kt:1318` registra il sottoalbero contenente `AppBackdrop`, quindi blur/trasformazioni; `PlayerScreen.kt:430`, `:735`, `:753` compone e distribuisce il medesimo fondale ai due scope. Confrontate immagini reali PRIMA/DOPO e fixture light contrastata: vetro coerente, lyrics adiacenti ai controlli, spazio traduzione/karaoke. Connect conserva il percorso cluster al fallimento fallback (`MainViewModel.kt:425`). | Nessun rilievo aggiuntivo nel perimetro. Telefono/mini senza nuova prova interattiva. |
| CP3 | 8 | Statico, fixture e prove root dichiarate | `AdaptivePlayerLayout.kt:12` e seguenti: soglie richieste, rapporto 45/55, gap 24, limiti 1200/560 e viewport finiti. I tre slot mantengono identità; stato/scroll sopra la misura adattiva. `PlayerScreen.kt:319`, `:345`: panel e immersive salvabili. XML layout/screenshot e prova font Samsung riferita da root pertinenti. | Decoder reale durante resize e profiling GPU NV. Non dedotti dalle fixture. |
| CP4 | 7 | Riesame approfondito auth; controllo selettivo account/download/Wear | R1 chiuso da `TokenStore.kt:69` e `MainViewModel.kt:954–1025`, `:1411`. Teardown nativo preceduto da join download (`PlaybackService.kt:882`); gate account e writer offline serializzato presenti. Pubblicazione indice atomica nondistruttiva; pump conserva ownership fino al ritorno JNI. | **R2 medio:** annullamento del job OAuth non chiude immediatamente il listener bloccante; nuovo tentativo può fallire sulla porta occupata. |
| CP5 | 9 | Integrità e artefatti controllati; esecuzioni root | XML I2: **16 TokenStore + 3 AccountLifecycle**, zero failure/error/skipped; log build `BUILD SUCCESSFUL`. XML I1 glass/lyrics: **4 layout + 18 screenshot + 3 Connect**, tutti senza errori. Sei nuovi test helper pertinenti ai rischi R1. Snapshot riproducibile e limiti espliciti. | Nessuna prova automatica del ViewModel o del listener reale. Nessun lint PASS attestato. |
| CP6 | 8 | Statico selettivo; runtime Wear NV | `PendingWatchDownloads.kt:41` e seguenti: persistenza prima del trasporto, invii serializzati, rimozione individuale dei successi senza cancellare append concorrenti. `WatchDownloadsRemote.kt:32` usa commit verificato. Nessuna integrazione AirPods Wear introdotta; diagnostica solo debug. | Pairing/smoke Wear completo NV. I 150/150 Wear restano evidenza riferita da root, non una mia esecuzione. |

**Riesame R1**

Il percorso corretto cattura generazione e client **prima** della sospensione, verifica la cancellazione dopo la risposta e controlla client/generazione nello stesso monitor del salvataggio: [TokenStore.kt:69](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/auth/TokenStore.kt:69).

Il controllo e il commit non lasciano una finestra nella quale `clear()` o `invalidate()` possano inserirsi tra validazione e scrittura. Se il commit precede il clear, quest’ultimo elimina comunque i token; se segue, la generazione impedisce il commit.

Nel [MainViewModel.kt:954](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/MainViewModel.kt:954):

- nuova auth, modifica effettiva del campo client, disconnect e logout annullano il job e avanzano il tentativo;
- `setClientId()` precede la cattura della sessione;
- stato connected, feed e ricerca vengono pubblicati solo dopo accettazione;
- `CancellationException` viene propagata;
- errori e `finally` precedenti non alterano il nuovo tentativo;
- logout e disconnect ripuliscono gli stati richiesti.

Ho controllato anche i collegamenti dei callback in `SquareApp.kt`: raggiungono questi metodi. Non ho trovato un altro salvataggio OAuth Web API incondizionato.

I sei test in [TokenStoreTest.kt:121](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/test/java/dev/lelonio/square/auth/TokenStoreTest.kt:121) provano il helper e la sua decisione di accettazione. Le variabili locali `connected`/`loads` **non costituiscono copertura del ViewModel**, del browser o del runtime Android. Il wiring è una verifica statica; logout reale A→B rimane NV.

**R2 — Medio: il listener OAuth sopravvive alla cancellazione del job**

**Trigger:** avviare OAuth, lasciare il browser senza completare il redirect, tornare all’app e avviare una nuova autorizzazione, eventualmente dopo cambio client o disconnect.

**Riferimenti:** snapshot [MainViewModel.kt:957](/C:/VibeCoded%20Projects/Fluidify/build/review-I2/source/app/src/main/java/dev/lelonio/square/ui/MainViewModel.kt:957); contesto preesistente [SpotifyOAuth.kt:181](</C:/VibeCoded Projects/Fluidify/app/src/main/java/dev/lelonio/square/auth/SpotifyOAuth.kt:181>), `:186`, `:311`, `:332`, `:355`.

`authorize()` esegue `ServerSocket.accept()` bloccante dentro `withContext(Dispatchers.IO)`. Il receiver viene chiuso da `use` soltanto quando il blocco termina; non esiste un collegamento tra cancellazione e chiusura del socket. Il vecchio listener conserva quindi `127.0.0.1:5588` fino al redirect o al timeout di cinque minuti. Il tentativo successivo prova a effettuare immediatamente un nuovo bind sulla stessa porta.

**Conseguenza:** possibile errore di porta occupata e impossibilità temporanea di riautorizzare. **Non riapre R1:** il risultato tardivo resta escluso dal salvataggio dei token.

**Provenienza e certezza:** difetto preesistente nel trasporto, rilevante per il nuovo percorso di cancellazione/sostituzione. `SpotifyOAuth.kt` non è incluso nei 76 congelati: l’ho letto come contesto live, verificando diff nullo rispetto alla base indicata e SHA256 `8389aa91296fa026c3dd2e955adb4b8352bcb7cb48e0dc26a45bf6cbcecb97d4`. È una deduzione statica, non una riproduzione runtime.

**Fix minimo:** rendere la cancellazione proprietaria della chiusura del receiver, interrompendo accept/lettura e assicurando il rilascio prima del nuovo bind. Aggiungere una regressione sul trasporto: listener in attesa → cancellazione → rilascio tempestivo → nuovo bind sulla stessa porta riuscito; includere cancellazione durante lettura del redirect e assenza di pubblicazione tardiva.

**Verdetto:** R1 risolto; **implementazione software da correggere per R2**, con CP4=7. Gate hardware AirPods **NV**, UI e lifecycle AirPods di prodotto ancora subordinati al PASS. Le prove reali logout A→B, cancel download JNI, smoke Wear e decoder al resize restano aperte. Il totale storico app156/156 è riferito da root: non l’ho ricostruito dai 25 XML UI conservati.

## Risoluzione root dopo entrambi i report

Per distinguere i due rilievi chiamati R2 dalle rispettive fonti, qui si usa **R2-P** per l'APK incompleto trovato da root e **R2-L** per il listener OAuth trovato da A2. I testi originali dei report restano invariati.

R2-L confermato nel vecchio ServerSocket.accept bloccante. Estratto `OAuthLoopbackReceiver`: il punto di attesa registra un handler di cancellazione che chiude listener e peer accettato; registrazione del peer e close sono sincronizzati senza tenere il lock durante I/O. Un mutex comune ai due flussi OAuth conserva l'ownership fino a use/close prima di un nuovo bind. Il listener viene rilasciato anche prima dello scambio token HTTP. Restano IPv4 loopback e porta registrata5588, messaggio browser e verifica state/code nel chiamante. Aggiunto timeout anche sulla lettura del peer.

Quattro test su socket TCP reali provano cancellazione durante accept, richiesta parziale, nuovo bind dopo cleanup e redirect completo UTF8. `build/fluidify-listener-I3.log`: build app e23/23 test auth PASS. Nuovo APK candidato I3 `build/review-I3/fluidify-I3-listener.apk`, SHA256 `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`; copertura DEX auth27/27 e16KB PASS. Questo candidato include entrambe le correzioni R2-P/R2-L; il precedente I3 f5ac… è solo la prova intermedia del pacchetto ricostruito prima di R2-L. Richiesto riesame indipendente su I3, secondo giro correttivo.
