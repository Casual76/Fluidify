# AirPods complete in Fluidify — piano integrato F1

Stato: **prima revisione correttiva F1, da congelare e sottoporre a due nuove review**.
Non è un'approvazione del prodotto o del gate hardware. Il nuovo requisito esplicito
è gestione AirPods completa dentro Fluidify, con automazioni anche per altre app
e Fluidify in background, attivate nelle impostazioni. La ricerca di convivenza
precedente è chiusa; I3/G1 e congelati storici rimangono immutati.

Upstream di riferimento verificato: LibrePods
`89356c08cea3db0b6b54792d9329d09a87cc85fa`; Fluid Engine effettivo3.0.5.
Nessun aggiornamento dell'engine o distribuzione della release incluso.

I [contratti operativi C1–C5](airpods-full-integration-contracts.md) sono parte
normativa di F1: formato head tracking, capacità, presenza companion, stop di sistema
e transizioni ear. Specificano e, dove necessario, correggono le formulazioni generali
sottostanti e le proposte discovery. F0 è storico immutato; nessun voto è ancora
attribuito a F1. I dati hardware restano NV.

## Requisiti osservabili da coprire nel piano definitivo

| ID | Requisito | Evidenza richiesta per la consegna |
|---|---|---|
| F01 | Capsula Home nello spazio fra wordmark e avatar indicato dall'utente | Layout reale e fixture Home con header espanso/collassato, avatar e friends presenti/assenti |
| F02 | Capsula solo setup completato, funzione attiva e AirPods collegate | Matrice stati con test UI; nessuna capsula per setup incompleto, disattivato, disconnesso o permesso revocato |
| F03 | Accesso da impostazioni anche senza capsula o login Spotify | Navigazione Settings→AirPods/setup, stato disconnesso e account Spotify assente |
| F04 | Quick settings Home/player e pulsante impostazioni complete | Unico contratto/repository; test comandi e apertura/back senza doppio socket o reset player |
| F05 | Tutte le impostazioni AirPods LibrePods applicabili | Catalogo sorgente completo, ogni ID con implementazione e verifica o esclusione motivata esplicita |
| F06 | Modelli/capacità e piattaforma effettivi | Mapping upstream e RX reali distinti dal nome; test per modelli/unknown/layout incompleti e comportamenti incompatibili |
| F07 | Batterie separate nullable, fresche e attribuite alla connessione corrente | Codec, timestamp per componente, test unknown/stale/disconnessione; nessuna carica custodia inventata |
| F08 | Device controls confermati e pending osservativo | Ultimo RX separato da comando/timeout, un pending per proprietà, generazioni e notifiche concorrenti |
| F09 | Automazioni in background per altre app, opt-in | Servizio e selezione sessioni indipendenti dal player Spotify; prove host e hardware con altra app |
| F10 | Rilevamento orecchie e pausa/ripresa con ownership | Ripresa solo della sessione/azione gestita da Fluidify, rispetto pausa manuale e cambi sessione/route |
| F11 | Gestione conversazione completa applicabile | Configurazione hardware distinta da evento voce e azione host; niente doppia attenuazione, ripristino volume manuale obsoleto o ripresa indebita |
| F12 | Servizio arrestabile e risorse rilasciate | Stop generale, Bluetooth off, revoca, unpair, morte processo, ritorno UI e transizioni esplicite testate; nessun retry continuo |
| F13 | Funzioni indipendenti dall'account Spotify | Logout/account switch non ereditano arbitrariamente il ciclo del POC playback; comportamento esplicito nel piano revisionato |
| F14 | Impostazioni complete Fluid e responsive | Pagine/gruppi/componenti reali dell'engine, limiti finiti, back e vetro sul fondale visibile; nessun redesign estraneo |
| F15 | Provenienza, licenze e aggiornabilità | Revisione upstream, file/asset/attribuzioni e adattamenti tracciati, separazione libreria/adattatore/UX |
| F16 | Nessun supporto simultaneo LibrePods promesso | Nessun arresto/modifica di LibrePods, widget, root, Xposed, takeover o patch di sistema |
| F17 | Preservazione Fluidify, Wear e lavoro concorrente | Diff circoscritto, ownership, build/test regressioni pertinenti, nessun protocollo Wear modificato |

