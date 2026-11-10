# AirPods complete in Fluidify — stato della nuova fase

Nuova decisione esplicita dell'utente, 2026-11-08: integrare le funzioni e tutte le
impostazioni AirPods di LibrePods in Fluidify, escluse integrazioni come Xposed.
La vecchia ricerca di convivenza a tempo è chiusa; G1 rimane NV e I3 storico immutato.
Questo incarico amplia il precedente backend minimo, non certifica hardware.

## UI richiesta

- Capsula compatta in vetro nella Home, nello spazio fra marchio Fluidify e avatar
  indicato nello screenshot dell'utente, aggiuntiva ai controlli nel player.
- Visibilità solo dopo configurazione nelle impostazioni e con AirPods collegate.
- Tap: impostazioni rapide; ulteriore pulsante: impostazioni complete AirPods.
- Le impostazioni dipendono dalle capacità reali di modello/piattaforma; ogni voce
  upstream deve essere inventariata e inclusa o avere un'esclusione esplicita motivata.

## Flusso e vincoli

Riutilizzata configurazione agentic-workflow: agenti GPT-6.1 Sol high, massimo tre
implementatori/discovery concorrenti, due reviewer indipendenti per fase/giro;
massimo tre giri correttivi dopo l'iniziale. La nuova fase pianifica il nuovo
perimetro funzionale, senza azzerare i contatori storici I3/G1/C1–C4.
La UI usa Fluid Engine3.0.5 e componenti esistenti, senza aggiornare l'engine.

Nessun telefono disponibile per ora; nessun device/ADB/install o prova fisica.
Nessun widget, modifica alla LibrePods installata, root, Xposed, patch di sistema,
commit, push o release. Controlli esclusivi, nessuna simultaneità dichiarata.
Preservare tutte le modifiche concorrenti e i congelati storici.

Upstream main ricontrollato via connettore GitHub:
`89356c08cea3db0b6b54792d9329d09a87cc85fa`.
Un inventario completo deve precedere il piano revisionato e l'implementazione;
non basta copiare i toggle ANC/CA del precedente POC.

## Automazioni in background — decisione confermata

L'utente conferma esplicitamente: automazioni ear detection/pausa/ripresa/gestione
conversazione anche con altre app e Fluidify in background, come LibrePods, con
attivazione esplicita nelle impostazioni. La connessione AirPods deve avere un
ciclo di vita indipendente dal playback e dall'account Spotify di Fluidify;
la precedente tolleranza alla pausa90s del POC non definisce questo nuovo servizio.
Vanno pianificati opt-in generale e automazioni configurabili, notifica e arresto
del servizio, autorizzazioni Android pertinenti e rispetto degli interventi manuali.
I tre agenti discovery hanno ricevuto questa precisazione nello stesso incarico.

Root ha verificato i vincoli Android correnti per foreground service connectedDevice,
avvio dal background e accesso alle MediaSession esterne. Note e fonti primarie in
`build/airpods-full-integration/orchestration/android-host-contract.md`; nessun
permesso o servizio dichiarato funzionante dalla sola lettura delle API.

## Discovery assegnata

Perimetri separati sotto `build/airpods-full-integration/`: catalogo funzionale,
architettura/servizio/gate, UX Home/player/impostazioni. Nessuna scrittura prodotto
prima dell'integrazione del piano e delle due review concordate.
Ledger dei lanci: `build/airpods-full-integration/orchestration/discovery.json`.
Questo è lo stato iniziale, non un piano già approvato né un'implementazione completa.

## Bozza di integrazione F0

`airpods-full-integration-plan.md` raccoglie17 requisiti osservabili, contratti di
ownership del servizio/media, punti host e proposta di assegnazioni. La lettura
del codice corrente conferma che la Home usa una copia trasparente del marchio
per le misure e una copia visibile nella HomeTopBar: la capsula deve rispettare
entrambi i ruoli durante scroll/resize, non essere aggiunta a una riga isolata.
Il manifest Bluetooth è ancora confinato al debug; il nuovo servizio e i permessi
di produzione non esistono già. Questi sono gap verificati, non implementazioni.

Le tre discovery sono concluse e i risultati sono integrati in F0:38 identificatori
AACP,38 funzioni supplementari,67 chiavi e11 modelli. Le35 voci incluse/adattate sono
tracciate in `airpods-full-integration-coverage.md`; gli indici lessicali non sono
una prova di parità funzionale. Root ha verificato36+49+133 copie delle fonti.
F0 è pronto per congelamento e due review; nessun lavoro prodotto è stato anticipato
e nessun gate hardware è stato dichiarato superato.

Decisioni root esplicite: consenso Android NLS per targeting media esterno consentito
dal nuovo requisito, Tile normale inclusa, disconnect ordinario best effort distinto
da policy privilegiate, ATT/Xposed escluso con motivazione. Associazione/presenza CDM
proposta per la riattivazione background API31+, senza assumere eccezione universale
dal solo evento Bluetooth. Servizio e componenti ausiliari condividono un solo owner.

## Congelamento e review F0

