# Review C3: nuove evidenze di convivenza

Fase **RICERCA**, giro **CORRETTIVO2**, versione **C3**. A e B: **GPT-6.1 Sol,
reasoning high**, nuovi reviewer rispetto ai giri precedenti.

Snapshot `build/review-C3-coexistence`, manifest SHA256
`837eab7b1e41e9e9baab6abf353ca9107938486e86de076bdac16156295cb2c9`.
Entrambi hanno verificato 764 file e sette input esterni, distinguendo prove proprie e degli autori.

| Criterio | A | B | Media | Minimo | Esito e problemi aperti nel congelato C3 |
|---|---:|---:|---:|---:|---|
| R1 — vincoli | 9 | 9 | 9 (2/2) | 9 | Nessuna violazione confermata |
| R2 — copertura tecnica | 9 | 8 | 8,5 (2/2) | 8 | B-01 basso: precisare perdita L/R nel consumer Samsung |
| R3 — esclusioni e controesempi | 9 | 9 | 9 (2/2) | 9 | Nessuna esclusione universale indebita individuata |
| R4 — provenienza e riproducibilità | 9 | 9 | 9 (2/2) | 9 | Risultati e limiti verificati nel perimetro |
| R5 — integrazione e decisione | 8 | 8 | 8 (2/2) | 8 | B-02 basso: giudizi precedenti esposti nel documento obbligatorio |
| CP1.b — gate fisico | NV | NV | — (0/2) | — | Nessuna prova completa di convivenza; prodotto non sbloccato |

Aggregati solo sui voti verificati comparabili dello stesso giro/versione. NV escluso,
non convertito in zero. Nessun voto dell'orchestratore sostituisce quelli individuali.

**Limite procedurale:** entrambi hanno incontrato riepiloghi C1/C2 nei documenti
integrati obbligatori, dichiarando di non averli usati. Non si presenta quindi questo
giro come privo di esposizione ai giudizi storici. B-02 corregge il fascicolo per i
reviewer successivi; non cambia retroattivamente C3 o quanto i reviewer hanno letto.

**Decisione:** giudizi tecnici favorevoli, senza candidato completo trascurato o
difetto materiale confermato. Root ha applicato le due integrazioni minori per C4:
B-01 esplicita il ramo sorgente L80/R90→aggregato80,L/R=-1; B-02 rimuove giudizi e
voti precedenti dai documenti tecnici obbligatori, lasciando intatti gli archivi
storici. C4 includerà anche l'inventario statico dell'APK I3, successivo a C3.
È il terzo giro dopo l'iniziale della ricerca, senza reset del contatore.

Nessuna modifica prodotto, operazione dispositivo o aggiornamento LP. G1/CP1.b
restano NV e la finestra termina **2026-11-08 03:52:42 UTC**. Non è una decisione
finale anticipata sull'uso esclusivo.

Review A: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3Af6131732-ec4e-4792-abfd-055108cd8e7f>

Review B: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A91b51f5b-03c0-459e-9cdc-35e9ab004388>

## Report integrale A

**Reviewer A — GPT-6.1 Sol, reasoning high. Fase RICERCA, giro CORRETTIVO2, versione C3.**

**Giudizio sulla ricerca: favorevole.** Non ho individuato un errore materiale nella sintesi né una strada completa trascurata nei percorsi approfonditi. **Avanzamento prodotto: non autorizzato da questa review; CP1.b resta NV.** Le evidenze non dimostrano ancora convivenza completa e duratura.

Ho letto `agentic-workflow/SKILL.md` e `references/runtime.md`; nessun AGENTS applicabile trovato nei percorsi controllati. Nessun subagente avviato. Non ho aperto report di altri reviewer; i documenti integrati richiesti contengono riepiloghi C1/C2, comparsi durante la lettura: li ho esclusi dalla valutazione.