F05 è una condizione di completezza: una pagina con soli ANC/CA/batterie non
soddisfa questo incarico. Il catalogo è stato consegnato e integrato:38 identificatori
AACP,38 funzioni supplementari,67 chiavi e11 modelli. Non sono76 impostazioni attive:
35 voci semantiche sono incluse/adattate,41 sono protocollo/infrastruttura/esclusioni/
funzioni latenti. [Matrice per ogni ID](airpods-full-integration-coverage.md).
Le227 stringhe UI riconciliate sono un indice lessicale, non una prova semantica.

## Contratti di integrazione da stabilizzare

### Un solo proprietario AirPods

L'Application espone il contratto comune; un servizio opzionale gestisce il solo
collegamento/protocollo/automazioni. UI Home, player e impostazioni osservano lo
stesso stato e inviano comandi a quel proprietario. Il player non apre socket e
non governa l'attività AirPods delle altre app. Il vecchio SessionOwner90s resta
storico finché la migrazione non è definita; non viene spacciato per il nuovo runtime.

Si mantengono identità verificata, capacità, stato collegamento controlli distinto
da route audio, valori RX nullable e pending per proprietà/generazione. Nuove
proprietà del catalogo richiedono mapping/range/reducer pertinenti, non un unico
toggle generico o una preferenza locale presentata come stato ricevuto.

### Preferenze e azioni host

Opt-in generale OFF. Setup esplicito, scelta della coppia abbinata e autorizzazioni
contestuali. Preferenze per auricolari e automazioni sono locali e distinte dalla
configurazione hardware letta. La riconnessione non deve riscrivere tutte le opzioni
per imporre i default Fluidify. Un'eventuale ripristino di preferenze va definito
per voce con il relativo effetto, senza trasformare gli unknown in valori noti.

Le automazioni globali devono osservare controller/route/eventi effettivi, ricordare
solo le azioni introdotte da Fluidify e rinunciare a una ripresa/ripristino quando
l'utente o un'altra sessione hanno cambiato lo stato. Permessi mancanti disabilitano
la sola funzione dipendente con spiegazione e percorso di concessione in Settings.
Non si richiede un privilegio di sistema o si assume acceso un notification listener.

### UI e navigazione

Un pannello rapido condiviso espanso dal punto di origine, batterie, modalità e
controlli del catalogo scelti per il quick access; pulsante diretto alla pagina
completa. Host dell'overlay sopra le superfici di origine, senza copiare il player
o muovere i transport. Le impostazioni complete usano gruppi/sottopagine per
funzione e capacità; anche con cuffie disconnesse restano raggiungibili le preferenze
locali e l'azione di collegamento, senza mostrare vecchi RX come stato attuale.

La Home deve considerare il marchio visibile della HomeTopBar, la copia di misura
trasparente nell'Header, l'avatar e le friend faces: aggiungere un tasto solo alla
riga non basta a provare assenza di sovrapposizioni al cambio scroll/viewport.

## Ownership proposta per implementare senza conflitti

Aggiornamento esplicito utente del2026-11-08: limite contemporaneo aumentato a
cinque implementatori e quattro reviewer. Modello Sol high, indipendenza delle
review, ownership dei file e massimo tre giri correttivi restano invariati.
Le review storiche restano quelle eseguite con i limiti precedenti.

- Protocollo/dominio: `airpods` modelli, capacità, codec, proprietà/riduttore/stato,
  trasporto; preservare o migrare esplicitamente il diagnostic harness.
- Runtime/automazioni: nuovi file in `airpods/runtime`, selezione MediaSession,
  lifecycle, preferenze e adattatori Android, con contratto del dominio concordato.
- UI: nuovi componenti `ui/airpods`, HomeScreen, PlayerScreen e SettingsScreen;
  una sola persona modifica ciascun file condiviso e consegna handoff al root.
- Root: Application, manifest/native/build, licenze/risorse comuni e navigazione
  SquareApp/Routes; integrazione seriale dopo handoff UI, nessuna modifica concorrente
  a questi file. Traduzioni/risorse condivise accorpate in un passaggio root.

Assegnazioni definitive e firme interne dipendono dai report discovery. Nessun
implementatore può espandersi nei file di un altro senza handoff. Un utilizzatore
per build onerosa/emulatore; telefono attualmente indisponibile e non interrogato.

