**Esito: I0 stabile richiede correzione.** Ho individuato un difetto nell’autorizzazione delle automazioni esterne: possono restare operative con background OFF e Fluidify non visibile. Il gate prodotto deve restare **FALSE**; **H1 resta NV**.

Reviewer indipendente, non autore: **GPT-6.1 Sol, reasoning high**. Fase **IMPLEMENTAZIONE**, giro **INIZIALE**, versione **I0 stabile**. Nessuna subdelega, modifica, build, installazione o operazione su dispositivi.

Snapshot esaminato: `build/review-airpods-full-implementation-I0-stable`. Manifest SHA256 verificato: `77cfdf2a2e9561a0a4b91802217e77b010a2bcb85c5b87dd8c24b5abf3b659bb`. **1130/1130 file integri**. Verificati anche dimensione/hash dello screenshot esterno, osservato privatamente senza copiarlo. Nessun AGENTS applicabile trovato.

| Criterio | Voto | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---:|---|---|---|
| I1 — Completezza funzionale | 7 | Strutturale completa; approfondimento selettivo | Riconciliati 35 ID inclusi nel registro, 67 preferenze e 41 altre disposizioni. Esaminati controlli scalari/multibyte, PPE, EQ, informazioni, tracking, steli e ingressi host. Il registro da solo non certifica ogni comportamento. | A1, alta: ambito background delle famiglie ear/CA esterne non rispettato. Tracciamento individuale di tutti i percorsi delle 67 preferenze non esaustivo. |
| I2 — Home/player/quick/full settings | 8 | Sorgente e test parent; verifica visiva limitata | Home misura lo spazio residuo fra marchio e avatar/friends; visibilità subordinata ai gate. Quick condiviso, pagina completa e accesso senza login risultano collegati. Test Home contengono asserzioni geometriche e navigazione effettiva. | Nessun difetto confermato nel campione. Ottica Samsung e contrasto del completo host Settings NV. |
| I3 — Lifecycle e ownership Android | 7 | Analisi statica mirata | Gate prima dell’acquisizione, lease condivisa, suppress persistente, separazione verifica/prodotto/debug e cleanup esplicito. La visibilità viene propagata al runtime media. | A1, alta: il ramo esterno non applica il consenso background propagato. Rami CDM non verificati integralmente da me. |
| I4 — Protocollo/stato/capability | 8 | Approfondita su sorgente, fonti pinned e corpi test | Padding/range, chime e call assignment multibyte, inversione Adaptive, EQ, campi identità, Unknown, pending 5s, generazioni, writer serializzato e connect 10s con close. `modelDeclared`, `deviceAnnounced` e osservazioni per proprietà restano distinti. | Nessun difetto confermato nel perimetro approfondito. Layout e frequenze RX reali NV. |
| I5 — Ear/CA/gesti/manual override | 6 | Analisi del coordinator e wiring media; riscontro bytecode | Motivi pausa separati, ricevute locali distinte dalla correlazione C6, controlli su target/route/epoch e cleanup senza PLAY. | A1, alta: automazioni esterne autorizzabili a UI nascosta senza opt-in background. |
| I6 — Validazione e artefatti | 8 | Controlli propri sugli archivi; esecuzioni parent | Ricalcolati XML: app 555, core 18, Wear 150, tutti senza failure/error/skip. Letti corpi significativi e JSON degli artefatti. Hash APK inclusi nell’integrità verificata. | La combinazione di A1 manca nei test. Build/R8/firma/16KB sono evidenze parent; nessuna mia esecuzione. R8 con gate FALSE non certifica futura abilitazione. |
| I7 — Confini/permessi/provenienza | 8 | Integrità completa; verifica semantica selettiva | Tutti i **133 input pinned** verificati contro SHA256 e Git blob. Attribuzione GPL presente; asset della capsula originali. PPE resta incluso separatamente dall’ATT hearing aid. Percorsi ordinari audio/assistant/chiamate esistono e non sono presentati come effetti confermati. | Nessuna esclusione infondata confermata nei percorsi approfonditi; non dichiaro una verifica semantica esaustiva di tutte le 41 disposizioni. |
| I8 — Integrazione/preservazione | 8 | Wiring statico e regressioni archiviate | Application→controller→servizio→repository/media, navigazione e teardown risultano collegati. Engine **3.0.5** e versione **2.0.1 (24)** mantenuti. | A1 attraversa il wiring reale. Preservazione globale del repository non certificabile dal congelato parziale. |
| H1 — Gate fisico standalone | **NV** | Non eseguito | Nessun telefono, radio o chiamata reale utilizzato. Mancano le tre sessioni fisiche e i readback richiesti. | Gate obbligatorio aperto; indisponibilità hardware non conteggiata come difetto software. |

