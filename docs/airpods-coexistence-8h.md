# Ricerca convivenza AirPods — finestra di otto ore

Avvio: 2026-11-07 19:52:42 UTC (20:52:42 Europe/Rome).
Termine: 2026-11-08 03:52:42 UTC (04:52:42 Europe/Rome).
Checkpoint finale: `scheduled-task:ee7d0992-d8bd-4722-bdd0-01284f9693da`, una sola esecuzione.

**Stato finale, 2026-11-08:** finestra scaduta e ricerca chiusa. Nessuna soluzione
completa verificata; G1/CP1.b rimane NV, senza impossibilità universale dichiarata.
Si applica il percorso esclusivo scelto dall'utente, ancora da implementare e
collaudare con gate aggiornati. Risultati, limiti e cleanup sono nella
[decisione alla scadenza](airpods-coexistence-decision.md). Le sezioni seguenti
conservano la cronologia dei checkpoint, comprese le indicazioni allora aperte.

## Istruzioni correnti dell’utente

Ricercare ancora una soluzione completa per Fluidify e LibrePods ufficiale, invariata,
attiva anche in background, entrambe in grado di controllare le AirPods. Non considerare
persa la convivenza solo perché sono falliti i tentativi già eseguiti. L’uso esclusivo è
il ripiego scelto soltanto se non emerge una soluzione entro questa finestra.

Sono esclusi widget incorporati, fork/modifiche LibrePods, modifica delle sue impostazioni,
root e patch di sistema. Eventuali interfacce già presenti nello stock possono essere
studiate, ma una copertura parziale o senza stato osservabile non soddisfa automaticamente
l’autonomia e le funzioni di P1. Nessun workaround parziale viene promosso a prodotto.

**Il telefono non è disponibile:** l’utente ne ha bisogno. Nessun accesso al dispositivo,
device_open, adb verso il telefono, installazione o prova fisica durante questa ricerca.
Il pannello S25 è già stato rilasciato; il probe widget è stato disinstallato e le sue
due associazioni rimosse. Il servizio LibrePods non è stato fermato. La modalità Adaptive
è tornata selezionata nella UI; non si rivendica una nuova conferma hardware.

## Base verificata e confini

- P1 originale: `airpods-implementation-plan.md`; software approvato I3 e gate G1 NV
  restano storici immutati. Il nuovo incarico riapre la ricerca, non trasforma G1 in PASS.
- I3 finale: `build/review-I3/fluidify-I3-listener.apk`, SHA256
  `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`.
- LibrePods pinned: `89356c08cea3db0b6b54792d9329d09a87cc85fa`; APK ufficiale raccolto
  `build/airpods-official-integration/librepods-installed-base.apk`, SHA256
  `5b7db3d157a89ae74b2f0c6b5482d85b2f2bd4bb4cb7687077de47351accb60b`.
- Log/binario Samsung già disponibili sotto `build/airpods-direct-diagnostics`,
  `build/airpods-direct-research` e `build/airpods-worn-gate`.
- Il binario Samsung esaminato contiene un allocatore di 15 RCB classici; occupanti
  runtime non misurati. Un secondo connect è fallito prima dell’AACP, con `no RCB`.
  Questo non prova un limite universale del doppio canale né una perdita permanente.
- Una sessione Fluidify diretta ha ricevuto identità e batterie; LibrePods non aveva
  allora una baseline contemporanea stabile. Nessun PASS convivenza ricavato da quella prova.
- Il probe widget separato è stato compilato e ha visualizzato dati e inoltrato comandi
  tramite LibrePods; questo percorso è stato rifiutato dall’utente e viene archiviato.
  Non è una soluzione corrente, né una prova del client diretto o di CA.
- Preservare README originale, engine 3.0.5 e tutte le modifiche concorrenti history/bridge.
  Nessun commit, push, release, aggiornamento engine o test su account/dispositivi.

## Metodo e unità di lavoro

La consultazione online di documentazione, sorgenti e catture pubbliche è autorizzata
e necessaria quando la cache non contiene un’informazione decisiva. “Offline” riferito
ai test significa senza telefono/radio: non impone di limitare la ricerca alla cache.
I report che non hanno fatto fetch sono soltanto inventari del materiale disponibile,
non esauriscono la ricerca pubblica sul rispettivo argomento.

