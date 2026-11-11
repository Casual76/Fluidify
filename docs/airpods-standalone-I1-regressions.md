# Collaudo standalone — regressioni I1 da osservare

**NON ESEGUITO. H1 NV.** Integrazione della checklist
[standalone](airpods-standalone-validation.md), senza nuovi consensi o accesso al
telefono. Il congelato I1 e gli incarichi di review restano immutati. Questa
checklist non approva I1 e non abilita il prodotto prima delle review e del gate
fisico già previsto. Nessun risultato sintetico sostituisce osservazioni reali.

Le righe seguenti precisano gli esiti attesi degli scenari già presenti nel
registro hardware. Non introducono nuove funzioni o un criterio automatico di PASS.

| Scenario del registro | Preparazione e azione, solo dopo disponibilità autorizzata | Evidenza ed esito atteso |
|---|---|---|
| `external-c6`, background | Con una sola sessione esterna eleggibile, C6 e automazioni ON, lasciare background OFF. Dopo aver nascosto Fluidify, generare nuovi eventi ear/CA; provare separatamente anche il passaggio in background con un effetto già accodato. Ripetere visibile e poi con background ON esplicito. | Registrare visibilità, tre consensi distinti, RX e dispatch con timestamp. Con background OFF e UI nascosta: nessun nuovo comando host, nessuna ripresa o restore dal cleanup. La ricezione degli eventi non è un effetto autorizzato. Visibile o background ON: valgono gli ulteriori vincoli C6, non una garanzia sulla route dell'app esterna. |
| `three-complete-conversations`, locale e C6 | Partire da un livello noto, lasciare terminare una conversazione e ripeterne un'altra; scegliere una transizione con più di un passo di rampa osservabile. Eseguire separatamente Spotify locale, file/video e target esterno. | Distinguere livello reale, comando, ricevuta locale o correlazione C6. Rampa fino all'obiettivo e restore del singolo livello iniziale solo con ownership valida. La ricevuta del passo precedente può restare visibile: non deve bloccare il successivo né confermarlo da sola. Una sessione/proprietario nuovo non acquisisce un vecchio comando come proprio. |
| `three-complete-conversations`, primo evento | Registrare l'intera apertura e il primo evento CA effettivamente ricevuto. Non aggiungere un inattivo sintetico e non richiamare manualmente callback interne. | Se il primo evento live è attivo e il target è autorizzato, deve applicarsi la policy host. Una cache non deve generare effetti. Se l'hardware invia prima un inattivo, annotare quella sequenza; non dichiarare riprodotto il diverso caso del primo attivo. |
| `manual-overrides-and-overlap` | Durante duck o restore: cambiare volume manualmente, cambiare target/route oppure revocare il consenso. Ripetere ear+CA nei due ordini già richiesti da C5. | Divergenza/intervento osservato revoca l'ownership, cancella il residuo e non emette restore o PLAY di cleanup. I motivi ear/CA restano distinti. PAUSE manuale già PAUSED o intento volume con identico valore possono essere invisibili su altre app: registrarli come limite C6, senza inventare ricevute causali. |
| `staged-bootstrap`, companion | Da un ingresso background consentito registrare callback companion, disponibilità effettiva del proxy A2DP e admission. Non generare un nuovo broadcast o un secondo tentativo per mascherare un ingresso perduto. | Se il proxy arriva dopo l'ingresso, una sola rivalutazione attraverso C3/C4; stop/revoca/cambio coppia o associazione prima della disponibilità impediscono FGS/socket. Se l'ordine osservato è diverso, riportarlo: non è una riproduzione fisica della race sintetica. Nessun retry AACP cieco. |
| `stop-revoke-unpair-system-exit` | Verificare separatamente perdita del bond selezionato, permesso Bluetooth revocato e transizione di pairing. Ripetere con una sessione e senza ticket attivo. | Solo BOND_NONE attuale autorizza cleanup UNPAIRED, rimozione selezione/verification e della sola associazione Fluidify. UNKNOWN/BONDING o accesso negato non provano unpair. Nessun callback vecchio riapre la sessione. Stop e storico di uscita conservano la precedenza C4. |
| `stop-revoke-unpair-system-exit`, impostazioni | Con una preferenza salvata ON, rendere assente un prerequisito di attivazione, per esempio revocare NLS. Riaprire le impostazioni e disattivarla sulla stessa coppia. | OFF persiste senza riattivare NLS, media, servizio o connessione. ON conserva i prerequisiti. Un'azione riferita a una coppia precedente non modifica quella nuova. Un'azione setup disponibile non mostra una spiegazione di indisponibilità. |

Usare gli stessi tre ingressi/sessioni e gli stessi riferimenti evidenza della
checklist principale. Un caso non osservato resta non riprodotto; una riga di
questa tabella, un log TX o un test JVM passato non costituiscono prova fisica.
Nessuna modifica a LibrePods, impostazioni di sistema o privilegi è autorizzata
da questo documento. Il prodotto resta conformance FALSE.
