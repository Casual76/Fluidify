**Reviewer D — GPT-6.1 Sol, reasoning high.** Fase IMPLEMENTAZIONE, giro CORRETTIVO1, versione I1 stabile. **Esito: correzioni richieste su I2; nessuna approvazione complessiva.** Conformance prodotto resta **FALSE**, H1 **NV**.

Review effettuata sul congelato richiesto. Nessun subagente, build, operazione device, modifica ai sorgenti o consultazione di review storiche. Nessun esito altrui incontrato; i riferimenti storici presenti nei documenti obbligatori non sono stati usati come conclusioni.

| Criterio | Voto | Stato verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| I1 Completezza funzionale | NV | Esame parziale | Letti catalogo normativo, coverage e registro dei controlli; esaminati editor, identità, gesti e audio ordinario. Non completata la riconciliazione indipendente di tutte le 35 famiglie, 67 preferenze e 41 esclusioni con upstream attivo. | Completezza complessiva non certificata. |
| I2 Home/player/quick/full | **7** | Statica approfondita, immagini e test archiviati | Home reale con wordmark, avatar/friends e inset; quick condiviso, footer finito, navigazione completa e ingresso signed-out presenti. Confermati quattro difetti sotto. | D1–D4, **media**. |
| I3 Lifecycle e ownership | **8** | Statica e corpi test; runtime NV | OFF separato dai prerequisiti ON; stamp coppia/consenso/generazione verificati prima del dispatch. Observer con reset dopo rifiuto, dedup pending e rilascio callback dopo close. Refresh 1 s osservativo. Esaminati gate C4 e ingresso companion differito. | Nessun difetto confermato nei percorsi esaminati; affidabilità Android/OEM NV. |
| I4 Protocollo/stato/capacità | NV | Campionamento | Esaminati proprietà tipizzate, distinzione observed/candidate, batterie correnti, coppie head tracking e vettori wire. Mancano audit completo di decoder, concorrenza e tutte le transizioni. | Nessun voto complessivo giustificato. D2 interessa anche la presentazione dello stato. |
| I5 Automazioni/media | NV | Campionamento significativo | Letti percorsi di autorizzazione e ricontrollo accodato, distinzione ricevute locali/C6 e test correttivi su volume, revoca e collisione receipt. Non verificata integralmente la matrice C5/C6 e chiamate. | Software complessivo non certificato; effetti fisici NV. |
| I6 Validazione/artefatti | **8** | Controlli host propri e archivi parent | Confermati XML, firme, ZIP16KB, ELF e class_defs selezionate. Limiti dei checker e gate FALSE correttamente esplicitati. | D1 rivela un limite delle assertion geometriche; nessuna prova di linkage completo o runtime. |
| I7 Confini/provenienza | **8** | Statica, perimetro esaminato | Attribuzione GPL e revisione pinned presenti; materiale Engine esistente, nessun upgrade. Percorsi ordinari e fallback Settings distinti da privilegi esclusi. | Nessuna violazione confermata nel codice esaminato; parità upstream integrale non certificata. |
| I8 Integrazione/preservazione | **8** | Statica e regressioni archiviate | Wiring Application→owner/media, Settings, Home/player, portale unico e ingresso onboarding esaminati. Contesti condivisi confrontati tramite manifest I0/I1 risultano invariati. | Continuità decoder, interazioni complete e preservazione comportamentale runtime NV. |
| H1 Gate fisico standalone | **NV** | Non eseguibile né autorizzato | Nessun telefono interrogato. Mancano le tre sessioni fisiche richieste. | Gate prodotto ancora aperto, senza penalità numerica hardware. |

Tutti i riferimenti seguenti sono relativi alla cartella `source/` del congelato.

**D1 — Batteria della capsula tagliata con font aumentato. Gravità media.**

- **Trigger:** Home 360×640, font 1,5 o 2; riscontro anche nella fixture 840 con friends/inset.
- **Posizione:** [AirPodsControls.kt:85](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/AirPodsControls.kt:85>), corpo alle righe 89–113.
- **Evidenza:** nelle immagini archiviate `home-360-f1_5.png`, `home-360-f2.png` e `home-840-f2-friends-inset240.png` il valore batteria risulta tagliato. `AirPodsHomeEntryTest.kt:64–74` verifica posizione e dimensioni dei bersagli, senza verificare la leggibilità del testo.
- **Conseguenza:** il layout supera il controllo geometrico mentre il dato mostrato non è leggibile. Il piano, riga 269, richiede di ridurre a icona quando manca spazio.
- **Fix minimo:** misurare lo spazio utile e omettere integralmente il testo quando non entra, mantenendo i due target da 48 dp.
- **Regressione:** assertion di layout del testo o confronto visivo mirato a font 1/1,5/2, con spazio minimo e friends presenti.