1. Riesame indipendente del percorso Android/Samsung: allocazione, vPSM, pool, rilascio,
   riuso e alternative reali nel client. Cercare controesempi, non confermare per inerzia
   la diagnosi precedente. Distinguere vincolo dimostrato, inferenza e dato mancante.
2. Ricerca nelle fonti primarie e nelle issue upstream: esperienze verificabili di client
   concorrenti, revisioni Android/LibrePods, semantica di handshake/sottoscrizione AACP.
3. Valutazione di interfacce stock complete e trasporti alternativi senza widget o privilegi;
   distinguere comandi inviabili da stato hardware ricevuto e reale autonomia Fluidify.
4. Root integra una matrice delle ipotesi. Per ogni candidato: evidenza, vincoli, codice
   interessato, esperimento discriminante locale, esito e limite residuo. Non ripetere
   varianti già escluse senza una nuova differenza osservabile.
5. Implementare soltanto POC motivati in directory isolate sotto `build/airpods-8h/`, con
   test JVM/host o fixture offline pertinenti; nessuna attivazione della UI prodotto.
6. Due reviewer indipendenti Sol high valutano lo stesso snapshot finale di ricerca/POC.
   Autori separati dai reviewer. Restano distinti i due giri correttivi software I3 già
   consumati; questa ricerca non azzera contatori o approvazioni precedenti.

Configurazione agentic-workflow: Sol high per gli agenti, fino a tre unità concorrenti
oltre al root se gli slot lo permettono; due reviewer per fase/giro. Ogni nuova unità ha
contesto fresco e perimetro assegnato. Dispositivi non disponibili a nessun agente.

## Criteri di valutazione della ricerca

- R1: fedeltà ai vincoli — stock LibrePods attiva, nessun widget/modifica/impostazioni/root.
- R2: correttezza tecnica e completezza — batterie, modalità, CA, identità/capacità e
  stato osservabile, autonomia del client o dipendenze esplicitamente identificate.
- R3: qualità delle esclusioni — percorsi e assunzioni verificati, controipotesi considerate;
  non confondere fallimenti locali con impossibilità universale.
- R4: riproducibilità — versioni/hash, sorgenti primarie, script e test eseguiti distinguibili
  da proposte; nessuna attività sul telefono o falsa attribuzione di prove hardware.
- R5: integrazione e decisione — compatibilità con P1/I3, ownership, limiti, prossimo
  esperimento preciso e scelta finale entro il tempo assegnato.

Voti 0–10 con almeno 8 per ogni criterio numerico di ciascun reviewer e nessun difetto grave
confermato. NV/NA distinti. Una buona review della ricerca non certifica il gate hardware.

## Possibili esiti

- Soluzione motivata con POC offline riuscito: consegnare patch/codice e prova discriminante
  per quando il telefono sarà disponibile. Compatibilità fisica e convivenza restano NV.
- Nessuna soluzione entro i vincoli esaminati: consegnare le esclusioni dimostrate e i limiti;
  attuare il ripiego esclusivo già scelto senza fingere un’impossibilità globale o un gate passato.
- Mancanza di prove indispensabili: indicare esattamente cosa non è determinabile dai materiali.

Non occorre consumare artificialmente otto ore se una soluzione verificabile emerge prima;
non dichiarare però chiusa la ricerca da un solo fallimento. Alla scadenza fermare nuove
esplorazioni, consolidare i risultati e annullare le sole deleghe divenute superflue.

## Stato

Prima ondata attiva: tre agenti Sol high indipendenti per stack, fonti upstream e
trasporti/interfacce alternativi. Nessuna attività sul dispositivo.

Il riesame root del client non trova cinque registrazioni implicite: il ciclo delle
firme ritorna al primo costruttore riuscito, connect è distinto e protetto dallo stato.
Dettagli con riferimenti in `build/airpods-8h/root/client-resource-review.md`.

Nuova ipotesi considerata: API socket-settings/offload. Due sorgenti AOSP versionati
raccolti; builder accetta RFCOMM/LE, offload richiede privilegi di sistema. Il terzo
fetch (binder) ha ricevuto HTTP429 e non viene spacciato per verifica riuscita.
Manifest locale in `build/airpods-8h/root/socket-settings-manifest.json`.

