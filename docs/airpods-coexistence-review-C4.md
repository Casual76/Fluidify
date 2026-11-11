# Review C4: provenienza, contratto e inventario APK

Fase **RICERCA**, giro **CORRETTIVO3**, versione **C4**. Due nuovi reviewer indipendenti
**GPT-6.1 Sol, reasoning high**. Terzo giro correttivo dopo l'iniziale, senza reset.

Snapshot `build/review-C4-coexistence`, manifest SHA256
`8a1fba179e026e1e204701f3bc936754e86ae09186c2b35ff74f51bcba17c304`.
Entrambi hanno verificato 771 file e sette input esterni, con controlli propri distinti.

| Criterio | A | B | Media | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---|
| R1 — vincoli | 9 | 9 | 9 (2/2) | 9 | Nessuna violazione confermata |
| R2 — copertura tecnica | 9 | 9 | 9 (2/2) | 9 | B-01 soddisfatto; nessun rilievo materiale |
| R3 — esclusioni e controesempi | 9 | 8 | 8,5 (2/2) | 8 | Esclusioni circoscritte; nessuna impossibilità universale |
| R4 — provenienza e riproducibilità | 9 | 9 | 9 (2/2) | 9 | Hash e riproduzioni conformi nel perimetro |
| R5 — integrazione e decisione | 8 | 8 | 8 (2/2) | 8 | B-02 soddisfatto; decisione temporale ancora aperta |
| CP1.b — gate fisico | NV | NV | — (0/2) | — | Nessuna prova completa di convivenza; prodotto non sbloccato |

Aggregati soltanto su voti verificati comparabili di questo giro/versione. NV escluso,
non vale zero. Le differenze di copertura sono esplicite nei report integrali sotto.

**Decisione:** C4 soddisfa i criteri concordati per la qualità della ricerca nel
perimetro congelato: tutti i voti numerici individuali almeno 8 e nessun difetto
grave confermato. Nessun ulteriore rilievo correttivo aperto; B-01 e B-02 soddisfatti.
I reviewer non hanno incontrato giudizi precedenti della ricerca nei tre documenti
obbligatori. Gli archivi C1–C3 sono rimasti immutati, senza modifiche retroattive.

Questo non dimostra convivenza AirPods, accessibilità OEM o supporto universale.
La conclusione condivisa è che nessuna catena completa è stata ricostruita nei
percorsi verificati, non che ogni futura soluzione sia impossibile. CP1.b/G1 restano
NV. Nessuna UI, A2DP o lifecycle di prodotto sbloccato; nessun accesso al telefono,
modifica di LP/sistema, commit o pubblicazione eseguito in questa ricerca.

La finestra rimane aperta fino al **2026-11-08 03:52:42 UTC**. Le eventuali verifiche
successive al congelamento vanno distinte da C4 e non ne ereditano automaticamente
i giudizi. Nessun ripiego esclusivo anticipato.

Review A: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A4437c749-80e9-4abc-a4de-f2c1e4330b8e>

Review B: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A8f5ffd2e-14a1-4140-ae56-223c2aa12609>

## Report integrale A

Reviewer A indipendente — **GPT-6.1 Sol, reasoning high effettivi**. Fase **RICERCA**, giro **CORRETTIVO3**, versione **C4**: terzo giro correttivo dopo l’iniziale, senza reset.

**Non ho individuato difetti correttivi confermati né una strada completa trascurata nel materiale verificato.** L’analisi sostiene conclusioni circoscritte e riproducibili; non dimostra il funzionamento della convivenza. **CP1.b resta NV.**

