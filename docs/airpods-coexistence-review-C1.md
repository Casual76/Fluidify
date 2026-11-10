# Review C1 — ricerca convivenza

Fase RICERCA, giro INIZIALE, snapshot C1. Reviewer A e B: GPT-6.1 Sol, effort high.
Entrambi indipendenti dagli autori. Questi voti valutano la ricerca, non hardware o UI.

| Criterio | A | B | Media | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---|
| R1 — Vincoli | 9 | 9 | 9 | 9 | Solido,2/2 voti |
| R2 — Analisi e copertura | 9 | 8 | 8,5 | 8 | Solido,2/2 voti; funzionalità completa NV |
| R3 — Esclusioni e controesempi | 9 | 9 | 9 | 9 | Solido,2/2 voti; niente impossibilità universale |
| R4 — Provenienza e riproducibilità | 8 | 7 | 7,5 | 7 | Correzione B1 necessaria; A-01 minore |
| R5 — Integrazione e decisione | 8 | 8 | 8 | 8 | Solido,2/2 voti; B2 minore |
| CP1.b — Hardware | NV | NV | — | — | 0/2 voti numerici; gate aperto |

Esito orchestratore: correggere C1 prima dell’approvazione della ricerca. R4 sotto soglia;
nessun avanzamento UI, nessun fallback esclusivo definitivo e nessuna chiusura anticipata
 della finestra di otto ore. I3 e G1 restano invariati.

Rilievi da risolvere senza nuove prove hardware:
- B1, media: aggiungere fonti citate o estratti sanitizzati con hash/revisione/mapping.
- B2, bassa: annotare come superata la proposta storica di estendere il harness GAP/JV.
- A-01, bassa: verificare esplicitamente il PC finale nel runner Register preesistente,
  correggere docstring e verificare il controllo contro l’arresto per budget.

Lo snapshot C1 resta immutato. Correzione successiva C2: primo giro correttivo di ricerca,
separato dai due giri software I3 già consumati. Nessuna nuova prova GAP/JV viene ripresa.

Manifest C1 SHA256 c491434afa6edad886ca71086835a5279bb84833cce029c076fb71272eecf776.

A: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A694a375f-48ef-4791-b34c-2035c441b783>
B: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A1b387401-8c14-4b4e-b5ad-520dc5dac9a6>

## Report A integrale

Reviewer **A — GPT-6.1 Sol, effort high**. Fase **RICERCA**, giro **INIZIALE**, versione **C1**. Applicati [agentic-workflow/SKILL.md](C:/Users/casua/.codex/skills/agentic-workflow/SKILL.md) e `references/runtime.md`.

**La ricerca C1 è solida nel perimetro documentale esaminato. La prova dei due Register è delimitata correttamente; non emerge una soluzione completa trascurata.** Ho un rilievo minore sul controllo di terminazione dello script preesistente. Nessun risultato autorizza PASS hardware, avanzamento UI o chiusura anticipata della finestra.

I riferimenti abbreviati seguenti sono relativi a [source/build/airpods-8h](</C:/VibeCoded Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h>), nello snapshot congelato.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificato documentalmente | Vincoli preservati nella sintesi e nelle piste: nessuna soluzione fondata su widget, modifiche LP, privilegi o telefono disponibile. La scelta AVRCP globale resta distinta da una soluzione accettata: `platform-prerequisite/REPORT.md:76–90`. | Nessuna violazione riscontrata nei materiali esaminati. Permanenza runtime LP non verificata da me. |
| R2 | 9 | Verificato staticamente | Corretta separazione registrazione/canale/RX; IPC LP verificato nei sorgenti; GATT e HAS distinti dall’endpoint AirPods effettivamente necessario. Mode, CA configurata, attività e boost sono trattati separatamente. `stack/REPORT.md:34–55`; `design-challenge/REPORT.md:24–54`; `upstream/report.md:87–104`. | Completezza funzionale e background restano NV, esplicitamente. Nessun errore materiale trovato. |
| R3 | 9 | Verificato sulle esclusioni campionate | Le esclusioni restano riferite a revisioni, percorsi e catture precisi. I sette notify non diventano sette caratteristiche semanticamente identificate; l’assenza di chiamate GATT nel DEX non diventa impossibilità remota. `notify-contract/REPORT.md:28–44`; `gatt-contract/REPORT.md:98–100`. | Nessun candidato positivo completo trovato. Restano legittimamente aperti endpoint sconosciuti e comportamento concorrente del firmware. |
| R4 | 8 | Integrità verificata direttamente; esecuzioni storiche non ripetute | Manifest corrispondente all’hash assegnato; tutti i 643 file e i cinque input esterni corrispondono per dimensioni/SHA256. Confrontate direttamente con l’ELF 439 parole istruzione dei tre disassemblati Register/allocator/finder. | **A-01, bassa:** manca l’asserzione esplicita del PC finale nel runner; dettaglio sotto. |
| R5 | 8 | Verificato documentalmente | P1 mantiene distinti accesso, doppio canale e convergenza; C1 conserva G1 NV, finestra aperta e gate UI. Gli esperimenti successivi richiedono una differenza concreta, senza ripetere connect alla cieca. `upstream/report.md:140–156`; `bip-public/REPORT.md:75–87`. | Nessuna priorità residua garantisce un risultato senza nuovi input pubblici. La verifica dell’ownership concorrente è limitata alle copie congelate. |
| CP1.b | **NV** | Hardware non verificato | Nessuna prova mia di due app con ricezioni complete e durature, audio/gesti/CA e lifecycle. Register sintetici, sorgenti e cattura macOS non soddisfano questo gate. | Gate aperto; nessun voto numerico ricavabile dalle fonti indirette. |

