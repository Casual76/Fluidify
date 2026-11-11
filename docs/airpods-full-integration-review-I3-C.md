# Review indipendente C — I3 stable

Reviewer effettivo: GPT-6.1 Sol, reasoning high, harness Codex/T3; non autore. Fase IMPLEMENTAZIONE / CORRECTIVE3, terzo correttivo dopo l'iniziale; nessun reset. r3/r4/r5 sono tentativi di validazione, non giri di review.

Congelato S: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I3-stable`.
Manifest SHA256: `81c1ae56a4e59e6f94c9ca3c24dfbfbb0555d73ad78bf1f62a70f554c6fbd757`; 3377 file + 1 riferimento privato esterno. Riferimenti di codice sotto S/source, riferimenti di evidenza sotto S/evidence. ProductFALSE; H1/G1 NV.

## Esito e tabella obbligatoria

Nel perimetro media/automazioni esaminato non confermo un difetto grave o un controesempio ai correttivi I3. Questo non è un'approvazione complessiva: i criteri NV non contribuiscono a una media o al superamento della soglia. Voti secondo la rubrica invariata, limitati alle verifiche software descritte. Nessuna abilitazione del prodotto o approvazione hardware.

| Criterion | Score0-10 | Verification status | Rationale/evidence | Problems/severity |
|---|---|---|---|---|
| I1 | NV | Parziale, catalogo e media | Verificati personalmente i 133 blob pinned e l'inventario; seguite le famiglie ear/CA/media/gesti. Non completato il tracciamento indipendente di tutte le 35 famiglie, 67 preferenze e 41 disposizioni. | Completezza globale non certificata; nessun voto ricavato dal conteggio o dalle sei chiavi stem. |
| I2 | NV | Parziale, ispezione statica/immagine | Visti riferimento privato e modale r5; letti requisiti host. Non eseguite interazioni UI né intera matrice Home/player/quick/full/font. | Qualità ottica completa, Samsung e continuità decoder non verificate. |
| I3 | NV | Parziale pertinente al media | Esaminati startup, gate, ingresso eventi, visibilità, NLS, arresto core e rimozione observer. Non riesaminati integralmente tutti i rami CDM/cleanup persistente. | Nessuna certificazione dell'intero lifecycle Android. |
| I4 | NV | Parziale | Normalizzazione EAR, separazione CA setting/voice e generazioni tracciate; corpus pinned autenticato. Non ripetuta la verifica di tutti i layout, range e reducer. | Nessun nuovo RX/readback fisico. |
| I5 | 8 | Software: lettura personale + disassembly; test parent archiviati | Origine EAR/CA persistita; revoche individuali, ultimo motivo, ricevute locali e C6 separate; ramp/restore, override, timeout e consenso ricontrollati. Dettagli sotto. | Limite O1: provider fake nei test integrati; effetti fisici NV. Nessun difetto serio confermato. |
| I6 | 8 | Parsing personale di raw selezionati; esecuzione parent | Riconteggi XML 3 negativi qualificati, 3 positivi, 97 targeted, 717 extended; 24 check con exit0; hash e ZIP APK verificati personalmente. | Non ricompilato; 840/1360 input non copiati; linkage completo e variante enabled non provati. Core18/Wear150 storici. |
| I7 | NV | Parziale | Manifest NLS/Tile/companion protetti, attribution GPL/pin letti; nessun takeover o dispatch globale media-key nel percorso media esaminato. Non completato audit globale di tutte le dipendenze e asset. | Nessuna approvazione globale di provenienza/licenze/privilegi. |
| I8 | 8 | Integrazione software media tracciata personalmente; regressioni parent | Seguita catena Application → owner → media core/coordinator → provider locale/C6 → bridge native/Exo; sostituzione player e teardown; chiamate separate dai gesti media. | Limite O1/O2. Preservazione runtime Spotify/Wear/bridge non equivalente ai test host archiviati. |
| S1 | NV | Fuori approfondimento C | Identificati negli XML selezionati test ricerca/download/date/request ownership, ma non completata verifica indipendente dei relativi algoritmi e UI. | Non assumo il voto di un altro reviewer né sostituisco S1 con I5. |
| H1 | NV | Non eseguito | Nessun device/ADB/radio/installazione; nessuna prova fisica corrente. | Gate separato obbligatorio; G1 storico resta NV. |

## Verifiche personali e limiti di esecuzione

Letti integralmente brief C, rubric, fatti I3, contratti C1-C6; consultati piano/coverage, catalogo corrente e fonti pinned pertinenti, selection e record raw. Letti agentic-workflow e runtime, Fluid Engine e Material3; non rilevati AGENTS nei percorsi ancestor controllati o nell'inventario del congelato. Prevalgono i vincoli espliciti del brief su build e dispositivi. Nessuna memoria, rollout, review/voto di altri critici, comunicazione con agenti o status live consultati. Nessuna subdelega.

Eseguiti personalmente soltanto Python host in scratch, lettura sorgenti/immagini e `javap -c -p` sui bytecode già congelati (disassembler, nessun compilatore). Nessuna esecuzione Kotlin/JUnit/JVM applicativa, Gradle, javac, JShell, device o ADB. Nessun runner parent eseguito. Riproducibili con `python -B integrity.py integrity-end` e `python -B audit.py` da questa cartella; tutti gli output restano qui.

`integrity-start.json` e `integrity-end.json`: verifica membership dichiarata, dimensioni e SHA256 di tutti i 3377 file e dell'input privato, zero mismatch. Il riferimento privato è stato solo visualizzato, senza copiarlo nei deliverable. `audit.json`: parsing XML e confronto record, hash dei due record selezionati, confronto input disponibili, CRC ZIP e hash APK, Git blob SHA1/SHA256 delle 133 fonti pinned. `coordinator.javap.txt` e `core.javap.txt`: disassembly personale; conferma selettiva dei rami di provenance/revoca, non equivalenza completa fonte/APK.

Le mappe `after.files` e `finalTests.files` coincidono, 1360 voci; delle 520 presenti sotto source nessuna diverge. Le altre 840 sono assenti dal pacchetto selettivo, quindi non verificate personalmente: questo include sorgenti non selezionati e configurazioni locali, non soltanto segreti. Non ho sostituito file mancanti con equivalenti live. Questo pacchetto non costituisce un checkout ricostruibile completo.

## Falsificazione I5 e integrazione I8

**Provenienza della pausa.** `app/src/main/java/dev/lelonio/square/airpods/runtime/automation/AirPodsAutomationCoordinator.kt:26` mantiene l'origine della richiesta, separata dai motivi correnti. `:92` richiede ricevuta nel dominio corretto prima di acquisire proprietà. Un PAUSED senza ricevuta non basta; una ricevuta tardiva oltre deadline non riacquista proprietà. `:164` tratta primo EAR, UNKNOWN/cache e nuova baseline senza azioni; `:183` cancella EAR-origin senza trasferirlo a CA, conservando CA-origin se il motivo CA è ancora presente. Il ramo corrispondente è presente anche nel bytecode congelato.

Ho cercato il controesempio EAR→CA→UNKNOWN e quello inverso CA→EAR→UNKNOWN: il primo perde l'autorità EAR, il secondo conserva quella CA. `:238` consuma l'ownership prima del submit PLAY; `PendingResume.earDependent` mantiene dipendenza EAR dopo che i motivi si sono svuotati. Un nuovo blocker a `:226` cancella il PLAY accodato. La dipendenza distingue origine EAR e rilascio finale EAR: non è dedotta dal solo insieme ormai vuoto.

`I3PauseReasonIntegrationTest.kt:14` usa core/coordinatore reali e dispatch differito; i suoi 11 metodi risultano negli XML extended selezionati. Include entrambi gli ordini, PAUSE accodata/posseduta, cache/unknown, PLAY accodato, dominio ricevute errato, nuove ragioni, cambi target e revoche. Il cache ingress è una chiamata riflessiva al coordinatore; non prova un ingresso cache Android realmente esistente. I provider applicano ricevute fake: questo delimita l'evidenza.

**Volume/riavvio/override.** Coordinator `:132` confronta ricevuta nuova con `lastEvidence`; `:265` fotografa una sola baseline e la ricevuta iniziale; `:254` evita gli ID attualmente trattenuti nel candidato, senza pretendere UUID globali persistenti. `:292` rinuncia al restore se il write è incerto; `:304` limita a un write non osservato e applica deadline. Ricevuta persistente già consumata non diventa nuovo ACK; livello coincidente da solo non conferma. `CorrectiveMediaRuntimeTest` esercita due step down/restore/seconda conversazione, receipt preesistente ID1, volume coincidente senza ACK, timeout, revoca del restore accodato e primo CA live. 11 metodi archiviati; non eseguiti da me.

**Autorizzazioni al dispatch.** `MediaRuntimeCore.kt:71`, `:95`, `:110`, `:169` separano automations, otherApps e foreground/background, invalidano baselines/observer/epoch e ricontrollano target/lease/accessibilità prima dell'effetto. Settings OFF/stop non producono PLAY o rollback (`:212`). Local e C6 condividono un coordinatore e il dominio STREAM_MUSIC. NLS segnala soltanto accesso/sessioni; non acquisisce un socket. Singleton bridge gestisce replacement e vecchio close tramite generazione.

**Route e causalità.** `ExternalBestEffortProvider.kt:49` richiede un contender coerente, consenso e fatti A2DP; conserva route storica solo sotto i limiti C6. I suoi `correlated*` non popolano `observed*`. `AndroidMediaEnvironment` rilegge controller/permessi/topologia e invalida su disconnessione/route/mode; i callback non sono una prova causale esterna. Le assunzioni C6 rimangono quelle accettate, inclusa la pausa manuale già PAUSED non osservabile.

`PlaybackAirPodsMediaBridge.kt:199` segue account/route reali e storico renderer esplicito; `:260` applica comandi solo al target corrente. Il write STREAM_MUSIC controllato più readback produce ricevuta; generico ContentObserver non la inventa. Differenza manuale osservata revoca la ricevuta anche prima del callback Settings. Per Spotify, `core/.../LibrespotPlayer.kt:271`, `:1130`, `:1770` seguono comando locale, pending, floor RX e URI; Connect/standby non ricevono comando locale. Per Exo, `AirPodsExoOutput.kt:20` ordina l'origine sulla coda playback, `:58` osserva sink pausato; `LocalPlayerFactory.kt` usa il sink esistente senza nuovo decoder e disabilita la gestione focus Exo duplicata. L'abbandono normale del focus non notifica un foreign loss (`AudioFocusController.kt:88`). Questo non dimostra comportamento fisico del sink.

**Wiring e chiamate.** `SquareApplication.kt:36` installa factory lazy; `AirPodsApplicationStartup.kt:21` mantiene conformance FALSE. `AirPodsAndroidRuntime.kt:540` inoltra eventi correnti; `:610` crea media dopo bootstrap. `PlaybackService.kt:67` collega il player esistente, e `:972` scollega prima dello swap locale/video/Spotify. `AirPodsAndroidRuntime.kt:566` conclude il ramo chiamata con return anche se l'operazione non riesce; `:575` blocca mix/chiamate prima del gesto media. Non ho trovato fallthrough chiamata→NEXT/PLAY.

## Evidenza parent archiviata, riconteggiata personalmente

Selection identifica r5 e i suoi record, hash entrambi corrispondenti. XML fresh: negativo 3/3 failure RGB con AssertionError, zero error/skip; positivo 3/3 pass; targeted 97/97 pass; extended 717/717 pass. Non sommati insiemi sovrapposti. Nel sottoinsieme media: coordinator44, core16, corrective11, C6 provider11, external evidence7, I3 provenance11, bridge15, native5, focus/sink5: conteggi di metodi archiviati, non nuovi test personali.

r3 conserva tre ComposeTimeoutException, quindi non è un negativo ottico qualificato. r4 registra zero test ed exit1, non un negativo qualificato. Gli XML NOT-EVIDENCE non sono contati. La selezione r5 conserva tre fallimenti RGB reali, poi pass; non trasforma fallimenti precedenti in pass. Il record build ha exit0; i 24 check artifact hanno exit0. Signature, ZIP/ELF16KB e class_defs selettive restano evidenza parent nei rispettivi limiti; personalmente ho verificato hash/size e CRC ZIP di tutti e tre gli APK. Nessuna verifica personale di firma, ELF/JNI o linkage completo.

Debug: 71961113 B / e64f4931dabd6788edad1a728978dc4049937639178d71737b7b6339f8b500b8. Minified: 20667273 B / 4e9c51bb78d6ef485ced38b84c5b7e8b82ba38e313d7ee9121980f39742885ee. Wear: 153220831 B / 7c8067be158bc08dc73b8a6ba1c7f690964a0624ee4ecf77f8c28f23dfc18564. GateFALSE può consentire eliminazione R8; nessuna qualificazione di enabledAPK. Nessun nuovo Core18/Wear150, lint o hardware PASS.

## Rilievi e limiti, senza difetti prodotto inventati

| ID / severità | Trigger e file:riga | Prova e conseguenza | Correzione minima / regressione proporzionata |
|---|---|---|---|
| O1 — limite di evidenza, non bug confermato | Promuovere i test integrati a prova del renderer; `app/src/test/java/dev/lelonio/square/airpods/runtime/media/I3PauseReasonIntegrationTest.kt:35` | Lettura personale: execute fake assegna direttamente le ricevute. Parent XML dimostra il successo di questa composizione; non la causalità native/AudioTrack o quella fisica. | Nessuna patch sorgente richiesta da questa osservazione. Conservare le qualifiche; nella futura finestra autorizzata verificare EAR+CA con provider reale, pause/restore in ritardo, focus/manual/account/route e cleanup, nei tre scenari H1. |
| O2 — limite di riproducibilità, non mismatch | Tentare rebuild o certificare preservazione globale dal solo package; `validation-selection.json:8` | Probe personale: 1360 input dichiarati, 520 copiati e corrispondenti, 840 assenti; hash APK non prova equivalenza source/APK né invarianti dei file non selezionati. | Per un futuro rebuild indipendente fornire checkout/input completi autorizzati e compiler baton. Non importare equivalenti live nel congelato. |
| O3 — limite ottico, non difetto estetico provato | Certificare tutto il vetro dal gutter; `app/src/test/java/dev/lelonio/square/ui/airpods/AirPodsOnboardingModalTest.kt:125` | XML negativo prova asserzioni RGB; vista personale del nuovo modale mostra banda sfocata nell'area divider/azioni, ma questo solo frame non dimostra violazione del requisito materiale. I2 resta NV. | Nessuna modifica estetica prescritta senza prova. QA dei frame/backdrop/tier e interazione host reale in finestra distinta; mantenere il test page-floor come prova di copertura soltanto. |

Nessun rilievo di codice confermato a severità alta/media nel focus C. Le osservazioni non impongono un nuovo giro, non consumano/resettono il contatore e non sostituiscono i criteri NV. La decisione complessiva appartiene all'orchestratore sulla copertura indipendente effettiva; questo report non può da solo approvare l'intera implementazione.

## Integrità finale

Riconfermato l'intero manifest a fine review con lo stesso checker: SHA256 `81c1ae56a4e59e6f94c9ca3c24dfbfbb0555d73ad78bf1f62a70f554c6fbd757`, 3377 file + 1 riferimento esterno, zero mismatch. Vedere `integrity-end.json`. Nessuna scrittura a S o sorgenti live; soltanto scratch assegnato. ProductFALSE, H1/G1 NV.