Tutti i riferimenti abbreviati sotto sono relativi al congelato `build/review-C3-coexistence/source/`.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificato documentalmente e per le operazioni proprie | Mandato e matrice mantengono LP invariata/attiva, divieti e indisponibilità del telefono. Ho eseguito soltanto letture, hash e controlli host in memoria. Nessun delta nei file prodotto AirPods confrontati tramite manifest. | Nessun difetto confermato. Non certifico retroattivamente tutte le operazioni degli autori. |
| R2 | 9 | Verifica approfondita BLE/BudsLink; campionamento OEM/RFCOMM | Confermati conteggi, origine temporale, frame TX/RX e insufficiente copertura BLE. BudsLink distingue configurazioni modello ma mantiene UUID/handler comuni; parsing ricevuto e aggiornamenti ottimistici sono correttamente separati. | Nessuna lacuna materiale nella sintesi. Endpoint alternativo completo, identità affidabile, freschezza e background restano non dimostrati. |
| R3 | 9 | Verificato nel perimetro esaminato | Le esclusioni sono circoscritte: assenza di mapping nei campioni e nei decoder esaminati, non impossibilità universale. Le condizioni di riapertura richiedono differenze osservabili. | Nessun blocco. La mancata prova di un percorso non viene trattata come difetto software. |
| R4 | 9 | Integrità verificata; riproduzione locale e controllo semantico | SHA256 del manifest conforme; **764/764 file e 7/7 input esterni** conformi per dimensione/hash. Cinque Git blob degli input pubblici verificati. Risultati BLE riprodotti tramite funzioni pure; audit RFCOMM e cache Samsung passati. | Provenienza remota e acquisizioni online restano quelle degli autori: non ho effettuato nuovi fetch. |
| R5 | 8 | Verificato documentalmente | Piano, mandato e matrice distinguono ricerca, I3 storico e gate fisico; preservano ownership, scadenza e contatori. Nessuna promozione anticipata a UI o ripiego esclusivo. | Decisione finale ancora aperta; nessun candidato completo pronto per integrazione. Non è un difetto del fascicolo. |
| CP1.b | **NV** | Non verificato | Nessun telefono disponibile. Non provate ricezioni complete nelle due app, convergenza, audio/gesti/CA e durata attraverso il lifecycle. | Gate prodotto aperto; nessun voto numerico sostitutivo. |

**Verifiche proprie su BLE.** Ho letto il parser e confrontato i campi con le copie Wireshark/CAPod fissate. Ho chiamato esclusivamente le funzioni pure, senza `main()` o runner che scrivono nel congelato.

- Riprodotti **510 + 360 record**, **74 + 41 annunci ricevuti completi**, **5 + 2 proximity plaintext**, **141 + 81 PDU**, **8 + 4 frame modalità**, zero frame CA configurata nel formato fisso cercato e zero anomalie di ricostruzione.
- I risultati coincidono con quelli salvati dopo normalizzazione JSON. La differenza iniziale nel confronto degli oggetti Python riguardava esclusivamente chiavi numeriche convertite in stringhe dalla serializzazione JSON.
- Verificati separatamente avanzamento dei record fino a EOF e byte ai sette offset proximity; tutti i 25 byte restano identici entro ciascun gruppo locale.
- Superati in memoria sei casi malformati, ricostruzione esatta di un frame modalità frammentato e otto controlli di troncamento. Non sono test hardware.

Il tempo zero è correttamente il primo record HCI: record12/offset563 e record13/offset614. Le note iniziali non diventano durata sperimentale. Le etichette A1/A2 esprimono uguaglianza dell’indirizzo nel singolo file: **non provano identità del dispositivo né collegamento al peer ACL**.

La conclusione decisiva regge: nella cattura mode i plaintext successivi a uno stato ricevuto noto seguono Off; mancano plaintext successivi ad Adaptive/ANC e fra RX Transparency e TX Off. Nella seconda cattura precedono entrambi il primo cambio. La costanza dei payload non può quindi escludere una codifica delle modalità. Vedi [REPORT BLE, riga63](<C:/VibeCoded Projects/Fluidify/build/review-C3-coexistence/source/build/airpods-8h/ble-mode-contract/REPORT.md:63>).

I dodici frame distinguono correttamente TX e RX; i sei RX corrispondono ai valori inviati. Questo sostiene un evento/eco AACP ricevuto, senza dimostrare conferma acustica, readback BLE o comportamento Samsung/Pro3. Lo zero CA riguarda `09 00 28 <value32>` nei PDU esaminati: non tutto il firmware. Le occorrenze `4B` sono correttamente escluse dalla conferma di CA configurata.