**Controlli svolti da me.** Ho ricalcolato hash e dimensioni senza eseguire collector, confrontato le istruzioni disassemblate con i byte ELF, verificato il noRCB originale a **LF114245**, e ricontato le etichette nella finestra macOS: il risultato coincide con l’artefatto root. Questi conteggi attestano la corrispondenza dell’estratto, senza attribuire semantica mode/CA ai payload.

L’emulazione delle 32.768 maschere e dei Register resta un’esecuzione **dell’autore, ripetuta da root secondo gli artefatti**: non l’ho rieseguita. Non ho eseguito emulazione GAP/JV, build, installazioni o operazioni sul dispositivo. Non ho consultato altre review.

La prova Register sostiene precisamente questo risultato:

- Le **32.768 maschere** riguardano allocatore/finder; i due Register sono verificati negli scenari sintetici con **0, 13, 14 e 15** registrazioni preesistenti.
- Il ramo outgoing cerca una chiave virtuale libera; il ramo di riuso della registrazione incoming aggiorna callback e parametri. Quest’ultimo non offre, da solo, una condivisione tra processi.
- Il successo restituisce registrazioni locali. Restano fuori dalla prova GAP/JV, negoziazione remota, sottoscrizioni, fanout degli eventi e durata in background.

Questa delimitazione è sostenuta sia da [emulate_rcb.py](</C:/VibeCoded Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/stack/emulate_rcb.py:28>) sia da `stack/REPORT.md:38–55`. Non vedo una promozione indebita a prova radio nella sintesi C1.

**Rilievo A-01 — controllo di terminazione incompleto, gravità bassa.**

- **Trigger:** una chiamata emulata raggiunge il limite di 1.000 istruzioni prima del ritorno atteso.
- **File/righe:** [emulate_rcb.py:23](</C:/VibeCoded Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/stack/emulate_rcb.py:23>)–26 e commento alle righe 44–46.
- **Evidenza:** `emu_start(..., count=1000)` è seguito dalla lettura di X0, senza verificare che PC abbia raggiunto `0x10000`. Il commento afferma invece che il ritorno è controllato tramite l’indirizzo di arresto.
- **Conseguenza:** il runner non distingue esplicitamente ritorno normale e arresto per budget. **Non ho evidenza che questo abbia falsato i risultati salvati**, coerenti con i rami disassemblati.
- **Correzione minima:** alla prossima revisione autorizzata dello script, verificare il PC finale e aggiornare la docstring iniziale, rimasta a “two leaf functions” dopo l’aggiunta di Register. Non occorre estendere la prova a GAP/JV.

Le esclusioni R2/R3 reggono ai controesempi esaminati:

- **IPC stock LP:** `LocalBinder.getService()` è locale (`design-challenge/sources/LP-service.kt:219–221`); batterie e ANC usano `setPackage(packageName)` (`:860–865`, `:1822–1826`). I comandi esportati non colmano il canale dati mancante. Questo delimita la revisione sorgente, senza certificare equivalenza integrale con l’APK.
- **GATT/notify/HAS:** disponibilità dell’API, trasporto accessibile e contratto remoto sono tre condizioni separate. Il probe PR716 presume CCCD=`handle+1` e contiene codice di logging, senza pubblicare i relativi RX. Non dimostra ANC/CA, ma nemmeno li esclude universalmente.
- **Risorse/BIP:** il callgraph AOSP lega il listener al profilo/versione locale. Non sostiene una patch Fluidify basata sulla rimozione delle cover; l’equivalente Java Samsung resta non acquisito.
- **Due client AACP:** la possibilità di registrazione resta aperta. Anche superandola, la separazione delle subscription e la convergenza dei due client rimangono incognite indipendenti.

