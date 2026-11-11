# Checkpoint della ricerca di convivenza

Aggiornamento finale del 2026-11-08, successivo a C4. La finestra assegnata è terminata
**2026-11-08 03:52:42 UTC / 04:52:42 Europe/Rome**. Ricerca a tempo chiusa, senza proroga.
Decisione ed evidenze: [chiusura delle otto ore](airpods-coexistence-decision.md).

Nessuna soluzione completa dimostrata. Il client Fluidify singolo ha ricevuto
identità/batterie in una prova storica; il tentativo concorrente G1 fallisce nella
registrazione locale prima dell'handshake. Non prova un veto universale del firmware.
Il codice Samsung permette due Register con risorse libere; non prova che quei
posti siano disponibili sul target, né due reader funzionanti o durata.

| Requisito del mandato | Evidenza attuale | Stato |
|---|---|---|
| Ricerca sistematica del percorso completo | Registro C01–C13, fonti e controlli congelati C4 | Completata per le piste concrete esaminate; non esaustività universale |
| Due review indipendenti | Report integrali e riepilogo in airpods-coexistence-review-C4.md | Completate; qualità della ricerca separata dal prodotto |
| LP invariata, niente impostazioni/helper/widget/privilegi | Perimetri e azioni della ricerca; nessun accesso al telefono | Vincoli conservati |
| Lavoro I3 e modifiche concorrenti preservati | Nessun delta nei file prodotto AirPods monitorati; snapshot/artefatto I3 conservati | Verificato nel perimetro; non certifica tutto il checkout concorrente |
| Catena endpoint→apertura→bootstrap→TX/RX→freschezza→permessi→background | Nessun candidato completo; gap dichiarati per ogni percorso | Non dimostrata |
| Prova fisica con LP attiva e tre sessioni complete | G1 negativo all'apertura; successivi scenari indisponibili per mandato | NV, non sostituita da prove host |
| Decisione temporale e ripiego autorizzato | Consegna attivata dal checkpoint alla scadenza | Ricerca chiusa; percorso esclusivo scelto, implementazione e collaudi ancora aperti |

C4 è il terzo giro correttivo dopo l'iniziale della ricerca; i contatori non sono
azzerati. I giudizi sono negli archivi separati dai documenti tecnici obbligatori.
Il gate hardware rimane NV; nessuna UI AirPods, A2DP o lifecycle prodotto attivato.

L'appendice root `build/airpods-8h/public-attachment-audit/REPORT.md`, successiva a
C4 e non coperta dalle sue review, classifica altri due ZIP advertising e immagini
pubbliche. Contiene byte modello Pro2/Pro3 ma non annotazioni mode/CA o un nuovo
endpoint. Non cambia la conclusione sul percorso completo.

## Dati che renderebbero possibile una nuova verifica discriminante

- Artifact Bluetooth Samsung esatto con DEX/enforcement e configurazione effettiva,
  oppure evidenza di margine reale per due registrazioni senza alterare LP/sistema.
- Dump primario ATT/GATT con dichiarazioni, proprietà e RX attribuiti a mode/CA,
  condizioni d'accesso note e percorso Android ordinario; i soli handle non bastano.
- Evidenza autorizzata di due canali completi e duraturi sul target, con RX nelle
  due app, CA configurata distinta dall'attività, audio/gesti e lifecycle.

Questi dati non sono disponibili nel corpus corrente. Il telefono resta escluso
dalla raccolta come richiesto. Non viene chiesto di liberarlo o cambiare LP.
Non esiste al momento una nuova patch client concreta giustificata dalle prove.
Ripetere gli stessi connect o cambiare handshake non risolverebbe il noRCB già
precedente al protocollo; il problema non è una coda di build rimasta da eseguire.

Il checkpoint automatico ha attivato la consegna alla scadenza. Il tempo trascorso
non diventa prova di impossibilità. Le25 deleghe proprie hanno ricevuto task_cancel:
24 completed e1 failed, nessun errore, nessuna ricerca residua da attendere.
771 file C4 e7 input esterni ricontrollati per dimensione/hash senza mismatch.
Nessuna risorsa dispositivo/build acquisita durante la finestra, nessun intervento
sulle altre sessioni. La preferenza esclusiva è ora il percorso successivo;
gate aggiornati, implementazione e prove fisiche restano da completare.