Nuova fonte da verificare: `BorgoGustavo/airpods-ctl`, note e capture macOS/Pro2 che
riferiscono un secondo canale nella finestra iniziale di connessione. Non è un risultato
Android/Pro3; serve a contestare un limite firmware universale. FastConnect via Echo
è stato aggiunto alle piste da valutare per l’accessibilità reale Android, senza
assumere che un protocollo documentato sia accessibile a una normale app.

### Prima ondata — risultati ricevuti

Stack: `build/airpods-8h/stack/REPORT.md`, script `emulate_rcb.py` e
`emulation-result.json`. L’agente riferisce esecuzione delle istruzioni Samsung reali,
logger sostituito, per tutte le 32768 occupazioni sintetiche. Due chiamate outgoing
Register riescono con almeno due voci libere: nessun limite intrinseco di un solo
client nella sola registrazione. Questo risultato verrà riprodotto da root; GAP/JV,
Binder, radio e AACP non sono coperti dalla prova. Individuato nei log il server
BIP OBEX di AVRCP Target come uno degli occupanti; inventario completo non disponibile.

Upstream: `build/airpods-8h/upstream/report.md` e `offline_audit.py`.
Testimonianza maintainer/utente S25 Ultra di una sola app per volta, senza trace
che localizzi il meccanismo. Capture macOS contrasta un divieto firmware universale,
ma non dimostra supporto Samsung/Pro3. Main coincide con il pinned; rewrite e PR
consultate non mostrano un bypass noRCB utilizzabile senza modificare LibrePods.

Prossimi approfondimenti motivati: (a) lifecycle del server BIP e rapporto con metadata/
MediaSession di Fluidify, per verificare un’eventuale leva legittima nel client;
(b) limiti aggiuntivi GAP/JV e failure cleanup, oltre alla sola registrazione.
Non si propone di sfruttare il rilascio involontario del server o di riprovare alla cieca.

### Seconda ondata — stato e limite del provider

Root ha rieseguito `stack/emulate_rcb.py`: 32768 maschere superate, due registrazioni
outgoing con due posti liberi, fallimento della seconda con uno solo. Ha rieseguito
`upstream/offline_audit.py`: nove riscontri statici passati. Questi sono test offline,
non prove di canali connessi, notifiche o durata in background.

Le unità BIP/lifecycle e contratto GATT sono state avviate in directory separate.
La terza delega, che proponeva di estendere l’emulazione ai percorsi GAP/JV e al cleanup
del binario, è terminata con stato failed: il provider ha segnalato “possible cybersecurity
risk”. Nessuna conclusione positiva o test GAP/JV viene attribuito a quella delega.
Quella prova viene interrotta, senza ripresentarla sotto un altro nome/modello.
La lettura architetturale ordinaria dei sorgenti pubblici rimane distinta dalla prova
bloccata e non dimostra il comportamento OEM o radio.

Root ha verificato l’integrità di 58 artefatti/input della prima ondata e la coerenza
dei risultati registrati: `root/audit-research-integrity.py`, `research-integrity.json`.
Il log originale conserva il suo hash: i riferimenti LF114245 e universal-newline114249
indicano lo stesso noRCB, con quattro CR isolati precedenti. Usare anche timestamp e hash
per evitare ambiguità dei riferimenti, senza riscrivere la raccolta congelata.

GATT/cache: nessun contratto completo trovato, ma l’unità non ha consultato blob/fonti
online mancanti; questa lacuna viene affrontata con una nuova unità esplicitamente online.
BIP/cache: mancano i sorgenti Java che decidono quando avviare il listener; serve la
consultazione del codice pubblico, non basta il nome di un metodo nativo.
Profili gestiti: report `managed-profiles/REPORT.md`, whitelist HFP +ANDROID, eventi
riconosciuti e funzioni AVRCP media non costituiscono controlli ANC/CA completi.

### Terza ondata — fonti pubbliche e cronologia