## Fasi e gate da rendere definitivi prima delle review

1. Integrare catalogo completo, architettura e UX nella versione F0 congelata.
   Due reviewer Sol high indipendenti, stessi requisiti/codici criterio e artefatti;
   soglia almeno8 per ogni voto numerico e nessun difetto grave confermato.
2. Implementare protocollo/stato, servizio/automazioni e UI in perimetri separati,
   con un integratore per host e risorse. Test significativi su codec/range,
   RX/pending/generazioni, preferenze, cancellazione e ownership media/volume.
3. Integrare e verificare build app/Wear, navigazione/setup e UI simulated a360×640,
   600×960,840×600,1280×800 e720×360 dp, font1/1.5/2, temi chiaro/scuro/AMOLED,
   Home header expanded/collapsed e player con lyrics/queue/video.
4. Due review indipendenti sul risultato integrato. I conteggi iniziale/correttivi
   della nuova implementazione sono espliciti; nessun reset dei vecchi I3/C1–C4.
5. Eseguire il nuovo gate fisico standalone quando il telefono sarà nuovamente
   autorizzato: tre sessioni, modello/capacità/batterie/mode/CA RX, audio/gesti,
   automazioni con altre app e interventi manuali, background/lifecycle/cleanup.
   G1 resta NV storico; test simulati non equivalgono al PASS hardware.

Il piano definitivo deve distinguere sviluppo UI/fixture dall'abilitazione sul
dispositivo e dalla consegna completa. Nessuna approvazione precedente o consenso
al nuovo perimetro viene trasformato automaticamente in un gate fisico superato.
I dettagli integrati del servizio, i default e i gate osservabili sono definiti sotto.

Fonti Android già verificate da root e gap attuali del progetto:
`build/airpods-full-integration/orchestration/android-host-contract.md`.

## Copertura completa e organizzazione delle impostazioni

La pagina AirPods è raggiungibile dalla normale Settings anche disconnessi o senza
login Spotify. Gruppi e sottopagine sono capability-aware, con stato servizio e
motivazioni delle indisponibilità. Tutte le35 voci incluse della matrice sono
obbligatorie; gli opcode solo protocollo o demo non diventano righe arbitrarie.

| Gruppo | Contenuto obbligatorio |
|---|---|
| Dispositivo | Nome, modello/firmware/versioni/informazioni, batterie L/R/custodia e charging, connetti/disconnetti propri controlli, setup/dimentica |
| Audio | Modalità supportate/Off consentito, preferenza rapida, intensità Adaptive, volume personalizzato, CA hardware, EQ low/mid/high e stato |
| Comandi | Modi nel ciclo long-press, azione lunga sinistra/destra, swipe volume e intervallo, gesti testa con tracking e propria verifica di fattibilità |
| Chiamate e microfono | Lato microfono e associazioni gesto mute/fine chiamata; permessi telefonici solo per effetti host effettivamente necessari |
| Accessibilità dei comandi | Velocità pressione e tenuta, ANC con un auricolare, volume toni; nessun effetto EQ nascosto durante apertura pagina |
| Protezione/energia | PPE AACP quando supportato, pausa sonno, ricarica ottimizzata; non confondere PPE con percorso ATT hearing aid escluso |
| Automazioni | Opt-in host, ear pause/resume, attenuazione CA, pausa conversazione, relativo/assoluto e volume conversazione, disconnessione quando non indossate best effort |
| Servizio e accessi | Controlli/automazioni ON/OFF separati, stato reale, Bluetooth/notifiche/accesso media, Arresta/Riprendi, Tile Android condivisa |
| Diagnostica | Stato compatibilità motivato, ultimo RX/richiesta non confermata e informazioni tecniche essenziali, export/delete log sanitizzati; nessun raw-command terminal necessario |

La matrice conserva per ogni ID range, encoding, capacità e fonti. Modello ignoto
non usa fallback Pro3. Capability del modello è distinta da implementazione consentita
e stato RX. Gesti testa senza takeover e disconnessione profilo ordinaria hanno prove
dedicate: non vengono né esclusi dal nome né dichiarati funzionanti dalla sola UI.

