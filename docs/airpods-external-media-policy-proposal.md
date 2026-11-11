# Automazioni media esterne: decisione residua

Decisione utente del2026-11-08: «Best effort come LibrePods» esplicitamente scelto.
Policy recepita nel nuovo contratto C6. Implementazione e prove restano da completare;
F1/C5 resta vigente per i target locali e per i gate non modificati da C6.
Non riguarda la convivenza con LibrePods: i controlli restano esclusivi.

## Dato tecnico

Il dossier `build/airpods-full-integration/implementation/external-media-research/REPORT.md`
ricostruisce i permessi e i dati effettivamente restituiti da Android. NLS permette
di controllare MediaSession; il routing di sistema e isA2dpPlaying della coppia
non identificano con certezza il renderer di quella specifica sessione. Le normali
callback non attestano la causa di PAUSED né una nuova richiesta PAUSE mentre è già
PAUSED. Non è una prova di impossibilità universale o una nuova prova Samsung.

## Candidato best effort vicino al comportamento LibrePods

- Consenso esplicito separato per altre app, revocabile; OFF predefinito. Un solo
  controller locale PLAYING non ambiguo, permesso NLS effettivo e coppia selezionata
  realmente connessa/in streaming. Il routing rimane inferito e dichiarato tale.
- Bloccare su controller concorrenti/remoti, chiamata/comunicazione, perdita dei
  permessi, cambio dispositivo/sessione/account o revoca. Non trasferire Spotify
  Connect. Gli effetti accodati ricontrollano tutti questi fatti.
- Correlare PAUSED successivo alla richiesta propria, senza chiamarlo ACK causale.
  Invalidare ownership per ogni intervento osservabile. Una PAUSE manuale già
  PAUSED può non essere distinguibile: la ripresa potrebbe contraddire
  quell'intervento. Questa è una differenza sostanziale dalla garanzia F1/C5.
- CA agisce sul volume multimediale del telefono, come la semantica host upstream,
  con un solo arbitro e baseline. Altro audio può condividere il mix; mantenere i
  blocchi su conflitti osservati e rinunciare al ripristino quando divergono i dati.
- Nessuna azione di cleanup PLAY/rollback; nessun cambiamento alle impostazioni LP,
  privilegio di sistema o componente che intercetti l'interfaccia delle altre app.
  Il gate fisico standalone resta obbligatorio e ora NV.

Testo di consenso proposto: "Automazioni per le altre app (best effort). Usa la
sessione multimediale attiva del telefono. Android potrebbe non distinguere una
pausa già attiva o l'audio separato di un'altra app. Puoi disattivare queste
automazioni in qualsiasi momento."

## Alternativa che conserva la garanzia stretta

Consentire gli effetti soltanto con un adapter cooperativo che fornisca route e
ricevute di origine, a partire dal player locale Fluidify. Le altre app restano
non disponibili quando manca questo contratto; non dichiararle implementate.

La scelta è stata richiesta ed è arrivata esplicitamente prima dell'implementazione.
La correlazione non diventa conferma causale e il requisito cross-app non è ancora
soddisfatto dalla sola decisione.
Sviluppo del bridge locale, UI e altre famiglie AirPods proseguono indipendentemente.