Versione iniziale F0 della fase PIANIFICAZIONE completa congelata in
`build/review-airpods-full-F0`:270 file e1 input esterno readonly (screenshot utente).
Manifest SHA256 `ea4ed8eee1448d9ceb125c87398f545d88806712ab49274ac0a9a08efa39e15b`.
Due nuovi reviewer indipendenti GPT-6.1 Sol high avviati sullo stesso congelato,
con rubriche P1–P8 e hardwareH1 separato. Ledger in orchestration/reviews-F0.json.
Al lancio non erano disponibili voti; nessuna implementazione anticipata o PASS hardware.

## Preparazione dei test successiva al congelamento F0

Root ha preparato55 vettori TX di riferimento per22 ID e8 casi di rifiuto/range,
con quattro ancore hex indipendenti e hash delle fonti pinned. Include payload
multi-byte dei toni/chiamate, inversione Adaptive, maschere, lunghezza UTF-8 rename
ed EQ. Script e JSON in orchestration/prepare-wire-reference.py e
wire-reference-vectors.json. Il generatore passa i controlli propri; non sono
test del codec app o hardware e non ereditano le review F0. Nessun sorgente prodotto
modificato né build/dispositivo utilizzato. Il congelato F0 rimane immutato.

## Prima revisione correttiva F1 in preparazione

Entrambe le review F0 sono concluse. Report e tabella generale completi sono in
`airpods-full-integration-review-F0.md`, separati dai documenti tecnici da fornire
ai nuovi reviewer. F1 integra i cinque rilievi nei contratti operativi C1–C5:
preferenza formato tracking, evidenze per proprietà senza annuncio capability
obbligatorio, registrazione companion/API/manifest, gate storico uscita+stop utente,
tabella deterministica ear inclusi mono/unknown/overlap CA.

La preferenza use_alternate_head_tracking_packets è ora collegata semanticamente
a host.head_gestures nel catalogo/generatore e nella matrice. Fonti upstream immutate;
nessun nuovo ID necessario. I nuovi contratti prevalgono sulle formulazioni generali
delle discovery originali. Questo è CORRETTIVO1, non un reset del giro iniziale o
della storia I3/G1/C1–C4. Mancano ancora le due review F1; nessun codice prodotto
o gate hardware sbloccato dalle correzioni documentali.

F1 congelato in `build/review-airpods-full-F1`:273 file,1 input esterno readonly.
Manifest SHA256 `d0053b88dbe41b6b5f5927c4d6ec14bdaa9cff66f5dae52ecce6417639ddef64`.
Delta F0:4 contenuti aggiunti,7 cambiati,2 esclusi dal pacchetto (ledger task e
rubrica precedente), più indice delta. Nessun file prodotto cambiato tra i manifest.
Due nuovi reviewer indipendenti Sol high avviati sullo stesso F1, primo giro correttivo;
nessun voto F1 ancora disponibile. Ledger orchestration/reviews-F1.json.

## Riferimenti di test successivi al congelato F1

Il generatore locale ora comprende59 vettori per23 ID e8 requisiti negativi,
con7 ancore: aggiunte le quattro coppie payload start/stop standard/alternativo
per la preferenza tracking C1. Risultati fuori dal congelato F1; non ne ereditano
le review. Nessun codice app, test app, build o dispositivo eseguito.

## Piano F1 approvato, avvio implementazione

Ricevute entrambe le review indipendenti F1: tutti i criteri numerici individuali
almeno8, nessun difetto bloccante confermato. Report integrali e riepilogo generale
sono in `airpods-full-integration-review-F1.md`, separati dai documenti tecnici.
Le cinque correzioni sono approvate nella pianificazione, non già implementate.

R-D1 documentale non bloccante: conservato il vecchio artifact-manifest.json come
storico discovery e creato catalog/artifact-manifest-F1.json per150 file derivati
correnti, verificati senza mismatch. SHA25654fa2138bb86464b24a561063bcaf5f5d7aa3a1f6dec72d47df94195d2c332d3.
Il congelato F1 resta immutato; nessuna approvazione retroattiva degli hash vecchi.

Prima ondata d'implementazione: protocollo/stato, coordinatore automazioni puro,
policy avvio/storage/lifecycle in perimetri separati. Seguiranno adattatori Android
su contratti effettivi consegnati e interfacce/fixture secondo i gate F1. Root solo
integratore di manifest/Application/host/risorse/native/build e proprietario build.
Nessuna prova fisica o abilitazione UI reale anticipata.

## Prerequisiti Android integrati da root

Manifest main: dichiarazioni Bluetooth produzione con legacy maxSdk30,
FGS connectedDevice, osservazione/avvio companion e feature companion optional.
Nessun privilegio Bluetooth/telefono o scan/advertise aggiunto. Debug manifest
mantiene soltanto la diagnostica interna; nessun grant o avvio automatico introdotto.
Aggiunta regola R8 mirata al simbolo JNI AirPodsSocket; comportamento minificato
ancora da verificare, senza dedurlo dalla presenza della regola.

