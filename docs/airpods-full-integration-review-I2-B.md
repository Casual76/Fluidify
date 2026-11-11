# Review indipendente B — I2 stabile

Reviewer: Codex, GPT-6.1 Sol, reasoning **high**, harness T3 Code/Codex. Fase **IMPLEMENTAZIONE**, giro **CORRETTIVO2**, versione **I2 stabile**. r2/r3 sono tentativi seriali di validazione, non giri di review. Nessuna subdelega. Data: 2026-11-08.

Snapshot S: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I2-stable`. Nei riferimenti seguenti i sorgenti sono relativi a `S/source/`, le evidenze a S. Focus: I3/I7/I8 e I6 pertinente. Non autore delle modifiche.

## Esito e rubrica

**Nessun difetto confermato nel focus lifecycle/companion/piattaforma esaminato.** I3, I6, I7 e I8 raggiungono 8 nel perimetro software specificato sotto. Questo report non approva gli altri criteri per assenza di verifica: le righe NV restano aperte e richiedono la copertura degli altri focus. Nessuna approvazione del prodotto: Product FALSE, H1/G1 NV.

Rubrica comune: 9–10 implementazione forte con evidenze dirette e incertezze delimitate; 8 requisito soddisfatto nel perimetro software con limiti verificabili; 6–7 difetto/omissione concreta o debolezza materiale delle evidenze; ≤5 violazione sostanziale o base insufficiente. Soglia ≥8 per ogni criterio numerico assegnato e nessun difetto grave confermato; media/maggioranza non compensano difetti. NV non equivale a zero né a PASS; NA solo se non applicabile. I numeri qui non certificano comportamento Android reale.

| Criterio | Voto 0-10 | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---:|---|---|---|
| I1 Completezza funzionale | NV | Lettura comune e controllo selettivo | Catalogo pinned, coverage ed esclusioni letti; approfonditi audio, assistente e chiamate. Non tracciate individualmente tutte le 35 famiglie/67 preferenze fino a UI e comportamento. | Completezza complessiva non certificata da B. |
| I2 Home/player/quick/settings | NV | Wiring statico parziale | Un binding condiviso in SquareApp; setup indipendente e retry cleanup raggiungibili nell'host. Nessuna verifica geometrica/ottica o interazione full host eseguita. | Samsung, IME, decoder e fedeltà UI non verificati. |
| I3 Lifecycle/ownership Android | 8 | Sorgente + probe JVM personale + parent host archiviato | C3/C4, lease prima FGS/factory, stop persistente, ingressi differiti, bond distinto, cleanup durevole e permit separato; dettagli sotto. XML r3 di DeferredIngress 11, CleanupRecovery 16, StartGate 18 e Ownership consultati, senza sommarli al totale. | Nessun difetto confermato; effetti CDM/FGS/OEM reali NV. |
| I4 Protocollo/stato/capability | NV | Lettura selettiva | Esaminati gate di scrittura/sessione e factory; non rifatta la verifica sistematica di byte/layout/range di tutte le proprietà. | Nessun voto dedotto dai test verdi. |
| I5 Ear/CA/gesti/media | NV | Chiamate/piattaforma approfondite, media parziale | Verificati isolamento call-vs-media e guardie live; non rieseguita la matrice completa C5/C6, ownership pause/volume e arbitro. | Effetti reali e matrice media complessiva non certificati da B. |
| I6 Validazione/artefatti finali | 8 | Audit personale dei raw e hash; esecuzione build/test attribuita al parent | XML r3: 81 suite/686 test e 2 suite/8 mirati sovrapposti, zero failure/error/skip. Log/command inventory e 24 checker controllati. Hash APK e input selezionati verificati entro lo scope. | Non equivalenza sorgente/APK, linkage completo, lint completo o runtime; Core18/Wear150 storici. |
| I7 Confini/permessi/provenienza | 8 | Sorgente e fonti pinned locali | Manifest, default/factory reali, CDM proprio, chiamate pubbliche con permessi, A2DP best effort senza espansione JNI, attribuzione GPL/pin e 133 fonti hashed. Engine 3.0.5. | Nessun difetto confermato nel focus; disponibilità API nascosta ed effetto piattaforma NV. |
| I8 Integrazione/preservazione | 8 | Tracciamento statico integrato + parent host; profondità lifecycle | Application→controller→gate→lease→FGS→attach→factory; host/UI condivisi; teardown media/profilo/socket; NLS/Tile inerti. Delta letto solo dai manifest I1/I2. | Preservazione runtime decoder/native/Spotify/Wear non provata da hardware né da DEX selettive. |
| S1 Miglioramenti secondari | NV | Fuori approfondimento B | Approvazione separata riconosciuta; conteggi r3 includono search, ma non costituiscono una review dei requisiti U1–U3. | Richiede focus S1; nessun voto inventato. |
| H1 Gate fisico standalone | NV | Non eseguito/non autorizzato | Nessuna nuova prova fisica di questo candidato; telefono assegnato non equivale a evidenza. | Gate obbligatorio aperto; nessuna abilitazione prodotto. |

## Integrità e identità verificate personalmente

Manifest SHA256 `a572b80b04a54a37beb333ac57638a03c6e485318457a97f0258ea6edd6e39b3`: verificati dimensione e SHA256 di **tutti i 2679 file**, zero discrepanze. Input screenshot privato esterno: esistenza, dimensione e hash corrispondenti; non aperto perché non necessario al focus B, non copiato né pubblicato. Nessun AGENTS applicabile trovato nei percorsi antenati controllati o nel congelato.

`validation-selection.json` seleziona i record r3; la verifica globale del manifest copre anche questi e le loro evidenze. Confrontate le mappe before/preFinalTests/finalTests/after: **1356 input ciascuna, mappe identiche**. Nel pacchetto scoped sono presenti **444** di questi input, tutti con hash corrispondente; **912 non sono inclusi**. Non li ho cercati nel live. Questo limita la verifica personale alla coerenza interna dei record e al sottoinsieme incluso; non contraddice il carattere scoped dichiarato.

133 fonti del `catalog/sources-manifest.json` controllate contro le copie pinned locali: zero discrepanze. Consultate fonti LibrePods pertinenti ad assistente/chiamate/audio e le fonti Android locali per CDM/public handles e A2DP. Per il delta ho letto esclusivamente il manifest storico I1: 20 percorsi source comuni cambiati e 105 aggiunti; nessun altro file I1 letto. Non attribuisco ogni differenza alla feature.

APK verificati personalmente rispetto al manifest:

- Debug: `500290a070bbacd39422c9342dfacb691d952105988189702b6da15a687b11ee`.
- Minified: `db3fb3d377caa2a80ef537d69eb686c61c9708cc3e8f296344be33e3c5e18484`.
- Wear: `7c8067be158bc08dc73b8a6ba1c7f690964a0624ee4ecf77f8c28f23dfc18564`.

## Tentativi concreti di falsificazione

### C3/C4 e ingressi differiti

`airpods/runtime/android/AirPodsAndroidRuntime.kt:471` ricostruisce l'osservazione dalla sola associazione propria selezionata; valida callback e registra una continuazione soltanto mentre il profilo è UNRESOLVED. `:501` confronta epoch, registrazione, selezione, consenso, enabled/suppress e connessione; `admit(:421)` passa ancora da C4. Stop, selezione, configurazione e permessi invalidano la continuazione. Non ho trovato un percorso che trasformi sola presenza BLE in connessione AACP o salti C4 prima di FGS.

`AndroidPlatform.kt:62` tratta false/throw di richiesta proxy senza retry autonomo e scarta i subscriber falliti; `:90` consente cancellazione. Ispezionati i test reali AndroidBluetoothReadinessTest: il default AndroidBluetoothFacts è esercitato con sola richiesta proxy sostituita. I test DeferredIngress esercitano il controller/receiver effettivi, con fatti/CDM/history fake e FGS contato senza attach: non sono una prova socket o radio. I raw XML r3 documentano la loro esecuzione parent, non una mia riesecuzione.

`AirPodsAndroidRuntime.kt:435` distingue permesso perso (REVOKED) da bond NONE (unpair), UNKNOWN e BONDING. I broadcast non forniscono fatti di admission: si rileggono quelli correnti. Con/senza ticket la rimozione passa dallo stesso percorso durevole. Il test parent include callback tardiva dopo barriera di stop e USER_REQUESTED inserito fra presenza e readiness: zero FGS attesi. È evidenza host significativa, non sola policy fake.

`lifecycle/AirPodsStartGate.kt:130` riconcilia lo storico prima dell'ammissione; `:183` persiste suppress prima di cleanup. Un semplice open/listen/bind non è consenso. Lo storico usa chiave composta e marker di processo; errore query/persistenza non autorizza. Ho controllato la distinzione fra stop esplicito e disconnessione ordinaria: quest'ultima conserva opt-in senza riprodurre pending o effetti.

### Cleanup schema4, errori e ricreazione

`AirPodsAndroidRuntime.kt:285` cattura il destinatario, lo persiste con forget prima di cancellare selezione e impostazioni operative, quindi esegue cleanup. `:306` offre retry esplicito sui marker, senza nuova selezione, FGS, socket o start observation. `:314` non completa il marker su errore query/stop/disassociate/persistenza.

`lifecycle/CompanionObservationPolicy.kt:107` conserva il pubblico handle ricordato; `:122` confronta ID/address/package, non fingerprint mutabili. Rilegge prima della disassociazione, verifica dopo, protegge endpoint pubblicamente diversi e non inventa un'identità di incarnazione. Nessuna garanzia di delete atomica contro una sostituzione OS indistinguibile: limite C3 correttamente dichiarato.

`storage/AirPodsRuntimeStore.kt:26` cancella durevolmente il vecchio intento sulla nuova selezione esplicita e lo conserva nell'audit cancelledCleanup, senza fingere rimozione hardware. Commit fallito non perde il marker. Schema4 separa handleCaptured/knownAbsent/cancelled; il decoder accetta v1–v3. Le fixture v1/v2 archiviate coprono record vuoti, non tutte le combinazioni storiche popolate: non le tratto come copertura esaustiva della migrazione.

**Probe personale:** `HostProbe.java`, eseguita con javac/java17 sulle classi congelate hash-verificate e Kotlin stdlib locale 2.3.21. 37 asserzioni PASS (`host-probe.txt`): API policy 31/32/33/35/36, metadata mutabili, query failure con handle catturato, disassociate=false, nuova policy e retry senza osservazione, callback vecchie, cambi ID/address/package al controllo finale; v1/v2 vuoti, fixture v3 indipendente e roundtrip v4. Non è una ricompilazione dei sorgenti né prova della loro equivalenza al bytecode. Primo tentativo javac fermato dal BOM del solo file scratch; rimosso il BOM, compilazione ed esecuzione riuscite. Nessun codice di prodotto modificato.

Ho tentato anche capture con query fallita senza alcun handle precedente: resta IdentityUnresolved finché l'associazione esiste, senza acquisire autorità su un nuovo handle al retry. Non lo considero un difetto: preserva il marker e protegge associazioni non identificabili. Il recupero dopo query fallita **con handle già catturato** è invece dimostrato; il caso irrisolto non viene presentato come rimozione riuscita.

### Ownership, permit e piattaforma reali

`AirPodsAndroidRuntime.kt:509` riserva la lease process-wide prima di scheduling FGS; `:625` costruisce il socket solo attraverso la lease e con admission corrente. `AirPodsTransportOwnership.kt:29` consente una sola factory, conserva la lease durante I/O/costruzione o close fallita e non crea retry worker. `AirPodsComponents.kt:21` non accetta entitlement da extra, usa registry e admission, START_NOT_STICKY; Tile listening e NLS callback non acquisiscono.

`AirPodsFunctionalTestOwner.kt:13` richiede BuildConfig.DEBUG per il permit privato, revocabile e temporaneo. Il controller diagnostico richiede scratchStore, non promuove productConformance e condivide l'esclusione di trasporto. `AirPodsApplicationStartup.kt:20` mantiene conformance=false. Non ho aperto diagnostic activity né usato quel permit.

Audio/assistente usano i default concreti AndroidOrdinaryAudioProfile/AndroidAssistantLaunchPort; callback accodate ricontrollano epoch, consenso, permesso, coppia e autorizzazione. Audio ha deadline 5s e fallback Settings soltanto per manuale; automazione unworn richiede preferenza e NONE fresco. Reflection A2DP non aggiunge esenzioni JNI: assenza della hidden API resta errore best effort. Non confondo richiesta accettata con connessione audio avvenuta o Activity effettivamente lanciata.

`AirPodsAndroidRuntime.kt:564` instrada RINGING alle operazioni chiamata e ritorna senza cadere sui gesti media, anche quando la richiesta è negata. `OrdinaryCallOperations.kt:56` rilegge READ_PHONE_STATE/ANSWER_PHONE_CALLS e ambiguità; una richiesta per ringing epoch, blocco call waiting osservabile e nessun accesso telephony nascosto. Effetto telefonico reale NV. `stopOwner(:639)` chiude risorse prima di cancellare scope e azzera media senza PLAY/restore; errori restano espliciti.

### Integrazione e confini

Seguito il wiring reale SquareApplication→startup→controller e SquareApp→binding condiviso→host dispatch. `ui/airpods/host/AirPodsHost.kt:300` espone retry marker anche dopo perdita selezione; `AirPodsHostComposition.kt:101` aggiorna UI senza connettere. Verificati manifest servizi protetti/non esportati e risorsa di abilitazione companion per versione, permessi opzionali espliciti. Nessun nuovo widget LP, takeover, patch di sistema, root/Xposed, modifica impostazioni LP o upgrade Engine nel percorso ispezionato. Il widget musicale preesistente nel manifest non è un widget LibrePods introdotto dalla feature.

## Evidenze parent e limiti

Consultati `evidence/serial-validator-I2-r3/REPORT.md` (handoff di validazione, non review), attempt-inventory, log originali e XML. App test task effettivamente eseguito; builds assembleDebug/assembleRelease/wear assembleDebug con exit0. 24 checker exit0 comprendono firme, metadati 2.0.1(24), ZIP16KB, ELF e class_defs selettive/JNI keep. Firma phone uguale fra debug/release; firma Wear diversa: nessuna inferenza di interoperabilità. Gli 8 mirati non si sommano ai 686 estesi; esclusi preexisting-XML-NOT-EVIDENCE, baseline685 e r2 fallito come final PASS.

Non ho eseguito Gradle/buildAndroid/install/device/ADB, test radio, impostazioni di sistema/LP, commit o release. Nessun live source/status, memoria/rollout o report di altri reviewer consultato. Le skill richieste sono state lette; i loro suggerimenti di build/device non ampliano l'autorizzazione. Nessun report o probe scritto fuori da I2-B.

H1 richiede tre sessioni fisiche indipendenti e tutti gli scenari/readback autorizzati; JNI reale, effetti di piattaforma, Samsung e decoder restano NV. Le fixture sintetiche non certificano il full host. R8 gate FALSE può eliminare percorsi: un futuro artefatto abilitato richiede validazione propria.

## Rilievi e riconferma finale

Nessun rilievo di difetto confermato da registrare con gravità/trigger/fix. I limiti di copertura sopra sono dichiarazioni di verifica, non nuove violazioni o preferenze stilistiche. Le righe NV impediscono di usare questo singolo report come approvazione globale.

Integrità riconfermata al termine tramite riesecuzione di `audit.py`: manifest atteso, 2679 file e screenshot esterno corrispondenti, zero mismatch; record degli input invariati. Risultato macchina: `integrity-and-evidence.json`. Scratch riproducibile: `HostProbe.java`, `host-probe.txt`, `audit.py`.