Ho letto `agentic-workflow/SKILL.md`, `references/runtime.md`, la rubrica C4 e i tre documenti tecnici obbligatori. Non ho trovato AGENTS applicabili nei percorsi controllati. Nessuna consultazione di review, voti precedenti o comunicazioni dell’altro reviewer; nei documenti obbligatori non ho incontrato giudizi precedenti della ricerca. Nessun agente avviato, accesso al telefono, build, emulazione, modifica di file, commit o push.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificata documentalmente | Vincoli conservati nel [requisito corrente](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/docs/airpods-coexistence-evidence.md:8>). Widget, modifiche LP, impostazioni globali e privilegi non vengono promossi a soluzione. Telefono escluso dalle operazioni. | Nessun difetto confermato. |
| R2 | 9 | Verificata nel focus; altre piste campionate | DEX, batterie Samsung, BLE e BudsLink distinguono riferimenti, endpoint, TX, RX, cache, identità, freschezza e background. CA configurata separata dall’attività vocale. Vedi riscontri sotto. | Copertura funzionale completa non dimostrata; limite dichiarato, non difetto software. |
| R3 | 9 | Verificata per esclusioni e controipotesi campionate | L’inventario non esclude reflection/JNI/processi esterni; BLE non prova assenza firmware; BudsLink non diventa RFCOMM dal nome del profilo. Le [condizioni di riapertura](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/budslink-contract/REPORT.md:90>) richiedono differenze concrete. | Nessuna esclusione universale ingiustificata riscontrata. |
| R4 | 9 | Integrità riprodotta; controlli host eseguiti | Manifest, 771 file e sette input esterni verificati. Inventario DEX riprodotto e confrontato con una seconda lettura delle tabelle. Analisi BLE riprodotta in memoria; provenienza locale BudsLink/Samsung verificata. | Hash coerenti non certificano autenticità Samsung, equivalenza sorgente/APK o comportamento hardware; limiti conservati. |
| R5 | 8 | Verificata documentalmente | P1, I3 e G1 restano distinti; nessun delta prodotto nel confronto dei manifest C3/C4. La [decisione resta aperta](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/docs/airpods-coexistence-evidence.md:168>), senza UI o scelta esclusiva anticipata. | Integrazione operativa e disponibilità contemporanea non provate. Nessun difetto confermato. |
| CP1.b | **NV** | Non verificabile nel mandato | Mancano prove fisiche contemporanee e durature delle due app, ricezioni complete, audio/gesti/CA e lifecycle. | Gate aperto; nessun voto numerico sostitutivo. |

**Controlli propri**

- SHA256 del manifest coincidente con `8a1fba179e026e1e204701f3bc936754e86ae09186c2b35ff74f51bcba17c304`; dimensioni e hash corrispondenti per **771 file e sette input esterni**.
- Confronto esclusivamente fra manifest C3/C4: confermati **sette nuovi contenuti, tre cambiati e un indice precedente rimosso**. Il manifest contiene inoltre il nuovo `changes-from-C3.json` stesso: questa differenza contabile non indica un prodotto aggiunto o rimosso.
- `inventory.py --check --apk …`: **33 DEX, nove coppie Bluetooth owner/nome, positivo `BluetoothSocket.connect` e due negativi superati**.
- Seconda lettura indipendente delle tabelle: stessi risultati per tutti i 33 elementi DEX dell’APK; nessun altro elemento `.dex` annidato trovato.
- BLE: entrambe le analisi complete coincidono con i JSON salvati dopo normale serializzazione. Riprodotti in RAM sei negativi malformati, un positivo a due frammenti e otto troncamenti.
- Verificati SHA256 e Git blob dei **22 sorgenti BudsLink** e dei **dieci sorgenti comparativi Samsung**. Eseguito anche l’audit readonly RFCOMM.

**DEX: conclusione sostenuta e confine**

Il [parser](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/own-resource-inventory/inventory.py:22>) usa campi e dimensioni coerenti con la specifica DEX catturata: `method_id_item` contiene `class_idx`, `proto_idx` e `name_idx`. La selezione riguarda owner `Landroid/bluetooth/`; il vincolo SHA256 precede l’apertura dell’APK e `--check` evita la scrittura.

Il risultato sostiene l’assenza dei riferimenti SDK diretti elencati **nella selezione esaminata**. Non conta callsite, chiamate, socket o RCB. Non esclude reflection, JNI, codice dinamico, riferimenti con owner fuori filtro o attività di processi esterni. Il [report](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/own-resource-inventory/REPORT.md:6>) conserva questi limiti. I due negativi verificano specifici rifiuti strutturali; non rendono il parser un validatore universale DEX/MUTF-8.

**B-01 e B-02**

B-01 è confermato direttamente in `samsung-metadata-contract/sources/S10-BluetoothDeviceBatteryManager.java`, righe 249–266: il ramo calcola il minimo e, per differenza assoluta ≤15, assegna `-1` a entrambi i laterali. Quindi **80/90 → aggregato 80, L/R −1**; 80/100 conserva invece i due laterali. Il [testo corretto](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/samsung-metadata-contract/REPORT.md:81>) attribuisce correttamente la perdita al consumer/cache, senza trasferirla alle letture raw `semGetMetadata` o all’hardware target.

Per B-02, i tre documenti obbligatori conservano requisiti, dati, limiti e riferimenti ai congelati senza esporre voti o verdetti precedenti della ricerca. Le menzioni di correzioni e checkpoint non sono state utilizzate come approvazioni. Non ho consultato gli archivi storici.

**Tentativi di falsificazione delle piste BLE/BudsLink**

Il ricalcolo BLE conferma **870 record, 115 annunci, sette proximity plaintext e dodici frame modalità TX/RX**. Non emerge una coppia di annunci attribuiti allo stesso peer che attraversi due stati ricevuti noti; manca inoltre il collegamento dimostrato fra peer BLE e ACL. Lo zero CA riguarda esclusivamente il formato fisso cercato. La [conclusione delimitata](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/ble-mode-contract/REPORT.md:67>) è sostenuta: dataset insufficiente, nessuna impossibilità firmware dimostrata.