Nuove risorse dedicate airpods_strings.xml in values e values-it:98 stringhe per
locale, inclusi35 titoli setting e autorizzazioni/controlli/stati. Entrambe compilate
con aapt2 37.0.0; XML, chiavi uniche e diff root senza whitespace error. Non ? build
app o test hardware. Evidenze/hash in root-validation/prerequisites.json.

## Prima verifica eseguibile backend

Consegnati protocollo, automazioni e lifecycle/storage. Root ha eseguito
`:app:testDebugUnitTest --tests dev.lelonio.square.airpods.*`: compilazione Kotlin
produzione/test riuscita; prima esecuzione103/104, poi104/104 senza skip/errori.
L'unica differenza era la stringa non ASCII della fixture rename, codificata due
volte; corretta in escape U+00E8 senza cambiare i byte wire attesi. XML iniziale
e finali conservati in root-validation, con log distinti. Sono test JVM del backend,
non prove Android media/service, hardware, background o abilità UI reale.

Root ha aggiunto un getter internal read-only dell'epoch account in PlaybackService
per rifiutare effetti media del vecchio login, e una porta process-local condivisa
AirPodsMediaPort per i prossimi adattatori. Non avvia/binda playback o servizi.
Attribuzioni ampliate in LICENSES/LibrePods-AirPods.md, distinguendo POC storico
e implementazione completa in corso. G1 resta NV; I3 storico non viene riscritto.

## Seconda ondata e visibilità Application

Avviati tre implementatori Sol high: servizio/companion/setup, adattatore media,
UI completa. Brief e taskId conservati in orchestration/implementation-wave2*.json.
Root ha aggiunto AirPodsAppVisibility e il solo hook inerte in SquareApplication:
Activity RESUMED osservate, nessuna acquisizione Bluetooth/FGS/consenso. Consegna
inviata ai due owner Android. Quattro regressioni Robolectric preparate ma non
ancora eseguite; hash/limiti in root-validation/visibility-handoff.json. Le prossime
build centralizzate attendono le scritture stabilizzate dei tre owner.

## Migrazione POC in sola lettura

Root ha ritirato le API di scrittura AirPodsPreferences, prive di chiamanti attivi.
La sola API suspend migrateInto(store) importa la cache candidata/preferenza locale
su IO e conserva l'originale anche in caso di errore. Abilitazione, modello/build
memorizzati e vecchi consensi non diventano autorizzazione o verifica del setup.
Consegna inviata all'owner Android; cinque test Robolectric preparati, ancora non
eseguiti. Non cambia il risultato storico104/104, relativo al backend prima di
questi hook root; serve la prossima verifica integrata per i nuovi adattatori.

## Risorse piattaforma e prima consegna media Android

Root ha creato un'icona vettoriale originale per notifica/Tile e la risorsa di
abilitazione companion false prima diAPI31, true daAPI31. Tre risorse parse e
compilate con aapt2; resa visiva e componente companion ancora da verificare.

Ricevuta la consegna media Android:13 test preparati, non eseguiti; NLS osservativo
dichiarato da root con BIND_NOTIFICATION_LISTENER_SERVICE. Nessuna acquisizione
FGS/socket dal binding. Bridge locale PlaybackService ancora da integrare.
Limite funzionale concreto: i candidati esterni sono attualmente esclusi per
route attiva non attribuita, quindi automazioni altre app RESTANO APERTE, non
considerate completate. Approfondimento mirato prima di accettare quel confine.

## Verifica seconda ondata e scelta utente C6

Root ha corretto la nullability del callback MediaController del nuovo SDK:
onAudioInfoChanged riceve PlaybackInfo non null. Compilazione e test mirati
successivi PASS:255 test, zero failure/error/skip, inclusi102 metodi UI/fixture.
XML copiati in root-validation/wave2-pass. Screenshot registrati: non ancora
equivalenti a QA fisica, navigazione applicativa o decoder reale.

L'utente ha aumentato il limite a5 implementatori contemporanei e4 reviewer;
orchestration/workflow-current.json è il riferimento operativo. Nessun reset storico.

Dopo la ricerca mirata sui media Android l'utente ha scelto esplicitamente
«Best effort come LibrePods» per le altre app. Contratto C6 aggiunto con opt-in
dedicato, provenienza di routing/ownership distinta dalle ricevute corroborate
locali e blocchi sugli eventi osservabili. Funzione ancora da implementare;
255/255 è l'evidenza precedente a C6, non ne certifica codice o comportamento.
Il gate fisico standalone rimane aperto e G1 rimane NV.

## Host installato e verifica corrente322/322

Root ha collegato l'host opzionale in Application tramite AirPodsApplicationStartup,
con media factory locale e conformance fisica ancora false. Nessun grant o socket
all'avvio; fallimento opzionale non impedisce al player di avviarsi. Il test
Robolectric del bootstrap è incluso nella verifica seguente, non prova un avvio
Android/Samsung reale.