**A1 — Gravità alta: background OFF non blocca le automazioni sulle altre app.**

Trigger: sessione ammessa, automazioni ON, consenso dedicato altre app ON, `fluidifyBackgroundEnabled=false`, Fluidify non visibile, singolo target esterno eleggibile e successiva transizione ear o CA.

In [MediaRuntimeCore.kt:109](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/media/MediaRuntimeCore.kt:109>), `accessible()` verifica `visible || settings.fluidifyBackgroundEnabled` soltanto per `c.local`. Il ramo esterno richiede automazioni, consenso altre app ed evidenza C6, ma **non verifica visibilità/background**. `allowed()` riutilizza questa autorizzazione anche prima del dispatch.

La perdita di visibilità viene effettivamente propagata in [AirPodsAndroidRuntime.kt:198](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/android/AirPodsAndroidRuntime.kt:198>), senza arrestare la normale sessione prodotto. La riconfigurazione invalida i vecchi effetti, ma i nuovi eventi possono nuovamente autorizzare quelli esterni.

**Prova:** lettura del percorso effettivo e disassemblaggio readonly con `javap` della classe congelata. Il bytecode conferma che il ramo esterno salta il controllo background. Non è una riproduzione Android o hardware.

**Conseguenza:** una PAUSE ear o un’attenuazione CA può essere richiesta sull’altra app mentre Fluidify è nascosta, nonostante background OFF. C6 autorizza la correlazione best effort; non sostituisce il consenso background separato.

**Fix minimo:** applicare il requisito `visible || settings.fluidifyBackgroundEnabled` anche ai candidati esterni, mantenendo gli ulteriori vincoli C6. Alla revoca/perdita di autorizzazione cancellare effetti e correlazioni senza PLAY o ripristino volume.

**Regressione:** in [MediaRuntimeCoreTest.kt:119](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/test/java/dev/lelonio/square/airpods/runtime/media/MediaRuntimeCoreTest.kt:119>) il test dei consensi separati controlla background OFF sul target locale e altre app OFF sul target esterno. I test C6 successivi usano invece la fixture con background ON. Aggiungere almeno:

- esterno, automazioni/altre app ON, background OFF, UI nascosta → zero effetti ear/CA;
- stessa configurazione con UI visibile → effetti consentiti;
- effetto accodato seguito da UI nascosta/background OFF → nessun dispatch, PLAY o restore;
- background ON esplicito → comportamento C6 conservato.

Il gate FALSE limita l’esposizione corrente, ma non risolve il difetto nell’implementazione destinata all’abilitazione.

I miei controlli sono stati integrità, riconciliazione strutturale del catalogo, confronto mirato con upstream, lettura di sorgenti/corpi test, conteggio XML e disassemblaggio. Non ho rieseguito i test del parent. Le fixture protocollo verificano bytes e reducer sintetici; le fixture UI verificano host e geometrie entro il proprio scope. Nessuna di queste evidenze prova ricezione radio, tracking fisico, esito chiamate o funzionamento Samsung.

Non propongo il passaggio della review software prima della correzione di A1. Anche dopo tale correzione, H1 e l’abilitazione prodotto restano separati.