Xposed/vendorIdHook/ATT-hearing, takeover, widget LP e patch restano esclusi per le
dipendenze esplicite. Tema, acquisti/billing e distribuzione dell'app LP non sono
impostazioni cuffie da duplicare; Fluidify conserva i propri sistemi. HRM, camera,
suoni/find-case senza percorso Settings Android attivo non vengono inventati.
Il catalogo contiene anche i limiti di proprietà commerciale e asset: non copiare
il premium gate LP come capability hardware e non importare asset senza licenza
verificata; preferire icone originali dell'app. Attribuzioni GPL e sorgente pinned
rimangono tracciate per i file realmente derivati.

## Decisioni operative F0

Queste scelte sono parte del piano sottoposto a review. Le divergenze rispetto a
singole proposte discovery sono registrate in `orchestration/integration-decisions.md`.

### Setup e avvio

Impostazioni→AirPods: spiegazione gestione esclusiva, opt-in controlli OFF di default,
permesso Bluetooth contestuale, scelta esplicita fra bonded candidate, prova singola
di costruzione/connect/bootstrap e identità ricevuta. Nome/UUID/cache non certificano
il setup. Timeout connect10s con close reale; errori motivati e retry manuale, nessun
loop. Opt-in automazioni separato, anch'esso OFF finché scelto dall'utente.

Per azioni affidabili sulle altre app, proporre accesso Android alle sessioni media
tramite NotificationListenerService abilitato manualmente dall'utente. Non richiedere
MEDIA_CONTENT_CONTROL come permesso runtime, non concedere accessi automaticamente,
non leggere/conservare contenuto notifiche estranee o usare notifiche LP come protocollo.
Senza questo accesso controlli hardware disponibili, automazioni dipendenti non operative
con spiegazione. Controllo locale Fluidify e controllo esterno condividono lo stesso arbitro.

AirPodsService separato, non esportato, FGS connectedDevice e notifica con Apri/Arresta.
Il manifest main integra BLUETOOTH_CONNECT e legacy necessari, FGS_CONNECTED_DEVICE;
POST_NOTIFICATIONS è gestito senza scambiarlo per permesso Bluetooth. Nessun SCAN,
ADVERTISE o permesso firma senza funzione concreta che lo richieda. NotificationListener
e Tile sono componenti di sistema protetti dai rispettivi BIND, senza comandi AirPods
esportati. Application lazy, JNI caricato alla prima attivazione, mai all'avvio generale.

Avvio iniziale da azione UI esplicita; mantenimento finché opt-in non sospeso e coppia
collegata, anche senza player Fluidify e durante pause oltre90s. Alla disconnessione
chiudere sessione/effetti e terminare FGS, mantenendo solo desiderio locale abilitato
se non vi è stop esplicito. Un vero evento Bluetooth di nuova connessione può avviare
un solo tentativo quando l'eccezione Android applicabile e i permessi lo consentono;
receiver protetto, verifica effettiva bond/profilo e deduplica, nessuna fiducia cieca
negli extra Intent. Gestire rifiuti FGS senza crash o polling e richiedere riattivazione
da azione visibile se non esiste un percorso consentito.

Percorso concreto proposto per la riconnessione automatica API31+: associazione
CompanionDeviceManager della coppia scelta con consenso Android esplicito, presenza
osservata tramite CompanionDeviceService e permesso
REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND. Il componente companion
è un trigger senza socket/reducer; verifica associazione, opt-in/suppress e stato
Bluetooth corrente, quindi passa l'acquisizione all'unico AirPodsService. Nessuna
associazione con un altro device scelto per nome o UUID presunto. Se il sistema non
offre/consente il percorso o il consenso è negato, mantenere controllo manuale e
stato “Riattivazione richiesta”, senza fingere riconnessione automatica. Su API26–30
il percorso legacy va verificato separatamente rispetto alle restrizioni applicabili.
Rimuovere l'associazione della sola Fluidify quando si dimentica la coppia, mai bond
o associazioni di altre app. Nessuna modifica all'app LibrePods o policy audio.