Richieste host1/2/4 integrate: gate pubblicato prima di attach, refresh informazioni
guardato, raccolta diagnostica esplicita OFF di default con start/stop reali.
La seconda esecuzione ha rilevato fixture Robolectric con PID0 e un test che
scorreva il footer ora fisso. Corretti i PID di test (guardia produzione conservata)
e verificata la visibilità del footer senza scroll. Corretto anche l'uso di un
vecchio getter generico Robolectric. Compilazione e322 test finali PASS, zero
failure/error/skip; XML/log in root-validation/wave3-pass e wave3-tests-final.log.
Root ha ispezionato il nuovo render tablet quick: batterie compatte, modalità
in due colonne e accesso alle impostazioni nel footer visibile. È una fixture,
non una schermata Samsung o una misura AGSL/GPU.

Restano requisiti software aperti espliciti: ricevute/ownership locali per ripresa
e CA, route ExoPlayer, corsia di collaudo funzionale debug senza falso PASS prodotto.
I test attuali coprono le guardie anche quando queste funzioni non sono ancora
disponibili; non sostituiscono la loro implementazione. Nessun gate rimosso.

Build centralizzata app/Wear debug firmata e test Wear completati:150/150, zero
failure/error/skip. APK app checkpoint SHA256bb9b9679efd7f72a829975fc21dddb5ecfc9660b754fb2089a54ec0879d6a49c.
Firma v2, zipalign16KB e SPAKE2 ELF16KB entrambe ABI verificati. DEX definisce
248 classi AirPods,67 UI e9 del prefisso bridge rispetto alle classi compilate.
È un controllo circoscritto del binario debug, non linkage completo o equivalenza
dei corpi; nessuna installazione. Hash/XML/log in root-validation/wave3-artifacts;
i percorsi APK normali sono mutabili e questo non è l'artefatto finale.

## Integrazione wave 4 — 2026-11-08

Consegne locali, debug funzionale, adattatori piattaforma e Home integrate in uno
snapshot compilabile. Root ha corretto due getter generici Robolectric e aggiornato
le prove diagnostiche al nuovo Activity senza owner privato: apertura/permesso
negato non acquisiscono trasporto né chiudono una lease appartenente ad altro owner.
Il vecchio test accedeva a un campo eliminato prima del proprio finally, provocando
una perdita della lease di test e tre fallimenti successivi. Nessun reset globale
o indebolimento dell'esclusione produzione/diagnostica.

Geometria Home: misurato il vero gruppo avatar/amici invece di uno spacer senza
area e spostato il tag del marchio dopo la trasformazione visiva. Centraggio e
assenza sovrapposizioni restano verificati con la stessa tolleranza. Root ha
ispezionato le fixture 360×640 e 720×360; non sono prove Samsung/AGSL.

Integrazione volume: una notifica Settings invariata non genera ownership, ma non
cancella più una precedente scrittura controllata con readback esatto. Divergenza
osservata di livello/massimo, comandi manuali e cambi route/focus/account revocano
permanentemente; intenti esterni senza variazione osservabile restano ignoti.
Il collaudo debug non mantiene più un wake lock aggiuntivo: usa le risorse della
produzione. La scadenza monotona blocca comandi anche se il sonno ritarda cleanup;
nessuna garanzia di wakeup/chiusura al millisecondo dedotta dal timer.
Dettagli in root-validation/wave4-root-integration.md.

Verifica centralizzata finale: **380/380 test mirati PASS**, zero failure/error/skip,
incluse ricevute locali, ear resume/CA, sink Exo, host, adattatori ordinari e fixture
Home aggiornate. Prima esecuzione 369/380; errore iniziale, log e XML conservati.
Archivio corrente: build/airpods-full-integration/root-validation/wave4-pass;
log wave4-tests-final.log. Nessuna nuova build APK/minificata o installazione.

Restano software da completare: wiring degli adattatori audio/assistente nel vero
host/runtime, gesti testa per chiamate entranti upstream, verifica di coerenza Tile.
Produttore aggiunto alle informazioni complete. La review della nuova fase di
implementazione non è iniziata; nessun giro correttivo consumato da queste wave.
Gate standalone H1 pendente/NV e G1 storico NV conservati. Telefono non usato.

Verifica condivisa aggiuntiva: **18/18 core + 150/150 Wear PASS**, zero
failure/error/skip, e assemble Wear riuscito dopo le modifiche locali a focus e
LibrespotPlayer. Esecuzione limitata ai moduli stabili core/Wear mentre le sole
sorgenti app della wave5 sono assegnate all'implementatore. Nessuna compilazione
app concorrente alle sue scritture. Log wave4-core-wear.log e XML/hash in
root-validation/wave4-core-wear-pass; APK Wear checkpoint non installato.

Preparata la rubrica della futura review d'implementazione, quattro reviewer Sol
high come richiesto, in orchestration/review-criteria-implementation.md. Non è un
lancio/review già eseguita: prima servono consegna wave5, integrazione stabile e
artefatti finali. Preparati docs/airpods-standalone-validation.md e ledger hardware
con35 famiglie, tre sessioni e15 scenari inizializzati NV; non sono prove svolte.