**BudsLink e ricerca di un percorso diverso.** Ho verificato direttamente:

- Pro2 Lightning `2014`, Pro2 USB-C `2024` e Pro3 `2027` hanno configurazioni distinte, ma confluiscono nello stesso `AirpodsSocket`, UUID `74ec2172-0bad-4d01-8f77-997b2be0722a`: [selezione e costruzione](<C:/VibeCoded Projects/Fluidify/build/review-C3-coexistence/source/build/airpods-8h/budslink-contract/sources/src/lib/devices/airpods/airpodsDevice.js:86>).
- `RegisterProfile` non specifica PSM/Channel. BlueZ risolve SDP; `connect_io()` sceglie L2CAP quando trova un PSM. Il nome “Profile” non dimostra RFCOMM.
- Il numero `0x1001` proviene dall’implementazione upstream accreditata, non da una misura SDP BudsLink Pro3. Il fascicolo conserva questa distinzione.
- Esistono parser RX reali per mode e CA; tuttavia default/deduplicazione possono sopprimere il primo Off e `setAwarenessMode()` pubblica immediatamente un aggiornamento dopo l’invio. Batterie `FF→0/disconnected`, merge e assenza di timestamp per componente non soddisfano automaticamente nullable/freschezza.

Non emerge quindi una diversa catena completa apertura → endpoint → bootstrap → TX/RX → stato fresco → permessi Android ordinari → background con LP attiva. BudsLink offre evidenza implementativa AACP, non il bypass del problema prehandshake.

**Campionamento OEM/RFCOMM.** Ho eseguito i due controlli readonly autorizzati e letto i sorgenti pertinenti. Il getter AOSP impone `BLUETOOTH_PRIVILEGED` senza eccezione CDM; questo risultato non viene trasferito a Samsung. Smali e Java comparativi confermano invece firme `semGetMetadata([B)[B` e `semSetMetadata([B)Z`. Nel consumer Samsung null/corto confluisce in false e Off deriva dalla negazione dei tre stati: non è un contratto di stato remoto fresco. Enforcement OEM, mapping AirPods/CA e background ordinario restano NV.

**Rilievi correttivi: nessuno confermato.** Non attribuisco gravità software alle prove mancanti già dichiarate. Il delta registrato contiene100 aggiunti,2 cambiati,1 rimosso; il confronto fra manifest conta101 aggiunte includendo lo stesso `changes-from-C2.json`. La differenza è spiegata dall’indice, senza rimozione prodotto.

La ricerca può proseguire entro la finestra assegnata sulle discriminanti documentate. Questa review non chiude la finestra, non anticipa il ripiego esclusivo e non modifica il gate fisico.


## Report integrale B

La sintesi C3 resiste ai tentativi di falsificazione nel perimetro esaminato: **non ho individuato una strada completa trascurata**, né una prova di impossibilità universale della convivenza. Il percorso Samsung resta incompleto soprattutto fra chiamante SystemUI, enforcement OEM, endpoint AirPods e provenienza remota degli aggiornamenti.

Reviewer **B**, modello effettivo **GPT-6.1 Sol, reasoning high**. Fase **RICERCA**, giro **CORRETTIVO2**, versione **C3**. Nessun subagente avviato.

