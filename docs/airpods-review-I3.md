# Review implementazione I3 — secondo giro correttivo

Versione congelata I3, base `a9247286a8bb86d7b8a83c5c30ffea0a2fd1b2d3`. Data: 2026-11-07. Giro CORRETTIVO2 dei tre consentiti dopo quello iniziale.

Manifest: `build/review-I3/source-manifest.json`, 80 file, SHA256 `63f154dcfe5662c8816d2ec71b96df89e5dfd491325994d2c582948880f31924`.
APK finale: `build/review-I3/fluidify-I3-listener.apk`, SHA256 `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`.

## Riepilogo conclusivo dell'orchestratore

A3 e B3: GPT-6.1 Sol, reasoning high, indipendenti dagli autori. Entrambi i report ricevuti, sulla medesima versione I3. I voti numerici riguardano il perimetro software verificato e non sostituiscono le prove hardware mancanti. Media e minimo escludono NV; nessun voto provvisorio incluso. Documenti di consegna live aggiornati dopo il congelamento; sorgenti e APK approvati restano quelli del manifest I3.

| Criterio | A3 | B3 | Media (validi/attesi) | Minimo | Esito e problemi aperti |
|---|---:|---:|---|---:|---|
| CP1.a — POC software AirPods | 9 | 9 | 9 (2/2) | 9 | Approvato nel perimetro software; AACP reale NV |
| CP1.b — Gate hardware | NV | NV | — (0/2) | — | Aperto; blocca UI, A2DP e lifecycle AirPods di prodotto |
| CP2 — UX e vetro | 8 | 8 | 8 (2/2) | 8 | Approvato nel perimetro verificato; movimento/GPU NV |
| CP3 — Tablet adattivo | 8 | 8 | 8 (2/2) | 8 | Approvato nel perimetro verificato; decoder reale al resize NV |
| CP4 — Audit e regressioni | 8 | 8 | 8 (2/2) | 8 | Approvato; T1 bassa sulla precisione del test, nessun bug residuo confermato |
| CP5 — Verifiche e artefatto | 9 | 9 | 9 (2/2) | 9 | Approvato nel perimetro verificato; nessun lint PASS |
| CP6 — Wear e compatibilità | 8 | 8 | 8 (2/2) | 8 | Approvato nel perimetro software; pairing/smoke completo NV |