Controllo root successivo del percorso locale: aggiunta invalidazione per i
comandi seekBack/seekForward/seekToDefaultPosition delegati, anche senza cambio
effettivo di stato. Due regressioni preparate non eseguite; queste modifiche sono
successive allo snapshot380 e attendono il prossimo test centrale ad app stabile.
Nessuna modifica nei file del responsabile wave5; nessun PASS esteso implicitamente.

## Integrazione stabile e preparazione review I0 completa — 2026-11-08

Wave5 consegnata, tutti gli implementatori terminali. Integrati audio/assistente
ordinari, chiamate nod/shake con permessi espliciti opzionali, tracking dopo
riconnessione e Tile ANC/Trasparenza con guardie. Invio accettato non è effetto
confermato; limiti Telecom, A2DP, OEM e background rimangono dichiarati.

Root ha corretto il caso PAUSED prima della ricevuta sink/engine: richiesta
pendente mantenuta entro deadline senza concedere ownership dal solo stato.
Conferma corrispondente, override, mismatch, scadenza e rilascio sono distinti.
Corretto anche il salto Main privo di lavoro quando manca il port media; il test
Robolectric sospeso è stato diagnosticato con thread dump e fermando soltanto il
worker Fluidify18488 verificato. Nessun daemon Gradle o altra sessione chiuso.
Tre getter generici di test aggiornati; regressioni e negativo abortito conservati.

Verifica estesa: **555 test app PASS**, zero failure/error/skip. Comprende nuove
automazioni, controller/host, UI, data/Listening, auth/download, Connect/player,
tema, Wear pending e bridge. Registrazione screenshot AudioLight estranea esclusa
per preservarne le baseline. Core18/Wear150 PASS archiviati, core invariato dopo.
Build debug, R8/minificata e Wear debug riuscite. Versione2.0.1/code24 invariata;
nessuna pubblicazione/installazione. Firma e ZIP16KB dei tre APK verificati;
ELF64 LOAD16KB per14 librerie debug e7 minificate. Debug arm64/x86_64, minificata
arm64. La prima richiesta del checker di entrambe le ABI era fuori dall'ambito
dell'APK minificato: corretto il comando, senza cambiare il packaging.

DEX debug:296 classi AirPods,67 UI,10 bridge,27 auth presenti come definizioni.
Classe JNI AirPodsSocket mantenuta nel minificato. I controlli non provano linkage
totale, equivalenza corpi o comportamento live dopo una futura abilitazione del
gate; conformance prodotto resta false. Nessun full lint PASS assunto.

Congelato **build/review-airpods-full-implementation-I0-stable**:1130 file e uno
screenshot utente esterno privato, tutti riverificati per hash/dimensione.
Manifest SHA256 **77cfdf2a2e9561a0a4b91802217e77b010a2bcb85c5b87dd8c24b5abf3b659bb**.
Il precedente tentativo I0 incompleto ha avuto collisione di nomi fra directory
screenshot e non ha prodotto un manifest/review; è conservato e non va usato.
I0-stable resta il giro INIZIALE della nuova implementazione completa, senza reset
di F1, I3, G1 o C1–C4. Stato, voti e vecchi verdetti sono esclusi dalla lettura
obbligatoria dei nuovi reviewer. H1 fisico resta NV; telefono non utilizzato.

## Review I0 completa — correzioni richieste

Raccolti e salvati tutti i quattro report A/B/C/D, Sol high, terminali e senza
deleghe pendenti. Tabella generale in docs/airpods-full-integration-review-I0.md,
voti originali nei report -A/-B/-C/-D; NV esclusi da medie/minimi con conteggi
espliciti. I0 non supera la soglia: tre difetti alti (background esterno OFF,
ricevuta volume persistente, proxy companion tardivo), tre medi (unpair, prima CA,
revoca consenso) e un sottotitolo setup errato. A1/D1 sono duplicati consolidati.
Nessuna media compensa i difetti confermati e nessun NV hardware diventa zero.

Avviata preparazione del primo giro correttivo I1 della nuova implementazione,
senza reset storici. Root ha corretto FI0-06/D2 e FI0-07/D3: disponibilità distinta
ON/OFF, revoca sulla coppia selezionata anche senza NLS/media/BT operativo, guardia
stale-pair conservata, sottotitolo neutro per setup abilitato. Sette test mirati
PASS, zero failure/error/skip (tre regressioni dispatcher e una row UI aggiunte);
log I1-ui-corrections.log/XML I1-ui-pass. È verifica circoscritta, non review I1 o
nuova build finale. Il precedente snapshot/APK I0 rimane immutato e identificato.

Preparati due incarichi esclusivi per lifecycle B1/B2 e media A1/D1/C-R1/C-R2,
con regressioni integrate e senza build concorrenti o telefono. Dopo consegna:
integrazione, suite estesa/build/artefatti aggiornati e quattro reviewer freschi
su I1 stabile. Tre giri correttivi massimi dopo l'iniziale, nessun azzeramento.
Conformance prodotto FALSE, H1 standalone NV, G1 storico NV.