Ho letto `agentic-workflow/SKILL.md` e `references/runtime.md`; non ho trovato AGENTS applicabili. Ho operato senza rete, dispositivi, build, installazioni o scritture. Non ho aperto altre review. Tuttavia, il documento integrato obbligatorio contiene alle righe 162–165 un riepilogo dei voti C2, incontrato durante la lettura: non l’ho usato per assegnare i voti seguenti, ma segnalo questa esposizione come limite all’indipendenza richiesta.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificato documentalmente | Mandato, matrice e piano mantengono LP invariata/attiva, divieti e indisponibilità del telefono. Nessun percorso parziale viene promosso a prodotto. | Nessuna violazione sostanziale individuata nei materiali esaminati. |
| R2 | 8 | Verifica statica diretta; hardware NV | Confermate firme OEM distinte, separazione metadata/Messenger, assenza di conferma remota nel client e limiti su identità, batterie, modalità, CA e background. Campionati BLE/BudsLink. | B-01, bassa: manca nel report Samsung un’ulteriore trasformazione distruttiva dei dati L/R. |
| R3 | 9 | Verificato nel perimetro delle fonti congelate | RFCOMM non viene escluso attraverso il percorso raw AACP; CDM non viene trasferito impropriamente ai metadata; catalogo Galaxy Buds e catture Pro2 non diventano esclusioni universali AirPods. Condizioni di riapertura concrete. | Nessun difetto materiale individuato. Restano endpoint ed enforcement sconosciuti. |
| R4 | 9 | Integrità verificata; riproduzione selettiva | Manifest, 764 file e sette input esterni corrispondono. Verificati dieci blob Samsung; audit RFCOMM passato; risultati BLE riprodotti in memoria; 56/56 asserzioni BudsLink ripetute senza scritture. | Hash confermano le copie, non autenticità degli APK o appartenenza alla build target. |
| R5 | 8 | Verificato documentalmente | Conservati separazione P1/I3/G1, contatori, termine assegnato e decisione ancora aperta. Nessuno sblocco UI o ripiego anticipato. | B-02, bassa sul fascicolo: il documento obbligatorio espone giudizi precedenti ai nuovi reviewer. |
| CP1.b | **NV** | Non verificato fisicamente | Mancano prove contemporanee e durature di entrambe le app, RX completi, audio/gesti/CA e lifecycle sul target. | Non sostituibile con audit statici o qualità della ricerca. |

I riferimenti seguenti sono relativi a `build/review-C3-coexistence/source/build/airpods-8h/`, salvo indicazione diversa.

**Verifiche proprie.** Il SHA256 del manifest coincide con `837eab7b1e41e9e9baab6abf353ca9107938486e86de076bdac16156295cb2c9`. Dimensioni/hash dei **764 file** e dei **sette input esterni autorizzati** corrispondono; il controllo finale non rileva alterazioni. Ho letto il delta dichiarato: 100 aggiunti, due documenti cambiati, rimozione del solo indice `changes-from-C1.json`; non ho usato sorgenti live come equivalenti al congelato.

Ho eseguito i due verificatori readonly Samsung/RFCOMM. Per BLE ho chiamato la funzione pura `analyze()` in memoria: entrambi i risultati coincidono integralmente con il JSON salvato dopo la normale conversione delle chiavi JSON. Confermati 510/360 record, 74/41 annunci, 5/2 plaintext, 8/4 frame modalità e zero frame CA configurata nel formato esaminato. Ho ripetuto le 56 asserzioni BudsLink sopprimendo in memoria la sola scrittura del risultato. **Non ho rieseguito i nove controlli parser**, l’emulazione Register o alcuna GAP/JV: i relativi esiti restano attribuiti ad autori/root.

Sul focus Samsung, i riscontri diretti sono questi:

- **Caller ed enforcement.** S2 conferma `semGetMetadata([B)[B` e `semSetMetadata([B)Z` alle righe 485/857/953/1049 e 1276/1336/1399/1429/1459/1513. Sono firme diverse da AOSP. Il chiamante SystemUI non dimostra né accessibilità ordinaria né un requisito signature del metodo OEM: entrambi restano da verificare nel server.
- **Metadata e ricezione.** S1:182–268 legge capability e booleani; valori assenti/corti confluiscono in false e possono produrre Off. Le capability conservate nel manager non vengono sempre invalidate. S1:28–43 rilegge metadata dopo un broadcast, ma non dimostra che questo segua un RX radio anziché una mutazione locale. S1:276–308 invia senza attendere conferma.
- **Messenger.** S6:28–40,72–77 mostra bind esplicito, invio e unbind. S7:57–103 distingue richiesta 1001 e risposta locale 1002; S8:47–51 invia 1003 senza ACK visibile. Mancano manifest/enforcement e handler del plugin. Una risposta JSON locale non prova freschezza degli auricolari.
- **Ambito prodotto.** S3 contiene plugin Galaxy Buds; S5 inizializza anche il percorso metadata indipendentemente dall’esito del ramo plugin. Quindi il catalogo non basta a escludere universalmente AirPods da `sem*`. S9:14–53 non offre identità AirPods, timestamp, batterie o CA configurata; `voiceBoost` non colma quest’ultima lacuna.
- **Provenienza.** I dieci blob locali corrispondono al materiale dichiarato. Restano decompilati pubblicati da terzi: commit 2024, intestazioni DEX e corrispondenza Java/smali non attestano la build S25 Android17. Non ho verificato nuovamente online le fonti ufficiali citate dall’autore.