BudsLink offre un controesempio utile all’idea che esista soltanto stato ottimistico: contiene parsing RX reale di modalità e CA configurata. Tuttavia, [`setAwarenessMode`](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/budslink-contract/sources/src/lib/devices/airpods/airpodsSocket.js:335>) notifica anche immediatamente dopo l’invio, mentre i parser RX deduplicano rispetto a default locali. Batterie sconosciute convertite a zero, merge senza TTL e capacità dichiarate localmente impediscono di copiarne il contratto come fotografia completa e fresca.

Pro2/Pro3 selezionano lo stesso UUID/handler AACP. Il client BlueZ non fissa PSM/Channel: la risoluzione SDP e l’upstream citato non dimostrano un endpoint AirPods alternativo utilizzabile su Android. Il servizio D-Bus Linux non offre accesso al reader di LibrePods stock.

Non ho quindi ricostruito una catena completa **endpoint → apertura → bootstrap → TX/RX → identità/freschezza → permessi → background** che superi le lacune già dichiarate.

La qualità della ricerca risulta solida nel perimetro verificato. L’avanzamento prodotto rimane invariato: nessuna nuova soluzione completa dimostrata, **G1/CP1.b NV**, nessuna autorizzazione implicita alla UI o al ripiego esclusivo. La review non chiude la finestra del **2026-11-08 03:52:42 UTC**.
## Report integrale B

**Reviewer B indipendente — GPT-6.1 Sol, reasoning high. Fase RICERCA, giro CORRETTIVO3, versione C4.**

**Nessun rilievo correttivo confermato nel perimetro verificato. B-01 e B-02 risultano soddisfatti.** Non ho individuato una soluzione completa trascurata; questo esito non dimostra impossibilità universale e non certifica la convivenza hardware.

Esaminato esclusivamente il congelato `build/review-C4-coexistence` e gli input autorizzati. SHA256 del manifest corrispondente a `8a1fba179e026e1e204701f3bc936754e86ae09186c2b35ff74f51bcba17c304`. Letti skill `agentic-workflow`, `references/runtime.md` e i tre documenti tecnici obbligatori; nessun AGENTS applicabile trovato. Nessuna consultazione di review, voti o comunicazioni dell’altro reviewer.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 — Vincoli | 9 | Verificato documentalmente | Vincoli e indisponibilità del telefono espliciti; impostazione AVRCP, widget, helper e modifiche LP non vengono promossi a soluzione. [Requisiti e distinzioni](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/docs/airpods-coexistence-evidence.md:8>). | Nessuna violazione confermata. |
| R2 — Copertura tecnica | 9 | Verificato staticamente, con campionamento delle fonti | Distinte identità/capacità, batterie nullable/fresche, modalità, CA configurata e attività vocale. Corrette le distinzioni consumer/raw metadata, OEM/AOSP, RX/ottimismo. [Contratto Samsung](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/samsung-metadata-contract/REPORT.md:36>). | Nessun errore materiale confermato; accesso OEM e comportamento remoto rimangono NV dichiarati. |
| R3 — Esclusioni e controesempi | 8 | Verificato nel campione esaminato | Esclusioni delimitate; RFCOMM non assimilato al raw AACP, GATT ordinario distinto dal manager HAP privilegiato, due Register non promossi a due canali. Condizioni di riapertura esplicite. [Registro dei candidati](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/docs/airpods-coexistence-evidence.md:32>). | Nessuna falsa esclusione confermata. Ricerca non esaustiva di ogni firmware/API OEM. |
| R4 — Provenienza e riproducibilità | 9 | Controlli propri eseguiti | Integrità verificata su 771 file e sette input; riprodotti inventario APK, verifica Samsung e audit RFCOMM. Risultati BLE riprodotti in memoria. Provenienza comparativa dei decompilati correttamente dichiarata. | Nessuna discrepanza materiale. Hash non equivalgono ad autenticità Samsung o corrispondenza alla build target. |
| R5 — Integrazione e decisione | 8 | Verificato documentalmente | P1/I3/G1 separati; prodotto AirPods invariato nel delta; terzo giro conservato; nessuna UI o scelta esclusiva anticipata. [Decisione aperta](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/docs/airpods-coexistence-evidence.md:158>). | Nessun blocco documentale confermato. Nessun candidato oggi completo da integrare. |
| CP1.b — Gate fisico | **NV** | Non verificabile nel mandato | Mancano prove contemporanee e durature delle due app, convergenza RX, audio/gesti/CA e lifecycle. | Gate aperto; nessun voto numerico sostitutivo. |