## I1 — integrazione e verifica centrale prima della review

Ricevute entrambe le consegne lifecycle e media. Root ha corretto un riferimento
device mancante nella nuova interfaccia e ha riprodotto un ulteriore caso volume:
player sopravvissuto al coordinatore con ricevuta1, nuovo comando1, rampa fermata
a35 anziché35→20→50. Gli identificativi ora evitano le ricevute già presenti,
senza trasformare lo stato coincidente in conferma. Regressioni positive/negative
locali e C6 passate. Aggiunti due test del proxy osservativo Home.

27 test mirati e586 app estesi PASS, zero failure/error/skip; nuovi APK debug,
R8/minificato e Wear compilati.15 controlli artefatti PASS. Core18/Wear150 restano
risultati storici:134 file core/Wear/Engine del precedente congelato invariati,
APK Wear identico. Nessuna esecuzione ripetuta di quelle suite rivendicata.

Evidenze in root-validation/I1-*. Le consegne degli autori mantengono gli hash
originari: il manifest I1 identificherà l'integrazione root effettiva. Si prepara
il congelato I1 e la prima review correttiva con quattro nuovi reviewer, senza
esporre loro voti/verdetti precedenti. Nessuna installazione o uso telefono;
conformance FALSE, H1 NV. Limiti UI host completo/ottica Samsung restano espliciti.

Congelato I1-stable:1166 file più riferimento screenshot esterno privato,
tutti riverificati senza mismatch. Manifest SHA256
51627c5cb141cabccf85d95ca24f2430cf11bccbcd72c22d21e9bd0b5d198dac.
Delta source I0→I1:26 aggiunte di contesto/test/strumenti,10 cambiati,2 sostituzioni
di indici/facts; otto file prodotto app effettivamente cambiati, nessun Engine o
core/Wear modificato. APK debug e99311a4…8160745, minificato897ee306…641debec,
Wear7c8067be…dfc18564. Pronta la prima review correttiva I1, quattro reviewer
freschi Sol high, focus catalogo/protocollo,lifecycle,media,UI/artefatti.

Preparata separatamente docs/airpods-standalone-I1-regressions.md, senza modificare
il congelato: esiti fisici attesi per consensi background, rampa/ricevute CA,
primo evento live, companion tardivo, unpair e revoca OFF. Nessuna prova eseguita.
Audit host del registro hardware:35 ID e disposizioni coincidono con il catalogo,
incluso include_best_effort_NV per disconnect_unworn;3 sessioni e15 scenari restano
NV, metadati fisici non raccolti. Il primo filtro dell'audit aveva omesso quella
disposizione; conservato il record e corretto il filtro, senza cambiare catalogo,
registro o codice. I1-preparation-audit-corrected.json è il record corretto.

## Review I1 completa — secondo correttivo I2 necessario

Raccolti tutti i quattro report originali in docs/airpods-full-integration-review-I1-*.md.
Tabella generale fedele e aggregati con conteggi NV esclusi in review-I1.md e
orchestration/review-I1-scores.json. Sette rilievi medi confermati: Tile ridotta a
due modalità; recupero disassociazione fallita senza destinatario durevole; quattro
problemi UI (testo batteria tagliato, minimo include custodia, ragioni generiche,
stato automazioni quick assente); quattro evidenze raw dichiarate conservate ma
omesse dal congelato. I1 non approvato. Nessuna media nasconde i difetti.

Root ha corretto il selettore Tile condiviso fra disponibilità e dispatch: ciclo
Off/Trasparenza/Adaptive/ANC filtrato da modello, restrizioni RX correnti e pending.
Cinque regressioni tabellari e tre componenti Android inerti PASS, otto totali.
Log/XML I2-tile-targeted.log/I2-tile-pass. Non è una build finale I2 o prova radio.
Il primo audit visivo root su quattro frame era parziale; due frame font1.5/2
aggiuntivi confermano la batteria tagliata segnalata da D, da correggere.

Preparati due incarichi I2 per cleanup durevole e UI, perimetri disgiunti.
Root mantiene pacchetto/log e integrazione del retry UI dopo gli handoff.
I2 è il secondo correttivo dopo iniziale, non un reset; restano i gate esistenti.
Conformance FALSE, H1/G1 NV. Nessuna risorsa telefono acquisita.

FI1-03/C-01: tools/check-review-package.py verifica in sola lettura i file
obbligatori e la loro appartenenza/dimensione/hash nel manifest. Negativo sul
congelato I1:exit1, otto file raw mancanti. Creato nuovo supplemento immutabile
build/review-airpods-full-I1-evidence-supplement,11 file manifestati; SHA256
8fad8bf888a531294b874bbdd27ce7c598829affae817c9395a11d1f890cde95.
Positivo sul supplemento:exit0, otto originali presenti e corrispondenti. XML
originali27 PASS; non un nuovo run. Record C01-I1-negative.json e
C01-supplement-positive.json. I1 storico non modificato o rivalutato.

