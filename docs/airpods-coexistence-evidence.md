# Convivenza Fluidify / LibrePods — evidenze per la review C4

Stato finale al 2026-11-08. Ricerca chiusa al termine assegnato 03:52:42 UTC;
nessuna soluzione completa verificata. [Decisione e cleanup](airpods-coexistence-decision.md).
Questo documento conserva la matrice C4 e non abilita la UI AirPods.
Le copie precedenti restano immutate nei rispettivi snapshot. Questo fascicolo
conserva requisiti, prove e limiti, omettendo giudizi e voti delle review precedenti.

## Requisito corrente

Fluidify deve controllare le AirPods insieme a LibrePods ufficiale invariata e attiva
anche in background, con identità/capacità, batterie separate nullable, modalità e CA
configurata con stato ricevuto. Restano esclusi widget, fork, modifiche/impostazioni LP,
root, patch di sistema e helper privilegiati. L’utente non vuole workaround parziali.
Il telefono serve all’utente: nessun accesso al dispositivo durante questa ricerca.

Il piano P1 e le prove G1 precedono questo incarico. I3 rimane approvato nel perimetro
software allora verificato; G1 rimane NV con un connect concorrente fallito dimostrato.
I due giri correttivi software I3 non vengono azzerati. La review di ricerca riguarda la qualità
e la copertura della ricerca, non l’approvazione hardware o dell’implementazione futura.

## Distinzioni necessarie

- Un RCB registra servizio/callback; non equivale a un’app o a un singolo canale.
- Due Register riusciti non provano due connect, ricezioni AACP o durata in background.
- Un comando inviato, una cache o una UI ottimista non confermano uno stato hardware.
- CA configurata, attività di conversazione e conversation boost sono dati diversi.
- Un servizio GATT possibile nello stack non dimostra un endpoint remoto appropriato.
- Un fallimento su Samsung non prova un divieto universale del firmware AirPods.
- Una ricerca senza risultati in fonti delimitate non prova che un servizio sconosciuto
  non esista. Le condizioni per riaprire ogni pista sono parte dell’esito.

## Registro delle ipotesi

