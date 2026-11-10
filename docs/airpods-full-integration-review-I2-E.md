# Review indipendente E — I2 stabile

Reviewer: E, non autore. Modello effettivo: GPT-6.1 Sol (`gpt-6.1-sol`), effort high.
Fase IMPLEMENTAZIONE; giro CORRETTIVO2; versione I2 stabile. r2/r3 sono tentativi seriali di validazione, non ulteriori giri. Nessun reset del massimo di tre correttivi dopo l'iniziale.

Snapshot S: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I2-stable`.
Manifest SHA256: `a572b80b04a54a37beb333ac57638a03c6e485318457a97f0258ea6edd6e39b3`.

Esito: nessun difetto concreto confermato nel focus evidenze/artefatti. I6 e I7 soddisfano la soglia nel perimetro software verificato. I8 ha riscontri parziali ma resta NV nel suo insieme: non assegno un voto alla preservazione completa di codice estraneo non incluso. Questa review non approva l'intera implementazione né il prodotto. H1/G1 NV; product FALSE.

Letti agentic-workflow e references/runtime.md, Fluid Engine e Material3. Nessun AGENTS.md trovato nei tre livelli antenati controllati (C:/, C:/VibeCoded Projects/, repository) o nel pacchetto. Nessuna memoria/rollout, review altrui o sorgente live consultata. Letti fatti I2, rubric, contratti C1–C6, piano/coverage storici subordinati, catalogo, esclusioni e fonti pinned pertinenti. Nessuna build, Gradle, installazione, dispositivo, ADB, radio, modifica LP/sistema o subdelega. Tutte le scritture personali sono in I2-E.

Scala del rubric: 9–10 implementazione forte con evidenze dirette e incertezze delimitate; 8 requisito soddisfatto nello scope software dichiarato; 6–7 difetto/omissione o debolezza materiale; ≤5 violazione sostanziale/base insufficiente. Soglia ≥8 per ogni criterio numerico assegnato e nessun difetto serio confermato. NV non equivale a zero o approvazione.

| Criterio | Voto 0-10 | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---|---|---|---|
| I1 | NV | Lettura requisiti/catalogo e provenienza; completezza non verificata | Hash e blob delle 133 fonti pinned verificati; non tracciate integralmente tutte le 35 famiglie/67 preferenze/41 esclusioni fino agli effetti host | Nessun difetto confermato; manca giudizio semantico completo di questo reviewer |
| I2 | NV | Ispezione host e fixture, senza audit UI completo | SquareApp.kt:603,1377,1529,2655,3050,3069; AirPodsHostComposition.kt:45; test screenshot sintetici | Ottica Samsung, IME, TalkBack e decoder NV; nessun voto ricavato dal numero di immagini |
| I3 | NV | Controllo selettivo startup/manifest | SquareApplication.kt:33; AirPodsApplicationStartup.kt:14; AndroidManifest.xml:76; esaminato test startup con assenza di servizio/media | Non rieseguita né auditata tutta la matrice C3/C4 e process death |
| I4 | NV | Provenienza codec e identità artefatti | Fonti pinned AACP/Packets e class_defs presenti; non riesaminati tutti layout/range/generazioni | Presenza DEX non prova protocollo corretto |
| I5 | NV | Lettura selettiva bridge e test meaningful | PlaybackAirPodsMediaBridge.kt:24,334; LocalAirPodsNativeApplicationTest.kt:39 controlla ricevuta solo dopo evento pertinente, override e callback precomando | Fake native commands/eventi sintetici; non certificano JNI/audio/CA reali |
| I6 | 9 | Esecuzione personale host + parent archiviato distinti | 2679 hash + input privato; 62 riferimenti; selezione r3; XML ricontati; 24 controlli strutturali ripetuti sui tre APK; input coperti coincidenti | Nessun difetto confermato. Linkage completo, runtime, full lint e hardware NV; Core/Wear storici |
| I7 | 8 | Audit sorgenti/provenienza e delta | 133 fonti pinned: SHA256, dimensioni e git blob corretti; GPL/attribuzione presenti; JNI invariato rispetto I1, ristretto ai due prefissi Bluetooth; Engine 3.0.5 invariato nei file comuni | Nessuna espansione vietata trovata nello scope. Non certifica legalità di una futura distribuzione né supporto piattaforma |
| I8 | NV | Audit integrazione selettivo e preservazione documentale | Startup unico, binding condiviso, attach/detach player e cleanup esaminati; delta I1 ricostruito; regressioni app archiviate | Preservazione globale README/bridge/native/Spotify/Wear non interamente verificabile dal sottoinsieme; nessun rebuild integrale richiesto |
| S1 | NV | Verifica evidenze r3 e lettura test classifier | Tutti i tre metodi SearchFailureTest presenti negli XML; assert su positivi/negativi e propagazione cancellazione, non mero smoke test | Non auditato tutto S1; diagnostici provider/JNI reali NV |
| H1 | NV | Non eseguito, fuori autorizzazione | Nessuna nuova prova fisica del candidato; telefono assegnato non è evidenza di esecuzione | Gate separato obbligatorio: tre sessioni e tutti gli scenari applicabili restano aperti |

## Prove eseguite personalmente

1. Letti e hashati tutti i 2679 file del manifest, verificando anche la dimensione e l'unico input privato esterno: zero errori. Lo screenshot non è stato copiato, pubblicato o incluso nei report; nessuna valutazione ottica era necessaria per questo focus. Hash manifest uguale al valore del brief e del file manifest.sha256.
2. Eseguito il checker read-only `source/tools/check-review-package.py` contro S con il file `review-evidence-I2-references.json`: 62 riferimenti, zero errori, passed=true, hardwareCertification=false. Il checker è stato letto prima di eseguirlo.
3. Hash dei due record selezionati uguali a validation-selection.json. Verificata corrispondenza hash/dimensioni dei tre APK ai record selezionati. before/preFinalTests/finalTests/after contengono gli stessi 1356 input; ricomputati tutti i quattro digest canonici: `a0ad9c7f00f2fd103a08b29ff82136e3677514df0a5c4075890cd1f1afdb9e09`. Ricomputato il delta dalla baseline after: solo SearchOutcome.kt e SearchFailureTest.kt, esattamente come dichiarato.
4. Intersezione fisicamente riesaminabile: 444 dei 1356 input hanno il corrispondente `source/<path>` nel congelato; tutti i 444 coincidono. Gli altri 912 sono registrati ma non inclusi: non dichiaro di averne verificato i byte live. Il freezer controlla prima/dopo i 1356 input nel checkout e i file copiati (freeze-implementation-I2.py:16 e controlli finali); questo è codice/provenienza parent, non mia riesecuzione del freeze. La selezione scoped è esplicita, quindi queste omissioni non sono un difetto né motivo per pretendere un rebuild.
5. Ricontati i testcase XML, esclusi i percorsi preexisting-XML-NOT-EVIDENCE: mirati r3 8/0/0/0 in 2 suite; estesi r3 686/0/0/0 in 81 suite. Identità classname+name: 686 casi unici, tutti gli 8 mirati inclusi negli estesi. Non sono 694 test distinti. r2: 8 casi, 1 failure, coerente con exit1. Non riciclato come successo. Controllato il runner r3: sposta preventivamente gli XML ereditati, copia quelli nuovi dopo il comando e usa --rerun --no-build-cache sul task test.
6. Supplemento I1: il digest del manifest I1 storico corrisponde al baseSnapshotManifestSha256 del supplemento. Tutti gli 8 originali raw evidence (3 log, result.json, 4 XML) copiati in I2 corrispondono per byte/hash al manifest supplementare. Gli altri tre record del supplemento sono README/requirements/checker del contenitore storico: il freezer non dichiara di copiarli come raw evidence; la loro assenza in I2 non è un originale di test perduto. Non aperto alcun altro file I1.
7. Ricostruito il delta dai due manifest: 105 source aggiunti, 3 rimossi, 20 cambiati; corrisponde a changes-from-I1.json. Le tre rimozioni sono strumenti/fatti I1 storici, non codice app. Nessun cambiamento dei file Engine comuni; cpp AirPods JNI invariato. Non equiparo il delta tra due snapshot scoped al diff completo del checkout.
8. Fonti pinned: 133 file, tutti con SHA256/dimensione e SHA1 Git blob ricomputati corretti; unica revisione 89356c08cea3db0b6b54792d9329d09a87cc85fa. Attribuzione e percorso GPL presenti in LICENSES/LibrePods-AirPods.md. Il file conserva paragrafi storici esplicitamente subordinati al contratto corrente.
9. Ripetuti sui soli APK congelati tutti i 24 controlli: 3 firme, 3 metadati, 3 ZIP16KB, 2 SPAKE2, 10 class_defs debug, 2 scansioni di tutte le librerie ELF64 supportate phone e 1 classe JNI minified. Tutti exit0. Wrapper personale `artifacts.py`, output completo `artifact-personal.json`; nessun output nei percorsi originari. La selezione class_defs confronta i .class congelati con vere definizioni DEX, non stringhe di riferimento.

## Risultati parent archiviati, non esecuzioni mie

Raw log r3: `I2-final-r3-targeted-tests.log:228,231` mostra task eseguito e BUILD SUCCESSFUL; `I2-final-r3-extended-tests.log:244,255` altrettanto. `I2-final-r3-builds.log:443,684,705,708` mostra i tre target assembleDebug/assembleRelease/Wear e uscita positiva. È un comando con tre target: Wear assembleDebug è UP-TO-DATE e il suo APK conserva il digest baseline. Non sono tre ricompilazioni complete né nuove prove Wear.

Baseline: 360 mirati/685 estesi precedono la correzione finale di due file. r2 fallisce e non produce build successive. r3 usa gli stessi filtri estesi della baseline e contiene un metodo classifier in più. I raw r2 e r3 sono distinti e conservati. Core18/Wear150 rimangono storici: i comandi r3 eseguono test app, non nuovamente quelle suite. Gli XML storici in altre directory non entrano nei miei totali finali.

Identità APK (verificate personalmente):

| Artefatto | Byte | SHA256 |
|---|---:|---|
| Debug | 71961113 | 500290a070bbacd39422c9342dfacb691d952105988189702b6da15a687b11ee |
| Minified | 20667263 | db3fb3d377caa2a80ef537d69eb686c61c9708cc3e8f296344be33e3c5e18484 |
| Wear | 153220831 | 7c8067be158bc08dc73b8a6ba1c7f690964a0624ee4ecf77f8c28f23dfc18564 |

Tutti package dev.pampa.fluidify, versione 2.0.1(24). Firme v2 valide: phone debug/minified certificato `825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43`; Wear `d7a409a3686ee645449d08741e8015af3f2fef436058167b0b86b6003e12497e`. La differenza è dichiarata, non prova interoperabilità phone/Wear. ZIP alignment e PT_LOAD sono verifiche separate; ELF è limitato alle ABI arm64-v8a/x86_64 confezionate nei due APK phone. Non ho esteso la scansione ELF al Wear attribuendola ai 24 controlli.

## Tentativi di falsificazione e limiti

- Cercati conteggi duplicati/ereditati e finalizzazione su tentativo fallito: nessun controesempio trovato. Conteggi corretti solo separando r3, baseline e r2.
- Cercata sostituzione degli originali I1, record terminali stale e differenze fra input congelati/testati coperti: nessuna discrepanza trovata.
- Cercati DEX con sole referenze, librerie non allineate, firme/metadati discordanti: nessun errore nei controlli selettivi ripetuti. Proguard-rules.pro conserva il nome JNI, ma la definizione della classe non dimostra risoluzione/esecuzione del metodo nativo.
- I test LocalAirPodsNativeApplicationTest usano il vero LibrespotPlayer con comandi/eventi sostituiti: verificano stato/ricevute, non l'esecuzione Rust/JNI reale. AirPodsApplicationStartupTest usa Application sintetica e invoca install, non un avvio completo SquareApplication. AirPodsUiScreenshots genera fixture con stato/backdrop sintetico e capture; 102 metodi non diventano 102 giudizi ottici.
- Cablaggio reale letto: SquareApplication installa il runtime con factory media lazy; AirPodsApplicationStartup.kt:19 imposta conformance=false; SquareApp condivide un binding e un portale quick, inclusa entrata signed-out. PlaybackService.kt:61–74 sostituisce/chiude l'attachment insieme al player; PlaybackAirPodsMediaBridge.kt:334 rilascia listener, observer e callback. Questi riscontri non sostituiscono un audit esaustivo I8 o una prova di continuità decoder.
- JNI legge VMRuntime e applica solo prefissi BluetoothSocket/BluetoothDevice; è il trasporto preesistente, invariato dal manifest I1. Non è una nuova esenzione globale o modifica di sistema. La possibilità effettiva su piattaforma resta NV.
- R8 con product FALSE può eliminare percorsi gated: la release presente non certifica l'artefatto che abiliterà il prodotto. All'abilitazione servono nuova validazione pertinente e gate fisico, senza chiedere di ricostruire questo pacchetto scoped.

Nessun rilievo correttivo confermato da assegnare a gravità/trigger/fix. Le limitazioni sopra sono dichiarazioni di copertura e NV, non difetti inventati. I7 non autorizza pubblicazione; H1 non è approvato.

## Riconferma finale

Vedere `integrity-final.json`: ricontrollo finale di tutti i 2679 hash/dimensioni e dell'input privato, digest manifest e 62 riferimenti obbligatori. Nessuna modifica al congelato. I risultati personali restano separati da Gradle/test parent in `artifact-personal.json`, `integrity.json` e `audit-additional.json`.