Preparati, NON eseguiti, freezer/checker artefatti I2:24 riferimenti obbligatori,
inclusi gli originali I1 e le future prove I2. Il freezer verifica la presenza
prima del manifest, l'identità dei raw rispetto al supplemento e il checker
finale dopo la copia. Mancano ancora consegne/integrazione/build I2: nessun freeze
o PASS relativo eseguito. Lavoro indipendente dai due writer UI/cleanup.

Configurazione aggiornata su istruzione successiva dell'utente: massimo6
implementatori contemporanei; nessun tetto utente per i reviewer, numero
proporzionato scelto dal root entro le capacità effettive. Previsti5 reviewer
freschi I2, separando UI da evidenze/artefatti. Il limite dei giri correttivi non
è cambiato e i quattro reviewer effettivi I0/I1 restano quelli storici.

Consegne I2 UI e cleanup terminali e ricevute. Root ha collegato RETRY_CLEANUP
dedicato alle impostazioni, anche senza selezione o con gate prodotto FALSE;
non usa Dimentica e non acquisisce servizio/socket. Mostra conteggio persistito
e ragione tipizzata del fallimento; dopo successo rilegge il registro. Aggiunte
due regressioni del dispatcher reale con nuova coppia e stampa stale.

Prima esecuzione mirata187 test:180 PASS,7 failure UI/fixture, archiviate raw/XML
I2-targeted-initial-results. Diagnostica successiva3 test:1 PASS,2 failure,
archiviate anch'esse. Individuati layout testo (37px container vs48px paragrafo),
selettore accessibilità precedente all'aggiunta della batteria, fixture che
tentava di cambiare verification da configure e proxy SDK35 privo dei metodi
Object/adapter. Correzioni root in verifica; nessun PASS finale anticipato.
I2freezer ora richiede anche questi4 record iniziali,28 file obbligatori totali.

Successivo run mirato187/187 PASS. Suite estesa625:624 PASS e una failure nella
fixture v1 AndroidRuntimeContractsTest che ritaglia il corrente schema3 come se
fosse v2. Archivio I2-extended-initial-results e I2-extended-initial.log preservato;
nessun PASS esteso o build finale I2 attribuito. Correzione della fixture richiesta.

Utente rimuove anche il tetto implementatori; root sceglie incarichi proporzionati
entro capacità effettiva, serializzando build e device. Autorizza audit opportunistico
app: bug dimostrati correggibili senza richiesta, cambi concettuali UX da proporre
prima, Engine separato e AirPods priorità. Nessun nuovo thread creato.

Preparati un nuovo incarico AirPods per verificare l'ipotesi del fingerprint CDM
mutabile dopo stop e correggerla solo se confermata con fonti primarie, più la
fixture v1; e una discovery in lettura su playlist/ricerca/offline con perimetro
separato. Nessun nuovo reviewer I2 o build mentre il writer runtime è attivo.
Conformance FALSE, H1/G1 NV; nessun accesso al telefono.


## Pre-freeze provenance correction ? 2026-11-08 15:04:12 UTC

Serial validator returned the baton: 360 targeted and 685 extended overlapping tests passed, three builds and 24 artifact checks passed on its fingerprinted baseline. Root then reproduced two synthetic native diagnostic misclassifications (port443/duration500) using the compiled classifier and applied a two-file minimal correction with regression. These old execution results are preserved as pre-fix evidence. A fresh finalization will identify current inputs/APKs before the I2 freeze. Product FALSE; H1/G1 NV; phone assigned but not used. No formal review count reset.


## Review and device preparation ? 2026-11-08T15:16:57.402072+00:00

Prepared independent I2 current-facts entry point without historical votes, explicit terminal input/artifact selection in freezer, and four host guard cases (matching accepted; test/build drift, stale live source and escaping path rejected). Wrote the unexecuted device-session runbook against actual debug harness controls; H1 and production conformance unchanged. r3 validator retains the exclusive Gradle/app-source baton. No phone operation or new hardware evidence.


## I2 stable frozen and independent reviews launched ? 2026-11-08 15:25:36 UTC

Snapshot build/review-airpods-full-implementation-I2-stable, manifest SHA256 a572b80b04a54a37beb333ac57638a03c6e485318457a97f0258ea6edd6e39b3. Root verified2679 files and1 private external input, zero mismatch; mandatory package checker62/62. Terminal r3 executed8 targeted and686 extended overlapping tests,3 builds and24 checks. Six fresh Sol high reviewer roles planned, first three A/B/F launched; C/D/E queued within actual capacity. Same frozen candidate and rubric. Formal CORRECTIVE2 unchanged. ProductFALSE; H1/G1NV; phone assigned, not used. Gradle remains free; no production writes during reviews.


## Read-only phone preflight ? 2026-11-08T15:27:38.724691+00:00

Assigned SM-S931B is booted on Android17/API37. Installed Fluidify APK remains historical I3 dd5e5120...46d2d2 and the verified signer matches frozen I2 phone candidates, so no signature-mismatch uninstall is required. No install, UI, Bluetooth operation, permission/settings change or hardware session; no Device panel acquired. LibrePods active-channel state not measured. Evidence validation/I2-device-preflight. Frozen I2 unchanged; H1/G1NV, productFALSE.