**Verifiche pubbliche aggiuntive, esterne allo snapshot.** Ho riletto online il [servizio LP pinned](https://raw.githubusercontent.com/librepods-org/librepods/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt) e il [probe PR716 pinned](https://raw.githubusercontent.com/librepods-org/librepods/c83e1026a18e9d95d8dfed046d337c19fd7dde22/windows/daemon/src/gatt.rs), senza trovare un contratto aggiuntivo risolutivo.

Ho inoltre esaminato `stuffz/airpods`: usa ancora Classic L2CAP PSM `0x1001`; le note riferiscono anche socket aperto senza report in presenza di un concorrente. È una testimonianza dell’autore, priva lì di una trace dual-client sufficiente: rafforza la necessità di distinguere apertura e ricezione, senza dimostrare un divieto universale. [Documentazione primaria](https://raw.githubusercontent.com/stuffz/airpods/main/docs/l2cap.md).

Per la finestra residua, le discriminanti utili restano **input pubblici concreti**: il dump originale delle declaration/notify citate dalla PR716; il Java/DEX Bluetooth della build Samsung corrispondente; oppure una cattura dual-client con attribuzione dei CID e RX mode/config CA. Nessuno di questi input è oggi attestato come disponibile nello snapshot. Non propongo prove sul telefono adesso.

**C1 è utilizzabile come checkpoint della ricerca, con A-01 non bloccante.** CP1.b resta NV; la decisione finale delle otto ore resta aperta.



## Report B integrale

Reviewer **B — GPT-6.1 Sol, reasoning high**. Fase **RICERCA**, giro **INIZIALE**, versione **C1**. Applicate `agentic-workflow/SKILL.md` e `references/runtime.md`.

La ricerca è approfondita e mantiene correttamente aperta la decisione. Ho rilevato una lacuna concreta nella riproducibilità dello snapshot e una raccomandazione storica da marcare esplicitamente come superata. **Non ho individuato una strada completa trascurata dimostrabile entro i vincoli.** Questo non prova un’impossibilità universale.

Alle **20:44:03 UTC del 7 novembre**, la finestra assegnata è ancora aperta.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | **9** | Verificato documentalmente | Vincoli telefono/LP espliciti; configurazione AVRCP globale mantenuta condizionale; widget, modifiche LP e privilegi esclusi. Nessun workaround promosso automaticamente. | Nessuna violazione operativa osservata nel mio lavoro; non certifico tutte le attività storiche degli autori. |
| R2 | **8** | Verifica statica mirata | Distinzioni corrette fra registrazione, canale, endpoint, RX e conferma; IPC stock, GATT e HAS analizzati con dipendenze esplicite. CA configurata distinta da attività e boost. | Contratti remoti e background restano incogniti dichiarati; nessuna certificazione funzionale. |
| R3 | **9** | Verificato nel perimetro consultato | Controesempi e condizioni di riapertura concreti. Nessuna impossibilità firmware dedotta dal Samsung; assenza dalla cache distinta da assenza universale. | Ricerca pubblica delimitata, senza pretesa di esaustività. |
| R4 | **7** | Integrità completa; provenienza parzialmente verificabile | Manifest conforme, **643/643 file** e **5/5 input esterni** corrispondenti. Riprodotti offset G1, conteggi macOS e controllo strutturale pcapng. | **B1, media:** alcune fonti decisive citate non sono disponibili nel perimetro congelato/autorizzato. |
| R5 | **8** | Verificato documentalmente | P1/I3/G1 conservati; due correzioni software I3 non azzerate; nessun avanzamento UI o esclusivo anticipato. Decisione ancora aperta. | **B2, bassa:** proposta storica di estensione del harness rimasta formulata come prossimo passo. |
| CP1.b | **NV** | Hardware non verificato | Mancano prove fisiche complete di ricezioni concorrenti, convergenza mode/CA, audio/gesti e lifecycle. | Non sostituibile con Register sintetico, capture pubbliche o qualità del dossier. Nessuna prova sul telefono richiesta ora. |

**B1 — Fonti citate fuori dal perimetro riproducibile C1. Gravità media.**

- **Trigger:** tentare di verificare esclusivamente dai materiali autorizzati le spiegazioni sul costruttore Java, sull’errore app e sulla baseline LP.
- **File/righe:** [client-resource-review.md:10](/C:/VibeCoded%20Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/root/client-resource-review.md:10), righe 10–12; [error-observability.md:5](/C:/VibeCoded%20Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/root/error-observability.md:5), righe 5–10; [stack/REPORT.md:16](/C:/VibeCoded%20Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/stack/REPORT.md:16) e riga 74.
- **Evidenza:** `BluetoothSocket.java`, `versions.txt` e `before-fluidify-bluetooth.txt` citati non figurano nello snapshot né nei cinque input esterni autorizzati. Esiste una copia AOSP di `btif_sock_l2cap.cc` sotto `stack/aosp/`, ma il riferimento root punta alla raccolta diagnostica originale senza una corrispondenza esplicita nel documento.
- **Conseguenza:** gli hash del congelato sono corretti, ma non rendono autonomamente verificabili tutte le affermazioni citate. Non ho consultato il checkout live per colmare queste assenze.
- **Correzione minima:** nel prossimo snapshot includere le copie pertinenti o estratti sanitizzati con hash dell’originale, revisione, convenzione delle righe e mappatura al percorso congelato. Per materiale non includibile, indicare esplicitamente “affermazione storica non riverificata in C1”. Nessuna nuova raccolta hardware necessaria.

**B2 — Prossimo passo storico non marcato come superato. Gravità bassa.**

- **Trigger:** usare autonomamente la sezione finale del report stack per scegliere il prossimo esperimento.
- **File/righe:** [stack/REPORT.md:132](/C:/VibeCoded%20Projects/Fluidify/build/review-C1-coexistence/source/build/airpods-8h/stack/REPORT.md:132), confrontato con [airpods-coexistence-evidence.md:93](/C:/VibeCoded%20Projects/Fluidify/build/review-C1-coexistence/source/docs/airpods-coexistence-evidence.md:93), righe 93–96.
- **Evidenza:** il primo propone ancora di estendere il harness a callback/free/BTA; il documento integrato registra che l’estensione bloccata dal provider è stata interrotta.
- **Conseguenza:** la decisione integrata è corretta, ma il report subordinato conserva un’indicazione operativa obsoleta.
- **Correzione minima:** aggiungere un’annotazione “proposta storica superata; non eseguire”, preservando il testo e la cronologia. Non riprendere né riformulare la prova bloccata.

Le verifiche **eseguite personalmente da B**, tutte senza scritture, comprendono:

- SHA256 del manifest: `c491434afa6edad886ca71086835a5279bb84833cce029c076fb71272eecf776`; verifica dei 643 file e dei cinque input esterni.
- Log G1: evento noRCB a **LF114245**, quattro CR isolati precedenti, coerente con **universal114249**.
- Capture macOS: riconteggio indipendente di **60 AAP Send e 99 AAP Receive** nella finestra dichiarata sul medesimo ACL. Le etichette non identificano payload mode/CA né attribuiscono completamente i due canali.
- Capture Pro3: **213.112 byte, 1.463 Enhanced Packet Blocks, linktype 254**, lunghezze dei blocchi coerenti. Questo mio controllo è strutturale; la classificazione dettagliata dei payload resta lavoro dell’autore.
- Lettura diretta del codice congelato: ritorno al primo costruttore Fluidify riuscito, connect separato e protetto dallo stato; `LocalBinder` LP privo di remoting dei getter; emissioni RX limitate al package LP; gate BIP AOSP17 indipendente dai metadata.

**Non ho rieseguito l’emulazione Register.** Ho letto script e risultato: il caricamento verifica l’hash dell’ELF, le istruzioni vengono estratte dai segmenti originali e il logger è sostituito. Le esecuzioni dichiarate rimangono dell’autore e di root; non dimostrano GAP/JV, radio o convivenza.

La separazione AOSP/OEM è rappresentata onestamente: il predicato pubblico BIP è verificabile, ma manca il Java Samsung corrispondente. Analogamente, il DEX stock e il sorgente pinned non vengono equiparati integralmente.

Come **nuovi dati esterni**, separati dallo snapshot, ho controllato due progetti promettenti: [omarchy-pods](https://github.com/thisisgm/omarchy-pods) pubblica stato tramite un daemon modificato; [kAirPods](https://github.com/can1357/kAirPods) usa un daemon Linux con L2CAP e IPC D-Bus `org.kairpods.manager`. Questi contratti concreti non forniscono un endpoint già presente nell’APK Android LP invariato; non costituiscono quindi una nuova soluzione completa.

Raccomando di correggere **B1** e annotare **B2**, continuando la ricerca autorizzata. **I3 resta approvato nel proprio perimetro storico; G1/CP1.b restano NV. Nessun avanzamento UI o passaggio definitivo all’esclusivo deriva da questa review.**