La documentazione Android [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
e [restrizioni FGS](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
motiva questo ingresso consentito. L'associazione non apre di per sé il collegamento
AirPods e non prova accesso AACP, rilevamento affidabile OEM o funzionamento radio;
assenza di callback, deny/revoke e disassociazione sono casi obbligatori di test.

Niente boot/startsticky universale assunto: dopo force-stop o arresto esplicito serve
azione utente; processo ricreato non riproduce comandi/media effect vecchi. Eventuali
restore dopo reboot/app update si limitano a riesaminare opt-in/suppress/bond e stato
reale già connesso attraverso percorso consentito, altrimenti restano inattivi con
riattivazione esplicita. Nessun servizio idle permanente usato solo per scansione.

### Stato, default e azioni host

Pending per proprietà con bytes/sequenza/generazione e timeout5s; RX pertinente
aggiorna observed e produce match/other, senza ACK causale inventato. Modifiche esterne
prevalgono, nessun replay automatico della preferenza. Valori multi-byte e range
specifici: ad esempio chime payload a due byte, call assignment complementare,
Adaptive UI0–100 con inversione wire, STEM_CONFIG distinto dal ciclo modi.

Default locali Fluidify espliciti: nessuna automazione prima opt-in; relativo CA
attivo quando scelta la relativa policy, target iniziale40% del livello corrente,
pausa durante conversazione OFF; tutti modificabili senza alterare hardware a pagina
aperta. Le scelte incoerenti nei default LP non diventano stato cuffie. La preferenza
rapida è Adaptive se supportata, altrimenti Trasparenza. Batteria live cinque minuti
come contratto precedente e subito non live su disconnessione, storico con timestamp.
Questa soglia richiede verifica di frequenza RX nel gate reale; non adottare60s non misurati.

Ear removal/CA operano solo su eventi validi correnti con target e route pertinenti.
Un set di motivi pausa impedisce che la fine CA liberi una pausa ear ancora attiva.
Conservare baseline/ultimo write volume e token sessione/route, rampe cancellabili,
nessun doppio duck tra servizio/player/STREAM_MUSIC. Cambi manuali osservabili,
nuova app, route, chiamata o account invalidano i token pertinenti; niente PLAY in
cleanup. Confermare lo stato media osservato prima di segnare pausa posseduta.
Se il sistema non rende attribuibile un intervento o il target è ambiguo, evitare
la ripresa/ripristino automatici: non promettere di distinguere gesti non osservabili.

Logout Spotify invalida effetti sul vecchio account/sessione, ma mantiene l'opt-in
AirPods del dispositivo e la gestione di altre app. BT OFF/revoca/unpair/Arresta
annullano subito pending/effects e chiudono prima di cancellare scope; quando il
percorso è eseguibile, risorse rilasciate entro2s, senza nuovi tentativi. Morte processo
non garantisce onDestroy o rollback volume: test e limite espliciti, niente replay.

### Home e quick panel

Visibilità Home: enabled + setup verificato + coppia selezionata attualmente collegata,
indipendente dall'output Spotify Connect. Errore AACP transitorio con profilo ancora
connesso conserva la capsula con stato di errore; permesso revocato rende la connessione
non verificabile e nasconde la capsula. Mai mostrare cache come cuffie connesse.

Capsula misura target48dp; mostra icona e batteria auricolari fresca minima se c'è
spazio, con lato se unico valore noto. Ridurre a icona prima di sacrificare controlli;
riservare corsie reali per avatar e marchio visibile, tenendo conto di FriendFaces,
LocalPageEndInset e transizione Header/HomeTopBar. Nessun duplicato interattivo o
scala del vetro da antenato. Non cambiare feed/filtri o redesign del resto Home.

Quick panel riusato dal player: device/stato, batterie separate, modalità supportate,
CA hardware, volume media e stato automazioni; pulsante Impostazioni complete sempre
raggiungibile. Espansione ancorata al punto d'origine mediante host/portale Fluid
esistente, overlay senza reflow del player, max560dp e scroll finito negli inset.
Back/tap esterno chiudono prima il pannello. Stessa semantica RX/pending nella pagina
completa, che raggruppa tutte le voci della matrice e mantiene impostazioni locali offline.

Nel fullscreen player conservare la collocazione P1: capsula centrale in alto fra
chiusura e selettore dispositivi Connect, con batteria compatta e pulsante rapido
ANC↔modalità preferita quando supportato; il pulsante rapido non apre l'espansione.
Se la capsula è presente, il contesto playlist/album navigabile passa in basso a
sinistra del selettore cover/lyrics conservando callback; nessun pulsante contesto
se assente o non navigabile. P1 conserva nel player una coppia già verificata anche
disconnessa con azione Connetti, mentre la nuova Home è assente se disconnessa come
richiesto ora. Transienti non spostano continuamente il contesto; off/revoca/unpair
lo riportano alla posizione originale. In video immersivo segue il chrome esistente.
Le impostazioni complete e il quick panel non sostituiscono Spotify Connect né
trasferiscono la riproduzione remota. Supporto tablet e stato decoder/pannelli invariati.

## Accettazione dettagliata del nuovo gate

- Backend: golden packet pinned per ogni controllo incluso e dependency, malformati/
  troncati/multi-byte/endianness/layout variabili, no conferme da Unknown; modelli e
  capability con Unknown. Clock controllato per5s/10s e vecchie generazioni, close
  durante connect/read, writer serializzato e concorrenza due UI.
- Runtime/host: opt-in/stop/receiver dedup/start rifiutato/NLS revocato/pair isolata,
  stopExactlyOnce, niente riapertura dopo suppress; sessioni media fake per ear+CA
  sovrapposti, volume e pause manuali osservabili, cambio app/route/account e chiamata.
- Standalone fisico prima abilitazione UI: tre sessioni indipendenti su Pro3/S25
  con buildOS/firmware/versione app e baseline registrate, singolo connect/bootstrap,
  INFORMATION modello e batterie attribuite; capacità statiche dal modello identificate
  come tali, annuncio dedicato opzionale, supporto per proprietà con RX/readback C2.
  Tre cambi per modalità supportata e CA
  ON/OFF, con RX entro5s; comandi di ogni altra famiglia inclusa verificati per proprietà.
  Test espliciti head tracking senza takeover, disconnect best effort e range slider.
- Automazioni fisiche: altra app media attiva e Fluidify BG senza playback proprio;
  tre sequenze ear e tre conversazioni, override volume/pausa, sovrapposizioni, cambio
  app/route e logout; intervallo di10min a schermo spento senza musica Fluidify, inclusa
  pausa oltre90s. Le durate sono soglie pianificate, non risultati misurati.
  Le sequenze ear e i risultati attesi seguono la tabella C5, inclusi primo RX, uso
  mono, unknown e sovrapposizione CA; “reinserimento” non è un trigger generico.
- Cleanup: disattivazione, permessi e bond persi, stop sistema, servizio distrutto e
  kill/ricreazione nei casi osservabili; due minuti senza riaperture dopo Arresta.
  Non chiamare PASS a tutto se una funzione inclusa resta NV o non verificabile.
- UI: matrici F01–F04/F14, font e viewport definiti sopra, header con friend faces,
  ultima riga e back raggiungibili, overlay finito, temi/motion. Fixture verificano
  geometria/stato; reali vetro/decoder/scroll e interazione richiedono QA distinta.

Esiti: PASS solo per gli scenari effettivamente superati con evidenze attribuite;
FAIL tecnico per difetto riproducibile introdotto dal nuovo client; NV per prove
assenti/insufficienti. La consegna completa richiede copertura di tutte le voci
applicabili, due review del risultato e gate pertinenti chiusi. In assenza di telefono
si prosegue su backend/servizio/test/fixture autorizzati; nessuna dichiarazione di
prodotto completo o avanzamento live UI basata su sole prove host.

## Evidenze integrate e rubrica di review

Le tre discovery sono concluse: catalog/REPORT.md e catalog.json, architecture/REPORT.md,
ux/REPORT.md sotto `build/airpods-full-integration/`. Root ha ricontrollato36+49+133
copie manifestate senza mismatch; le tre vecchie cache differiscono dalle nuove
fonti solo per una riga vuota finale. Non è una nuova prova hardware.
Le decisioni root sopra risolvono l'interpretazione troppo ampia di “settings esterne”
del report UX e il trattamento iniziale Tile/disconnect del catalogo, senza alterare
retroattivamente i report o i congelati storici.

Rubrica P1–P8/H1 in `orchestration/review-criteria-F1.md`: completezza catalogo, UX,
servizio, protocollo/stato, automazioni, gate, confini/dipendenze, ownership. Due
reviewer nuovi Sol high sul medesimo F1 congelato; nessun voto F1 ancora disponibile.
Il primo giro correttivo F1 integra i cinque rilievi iniziali nei contratti C1–C5;
non attribuisce chiusura dei rilievi prima del riesame indipendente.