## I2 review partial ? 2026-11-08T15:32:48.865421+00:00

A/B/F delivered. A: I1=7, I4/I6/I7=8; medium Tile refresh finding, source deduction not hardware. B: I3/I6/I7/I8=8, other criteriaNV;37 personal host assertions. F:S1=8;I6=8 provisional/partial excluded from full I6 aggregates, other criteriaNV. C/D/E freshly launched on the identical frozen snapshot. All six planned reviewers now launched, three terminal/three working. No complete-round decision or hardware approval; productFALSE H1/G1NV. Source writes remain deferred until findings consolidated.


## I2 complete review decision ? 2026-11-08T15:41:58.727368+00:00

All6 independent Sol high reviews delivered; software I2 not approved. Consolidated table docs/airpods-full-integration-review-I2.md, raw individual reports preserved. Confirmed FI2-01 Tile refresh (medium), FI2-02 ear UNKNOWN invalidates CA-only pause (medium), FI2-03 onboarding modal host occlusion (high). Six stem preference dispositions still need source-based closure, no automatic exclusion. Proceed within authorized third corrective round I3; no counter reset or product enable. ProductFALSE/H1/G1NV.


## I3 corrective writers started ? 2026-11-08T15:46:38.645878+00:00

Root checked1356 live inputs still equal terminal I2 baseline before writers. Three fresh Sol high tasks started on disjoint Tile/media/UI scopes, no child build/device/Gradle. This is formal CORRECTIVE3 of3 after initial, no reset. Central validation follows terminal handoffs; six stem preference dispositions still require source tracing when capacity frees. Frozen I2 remains immutable. No product enable; H1/G1NV.


## I3 serial validation assigned ? 2026-11-08T15:57:14.842754+00:00

All3 app writers terminal/delivered. Fresh serial validator owns Gradle/app-source, no other app edits.28 new regressions prepared, execution not yet claimed. Targeted, current extended, new APK builds/checker and targeted optical onboarding inspection assigned with raw preservation. Stem-disposition researcher is isolated read-only on pinned source plus own report/proposed patch, no app/catalog edits. ProductFALSE/H1/G1NV.


## I3 root integration after targeted failure ? 2026-11-08T16:14:04.382740+00:00

First94 targeted tests:87 passed/7 failed, originals preserved; no extended/build/APK checks. Root moved the pointer barrier behind pages to avoid merging PaneTitle semantics and added a fixture-only Tile framework teardown shadow; real component lifecycle/observer and interaction assertions remain. No new pass claimed yet. Applied bounded6-stem metadata disposition and regenerated catalog: six records only gainedfunctional_disposition, controls/features/models unchanged,133originalblobs and historical manifests byte-identical, current152-filemanifest verifies. New serial r2 remains within unfrozen CORRECTIVE3; no round reset. ProductFALSE/H1/G1NV.


## I3 review readiness references ? 2026-11-08T16:19:20.815947+00:00

Prepared I3 freezer/required-evidence mapping without executing a freeze. Current facts remain unwritten until terminal validation results. Common rubric preserves IDs/thresholds and adds discriminating checks for existing residual-key, modal hierarchy, pause-origin and fixture-scope requirements; six fresh roles remain planned. This is preparation only, not a pass or review launch. Validator I3-r2 owns source/build baton; productFALSE/H1/G1NV.


## I3-r2 validation terminal and optical follow-up ? 2026-11-08T16:30:52.661062+00:00

Fresh94/94targeted,714/714extended,3build and24checksPASS,1360inputs no drift; source manifest8c0e580aaae722d971d6d5c0a3fcc9d6923604f49ad276005112082576bfe0af. Debug0fc7bf3289623abbb7b6624b35ed6fbfd0dca0912cdbf639e268beae8b00cfd3, minified9b0d760fa913eeb9643e934fdf6fc81128afaea98521aed5fe239b6b384de7a7, Wearunchanged. Two freshPNG show interaction/occlusion fixed, but Forget background bleed remains an observed optical concern in nativegraphics fixture. Root also viewed both. Source sampling differs intentionally between context/alert and standard popover; no unproven Enginebug claim or Engineedit. Fresh read-only rendering diagnosis assigned before freeze. ProductFALSE/H1/G1NV, no phone action or release.


## I3 optical source diagnosis and bounded correction ? 2026-11-08T16:46:27.017120+00:00

Read-only specialist confirmed transparent page backdrop source violates the documented Engine source contract: SquareTheme backgroundTransparent, localPageHost omitted pageColor. Full optical causation remains to be tested. No confirmed Engine defect or Robolectric limitation inferred. Root prepared3 rendered-page tests against hostile underlay across light/dark/AMOLED; production still unchanged for negative control. Approved exact one-line app pageColor=existingpageFloor only after expected failures; no Engine edit. Fresh serial r3 will preserve red/baselines and verify positive rendering/tests/APK. Still unfrozen CORRECTIVE3, no reset, productFALSE/H1/G1NV.