| ID | Strada | Miglior evidenza attuale | Limite determinante e prossima discriminante |
|---|---|---|---|
| C01 | Due client AACP ordinari | Le istruzioni Samsung Register/finder/allocator, eseguite in RAM sintetica, ammettono due registrazioni con almeno due posti liberi | Occupanti e disponibilità reale non misurati; G1 fallisceva sul pieno. Servono due canali realmente collegati con ricezioni fresche in entrambe le app. |
| C02 | Correggere aperture superflue Fluidify | Il ciclo delle cinque firme ritorna al primo costruttore riuscito; connect è distinto e protetto dallo stato | Nessuna doppia registrazione propria individuata da eliminare. Costruttori/UUID diversi non cambiano il percorso ordinario verificato. |
| C03 | Ridurre risorse tramite metadata/MediaSession | Nei due AOSP il listener BIP dipende dall’avvio del profilo/versione locale, non da artwork o numero di sessioni | Togliere cover non ferma quel listener; nessuna patch equivalente della sola app motivata. Java Samsung corrispondente non ottenuto. |
| C04 | Prerequisito piattaforma AVRCP | Nei sorgenti AOSP una versione locale inferiore a1.6 evita la richiesta BIP quando viene costruito il profilo | È una configurazione globale persistente, non un’opzione Fluidify. Non accettata come soluzione autonoma, nessun setting cambiato; guadagno RCB e convivenza Samsung NV. |
| C05 | GATT Android gestito | Può riusare la registrazione ATT dello stack; HAS offre un modello standard di preset confermati | Manca un endpoint AirPods con mapping verificato di ANC/CA e ricezioni. Il percorso ATT noto LP riguarda altre funzioni e documenta prerequisiti fuori vincolo. |
| C06 | Sette notify della PR716 | Codice e storia pubblici enumerano sette handle; predecessore descrive servizi Apple custom | Mancano declaration UUID/proprietà e RX etichettati; il probe usa prima AACP e un driver Windows. Non è un contratto Android completo. Serve il dump citato, non dati sintetici. |
| C07 | BLE advertising | Due catture pubbliche macOS/Pro2 contengono 115 annunci ricevuti e sette proximity plaintext validi; dodici frame mode TX/RX separati identificano sei cambi | Gli annunci dello stesso gruppo non attraversano più stati ricevuti noti; assenti frame CA configurata 0x28. Copertura insufficiente, nessun mapping completo e nessuna esclusione universale. Attribuzione del peer BLE al peer ACL non dimostrata. |
| C08 | IPC stock LibrePods | Esistono il proprietario dei dati e comandi ANC/CA esportati; servizio bindabile | LocalBinder non serializza getter; broadcast dati sono indirizzati al package LP, senza contratto snapshot/subscription esterno. Un’API nuova richiederebbe cambiare LP, fuori mandato attuale. |
| C09 | Profili gestiti HFP/AVRCP | API pubbliche HFP, eventi ricevuti riconosciuti, funzioni media e metadata | +ANDROID, batteria aggregata, EC/NR voce e metadata locali non equivalgono a controllo/stato ANC/CA. Nessun contratto AirPods completo emerso. |
| C10 | FastConnect | Dissectors e capture mostrano negoziazione CID e trasporto di dati AACP | Nessun ingresso normale Android per signaling e consegna dei CID individuato; non basta un serializer o un’altra UUID. Non si propone accesso privilegiato allo stack. |
| C11 | Altri trasporti/socket offload | Sono percorsi effettivamente diversi in alcuni casi | Nessun endpoint AACP equivalente verificato; offload richiede privilegi e ha tipi limitati. Kernel/HAL e FD di altri processi non sono concessi dal normale CONNECT. |
| C12 | RFCOMM / possibile iAP2 | Il profilo RFCOMM AOSP ha registrazione distinta dal raw AACP. BudsLink Pro2/Pro3 seleziona lo stesso UUID AACP e handler con risoluzione SDP BlueZ, senza PSM numerico esplicito; il suo upstream citato usa 0x1001 | La libreria multi-marca non offre qui un endpoint AirPods RFCOMM/iAP2 completo. Nessun nuovo ingresso remoto identificato, nessun canale inventato o aperto. Non è un'esclusione universale. |
| C13 | Metadata/controlli OEM Samsung | Il decompilato comparativo SystemUI legge/scrive SmepTag e usa separatamente un plugin Messenger per Galaxy Buds; firme OEM distinte da AOSP | Non è la build S25 Android17. Mancano enforcement ordinario, mapping AirPods/CA e RX fresco. Inoltre il consumer batteria S10 può aggregare e scartare L/R: L80/R90→aggregato80,L/R=-1. Questo non dimostra perdita nelle letture metadata originarie. |

Nessun candidato ha finora una catena completa dimostrata entro i vincoli. C01 resta
possibile nel codice al livello di registrazione; C05/C06 mantengono incognite remote.
Non si deduce che siano impossibili in ogni configurazione o firmware. C04 non viene
promosso a workaround accettato dall’utente e non risolve da solo il gate.

## Riscontri riprodotti da root

1. `build/airpods-8h/stack/emulate_rcb.py`: 32768 maschere di occupazione, capacità15,
   due Register outgoing con13 voci preesistenti, seconda fallita con14. Codice macchina
   Samsung in memoria sintetica, logger sostituito. Non esegue GAP/JV/Binder/radio/AACP.
2. `upstream/offline_audit.py`: nove controlli documentali passati. Non sono nove test Bluetooth.
3. `gatt-contract/audit_contract.py`: nove hash cached, quattro tree e identificatori
   del DEX stock verificati; nessuna chiamata diretta GATT trovata. Non esclude reflection,
   codice nativo, split non raccolti o servizi remoti sconosciuti.
4. `root/audit-research-integrity.py`:252 percorsi artefatto/input corrispondenti ai relativi
   hash, alla data del run. Sono copie/artefatti, non252 esperimenti o fonti indipendenti.
5. `root/audit-mac-overlap.py`: durante la finestra dichiarata del canale dell’autore,
   il testo pubblico contiene60 AAPSend e99 AAPReceive sullo stesso ACL, oltre a L2CAP.
   Le etichette non certificano payload/convergenza di mode/CA né attribuzione completa
   dei canali. È un estratto macOS/Pro2, non una prova eseguita da root su Samsung/Pro3.
6. `rfcomm-contract/audit_contract.py`: quattro sorgenti primari verificati per hash e
   asserzioni sul percorso RFCOMM; getter metadata AOSP senza eccezione CDM. Sono
   riscontri statici, non un inventario runtime Samsung o un endpoint AirPods.