**Controlli propri eseguiti.**

- Ricalcolati dimensioni e SHA256 di tutti i **771 file e sette input esterni**: nessuna difformità.
- Confrontati soltanto manifest storico C3 e manifest/delta C4: confermati sette nuovi file di contenuto, tre cambiati e rimozione del vecchio indice generato. Il confronto grezzo comprende inoltre il nuovo `changes-from-C3.json`. Nessun delta prodotto AirPods.
- Eseguito `inventory.py --check --apk …`: **33 DEX, nove coppie owner/nome Bluetooth, positivo connect e due negativi passati**.
- Eseguiti `verify_local_cache.py` Samsung e `audit_contract.py` RFCOMM: controlli passati.
- Verificati autonomamente SHA256 e Git blob delle **22 copie primarie BudsLink**.
- Importato il parser BLE senza bytecode e chiamata soltanto `analyze()` in memoria: risultati serializzati identici ai salvati per entrambe le catture; **870 record, sette proximity plaintext, dodici frame modalità, zero frame CA nel formato fisso cercato**, nessun problema di reassembly riportato.

Non ho rieseguito le nove prove parser, le 56 asserzioni BudsLink o l’emulazione Register: restano risultati degli autori/root, distinti dai miei controlli. Nessuna scrittura, build Android, esecuzione dei sorgenti acquisiti o operazione su dispositivi.

**B-01: correzione confermata.** Nel [consumer S10, righe 232–266](</C:/VibeCoded Projects/Fluidify/build/review-C4-coexistence/source/build/airpods-8h/samsung-metadata-contract/sources/S10-BluetoothDeviceBatteryManager.java:232>), le letture `semGetMetadata` precedono il ramo di aggregazione. Con entrambi i livelli validi e differenza ≤15, il consumer conserva il minimo e pubblica entrambi i laterali come −1. La derivazione statica **80/90 → 80, −1, −1** è corretta; anche la soglia è inclusiva. Non dimostra perdita nelle letture raw, comportamento hardware o supporto AirPods.

**B-02: correzione confermata nel perimetro richiesto.** Nei tre documenti obbligatori non ho incontrato voti o verdetti delle ricerche precedenti. Restano requisiti, dati, limiti, riferimenti ai congelati e contesto software I3/G1 consentito. Le soglie del mandato sono presentate come regole. La verifica non richiede di aprire gli archivi originali, che non ho consultato.

**Distinzione OEM/AOSP e provenienza.** Lo smali Samsung conferma `semGetMetadata([B)[B` e `semSetMetadata([B)Z`; il getter AOSP esaminato usa invece chiave intera e impone `BLUETOOTH_PRIVILEGED`, senza l’eccezione CDM dell’alias. Il fascicolo evita correttamente di trasferire tale enforcement ai metodi OEM. Le dieci copie Samsung sono decompilati terzi identificati per blob/hash: il commit del 2024 non certifica ogni APK né il target Android17. Plugin Messenger Galaxy Buds, stato SystemUI e accesso di un’app ordinaria restano piani distinti.

**Tentativi di falsificazione.**

- **IPC LP più letture alternative:** il servizio stock offre comandi, ma il `LocalBinder` non espone remoting dei getter e i broadcast pertinenti sono limitati al package LP. Aggiungere batterie BLE o cache Samsung non colma il readback di modalità e CA configurata.
- **BudsLink come trasporto alternativo:** configurazioni Pro2/Pro3 distinte, ma stesso UUID/handler AACP. La risoluzione SDP BlueZ non prova RFCOMM e non offre un endpoint LP Android. Il callback CA immediato dopo invio resta ottimistico.
- **GATT/HAS:** il normale GATT non è escluso dal requisito privilegiato del manager HAP. Mancano però endpoint AirPods attestato, mapping completo mode/CA, bootstrap, identità e batterie fresche.
- **Recupero risorse proprie:** l’inventario APK limita soltanto i riferimenti SDK selezionati. Non misura socket/RCB e non esclude reflection, JNI, codice dinamico o processi esterni. Non emerge una risorsa aggiuntiva documentata da rilasciare.
- **Handshake alternativo:** non corregge retroattivamente il noRCB precedente all’handshake. Nessuna estensione GAP/JV eseguita.

La qualità dell’analisi soddisfa la rubrica nel perimetro controllato; **l’avanzamento prodotto resta separato**. Nessun candidato dispone della catena completa endpoint → apertura → bootstrap → TX/RX → freschezza → permessi → background. CP1.b resta NV e questa review non chiude la finestra fino al **2026-11-08 03:52:42 UTC**, né autorizza anticipatamente UI o uso esclusivo.
