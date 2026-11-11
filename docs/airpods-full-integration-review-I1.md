# Integrazione AirPods — review I1 consolidata

Fase IMPLEMENTAZIONE, CORRETTIVO1, versione I1 stabile. Tutti i quattro reviewer sono GPT-6.1 Sol reasoning high, indipendenti e terminali. A:catalogo/protocollo; B:lifecycle/piattaforma; C:media; D:UI/artefatti. Report originali nei file -A/-B/-C/-D, senza modifica dei voti.

| Criterio | A | B | C | D | Media | Minimo | Contributi | Esito e problemi aperti |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| I1 Completezza funzionale | 7 | NV | NV | NV | 7 | 7 | 1/4 | Correggere ciclo Tile |
| I2 Home/player/quick/full | NV | NV | NV | 7 | 7 | 7 | 1/4 | D1-D4: batteria, ragioni, stato automazioni |
| I3 Lifecycle e ownership | NV | 7 | NV | 8 | 7.5 | 7 | 2/4 | Cleanup CDM non recuperabile dopo errore |
| I4 Protocollo/stato | 8 | NV | NV | NV | 8 | 8 | 1/4 | Verificato nel focus A; hardware NV |
| I5 Automazioni/media | NV | NV | 9 | NV | 9 | 9 | 1/4 | Nessun nuovo difetto funzionale grave nel focus C |
| I6 Validazione/artefatti | 8 | 8 | 7 | 8 | 7.75 | 7 | 4/4 | Log/raw mirati mancanti dal congelato |
| I7 Confini/provenienza | 8 | 8 | NV | 8 | 8 | 8 | 3/4 | Nessun nuovo difetto confermato |
| I8 Integrazione/preservazione | NV | 7 | 8 | 8 | 7.67 | 7 | 3/4 | Cleanup CDM da completare |
| H1 Gate fisico | NV | NV | NV | NV | — | — | 0/4 | NV: nessun telefono, gate prodotto chiuso |

Media/minimo sui soli voti numerici verificati/comparabili; NV esclusi, mai zero. Il campionamento degli altri focus non viene trasformato in verifica completa. La media non compensa rilievi riproducibili.

Decisione: I1 richiede correzioni; preparare CORRETTIVO2 I2. Sette rilievi medi confermati: FI1-01 Tile (A1), FI1-02 cleanup CDM durevole (B1), FI1-03 log/raw omessi (C-01), FI1-04 batteria tagliata font1.5/2 (D1), FI1-05 custodia inclusa nel minimo auricolari (D2), FI1-06 ragioni di indisponibilità generiche (D3), FI1-07 stato automazioni assente dal quick (D4). I0/I1 congelati restano immutati. Primo correttivo consumato; due ulteriori correttivi disponibili, massimo tre dopo iniziale.

La prova media propria di C comprende90 test JVM (86 iniziali inclusi, non aggiuntivi), distinti dalle586 esecuzioni app parent e168 storiche. A riferisce2946 asserzioni codec proprie, non test hardware. I27 mirati e relativi log esistono live ma non sono nel congelato I1: è un errore di selezione del pacchetto, da correggere nel prossimo freeze includendo gli originali e una verifica obbligatoria dei riferimenti. Nessun PASS hardware o UI reale viene dedotto.

Conformance prodotto FALSE, H1 e G1 NV. Nessun dispositivo, installazione, release o versione cambiata. Correzioni e nuove verifiche centrali prima di quattro nuovi reviewer I2.