**D2 — Il minimo batteria include la custodia senza identificarla. Gravità media.**

- **Trigger:** batterie fresche sinistra 80%, destra 70%, custodia 5%.
- **Posizione:** [AirPodsControls.kt:368](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/AirPodsControls.kt:368>), in particolare righe 370–376.
- **Evidenza:** `known` comprende tutti i `batteryComponents`; con più componenti viene visualizzato il minimo globale, quindi **5%** senza indicazione custodia.
- **Conseguenza:** il valore può essere interpretato come autonomia degli auricolari, contrariamente al minimo auricolari richiesto dal piano.
- **Fix minimo:** calcolare il minimo sui soli auricolari; se si presenta un fallback custodia, identificarlo esplicitamente.
- **Regressione:** casi custodia inferiore agli auricolari, singolo auricolare noto, sola custodia e componenti stale. È una deduzione diretta dal corpo della funzione, non un’esecuzione UI mia.

**D3 — Le righe indisponibili non spiegano il prerequisito concreto mancante. Gravità media.**

- **Trigger:** assenza NLS per OTHER_APPS; feature companion assente; ENABLE bloccato dal gate prodotto.
- **Posizione:** [AirPodsHost.kt:107](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/host/AirPodsHost.kt:107>), disponibilità alle righe 108–143.
- **Evidenza:** condizioni distinte convergono su `airpods_host_requirements`: “Richiede coppia selezionata e verificata, permessi attuali e gate di prodotto pertinenti.” `SetupButton` rimuove correttamente l’hint dalle azioni disponibili, ma per quelle indisponibili riceve questa spiegazione generica. Il nuovo test controlla l’esistenza della ragione, non la sua pertinenza.
- **Conseguenza:** l’utente non può individuare quale requisito risolvere; manca la motivazione concreta richiesta.
- **Fix minimo:** produrre ragioni distinte dai fatti già disponibili, mantenendo OFF revocabile.
- **Regressione:** matrice della ragione visualizzata per NLS, feature companion, Bluetooth e gate prodotto, oltre al caso disponibile senza hint.

**D4 — Quick panel privo dello stato automazioni richiesto. Gravità media.**

- **Trigger:** apertura quick con automazioni abilitate, disabilitate oppure bloccate.
- **Posizione:** [AirPodsControls.kt:154](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/AirPodsControls.kt:154>).
- **Evidenza:** il corpo contiene batterie, errore AACP, modalità, preferenza, CA hardware e volume. Non presenta lo stato automazioni previsto dal piano alle righe 275–277.
- **Conseguenza:** dal quick non si distingue CA hardware attiva da automazione host operativa o bloccata.
- **Fix minimo:** aggiungere un riepilogo osservativo di automazioni, background e consenso altre app, con eventuale causa di blocco.
- **Regressione:** fixture ON/OFF/bloccato; footer ancora raggiungibile a font 2.

**Verifiche e limiti delle evidenze.** Ho eseguito personalmente i controlli host registrati in [host-checks.json](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-D/host-checks.json>):

- XML archiviati: **586 test/67 suite**, zero failure/error/skip; separatamente **168 storici core/Wear**, anch’essi senza errori. Ho analizzato gli XML, non rieseguito questi test.
- Tre APK: firme v2 valide e ZIP16KB validi.
- Debug: **302/67/10/27** classi compilate selezionate presenti come definizioni DEX.
- ELF phone: **14 debug/7 minified** con LOAD conformi al controllo 16KB; classe JNI `AirPodsSocket` presente nel minified.
- Manifest I0: debug e minified diversi da I1, Wear invariato. Nessun report I0 consultato.

I 27 mirati e i fallimenti iniziali/collisione receipt sono descritti in `I1-integration.md`; nel congelato non ho trovato i relativi log/XML separati, quindi non li presento come controlli raw verificati da me. Le build riuscite sono evidenza parent archiviata.

Le fixture full settings montano **il corpo**, non l’intero host di produzione; il test signed-out sostituisce la pagina settings con contenuto sintetico. Engine theme/page/navigation sono ora ispezionabili e il materiale usato è reale, ma ciò non certifica contrasto completo, Samsung, motion o decoder. Gate FALSE consente inoltre eliminazioni R8: questi controlli non qualificano un futuro APK abilitato.

**Integrità finale riconfermata:** manifest SHA256 `51627c5cb141cabccf85d95ca24f2430cf11bccbcd72c22d21e9bd0b5d198dac`; **1166/1166 file senza mismatch**. Anche screenshot privato esterno conforme per hash e dimensione, consultato senza copiarlo o pubblicarlo.
