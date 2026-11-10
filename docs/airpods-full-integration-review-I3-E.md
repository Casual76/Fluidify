# Review indipendente E — I3 stable

Reviewer: GPT-6.1 Sol, reasoning high, non autore. Fase IMPLEMENTATION, CORRECTIVE3, terzo giro correttivo dopo I0; nessun reset. r2/r3/r4/r5 sono tentativi di validazione, non nuovi giri.

Snapshot esclusivo: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I3-stable` (S). Manifest SHA256 `81c1ae56a4e59e6f94c9ca3c24dfbfbb0555d73ad78bf1f62a70f554c6fbd757`. Product FALSE; H1/G1 NV. Questo report non approva il prodotto né hardware.

## Metodo e indipendenza

Letti brief E integralmente, rubric corrente, current facts I3, contratti C1–C6, piano/coverage, istruzioni agentic-workflow/runtime, Fluid Engine e Material3. Nessun AGENTS trovato agli antenati verificati o nello snapshot. Nessuna memoria, rollout, altra review, voto, status live o fonte equivalente live consultata. Esaminati raw, XML, codice e metadata congelati; nessuna subdelega, modifica sorgenti, compilazione, Gradle/Kotlin/javac/JShell, dispositivo/ADB, installazione, radio o pubblicazione. Non eseguiti i runner congelati che scrivono nel checkout.

Legenda: **P** = esecuzione personale host in sola lettura; **A** = esecuzione parent archiviata, ricontrollata personalmente ma non rieseguita; **I** = inferenza dal sorgente; **NV** = parte non verificata. Le scritture personali sono esclusivamente in questa directory scratch. Apksigner è stato usato come verificatore JVM, non come compilatore; aapt2 solo `dump badging`, zipalign solo `-c`.

## Tabella secondo la rubrica invariata

| Criterion | Score0-10 | Verification status | Rationale/evidence | Problems/severity |
|---|---:|---|---|---|
| I1 | NV | Copertura personale parziale, P/I | Verificati originali/catalogo e classificazioni; non completato il tracing funzionale di tutte le 35 famiglie/67 preferenze/41 disposizioni e dei consumer raggiungibili. Inventario integro non equivale a completezza. | Nessun voto complessivo inventato; completezza funzionale resta aperta in questa review. |
| I2 | NV | P/A/I parziale | Letti composite reale e test pointer/Back; ispezionata la PNG finale Forget e misurati i pixel delle 6 immagini negative/positive. Non completata tutta la matrice Home/player/quick, font/geometry, decoder, IME e host reali. | La prova pageFloor non certifica tutte le proprietà ottiche; nessun difetto estetico promosso a requisito. |
| I3 | NV | A/I parziale | Manifest, startup Product FALSE, componenti e teardown osservativo esaminati. Fixture Tile mantiene componente reale ma sostituisce il teardown framework. Non verificati autonomamente tutti i rami CDM/stop/permessi. | Android/SystemUI/OEM NV; nessun PASS lifecycle completo. |
| I4 | NV | A/I parziale | Dossier C1/C2 e hash dei sorgenti verificati; questa review non ha completato il riesame semantico di tutti i codec/range/generazioni. | Nessuna equivalenza fra test verdi e protocollo fisico. |
| I5 | NV | A/I parziale | Selezione XML include media/automation e casi di ownership; non completato il tracing autonomo di tutte le transizioni C5/C6 e degli effetti dispatch. | Correlazione esterna e ricevuta locale non sono la stessa prova; effetti fisici NV. |
| I6 | 8 | P + A, software scoped | Integrità completa; raw/XML ricontati senza sovrapposizioni; firma/package/ZIP, class_defs e segmenti ELF64 verificati personalmente; patch e input identity controllati. Corpi discriminanti di test onboarding e Tile letti. | Limiti espliciti di fingerprint scoped, shadow framework, R8 gate FALSE, linkage/runtime e regressioni core/Wear storiche. Nessun difetto serio confermato in questo perimetro. |
| I7 | 8 | P/I, confini e provenienza del software | 133 originali verificati anche come Git blob; tree ricostruito, 152 hash catalogo corretti; attribuzione GPL presente. Esaminati manifest, JNI, socket e adapter ordinari: nessuna espansione nascosta del profilo audio o takeover trovato. Engine dichiarato 3.0.5. | Provenienza documentale congelata, non autenticazione live della commit remota; piattaforma fisica NV. La presenza del widget musicale preesistente non è un widget AirPods introdotto. |
| I8 | NV | P/A/I parziale | Tracciati Application→startup, binding, route/settings, composite signed-out, quick, service/Tile e chiusura host. Artefatti verificati. Non completato riesame integrale renderer/native/Spotify/Wear/bridge né confronto indipendente completo della preservazione. | Nessun voto globale da wiring parziale. Non richiesto rebuild integrale di snapshot scoped. |
| S1 | NV | A/I parziale | Gli XML estesi includono dati, ordering, requests, search e playlist UI. Non completato il tracing personale end-to-end di cancellazione A→B→A, owner/occurrence, download reattivi e classificazione fallback. | Conteggi e nomi dei test non certificano il criterio. |
| H1 | NV | NV | Nessuna nuova evidenza fisica autorizzata del candidato; nessun dispositivo usato. | Gate prodotto obbligatorio aperto. Anche G1 resta NV. |

Gli 8 di I6/I7 si applicano al rispettivo ambito software ispezionato, con le limitazioni specificate; non sono voti sul prodotto. Le righe NV non soddisfano una soglia numerica né si trasformano in zero. Non propongo un'approvazione complessiva.

## Riscontri personali di integrità e provenienza

- `integrity-start.json` e `integrity-end.json`: hash del manifest atteso; verifica size/SHA256 di tutti i **3377** file e dell'unico input privato, senza mismatch. Lo screenshot privato è stato solo letto per l'hash: non copiato, pubblicato né usato per un giudizio visivo. Il vecchio riferimento del focus a 2679 hash non descrive questo manifest.
- `provenance-audit.json`: **103/103** riferimenti obbligatori correnti presenti; il template storico “62” non è il conteggio I3. **152/152** voci di `catalog/artifact-manifest-I3.json` corrispondono. I manifest interni precedenti sono storici, non checker del catalogo attuale.
- Tutti i **133** originali in sources-manifest corrispondono in lunghezza, SHA256 e Git SHA1 (`blob <len>\0<bytes>`) e alle rispettive voci dell'albero pinned. Ricostruzione personale del tree root: `d27494ba06f2e9613d15887faf7ee2a347ce9e02`, coincidente con il metadata commit congelato. Il campo top-level `upstream-tree.json:2` contiene invece l'ID commit `89356c08...`: caveat dichiarato, non prova che commit e tree siano lo stesso oggetto. Nessuna query live effettuata.
- Gli **8** record raw/XML del supplemento I1 sono byte-identici ai digest originali del supplemento: tre log, result e quattro XML. README/requirements/checker storici elencati da quel vecchio manifest non sono ripresentati alla radice di I3; non li ho sostituiti con equivalenti correnti e non li conto come evidenza mancante dei raw I1. Il manifest storico non è un verificatore dell'intero package I3.

## Input, patch e selezione terminale

`validation-selection.json` seleziona esclusivamente `I3-final-r5-source-inputs.json`, `I3-final-r5-artifact-checks.json`, testedStage finalTests e finalStage after. I due record sono compresi nel manifest verificato; non si sostituiscono ai raw.

Ricalcolati personalmente i fingerprint con JSON ordinato e separatori canonici: tutti coincidono. Gli stadi contengono **1360** input, non i 1356 storici del focus. after = finalTests = fixedBeforeTargeted, fingerprint `fc00f2e41f68f3ddc8f671ec4856fc9500660c6ac491da00d4eb6d4cbb11e090`. Dei 1360, **520** sono presenti sotto S/source e corrispondono esattamente; **840** non sono copiati. Per questi ultimi l'identità rimane attestazione A del parent, non verifica personale dei byte né difetto automatico di un pacchetto espressamente scoped. Include dipendenze/configurazioni e parti non selezionate; non ho letto gli equivalenti live.

before = negativeSource, fingerprint `3d14269698c93ac74f3f91a3d208152805362c347cd4f0c17b0f031e9c8a9425`. La sola differenza finale è SquareApp. `patch-audit.json`: approved.patch e applied.patch sono byte-identici, SHA256 `992acc7b9c14fce68e0c5735de1a087dbca03138ba9d7d9683aaddae2fafa1cb`. Rimossa **solo in memoria** la riga `pageColor = ...pageFloor` da S/source/.../SquareApp.kt:3631, ricostruito esattamente l'hash before `e0e607bdffd6b5878b21b0b39fadbfe8ede3cfaa4ff1345d41e5ffc5e0eae3d3`; il file finale ha hash `edc4c0ff2c728175b71c45d6afe12a45f21b66dbbf4cb855f3b6dc96e58d41bd`. Il git apply --check con exit0 è A, non una mia mutazione.

## Esecuzioni archiviate: conteggi ricostruiti, non nuovi test personali

| Tentativo | XML freschi | Test / failure / error / skip | Interpretazione |
|---|---:|---|---|
| I3 iniziale targeted | 7 | 94 / 7 / 0 / 0 | 5 PaneTitle, 1 offline-setup non visualizzato, 1 cast ShadowTileService→ShadowService. Fallimento conservato. |
| I3 r2 targeted | 7 | 94 / 0 / 0 / 0 | Baseline dopo correzioni, non finale r5. |
| I3 r2 extended | 84 | 714 / 0 / 0 / 0 | Baseline. |
| I3 r3 negative | 1 | 3 / 3 / 0 / 0 | ComposeTimeoutException prima delle asserzioni RGB: NON negativo qualificato. |
| I3 r4 negative | 0 | 0 / 0 / 0 / 0 | compileDebugUnitTestKotlin fallisce su imageio/getRGB; zero test. NON negativo qualificato. |
| I3 r5 negative | 1 | 3 / 3 / 0 / 0 | Tre AssertionError “Page floor red”: negativo qualificato. |
| I3 r5 positive | 1 | 3 / 0 / 0 / 0 | Positivo dopo patch. |
| I3 r5 targeted | 7 | 97 / 0 / 0 / 0 | Selezionato, exit0/raw BUILD SUCCESSFUL. |
| I3 r5 extended | 84 | 717 / 0 / 0 / 0 | Selezionato, exit0/raw BUILD SUCCESSFUL. |

I 97 targeted sono tutti nei 717 extended; i 3 positivi sono già nei targeted. **Non sommare** 3+97+717. I2 r3 ricontato separatamente: 2 suite/8 targeted e 81 suite/686 extended, storici, non esecuzioni nuove I3. Core18/Wear150 non sono stati rieseguiti dal reviewer e restano storici.

Letto common.py: prima di ciascun run test sposta XML e PNG precedenti in `preexisting-*-NOT-EVIDENCE`, poi conserva command/result/raw e nuove catture. Il mio conteggio usa solo `app/XML/TEST-*.xml`, mai i preexisting. Il build raw r5 termina con successo e registra :app:assembleDebug, :app:assembleRelease, :wear:assembleDebug; Wear è UP-TO-DATE. È una chiamata di build con tre target riusciti, non tre rebuild puliti. R8 esegue; lintVital non è full lint. I24 controlli archiviati hanno exit0; non li presento tutti come esecuzioni mie.

## PNG e significato dei test

`AirPodsOnboardingModalTest.kt:102` usa coordinate pointer reali; i casi coprono cancel/confirm, Back prima del page-back, menu, unmount e shielding della modal globale. Monta AirPodsOnboardingAccess e pagina reali, ma con stato sintetico e action sink; non socket o effetti piattaforma.

A righe128–133 BitmapFactory legge la PNG nativa appena creata e controlla RGB/alpha a (8,height/2). Misurazione personale a (8,500), immagini800×1000: tutti i negativi sono `(255,0,255,255)`; positivi light `(241,242,246,255)`, dark `(10,10,12,255)`, AMOLED `(0,0,0,255)`. Concorda con XML e patch: la pagina copre il canvas ostile. Non dimostra alpha interno del layer registrato, esecuzione di ogni tier/blur o qualità fisica Samsung.

Ho aperto personalmente la PNG finale `serial-validator-I3-r5/targeted-captures/forget-above-real-page.png`: alert visibile, testo/azioni leggibili nella cattura, contenuto sottostante sfocato con una fascia attraverso divisore/azioni. Non ho eseguito un confronto visivo A/B completo con le catture nominali vecchie; nessuna dichiarazione personale di miglioramento estetico o certificazione ottica completa.

La fixture Tile sostituisce soltanto il framework `TileService.onDestroy` (`AirPodsComponentsTest.kt:16,57`); il componente reale chiama `availability.destroy()` (`AirPodsComponents.kt:89–90`). Questo corregge il crash del test e conserva la verifica del codice applicativo; non dimostra il teardown reale SystemUI.

## APK, DEX, ELF e firma: verifiche personali

`apk-audit.json` conserva stdout/stderr ed exit delle **9** chiamate personali di verifica firma, metadata e zipalign sui tre APK congelati. Tutte exit0. Package `dev.pampa.fluidify`, versione2.0.1/code24; Wear minSdk36, telefono26. Phone debug/minified firmati v2 con SHA256 certificato `825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43`. Certificato Wear diverso: non prova interoperabilità Data Layer né accoppiamento delle installazioni.

Hash/size personali corrispondono al manifest: debug `e64f4931...`71961113B, minified `4e9c51bb...`20667273B, Wear `7c8067be...`153220831B; digest completi nel manifest e nei JSON della review.

Parsing class_defs sui DEX reali: debug61180 definizioni, **1016/1016** classi .class congelate definite; minified8845, Wear55940. Non sono semplici stringhe di riferimento. Nel minified il nome JNI AirPodsSocket rimane; 1009 nomi del campione compiled non sono presenti con nome originale, fatto compatibile con ottimizzazione/rinomina/R8 e non prova automatica di difetto. Nel Wear il campione phone non è un requisito.

Esaminati tutti i `.so` dei tre APK: debug14, minified7, Wear6. Per ciascun ELF64 analizzati program header PT_LOAD: allineamento almeno16384 e congruenza offset/virtual address modulo16384, senza violazioni. Simbolo JNI atteso presente nella libreria AirPods dei package telefono; regola di mantenimento a `app/proguard-rules.pro:61`. ZIP16KB verificato con zipalign su tutti e tre. Questi controlli non eseguono dlopen, JNI resolution, chiamate native né full linkage.

`AirPodsApplicationStartup.kt:21` conserva `conformance = { false }`. R8 di tale candidato può eliminare i rami prodotto: firma, definizioni selezionate e un APK minified riuscito **non qualificano un futuro APK gateTRUE**. Quel candidato richiederà proprie build/verifiche/runtime prima del prodotto; non occorre ricompilare questo snapshot scoped per dichiarare il limite.

## Confini e rilievi

Nessun difetto bloccante o serio confermato nell'approfondimento I6/I7. Non trasformo le limitazioni già dichiarate in richieste di correzione del codice. Non ho dimostrato assenza di difetti negli ambiti NV.

Punti concreti da preservare ai gate successivi (limiti di evidenza, **non bug confermati**):

| Gravità/stato | Trigger e file:riga | Prova e conseguenza | Azione/regressione proporzionata |
|---|---|---|---|
| Limite materiale, NV runtime | Abilitare prodotto; AirPodsApplicationStartup.kt:21 | P/I: gateFALSE attuale, R8 eseguito. Gli APK presenti non provano il percorso enabled. | Quando autorizzato gateTRUE, qualificare quel preciso APK e scenari H1; mantenere separato il debug permit. |
| Limite circoscritto, NV framework | Teardown Tile reale; AirPodsComponentsTest.kt:57 | P/A: super framework stub, componente reale eseguito. Nessuna prova SystemUI. | Alla finestra dispositivo autorizzata verificare stop/destroy/unregister e assenza di acquisizioni; non rimuovere una shadow necessaria per simulare prova hardware. |
| Limite circoscritto, NV ottica completa | Certificare blur/alpha interni da un pixel; AirPodsOnboardingModalTest.kt:129 | P: pixel finale opaco; non readback interno. | Conservare test discriminante di copertura; misurare separatamente materiale/tier/frame reali solo se si vuole chiudere quella proprietà. |
| Limite provenienza, già documentato | Usare top-level SHA come tree object; catalog/upstream-tree.json:2 | P: è commit ID; tree ricostruito d27494... distinto e coerente col metadata congelato. | Usare il mapping commit→tree del dossier; eventuale futuro schema dovrebbe dare nomi distinti ai due campi, senza alterare il congelato. |

L'adapter audio ordinario usa A2DP connect/disconnect senza `isAccessible`, policy privilegiata o nuove exemptions; controlla permessi/bond/route e distingue RequestAccepted dal risultato. Chiamate via TelecomManager sono permission-gated e rimangono richieste, non prova dell'effetto. Il JNI Bluetooth preesistente rimane lazy e limitato a BluetoothSocket/BluetoothDevice; non rilevata estensione a hidden API del profilo audio. Attribuzione e licenza pinned presenti, nessun asset Apple copiato dimostrato dai sorgenti selezionati; nessun nuovo widget AirPods o takeover rilevato. L'Engine dichiarato resta3.0.5; l'aggiunta di sorgenti engine-ui al pacchetto amplia la documentazione, non dimostra un upgrade.

## Esito

I6=8, I7=8 nel software scoped; altri criteri NV secondo profondità effettiva. Nessun PASS hardware, nessun PASS prodotto, nessun PASS globale forzato dai conteggi. Product FALSE, H1/G1 NV. Integrità finale da leggere in `integrity-end.json`; medesimo manifest e nessuna modifica del congelato.