- `bip-public/REPORT.md`: 45 copie primarie Android16/17. In quei sorgenti il listener
  BIP parte con AVRCP Target se la versione locale è almeno1.6; non attende artwork,
  MediaSession o un controller. Rimuovere metadata/cover non lo ferma. Il Java OEM
  Samsung corrispondente non è stato ottenuto; il portale pubblico ha risposto403.
  La proprietà globale della versione AVRCP è un possibile prerequisito piattaforma,
  non un’impostazione ordinaria di Fluidify e non viene modificata né proposta come
  prodotto automaticamente. Il requisito “niente workaround” resta da rispettare.
- `gatt-public/REPORT.md`: 94 download verificati, blob prima mancanti e capture Pro3
  pubblica. Nessun contratto completo; PR716 menziona sette handle notify senza la
  tabella UUID/proprietà/RX necessaria. Questa è una pista da approfondire, non un POC PASS.
- `resource-timeline/REPORT.md`: 91 occorrenze deduplicate in70 eventi pertinenti.
  Register Fluidify18:42:28.345 e noRCB LibrePods186ms dopo confermano disponibilità
  puntuale per un client. Nessun inventario iniziale completo o deregistrazione riuscita
  esplicita permette di provare una finestra per due; nessun socket close è contato
  arbitrariamente come RCB liberato.
- Root `audit-mac-overlap.py`: nella finestra del canale dell’autore, la capture testuale
  pubblica include60 eventi AAPSend e99 AAPReceive sullo stesso ACL, oltre al traffico
  L2CAP. Le etichette non espongono payload mode/CA e non certificano fanout/convergenza
  dei due client. Il risultato rimane macOS/Pro2, non Samsung/Pro3.

Nessun accesso al telefono. Nessuna UI AirPods o modifica prodotto attivata. La ricerca
continua entro la finestra assegnata sulle lacune concrete appena emerse.

### Review del risultato corrente C1

Raccolta congelata in `build/review-C1-coexistence`:643 file, cinque input esterni
read-only verificati. Manifest SHA256
`c491434afa6edad886ca71086835a5279bb84833cce029c076fb71272eecf776`.
Due nuovi reviewer Sol high indipendenti sono stati avviati sullo stesso snapshot;
nessun voto disponibile al momento dell’avvio. La ricerca e il termine restano aperti.
La matrice live è `airpods-coexistence-evidence.md`; la copia C1 non verrà riscritta.

Durante la review prosegue separatamente la ricerca di un artefatto Java Samsung
ufficiale pubblico della build pertinente, per affrontare l’incognita OEM senza telefono.
Nessun download massivo di firmware, nessuna autenticazione aggirata o richiesta IMEI.

### Correzione di ricerca C2

Il contesto di provenienza B1, la raccomandazione storica B2 e la guardia A-01 sono
oggetto delle modifiche C2. I report storici sono archiviati separatamente; i
congelati non vengono alterati.
Root aggiunge contesto primario/estratti sanitizzati con hash e mappatura, annota
direttamente la proposta obsoleta e verifica il PC di ritorno del runner Register
con controllo negativo. La ripetizione dei test preesistenti passa; nessun GAP/JV.

C2 è congelato in `build/review-C2-coexistence`,664 file e sette input esterni.
Manifest SHA256 `a395b72d620a8c7b14ea0cd738664fa9537a923602522bf82bdcb975ab24671b`.
`changes-from-C1.json` identifica il delta; nessuna modifica prodotto. È il primo
giro correttivo della ricerca, non un reset dei due giri software I3.

La ricerca di artefatti Samsung non ha acquisito il Java OEM; release note e limiti
di reperibilità sono documentati in `samsung-artifact/REPORT.md`. Nessun dispositivo.
B1, B2 e A-01 hanno le modifiche tecniche documentate nei sorgenti e negli estratti
C2. I giudizi delle review restano nei documenti storici separati da questo fascicolo.
CP1.b resta NV e la finestra ancora aperta.

L'analisi successiva riguarda separatamente le catture pubbliche di cambi modalità
per verificare la copertura di eventuali advertising BLE. Non modifica C2, non usa
il telefono e non presume che i comandi pubblici di advertising del Mac siano
notifiche ricevute dalle AirPods.

### Verifiche successive al checkpoint C2