Per **RFCOMM/CDM**, `rfc_l2cap_if.cc:56–79` registra il profilo RFCOMM allo startup; il percorso applicativo usa risorse proprie. Questo mantiene aperta una differenza concreta rispetto alla registrazione raw AACP, senza provare disponibilità Samsung. In `AdapterService.java:3743–3767`, getter/setter metadata impongono `BLUETOOTH_PRIVILEGED`; l’eccezione CDM compare invece nell’alias, righe 2993–3013. È una conclusione sul ramo AOSP fissato, non sui metodi OEM.

BudsLink non completa quella strada: il medesimo UUID/handler AirPods e la risoluzione SDP BlueZ non dimostrano RFCOMM, né fissano nel client un PSM numerico. Il codice contiene RX reali mode/CA, ma anche aggiornamenti ottimistici, default Off e trattamento `FF` come zero/disconnected. Le catture BLE non collegano il peer advertising al peer ACL e non attraversano più stati ricevuti noti dello stesso gruppo: la loro insufficienza è correttamente circoscritta.

I rilievi sono documentali, senza difetti software Fluidify dimostrati:

1. **B-01 — perdita dei valori laterali nel consumer batteria Samsung. Gravità bassa.**  
   Trigger: entrambi i livelli validi e differenza assoluta ≤15. In [S10:249](<C:/VibeCoded Projects/Fluidify/build/review-C3-coexistence/source/build/airpods-8h/samsung-metadata-contract/sources/S10-BluetoothDeviceBatteryManager.java:249>), il consumer conserva il minimo come aggregato e imposta entrambi i livelli laterali a `-1`, poi li pubblica alle righe 263–266. Il report Samsung:75–79 descrive letture e sentinel, ma omette questa trasformazione. Conseguenza: persino una cache fresca di quel consumer può non conservare L/R separati; non sarebbe corretto ricostruirli dall’aggregato. **Fix minimo:** aggiungere il limite e il controesempio statico L=80/R=90 → aggregato 80, L/R=-1. Non serve hardware e non cambia la conclusione generale.

2. **B-02 — giudizi precedenti nel documento obbligatorio. Gravità bassa per la ricerca, rilevante per l’indipendenza procedurale.**  
   Trigger: lettura della sezione finale della [matrice integrata:160](<C:/VibeCoded Projects/Fluidify/build/review-C3-coexistence/source/docs/airpods-coexistence-evidence.md:160>). Le righe successive riportano esito e voti aggregati C2, mentre il brief vieta di leggerli. Conseguenza: il fascicolo espone inevitabilmente il reviewer a un giudizio precedente. **Fix minimo:** nelle successive copie per review conservare manifest/delta e stato storico, omettendo voti e verdetti precedenti. Non modificare retroattivamente i congelati.

**Giudizio di ricerca:** favorevole, con le due integrazioni minori indicate. Non ho trovato un candidato positivo completo. RFCOMM conserva una differenza di apertura interessante, ma mancano endpoint AirPods e bootstrap; Samsung conserva un possibile percorso gestito, ma mancano enforcement ordinario, mapping completo e RX fresco. Per entrambi restano inoltre da dimostrare identità/capacità, batterie nullable, modalità, CA configurata e background con LP attiva.

**Avanzamento prodotto:** non autorizzato da questa review. CP1.b/G1 restano **NV**; nessun PASS hardware, nessuno sblocco UI e nessuna scelta anticipata dell’uso esclusivo. La decisione resta quella prevista dal mandato alla scadenza del **2026-11-08 03:52:42 UTC**.