7. `ble-mode-contract/check_parser.py`: nove controlli del parser passati anche nel
   run root, su input malformati e fixture di frammentazione. Root ha rieseguito anche
   `analyze.py`: 510/360 record, cinque/due annunci proximity validi, otto/quattro
   frame mode e zero frame CA nel formato fisso esaminato; risultato JSON byte-identico
   a quello dell'autore. Non sono prove radio.
8. `budslink-contract/audit.py`: 56/56 verifiche statiche (incluse integrità SHA256/Git
   blob) passate anche nel run root. Non sono 56 test di protocollo o hardware.
9. `samsung-metadata-contract/verify_local_cache.py`: dieci copie comparative
   corrispondenti ai blob pubblicati e agli hash dell'autore. Integrità del testo,
   non autenticità dell'APK originale o appartenenza alla build Samsung target.
10. `own-resource-inventory/inventory.py --check`: APK I3 identificato per hash,
    33 DEX e nove coppie owner/nome Bluetooth nella tabella dei riferimenti, con
    controllo positivo e due negativi. Non sono call site, chiamate eseguite o un
    conteggio di socket/RCB; reflection/JNI e processi esterni restano fuori campo.

Gli script di raccolta non vanno rieseguiti per una semplice verifica d’integrità:
alcuni aggiornano timestamp o indici. Le review possono verificare hash e dati in sola
lettura. Riferimenti LF114245/universal114249 identificano lo stesso noRCB G1; quattro
CR isolati spiegano l’offset, senza modifica dei byte del log.

## Evidenze e limiti delle unità

Tutti i percorsi seguenti sono sotto `build/airpods-8h/`:

- `stack/REPORT.md`: codice Samsung, registrazione, allocatore e limiti del confronto AOSP.
- `upstream/report.md`: versioni, testimonianza Samsung, capture macOS, commit/PR e semantica CA.
- `alternatives/REPORT.md`: API, autorizzazioni e trasporti realmente distinti.
- `gatt-contract/REPORT.md`: analisi della cache e del DEX, con limiti espliciti.
- `gatt-public/REPORT.md`:94 download e capture Pro3; la ricerca online colma la sola lacuna della cache.
- `notify-contract/REPORT.md`:51 download verificati e storia dei sette handle; due fetch falliti documentati.
- `managed-profiles/REPORT.md`:17 copie verificate su HFP/AVRCP/metadata.
- `bip-lifecycle/REPORT.md`: riferimenti binari e limiti del solo materiale cached.
- `bip-public/REPORT.md`:45 copie Android16/17, callgraph pubblico; Java Samsung non ottenuto.
- `platform-prerequisite/REPORT.md`:50 copie verificate, proprietà globale e differenza fra UI/default/versione campionata.
- `resource-timeline/REPORT.md`:91 occorrenze deduplicate in70 eventi, senza inventare stato iniziale del pool.
- `design-challenge/REPORT.md`:15 fonti primarie e verifica indipendente di IPC, HAS e risorse proprie.
- `root/client-resource-review.md`, `root/error-observability.md`: singolo socket e limiti della diagnosi dall’errore app.
- `ble-mode-contract/REPORT.md`: analisi passiva di due catture Pro2, formati primari,
  reassembly, timeline mode e copertura BLE insufficiente; nessuna CA configurata.
- `rfcomm-contract/REPORT.md`: percorsi AOSP RFCOMM e distinzione CDM/metadata;
  nessun endpoint remoto AirPods dedotto dalle sole API.
- `budslink-contract/REPORT.md`: selezione Pro2/Pro3, risoluzione UUID/SDP BlueZ,
  parser RX e aggiornamenti ottimistici, nessun nuovo endpoint AirPods alternativo.
- `samsung-metadata-contract/REPORT.md`: client SystemUI/SmepTag, plugin Galaxy Buds
  e consumer batteria comparativi; enforcement, contratto AirPods e freschezza NV.
- `own-resource-inventory/REPORT.md`: riferimenti API Bluetooth nel solo APK I3,
  senza un nuovo percorso SDK diretto individuato da eliminare. Non prova assenza
  di ogni possibile allocazione o corrispondenza del checkout live con l'APK.