L'unità `ble-mode-contract/REPORT.md` ha verificato dai byte 870 record pubblici,
115 annunci ricevuti e dodici frame modalità TX/RX. Solo sette annunci proximity
plaintext validi; non attraversano più stati ricevuti noti dello stesso dispositivo.
Nessun frame CA configurata 0x28. Questo dataset non dà un readback BLE completo,
ma non dimostra un'assenza universale. Nove controlli parser passati; nessun telefono.

Root `rfcomm-contract/REPORT.md` distingue la registrazione del profilo RFCOMM da
quella del socket raw AACP e verifica che l'eccezione CDM dell'alias non si applichi
al getter metadata AOSP fissato. Asserzioni sorgente e hash passati; nessuna prova
di endpoint AirPods/RFCOMM o equivalenza Samsung. La dichiarazione aggregata di
BudsLink su marche e trasporti è stata verificata nel codice: Pro2 e Pro3 scelgono
lo stesso UUID/handler AACP con risoluzione SDP BlueZ, non un nuovo endpoint RFCOMM
o GATT. Il client non fissa un PSM numerico; il suo upstream accreditato usa0x1001.
`budslink-contract/REPORT.md` distingue RX, ottimismo e contratto nullable/freshness
incompleto. Le56 asserzioni statiche sono passate anche nel run root.

`samsung-metadata-contract/REPORT.md` ricostruisce client semGetMetadata/SmepTag e
plugin Messenger dei Galaxy Buds da un decompilato pubblico comparativo. Mancano
enforcement app ordinaria, contratto AirPods/CA e conferma remota fresca. Root ha
aggiunto copie locali dei dieci sorgenti con hash coincidenti per una review senza
rete. Il commit2024 non certifica versione/data dei singoli APK o la build S25.

Root ha rieseguito l'analisi delle catture BLE: risultato JSON byte-identico a quello
dell'autore. Gli otto file prodotto AirPods monitorati sono invariati rispetto a C2;
non si certificano con questo altri lavori concorrenti o un nuovo APK.

### Preparazione del checkpoint C3 (storica)

C3 integra queste nuove evidenze sul medesimo mandato. È il secondo giro dopo
l'iniziale della fase RICERCA (CORRETTIVO2 ai fini del limite), non un reset del
contatore e non una riapertura della review software I3. Due nuovi reviewer Sol high
devono verificare il congelato prima di attribuirgli una valutazione della ricerca.
Il gate G1/CP1.b rimane NV; la finestra termina 2026-11-08 03:52:42 UTC.

### Preparazione del checkpoint C4

Il fascicolo corrente aggiunge il limite del consumer batteria Samsung: livelli
laterali validi con differenza≤15 diventano -1 e l'aggregato è il minimo. Non si
ricostruiscono L/R da quel valore. È una lettura statica comparativa, non una
misura Samsung o una perdita dimostrata nei metadata originari.

I documenti obbligatori non riportano più giudizi o voti delle review storiche,
che restano negli archivi separati e nei congelati originali. Questo modifica
l'esposizione dei nuovi reviewer, non ciò che i reviewer precedenti hanno letto.

Root ha inoltre inventariato i riferimenti method_ids dei33 DEX nell'APK I3
identificato per hash: nove coppie owner/nome Bluetooth, nessun altro percorso SDK
diretto individuato. Non sono call site o socket runtime; reflection/JNI e servizi
esterni non sono esclusi. Il controllo --check è readonly, con due negativi.

C4 è il terzo giro dopo l'iniziale della fase RICERCA (CORRETTIVO3 ai fini del
limite). Nessun reset, modifica prodotto o nuovo risultato hardware. La decisione
delle otto ore e il gate CP1.b restano aperti; nessun uso esclusivo anticipato.

### Chiusura alla scadenza

Il termine 2026-11-08 03:52:42 UTC è trascorso. Nessuna nuova esplorazione o proroga.
`task_cancel` applicato alle25 deleghe della ricerca/review:24 completed e1 failed,
nessun errore; erano tutte terminali. Nessuna risorsa device/build acquisita durante
la finestra da rilasciare, nessun intervento sulle altre chat. C4 ricontrollato:
771/771 file e7/7 input esterni corrispondono per dimensione/hash.
Il risultato consolidato e l'emendamento necessario per il percorso esclusivo
sono in `airpods-coexistence-decision.md`. Nessun gate hardware passato o eliminato.