**Decisione:** chiuso il giro di review software I3, con tutti i minimi numerici almeno 8/10 e nessun difetto software bloccante confermato. R1 (persistenza OAuth tardiva), R2-L (listener non interrotto) e R2-P (classi auth mancanti dall'APK I2) chiusi nel perimetro verificato. L'APK finale è stato installato con dati conservati e avviato da root su S25 e tablet.

**Il piano complessivo resta aperto su CP1.b e sulle prove runtime indicate.** Il gate hardware AirPods è NV e impedisce UI, A2DP e lifecycle di prodotto. Nessuna approvazione hardware è dedotta dai soli voti software; nessun fork, bridge o intervento sul sistema è autorizzato automaticamente.

### T1 — precisione test, bassa e non bloccante

A3 rileva che il segnale `entered` del primo test TCP precede `awaitRequest()`: il test può quindi coprire cancellazione anticipata anziché un accept già bloccato. Non dimostra un malfunzionamento del listener. Il limite è accettato esplicitamente per I3, che entrambi i reviewer giudicano approvabile. Non si attribuisce a quel test la prova deterministica dell'interruzione durante accept. Un eventuale perfezionamento dovrà distinguere i due casi senza cambiare retroattivamente le evidenze I3. La lettura statica del cleanup e gli altri test socket restano evidenze distinte.

### Verifiche e limiti della consegna

- Suite app completa 167/167 prima della modifica listener; 23/23 auth dopo la modifica, inclusi quattro test TCP reali JVM.
- UI 25/25 e Wear 150/150 associati agli artefatti conservati dei rispettivi run. Non sono una nuova esecuzione unica né una somma di test distinti.
- Definizioni DEX auth finali 27/27; controllo negativo sul precedente I2: tre classi mancanti su 22. Checker circoscritto ad APK debug non minimizzati e al prefisso selezionato.
- ELF SPAKE2 e offset ZIP delle librerie native: 16 KB PASS per entrambe le ABI.
- Avvio reale e schermate finali osservati da root su entrambi i Samsung; account conservati. Screenshot in `build/review-I3/tablet-listener-final.png` e `s25-listener-final.png`.
- Ancora NV: login OAuth Android/browser completo, logout reale A→B, cancellazione download JNI hardware, pairing/smoke Wear completo, decoder durante resize e costo GPU/ottica dinamica. Nessun lint PASS dichiarato.
- Nessuna UI AirPods di prodotto, nuova funzione Wear AirPods, release, commit, push o aggiornamento engine.

## Reviewer A3

<t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A7bce46a3-227c-42ab-abf4-5cc53f0c9e47>

Reviewer indipendente: **GPT-6.1 Sol, reasoning high effettivo**. Fase **implementazione**, giro **CORRETTIVO2**, versione **I3**, base `a9247286a8bb86d7b8a83c5c30ffea0a2fd1b2d3`.

**Esito: implementazione software e APK finale approvabili nel perimetro verificato. Gate hardware AirPods aperto: NV.** Nessun difetto software bloccante confermato; una lacuna minore nella precisione di un test.

Ho operato in sola lettura, applicando le tre skill richieste. Non ho trovato AGENTS applicabili nei percorsi controllati. Nessuna delega, Gradle, operazione sul dispositivo, modifica o lettura dei report altrui. Del documento di stato ho verificato esclusivamente l’hash.

Nel seguito, `S/` indica lo [snapshot congelato I3](</C:/VibeCoded Projects/Fluidify/build/review-I3/source>); i percorsi Kotlin sono relativi a `S/app/src/main/java/dev/lelonio/square/`.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| CP1.a | **9** | Statico; XML root controllati | `airpods/AirPodsSocket.kt` verifica bond, costruisce BR/EDR tipo 3/PSM `0x1001`, carica JNI su richiesta. `AirPodsRepository.kt:77` implementa deadline connect, pending osservativi e generazioni; `AirPodsSessionOwner.kt:49` lease 90 s. Opt-in OFF in `AirPodsPreferences.kt`. Manifest diagnostico solo debug, non esportato; upstream fissato in `S/LICENSES/LibrePods-AirPods.md`. XML I3: 17 test codec/repository/owner riusciti. | Nessun difetto concreto rilevato nel POC software. Comportamento AACP reale NV. |
| CP1.b | **NV** | Evidenza hardware root incompleta | `S/docs/airpods-poc-s25.md`: due tentativi falliscono nella registrazione locale PSM, prima di handshake/notifiche/comandi. Mancano baseline indossata e tre sessioni complete. | Gate aperto. Nessuna conclusione generale su Android 17 o convivenza dual-channel. |
| CP2 | **8** | Statico, test simulati e immagini root ispezionate | `ui/player/GlassSurface.kt:15`, `PlayerScreen.kt:524,735,753`: sorgente comprende blur/trasformazioni, chrome fratello escluso, entrambi gli scope ricevono ground+stage. `LyricsView.kt:94,134,146`: ancoraggio tablet e margini/fade compatti, centratura telefono conservata. `ConnectRefreshState.kt:6` preserva la lista; 3 test Connect negli XML. Screenshot finali coerenti. | Nessun difetto concreto. Verifica ottica dinamica/GPU NV; mini-player non rivalidato interattivamente da me. |
| CP3 | **8** | Statico, fixture e schermata reale root | `AdaptivePlayerLayout.kt:12–66`: soglie, rapporto 45/55, gap 24, limiti 1200/560, viewport finiti e medesimi slot compositivi. `PlayerScreen.kt:319,345` salva panel/immersive. XML I3: 4 test layout e 18 screenshot/stato riusciti; immagine tablet finale ispezionata. | Decoder reale durante resize e continuità audio NV. I test di owner simulato non dimostrano questi comportamenti hardware. |
| CP4 | **8** | Analisi approfondita auth; audit statico mirato | R1 e R2-L coerenti: `auth/TokenStore.kt:69`, `auth/OAuthLoopbackReceiver.kt:18–103`, `auth/SpotifyOAuth.kt:184–221`, `ui/MainViewModel.kt:954–1025,1382–1435`. Join prima dello shutdown in `playback/PlaybackService.kt:893`; singolo pump/cancellazione in `download/DownloadPump.kt:26–112`; writer effettivo serializzato in `data/DownloadStore.kt:546`, pubblicazione atomica in `DownloadIndexPublication.kt:15`. XML auth finali 23/23. | Lacuna test **bassa**, descritta sotto. OAuth browser/Android, logout reale A→B e cancellazione JNI hardware NV. |
| CP5 | **9** | Integrità e checker rieseguiti personalmente; XML/log root controllati | Manifest e tutti gli 80 hash validi; APK finale corrispondente. Checker DEX: I3 **27/27**, I2 **3/22 mancanti**, come atteso. ELF SPAKE2 e offset ZIP 16 KB verificati per entrambe le ABI. XML: **167/167 prima del listener**, **23/23 auth dopo**. Log riportano build riuscite. | Checker limitato al prefisso auth e a debug non minimizzato; nessuna prova completa dell’app. Nessun lint PASS disponibile. Causa originaria degli intermedi I2 non dimostrata. |
| CP6 | **8** | Statico e risultati root | `wear/PendingWatchDownloads.kt:40–65`: persistenza prima dell’invio, gate seriale, rimozione individuale dei successi senza perdere append concorrenti. Wiring persistente in `WatchDownloadsRemote.kt`. XML I3: 11 test pending; XML Wear precedenti: 150/150. Nessuna nuova API Android esportata nel delta diagnostico; AirPods Wear rinviato. | Pairing e smoke Wear completo NV. Non attribuisco ai test JVM una verifica del trasporto reale. |

**Riesame A3: listener e pubblicazioni tardive**

La cancellazione è registrata prima dell’I/O (`OAuthLoopbackReceiver.kt:64`). Se arriva prima di `accept`, chiude il server; durante `accept` lo sblocca; durante una richiesta parziale chiude anche il peer. La race tra accettazione e assegnazione del peer è coperta dal monitor: un socket accettato dopo la chiusura viene rifiutato e chiuso (`:75–80`). Nessun monitor resta trattenuto durante `accept` o `readLine`; anche la lettura ha timeout.

Il mutex comune comprende costruzione, blocco chiamante e `use/close` (`:18–27`). Nel wiring esaminato il nuovo proprietario attende quindi il cleanup prima del bind. Errori di browser, parsing, state o code attraversano lo stesso cleanup. `SpotifyOAuth.kt:215–217` mantiene verifica state/code; la risposta browser conserva UTF-8 e lunghezza in byte. Il listener viene rilasciato **prima** dell’exchange HTTP (`:220–221`).

Non ho identificato una race residua concreta che consenta un rebind anticipato nei percorsi esaminati. L’exchange HTTP resta bloccante: cancellarlo non implica arrestare immediatamente la chiamata OkHttp. La protezione dalle pubblicazioni tardive resta però presente:

- `TokenStore.authorizeIfCurrent` cattura generazione/client prima della sospensione, verifica attività al ritorno e controlla il salvataggio sotto il monitor condiviso.
- MainViewModel cancella il job e cambia tentativo su sostituzione, modifica client, disconnect e logout; pubblicazioni e `finally` sono condizionati al tentativo corrente.
- Il login playback verifica attività, tentativo e generazione prima di salvare.

**R1 risolto nel perimetro statico e JVM verificato; R2-L risolto nello stesso perimetro.** Il login Android completo resta NV.

I quattro test TCP usano socket reali: quello sulla richiesta parziale verifica peer chiuso e rebind; quello di replacement verifica il mantenimento dell’ownership durante cleanup sospeso; quello normale verifica target e risposta UTF-8. Non provano Custom Tabs, parsing Android, wiring completo del ViewModel o Spotify.

**Rilievo non bloccante — gravità bassa**

`S/app/src/test/java/dev/lelonio/square/auth/OAuthLoopbackReceiverTest.kt:17–23`: il segnale `entered` precede `awaitRequest()`.

- **Trigger:** il thread del test riceve il segnale e cancella prima che il worker entri effettivamente in `accept`.
- **Conseguenza:** il test può passare verificando cancellazione anticipata anziché interruzione di un `accept` già bloccato.
- **Fix minimo:** rendere osservabile/sincronizzabile l’ingresso effettivo nell’accept e distinguere i due casi.
- **Regressione richiesta:** cancellazione prima dell’I/O e durante accept bloccato, entrambe con join limitato, nessuna pubblicazione e rebind sulla stessa porta.

È una lacuna della prova; non dimostra un malfunzionamento del listener.

**Integrità dell’artefatto**

Verificati personalmente:

- Manifest: `63f154dcfe5662c8816d2ec71b96df89e5dfd491325994d2c582948880f31924`, **80/80 file validi**, compreso il documento di stato senza leggerlo.
- [APK finale I3](</C:/VibeCoded Projects/Fluidify/build/review-I3/fluidify-I3-listener.apk>): `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`.
- Controllo negativo I2 sulle sue 22 classi: mancano esattamente `TokenStore$2`, `$3`, `$authorizeIfCurrent$1`, exit 1.
- Controllo positivo finale I3: 27/27 definizioni DEX, exit 0.
- SPAKE2: segmenti ELF allineati a 16384 e librerie ZIP non compresse con offset multiplo di 16384, entrambe le ABI.

**R2-P risolto per il difetto auth identificato nell’APK finale.** Il checker verifica definizioni, non semplici riferimenti; non certifica equivalenza dei bytecode, linkage completo o funzionalità dell’intera app. L’avvio sui due dispositivi e la conservazione degli account sono prove eseguite da root; io ho ispezionato le immagini finali, senza ripetere quelle operazioni.

L’approvazione riguarda dunque software e artefatto verificati. **Non autorizza a considerare superato CP1.b né a procedere con UI AirPods, reconnect A2DP o lifecycle di prodotto subordinati al PASS hardware.**

## Reviewer B3

<t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A7bbaaacf-9028-42c1-9fe8-c0387f049b64>

Reviewer indipendente: **GPT-6.1 Sol, reasoning high**. Fase **implementazione**, giro **CORRETTIVO2**, versione **I3**, base `a9247286a8bb86d7b8a83c5c30ffea0a2fd1b2d3`.

**Esito: favorevole all’implementazione software e all’artefatto nel perimetro verificato. Gate hardware AirPods aperto: NV.** Non ho individuato nuovi difetti concreti che richiedano una correzione.

Ho applicato le tre skill richieste; non sono emersi AGENTS applicabili dalle ricerche effettuate. Nessuna scrittura, build, delega o operazione sui dispositivi. Nessun report altrui consultato; del documento di stato ho verificato soltanto l’hash.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| CP1.a | 9 | Statico verificato; test root esaminati | Paired-only, JNI lazy limitato ai tipi Bluetooth, BR/EDR tipo 3 e PSM `0x1001` in [AirPodsSocket.kt:24](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/airpods/AirPodsSocket.kt:24). Repository con timeout 10 s, pending osservativi e generazioni; opt-in OFF e helper lease 90 s. [Manifest debug](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/debug/AndroidManifest.xml:1) non esportato; attribuzione GPL e upstream fissato in [licenza](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/LICENSES/LibrePods-AirPods.md:1). | Nessun difetto concreto rilevato nel POC. Lifecycle e UI di prodotto restano subordinati al gate. |
| CP1.b | NV | Hardware incompleto, prove root | [POC S25 congelato](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/docs/airpods-poc-s25.md:1): costruzione riuscita, due connect falliti nella registrazione locale PSM, prima di handshake/notifiche/comandi. | Baseline indossata, tre sessioni, conferme hardware, audio/gesti/CA e lifecycle prodotto assenti. Non è PASS né prova di impossibilità generale. |
| CP2 | 8 | Statico e immagini verificati; prove UI root | Source prima di blur/trasformazioni in [GlassSurface.kt:17](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/ui/player/GlassSurface.kt:17), chrome fratello escluso e ambedue gli scope alimentati con ground+stage in [PlayerScreen.kt:735](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/ui/player/PlayerScreen.kt:735). Gruppo controlli unico, lyrics tablet compatte e Connect con errore non bloccante. Esaminati screenshot finali e XML UI 25/25. | Nessun difetto concreto rilevato; immagini statiche non provano qualità del movimento o costo GPU. |
| CP3 | 8 | Statico, simulato e immagine reale distinti | [AdaptivePlayerLayout.kt:12](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/ui/player/AdaptivePlayerLayout.kt:12): soglie, rapporto 45/55, gap 24, limiti e viewport finiti coerenti. Slot media/detail stabili; panel e immersive salvabili. MiniPlayer e FloatingMiniPlayer identici alla base, confrontando le terminazioni di riga normalizzate. | Resize del decoder reale NV. Le fixture verificano geometria/stato, non continuità del decoder. |
| CP4 | 8 | Statico verificato; regressioni root esaminate | R1 e R2-L risultano corretti, dettagli sotto. [PlaybackService.kt:882](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/playback/PlaybackService.kt:882) attende download prima dello shutdown; pump e gate impediscono sovrapposizioni/wake tardivi. Writer effettivo serializzato in [DownloadStore.kt:546](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/data/DownloadStore.kt:546), pubblicazione atomica nondistruttiva. | Logout reale A→B e cancellazione JNI hardware NV. Nessuna regressione concreta individuata. |
| CP5 | 9 | Controlli indipendenti riprodotti | Manifest 80/80, hash APK, negativo DEX I2 e positivo I3, XML e allineamento verificati. [Checker congelato:16](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/tools/check-apk-class-coverage.py:16) usa `class_defs`, non riferimenti/stringhe. | Controllo circoscritto alle classi auth e ad APK non minimizzati; non certifica tutta l’app. Nessun lint PASS attribuito. |
| CP6 | 8 | Statico verificato; test Wear root esaminati | [PendingWatchDownloads.kt:45](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/wear/PendingWatchDownloads.kt:45): invii serializzati, persistenza e rimozione individuale dei successi. XML Wear 150/150. Nessun delta wear/wear-protocol rispetto alla base; nessuna API AirPods Android esportata. | Pairing e smoke Wear completo NV. AirPods Wear resta futuro. |

**Integrità dell’artefatto — controlli miei**

- Manifest SHA256: `63f154dcfe5662c8816d2ec71b96df89e5dfd491325994d2c582948880f31924`; tutti gli **80 hash corrispondono**.
- APK finale `fluidify-I3-listener.apk`: SHA256 **`dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`**.
- Negativo riprodotto su I2, hash `055cc522…78a55e`, contro le **22 classi I2**: exit **1**, mancanti esattamente `TokenStore$2`, `$3`, `$authorizeIfCurrent$1`. Il log del crash identifica realmente `$2`.
- Positivo sul finale contro le **27 classi auth I3**: **27/27**, exit **0**.
- ELF SPAKE2: entrambe le ABI PASS a 16 KB; le **14 librerie native non compresse** hanno offset ZIP allineati a 16 KB.

Il checker segue correttamente `class_def → type_id → string_id`. Rifiuta directory mancanti e selezioni vuote. I limiti sono appropriati allo scopo: non valida corpi dei metodi, risoluzione di tutte le dipendenze, corrispondenza completa sorgenti/binario, firma o funzionamento runtime. La presenza in DEX dimostra la rimozione della specifica omissione rilevata, senza provare la causa degli intermedi I2.

**R1 — nessuna persistenza tardiva individuata**

[TokenStore.kt:69](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/auth/TokenStore.kt:69) cattura generazione/client prima della sospensione, verifica cancellazione al ritorno e salva sotto il monitor condiviso. Clear e cambio client invalidano sotto lo stesso monitor.

[MainViewModel.kt:954](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/ui/MainViewModel.kt:954) annulla il job e incrementa il tentativo su sostituzione, modifica client, disconnect e logout; pubblicazioni, errori e `finally` rispettano il tentativo corrente. Il percorso playback usa analoghe verifiche prima del salvataggio. È una conclusione statica sostenuta dai test auth, non una prova di login Android completo.

**R2-L — correzione confermata anche dal chiamante**

In [OAuthLoopbackReceiver.kt:60](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/auth/OAuthLoopbackReceiver.kt:60):

- la cancellazione registra la chiusura prima dell’I/O bloccante;
- cancellare durante accept chiude il server; durante lettura chiude anche il peer;
- assegnazione del peer e flag closed condividono il monitor: se close precede l’assegnazione, il socket appena accettato viene chiuso;
- accept/read non trattengono quel monitor; entrambi hanno timeout;
- `use/close` completa prima del rilascio del mutex comune e del successivo bind.

[SpotifyOAuth.kt:184](/C:/VibeCoded%20Projects/Fluidify/build/review-I3/source/app/src/main/java/dev/lelonio/square/auth/SpotifyOAuth.kt:184) conserva porta 5588, PKCE, state/code e messaggio browser; rilascia il listener **prima** dello scambio HTTP. Errori e cancellazioni attraversano il cleanup. Non ho trovato un percorso che ripubblichi il risultato abbandonato.

I quattro test con socket reali sono pertinenti; restano prove JVM locali, senza browser, Spotify o ViewModel Android.

**Prove e limiti**

Ho ricontato gli XML prodotti da root: **167/167 prima della modifica listener**, **23/23 auth dopo**, **25/25 UI** e **150/150 Wear** storici. Non li ho rieseguiti. Ho verificato inoltre che i 36 file UI/Wear comuni ai manifest I1/I3 siano invariati.

Ho ispezionato le due immagini finali: sostengono layout tablet con lyrics vicino ai controlli e conservazione della composizione telefono. Installazione, avvio su entrambi i dispositivi e account preservati sono prove riferite da root; non le attribuisco alla mia esecuzione.

Non emergono rilievi correttivi con trigger e conseguenza dimostrati. L’implementazione esaminata è accettabile nel perimetro software verificato; **non autorizza l’avanzamento della UI/lifecycle AirPods di prodotto finché CP1.b rimane NV**.