Le unità aggiuntive hanno concluso le scritture. Gli input pubblici nuovi sono sotto
`public-mode-captures/` con revisioni e Git blob verificati. Root ha aggiunto le dieci
copie Samsung locali per consentire la review senza rete, preservando la provenienza
di decompilati terzi. Il record del commit non prova versione/data di ogni APK.

Root ha letto anche corpo e due commenti pubblici di LibrePods issue782, congelati
in `root/issue-782-public-snapshot.json`. L'autore descrive firmware/setup rooted
diversi e nuove aspettative di sessione Apple; non espone un endpoint alternativo
completo né risolve un noRCB precedente al protocollo. Nessun allegato, chiave,
hook o impostazione descritto nell'issue viene acquisito/eseguito o proposto qui.

I conteggi comprendono copie, indici e metadati e non vanno sommati come implementazioni
indipendenti. Le unità discovery non sono i due reviewer indipendenti richiesti per C1.

La delega di estensione dell’emulazione GAP/JV è terminata failed per filtro del provider
(“possible cybersecurity risk”). Non ha prodotto una prova riuscita; quella prova è stata
interrotta e non ripresentata sotto un altro nome/modello. Le altre unità riguardano
contratti pubblici ordinari e analisi passiva di testi, senza attivare bug o race.

## Correzioni C2 e dati aggiuntivi

- B1: `root/primary-context/` contiene il Java citato, verificato byte-identico a un
  nuovo fetch primario del commit AOSP fissato, il btif sorgente con mapping alla copia
  già inclusa e i metadata storici completi. Due righe Socket Events e13 righe RX mode
  sono pubblicate come estratti sanitizzati con hash degli originali e doppia numerazione.
  La cronologia non viene promossa a inventario attuale o nuova prova hardware.
- B2: la proposta storica di estendere il harness è annotata direttamente nel report
  subordinato come superata e non eseguibile; C1 resta congelato senza questa correzione.
- A-01: il runner Register verifica il PC di ritorno prima di accettare il risultato.
  Un controllo negativo a budget1 è respinto; la ripetizione delle32768 maschere e dei
  quattro scenari Register è passata. Nessuna nuova emulazione GAP/JV.
- Nuova discovery `samsung-artifact/REPORT.md`: release note ufficiali corroborano
  modello/build/Android17, ma non il Java OEM. Nessun APK/DEX corrispondente acquisito;
  accessi pubblici403 fermati, archivi firmware multiGB non scaricati. Solo66533byte
  di payload diretto. Nessuna configurazione Samsung o disponibilità RCB inferita.

Le correzioni C2 costituiscono il primo giro correttivo della ricerca, separato dai giri software I3.
Nessuna prova fisica, modifica prodotto, impostazione LP o sistema è stata eseguita.

C3 integra analisi ulteriori; C4 aggiunge due precisazioni documentali e l'inventario
statico dell'APK I3. Il prossimo giro è il terzo successivo all'iniziale della stessa
fase di ricerca (CORRETTIVO3 ai fini del limite), senza azzerare i contatori.
Gli otto file prodotto AirPods monitorati sono
stati confrontati da root con C2: nessun delta; questo non certifica tutto il checkout
concorrente o un nuovo APK. Nessun nuovo risultato hardware.

## Decisione alla scadenza

La finestra di ricerca è conclusa. I report delle review storiche sono archiviati
separatamente da questo fascicolo tecnico, per evitare di esporre i nuovi reviewer
ai giudizi precedenti. Il gate fisico resta NV. Nessuna nuova UI,
connessione A2DP o integrazione lifecycle di prodotto è stata sbloccata. L’uso esclusivo
è il percorso successivo indicato dall’utente in assenza di una soluzione verificata;
non è già un'implementazione collaudata né una prova di impossibilità della convivenza.
L'emendamento al piano e il gate esclusivo devono preservare i criteri di correttezza
e i collaudi pertinenti; G1 non viene trasformato in PASS o cancellato.

Un'eventuale nuova prova fisica di convivenza, quando sarà nuovamente autorizzata la disponibilità del telefono,
richiederà una differenza concreta rispetto al tentativo già fallito e la baseline LP
stabile. Non si ripete un connect alla cieca e non si chiudono servizi per creare una
falsa baseline. Qualsiasi modifica di requisito/prerequisito resta una decisione esplicita.
Il percorso esclusivo ha invece bisogno dell'emendamento e dei collaudi descritti
nella decisione alla scadenza; non eredita un PASS dalle prove di convivenza.
